"""Create an importable PP-OCR runtime component from the pinned official AAR."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "translation-engine/src/main/assets/ppocr-runtime.json"


def package(aar, output):
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    data = aar.read_bytes()
    if len(data) != manifest["bundle"]["size"] or hashlib.sha256(data).hexdigest() != manifest["bundle"]["sha256"]:
        raise ValueError("Official AAR checksum mismatch")
    output.mkdir(parents=True, exist_ok=True)
    path = output / f"ehviewer-ppocr-runtime-{manifest['version']}-{manifest['abi']}.zip"
    with zipfile.ZipFile(aar) as source, zipfile.ZipFile(path, "w") as target:
        for entry in manifest["models"]:
            library = source.read(f"jni/{manifest['abi']}/{entry['name']}")
            if len(library) != entry["size"] or hashlib.sha256(library).hexdigest() != entry["sha256"]:
                raise ValueError(f"Native library checksum mismatch: {entry['name']}")
            info = zipfile.ZipInfo(entry["name"], date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100444 << 16
            target.writestr(info, library, compresslevel=6)
    with zipfile.ZipFile(path) as check:
        if check.namelist() != [entry["name"] for entry in manifest["models"]]:
            raise ValueError("Component file list differs from manifest")
        for entry in manifest["models"]:
            if hashlib.sha256(check.read(entry["name"])).hexdigest() != entry["sha256"]:
                raise ValueError("Packaged native checksum mismatch")
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    (output / (path.name + ".sha256")).write_text(f"{digest}  {path.name}\n", encoding="utf-8")
    (output / MANIFEST.name).write_bytes(MANIFEST.read_bytes())
    (output / "translation-engine-notices.txt").write_bytes(
        (ROOT / "translation-engine/src/main/assets/translation-engine-notices.txt").read_bytes())
    print(json.dumps({"archive": str(path), "bytes": path.stat().st_size,
                      "unpacked_bytes": sum(entry["size"] for entry in manifest["models"]), "sha256": digest}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aar", type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/ppocr-runtime")
    args = parser.parse_args()
    package(args.aar, args.output)
