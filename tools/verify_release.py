"""Verify an APK and its native build before release (Python standard library only)."""

import argparse
import json
from pathlib import Path
import re
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile

ANDROID = "{http://schemas.android.com/apk/res/android}"
SYSTEM_LIBS = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
               "libjnigraphics.so", "libz.so", "libEGL.so", "libGLESv2.so",
               "libGLESv3.so", "libOpenSLES.so"}
CPU_VARIANTS = {"android_armv8.0_1", "android_armv8.2_1", "android_armv8.2_2",
                "android_armv8.6_1", "android_armv9.0_1", "android_armv9.2_1",
                "android_armv9.2_2"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def elf_dependencies(data, name):
    require(data[:6] == b"\x7fELF\x02\x01", f"{name}: expected ELF64 little endian")
    require(struct.unpack_from("<H", data, 18)[0] == 183, f"{name}: expected AArch64")
    offset = struct.unpack_from("<Q", data, 32)[0]
    stride, count = struct.unpack_from("<HH", data, 54)
    segments = [struct.unpack_from("<IIQQQQQQ", data, offset + i * stride) for i in range(count)]
    loads = [s for s in segments if s[0] == 1]
    require(loads, f"{name}: no loadable segments")
    for s in loads:
        require(s[7] >= 16384 and s[2] % 16384 == s[3] % 16384,
                f"{name}: LOAD segment is not compatible with 16 KB pages")
    dynamic = next((s for s in segments if s[0] == 2), None)
    if dynamic is None:
        return set()
    tags = []
    for pos in range(dynamic[2], dynamic[2] + dynamic[5], 16):
        tag, value = struct.unpack_from("<qQ", data, pos)
        if tag == 0:
            break
        tags.append((tag, value))
    needed = [value for tag, value in tags if tag == 1]
    if not needed:
        return set()
    strings = next(value for tag, value in tags if tag == 5)
    containing = next(s for s in loads if s[3] <= strings < s[3] + s[5])
    string_offset = containing[2] + strings - containing[3]
    return {data[string_offset + n:data.index(b"\0", string_offset + n)].decode("utf-8")
            for n in needed}


def verify_apk(path):
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        native_names = [n for n in names if n.startswith("lib/") and n.endswith(".so")]
        require(all(n.startswith("lib/arm64-v8a/") for n in native_names), "APK contains an unsupported native ABI")
        libraries = {n.rsplit("/", 1)[-1]: n for n in native_names}
        require(len(libraries) == len(native_names), "Duplicate native library entries")
        expected = {f"libggml-cpu-{v}.so" for v in CPU_VARIANTS} | {
            "libehnz_llama.so", "libllama.so", "libggml.so", "libggml-base.so", "libyakuyomi_ncnn.so"}
        require(expected <= libraries.keys(), f"Missing native libraries: {sorted(expected - libraries.keys())}")
        for basename, name in libraries.items():
            require(name.startswith("lib/arm64-v8a/"), f"Unexpected ABI: {name}")
            require("test" not in basename and "probe" not in basename, f"Test library packaged: {name}")
            dependencies = elf_dependencies(apk.read(name), name)
            require(dependencies <= libraries.keys() | SYSTEM_LIBS,
                    f"{name}: unresolved libraries {sorted(dependencies - libraries.keys() - SYSTEM_LIBS)}")
            # Direct dependencies must be portable. CPU variants are loaded only
            # after their feature probe returns a compatible score.
            require(not any("libggml-cpu-" in d for d in dependencies),
                    f"{name}: directly links a CPU variant, bypassing runtime selection")
            entry = apk.getinfo(name)
            if entry.compress_type == zipfile.ZIP_STORED:
                with path.open("rb") as stream:
                    stream.seek(entry.header_offset + 26)
                    filename_size, extra_size = struct.unpack("<HH", stream.read(4))
                start = entry.header_offset + 30 + filename_size + extra_size
                require(start % 16384 == 0, f"{name}: uncompressed library lacks 16 KB ZIP alignment")
        for name in names:
            require(not name.lower().endswith((".gguf", ".jks", ".keystore")), f"Local artifact packaged: {name}")
            if re.fullmatch(r"classes\d*\.dex", name):
                data = apk.read(name)
                require(b"NativeBackgroundProbeService" not in data, "Legacy background probe packaged")
                require(b"Lorg/junit/" not in data and b"Lorg/robolectric/" not in data,
                        "Test framework packaged")
        print(f"APK: {len(libraries)} arm64 libraries; all LOAD segments support 16 KB pages")


def verify_compilation(root):
    # AGP retains old build directories. Inspect only the most recently generated
    # release compilation database and require every source to target Android 26.
    databases = list(root.glob("*/arm64-v8a/compile_commands.json"))
    require(databases, f"No compilation database under {root}")
    database = max(databases, key=lambda p: p.stat().st_mtime_ns)
    commands = json.loads(database.read_text(encoding="utf-8"))
    require(commands, "Empty compilation database")
    found_variants = set()
    for entry in commands:
        command = entry.get("command") or " ".join(entry["arguments"])
        require("aarch64-none-linux-android26" in command, f"Wrong Android target: {entry['file']}")
        require(not re.search(r"-m(?:arch|cpu)=native\b", command), f"Host CPU flag: {entry['file']}")
        variant = next((v for v in CPU_VARIANTS if f"ggml-cpu-{v}.dir" in command), None)
        if variant:
            found_variants.add(variant)
        # Only the optimized compute objects may require optional instructions.
        # Feature probes are called while scanning *all* variants on older CPUs.
        if variant is None or variant == "android_armv8.0_1":
            flags = re.findall(r"-m(?:arch|cpu)=([^\s\"]+)", command)
            require(all(f == "armv8-a" for f in flags), f"Nonportable baseline/probe: {entry['file']}: {flags}")
    require(found_variants == CPU_VARIANTS, f"Missing compiled variants: {sorted(CPU_VARIANTS - found_variants)}")
    print("Native compilation: Android 26 baseline and portable CPU feature probes verified")


def verify_manifest(path):
    root = ET.parse(path).getroot()
    require(root.get("package") == "com.nz.ehviewernz", "Unexpected release package")
    sdk = root.find("uses-sdk")
    require(sdk is not None and sdk.get(ANDROID + "minSdkVersion") == "26", "Expected Android 8.0 minimum")
    app = root.find("application")
    require(app is not None, "Missing application")
    require(app.get(ANDROID + "debuggable", "false") == "false", "Release is debuggable")
    require(app.get(ANDROID + "testOnly", "false") == "false", "Release is test-only")
    require(app.get(ANDROID + "extractNativeLibs") == "true", "CPU selection requires extracted native libraries")
    require(not any("Probe" in e.get(ANDROID + "name", "") for e in app), "Probe component registered")
    print("Manifest: release package, minimum SDK and extracted libraries verified")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--native-build", required=True, type=Path,
                        help="translation-engine/.cxx/RelWithDebInfo or Release")
    parser.add_argument("--manifest", required=True, type=Path, help="Merged release AndroidManifest.xml")
    args = parser.parse_args()
    try:
        verify_apk(args.apk)
        verify_compilation(args.native_build)
        verify_manifest(args.manifest)
    except (ValueError, OSError, KeyError, StopIteration, struct.error, ET.ParseError, zipfile.BadZipFile) as error:
        print(f"Release verification FAILED: {error}", file=sys.stderr)
        return 1
    print("Release verification PASSED (static checks; device inference still requires real devices)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
