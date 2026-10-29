"""Builds the console palette atlases (graphite / lavender) from the cream one.

The console models keep their UVs; only the `#atlas` texture changes, so a tone
is a recoloured copy of the shared palette where the cream family is remapped.
Run:  python tools/build-console-atlases.py
"""
from __future__ import annotations

import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ATLAS = ROOT / "common/src/main/resources/assets/retroconsole/textures/block/retro_console_atlas.png"

# Chassis ramp of each finish: shadow, body, highlight. The cream values are the
# ones the lab painted; graphite and lavender keep the same relative lightness so
# the pre-shaded faces still read as the same shape.
RAMPS = {
    "cream": {"shadow": (0xAC, 0xA5, 0x93), "body": (0xD7, 0xCF, 0xB9), "light": (0xEE, 0xE5, 0xCF)},
    "graphite": {"shadow": (0x22, 0x26, 0x2E), "body": (0x3B, 0x41, 0x4D), "light": (0x55, 0x5C, 0x6B)},
    "lavender": {"shadow": (0x77, 0x63, 0x8E), "body": (0xB9, 0xA2, 0xD8), "light": (0xD8, 0xC8, 0xEA)},
}
# Pixels that belong to the chassis ramp and may be recoloured. Everything else
# (LEDs, cartridge, metal, rubber) is shared by every finish.
CREAM_FAMILY = {(0xD7, 0xCF, 0xB9), (0xAC, 0xA5, 0x93), (0xEE, 0xE5, 0xCF),
                (0xE2, 0xD7, 0xB7), (0xFA, 0xF0, 0xD4), (0xC5, 0xBD, 0xA7)}


def read_png(path: Path):
    data = path.read_bytes()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos, idat, header = 8, bytearray(), None
    while pos < len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8])
        body = data[pos + 8:pos + 8 + length]
        pos += 12 + length
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            idat += body
        elif kind == b"IEND":
            break
    width, height, depth, color, _, _, interlace = header
    assert depth == 8 and color == 6 and interlace == 0, f"unsupported PNG {header}"
    stride = width * 4
    raw = zlib.decompress(bytes(idat))
    rows, prev, p = [], bytearray(stride), 0
    for _ in range(height):
        kind = raw[p]
        line = bytearray(raw[p + 1:p + 1 + stride])
        p += 1 + stride
        for i in range(stride):
            a = line[i - 4] if i >= 4 else 0
            b = prev[i]
            c = prev[i - 4] if i >= 4 else 0
            if kind == 1:
                line[i] = (line[i] + a) & 0xFF
            elif kind == 2:
                line[i] = (line[i] + b) & 0xFF
            elif kind == 3:
                line[i] = (line[i] + (a + b) // 2) & 0xFF
            elif kind == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pred = a if pa <= pb and pa <= pc else (b if pb <= pc else c)
                line[i] = (line[i] + pred) & 0xFF
        rows.append(line)
        prev = line
    return width, height, rows


def write_png(path: Path, width: int, height: int, rows):
    raw = bytearray()
    for line in rows:
        raw.append(0)
        raw += line

    def chunk(kind: bytes, body: bytes) -> bytes:
        return (struct.pack(">I", len(body)) + kind + body
                + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF))

    out = bytearray(b"\x89PNG\r\n\x1a\n")
    out += chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
    out += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    out += chunk(b"IEND", b"")
    path.write_bytes(out)


def luminance(color) -> float:
    r, g, b = color
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def recolor(rows, tone: str):
    ramp = RAMPS[tone]
    base, shadow, light = RAMPS["cream"]["body"], RAMPS["cream"]["shadow"], RAMPS["cream"]["light"]
    lo, hi = luminance(shadow), luminance(light)
    span = hi - lo
    out_rows = []
    for line in rows:
        out = bytearray(line)
        for i in range(0, len(line), 4):
            rgb = (line[i], line[i + 1], line[i + 2])
            if rgb not in CREAM_FAMILY:
                continue
            t = (luminance(rgb) - lo) / span if span else 0.5
            t = min(1.0, max(0.0, t))
            # Piecewise ramp: shadow -> body -> light.
            if t < 0.5:
                k = t / 0.5
                from_, to_ = ramp["shadow"], ramp["body"]
            else:
                k = (t - 0.5) / 0.5
                from_, to_ = ramp["body"], ramp["light"]
            out[i] = round(from_[0] + (to_[0] - from_[0]) * k)
            out[i + 1] = round(from_[1] + (to_[1] - from_[1]) * k)
            out[i + 2] = round(from_[2] + (to_[2] - from_[2]) * k)
        out_rows.append(out)
    return out_rows


def main() -> int:
    width, height, rows = read_png(ATLAS)
    for tone in ("graphite", "lavender"):
        target = ATLAS.with_name(f"retro_console_atlas_{tone}.png")
        write_png(target, width, height, recolor(rows, tone))
        print(f"wrote {target.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
