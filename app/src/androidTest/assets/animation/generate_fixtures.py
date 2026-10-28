"""Regenerate tiny disposal/blending fixtures with Pillow (lossless WebP support)."""
from pathlib import Path

from PIL import Image, ImageDraw

root = Path(__file__).parent
frames = []
for index in range(24):
    frame = Image.new("RGBA", (32, 32), (0, 0, 0, 0))
    draw = ImageDraw.Draw(frame)
    left, top = index % 8 * 3, index // 8 * 7
    draw.rectangle((left, top, left + 8, top + 10),
                   fill=(index * 37 % 256, index * 71 % 256, index * 19 % 256, 255))
    draw.rectangle((0, 27, 31, 31), fill=(20, 40, 80, 255))
    frames.append(frame)

durations = [20, 40, 60, 80] * 6
frames[0].save(root / "disposal.gif", save_all=True, append_images=frames[1:],
               duration=durations, loop=0, disposal=[1, 2, 3] * 8, optimize=False)
frames[0].save(root / "blend.webp", save_all=True, append_images=frames[1:],
               duration=durations, loop=0, lossless=True, minimize_size=True)
