"""Package already acquired, pinned image weights for this project's Releases.

No model conversion or upstream access occurs here. The app's embedded manifest
is the only authority; unknown source files are not included.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "translation-engine/src/main/assets/translation-models.json"


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def package(source, output):
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    models = manifest["models"]
    for entry in models:
        path = source / entry["name"]
        if path.stat().st_size != entry["size"] or sha256(path) != entry["sha256"]:
            raise ValueError(f"Model checksum mismatch: {entry['name']}")
    output.mkdir(parents=True, exist_ok=True)
    archive = output / manifest["bundle"]["url"].rsplit("/", 1)[1]
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        for entry in models:
            # Stable metadata: identical inputs and zlib yield an identical ZIP.
            info = zipfile.ZipInfo(entry["name"], date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            with (source / entry["name"]).open("rb") as src, bundle.open(info, "w") as dst:
                shutil.copyfileobj(src, dst, 1024 * 1024)
    # Read back the actual artifact, including CRC and every uncompressed hash.
    with zipfile.ZipFile(archive) as bundle:
        if bundle.namelist() != [entry["name"] for entry in models]:
            raise ValueError("Packaged file list differs from manifest")
        for entry in models:
            with bundle.open(entry["name"]) as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != entry["sha256"]:
                    raise ValueError(f"Packaged checksum mismatch: {entry['name']}")
    for path in (MANIFEST, ROOT / "LICENSE", ROOT / "translation-engine/MODEL_ASSETS.md",
                 ROOT / "translation-engine/MODEL_SOURCES.json", Path(__file__).resolve()):
        shutil.copyfile(path, output / path.name)
    digest = sha256(archive)
    (output / (archive.name + ".sha256")).write_text(f"{digest}  {archive.name}\n", encoding="utf-8")
    print(json.dumps({"archive": str(archive), "bytes": archive.stat().st_size,
                      "unpacked_bytes": sum(e["size"] for e in models), "sha256": digest}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="Directory containing the seven original model files")
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/manga-models/release")
    args = parser.parse_args()
    package(args.source, args.output)
