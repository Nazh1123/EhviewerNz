"""Summarize device JSON/PNGs; Pillow and NumPy only. Run with the pulled folder."""
import argparse
import json
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


def block_ssim(a, b):
    """Mean luminance SSIM over non-overlapping 8x8 blocks (population variance)."""
    weights = np.array([0.2126, 0.7152, 0.0722])
    a, b = a @ weights, b @ weights
    h, w = a.shape
    h, w = h // 8 * 8, w // 8 * 8
    a = a[:h, :w].reshape(h // 8, 8, w // 8, 8).transpose(0, 2, 1, 3)
    b = b[:h, :w].reshape(h // 8, 8, w // 8, 8).transpose(0, 2, 1, 3)
    ma, mb = a.mean((-1, -2)), b.mean((-1, -2))
    va, vb = a.var((-1, -2)), b.var((-1, -2))
    cov = ((a - ma[..., None, None]) * (b - mb[..., None, None])).mean((-1, -2))
    c1, c2 = (0.01 * 255) ** 2, (0.03 * 255) ** 2
    return float((((2 * ma * mb + c1) * (2 * cov + c2)) /
                  ((ma * ma + mb * mb + c1) * (va + vb + c2))).mean())


def metrics(reference, actual):
    a = np.asarray(Image.open(reference).convert("RGB"), dtype=np.float64)
    b = np.asarray(Image.open(actual).convert("RGB"), dtype=np.float64)
    assert a.shape == b.shape
    delta = b - a
    white = np.min(a, axis=-1) >= 254
    return {"max": float(np.abs(delta).max()), "mae": float(np.abs(delta).mean()),
            "signedMean": float(delta.mean()), "p99": float(np.percentile(np.abs(delta), 99)),
            "blockSsim8x8Luma": block_ssim(a, b),
            "whiteSignedMean": float(delta[white].mean()) if np.any(white) else None}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("folder", type=Path)
    args = parser.parse_args()
    folder = args.folder
    report = json.loads((folder / "report.json").read_text(encoding="utf-8-sig"))
    grouped = defaultdict(list)
    for item in report["timings"]:
        if item["name"].startswith("page_"):
            grouped[item["name"].split("_", 2)[2]].append(item["totalMs"])
    summary = {"timingMs": {name: {"runs": values, "median": float(np.median(values)),
                                   "min": min(values), "max": max(values)}
                             for name, values in grouped.items()}, "quality": {}}
    modes = ["GPU_FP32", "GPU_FP16", "upstream16"]
    for fixture in ["quality", "public"]:
        reference = folder / f"{fixture}-cpu.png"
        for mode in modes:
            actual = folder / f"{fixture}-{mode}.png"
            if actual.is_file():
                summary["quality"][f"{fixture}_{mode}"] = metrics(reference, actual)
        upstream = folder / f"{fixture}-upstream16.png"
        if upstream.is_file():
            summary["quality"][f"{fixture}_optimized16_vs_upstream16"] = metrics(
                upstream, folder / f"{fixture}-GPU_FP16.png")
    # Unscaled crops permit 100% inspection of lines, dots, text and gradients.
    names = ["cpu", *modes]
    width, cell_height = 360, 440
    sheet = Image.new("RGB", (width * len(names), cell_height * 2), "white")
    draw = ImageDraw.Draw(sheet)
    for column, mode in enumerate(names):
        for row, fixture in enumerate(["quality", "public"]):
            path = folder / f"{fixture}-{mode}.png"
            if not path.is_file():
                continue
            image = Image.open(path).convert("RGB")
            crop = image.crop((160, 260, 160 + width, 660)) if fixture == "quality" else image.crop((70, 0, 70 + width, min(400, image.height)))
            origin = (column * width, row * cell_height)
            draw.text((origin[0] + 8, origin[1] + 8), f"{fixture}: {mode}", fill="black")
            sheet.paste(crop, (origin[0], origin[1] + 30))
    sheet.save(folder / "comparison.png")
    (folder / "analysis.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
