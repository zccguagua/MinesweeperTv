"""Renders the launcher icon PNG used on API 19 (Android 4.4).

The framework only understands <vector> drawables from API 21, so the vector
icon lives in drawable-anydpi-v21/ and this script produces the raster fallback
in drawable-nodpi/. Keeping the geometry here means the two stay in sync.

Usage:  python tools/generate_icon_png.py
"""

from __future__ import annotations

import math
import struct
import zlib
from pathlib import Path

# Mirrors drawable-anydpi-v21/ic_launcher.xml, which uses a 108x108 viewport.
VIEWPORT = 108.0
SIZE = 192  # output pixels (square)
SUPERSAMPLE = 4

BACKGROUND = (0x2B, 0x2B, 0x2B)
MINE_BODY = (0x10, 0x10, 0x10)
MINE_HIGHLIGHT = (0x40, 0x40, 0x40)

SPIKES = [
    (81, 54, 95, 54),
    (73, 73, 83, 83),
    (54, 81, 54, 95),
    (35, 73, 25, 83),
    (27, 54, 13, 54),
    (35, 35, 25, 25),
    (54, 27, 54, 13),
    (73, 35, 83, 25),
]
STROKE_WIDTH = 5.0

OUT_PATH = Path(__file__).resolve().parent.parent / (
    "app/src/main/res/drawable-nodpi/ic_launcher.png"
)


def scale(value: float) -> float:
    return value * (SIZE * SUPERSAMPLE) / VIEWPORT


def distance_to_segment(px, py, x1, y1, x2, y2) -> float:
    dx, dy = x2 - x1, y2 - y1
    length_sq = dx * dx + dy * dy
    if length_sq == 0.0:
        return math.hypot(px - x1, py - y1)
    t = ((px - x1) * dx + (py - y1) * dy) / length_sq
    t = max(0.0, min(1.0, t))
    return math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))


def render_samples():
    """Returns SIZE x SIZE pixels of (r, g, b), antialiased by supersampling."""
    center = scale(54.0)
    body_radius = scale(21.0)
    highlight_radius = scale(9.0)
    half_stroke = scale(STROKE_WIDTH) / 2.0
    segments = [tuple(scale(v) for v in spike) for spike in SPIKES]

    samples = SUPERSAMPLE * SUPERSAMPLE
    pixels = []
    for oy in range(SIZE):
        row = []
        for ox in range(SIZE):
            red = green = blue = 0
            for sy in range(SUPERSAMPLE):
                py = oy * SUPERSAMPLE + sy + 0.5
                for sx in range(SUPERSAMPLE):
                    px = ox * SUPERSAMPLE + sx + 0.5
                    color = BACKGROUND
                    if any(
                        distance_to_segment(px, py, *seg) <= half_stroke
                        for seg in segments
                    ):
                        color = MINE_BODY
                    distance = math.hypot(px - center, py - center)
                    if distance <= body_radius:
                        color = MINE_BODY
                    if distance <= highlight_radius:
                        color = MINE_HIGHLIGHT
                    red += color[0]
                    green += color[1]
                    blue += color[2]
            row.append((red // samples, green // samples, blue // samples))
        pixels.append(row)
    return pixels


def png_chunk(tag: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + tag
        + data
        + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    )


def main() -> None:
    pixels = render_samples()

    raw = bytearray()
    for row in pixels:
        raw.append(0)  # filter type: none
        for r, g, b in row:
            raw += bytes((r, g, b))

    png = b"\x89PNG\r\n\x1a\n"
    png += png_chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0))
    png += png_chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    png += png_chunk(b"IEND", b"")

    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_bytes(png)
    print(f"wrote {OUT_PATH} ({len(png)} bytes)")


if __name__ == "__main__":
    main()
