"""The app's launcher icon: an original pixel-art N, glowing like embers, whose foot crumbles into falling sand. It
isn't the game's logo (a blackletter n), on purpose: the APK ships it, and it has no art of the game's.

The art is drawn in code on a 72 x 72 pixel grid, 1.5 dp a pixel of the 108 dp adaptive icon, and written as PNGs at
each density (vector squares would show hairline seams between the dither's colours): a background with a glow, the
letter, and a monochrome layer for themed icons.

  python3 tools/android/app_icon.py                 writes android/app/src/main/res/mipmap-*/ic_app_*.png
  python3 tools/android/app_icon.py --png out.png   also a 432 px preview of what a round mask shows
"""
import math
import struct
import sys
import zlib
from pathlib import Path

RES = Path(__file__).resolve().parent.parent.parent / "android/app/src/main/res"
GRID = 72
DENSITIES = (("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4))

RAMP = ["#FFF4C4", "#FFDD73", "#FFB443", "#FF8A3A", "#F2603F", "#D23E4F", "#A12C59", "#6E2255"]  # the letter, top down
OUTLINE, SHADOW = "#140B1E", "#2A1638"
GLOW = ["#0D0914", "#120B1A", "#170E20", "#1D1124", "#241528", "#2C192B", "#361D2D"]  # the background, edge to centre

# The letter: thin stems and a thick diagonal, slabs at the right stem's head and the left one's foot. Its top-left
# is at (X0, Y0) on the grid; with the sand below it, it stays inside the 66 dp circle every launcher's mask keeps.
W, H, STEM, DIAG = 24, 28, 5, 8
X0, Y0 = GRID // 2 - W // 2, 21
R, B = X0 + W, Y0 + H  # its right edge and bottom


def in_letter(x: int, y: int) -> bool:
    x, y = x - X0, y - Y0
    if not 0 <= y < H:
        return False
    return ((0 <= x < STEM and y >= 1)  # the left stem: the diagonal makes its head
            or (W - STEM <= x < W and y < H - 2)  # the right stem: the diagonal makes its foot
            or round(y * (W - DIAG) / (H - 1)) <= x < round(y * (W - DIAG) / (H - 1)) + DIAG
            or (y < 2 and (W - STEM - 2 <= x < W + 2 or -2 <= x < 2))  # the right head's slab, the left head's spur
            or (y >= H - 2 and -2 <= x < STEM + 2))  # the left foot's slab


CRUMBLED = {(R - 1, B - 1), (R - 3, B - 1), (R - 5, B - 1), (R - 2, B - 2), (R - 1, B - 3)}  # gone from the foot...
SAND = [(R - 2, B + 1, 4), (R - 5, B + 1, 5), (R - 3, B + 3, 4), (R - 1, B + 3, 5), (R - 4, B + 5, 5), (R - 2, B + 6, 6)]
EMBERS = [(X0 + 4, Y0 - 3, 1), (X0 + 9, Y0 - 7, 0), (R + 1, Y0 - 4, 1), (R - 5, Y0 - 7, 2), (X0 - 3, Y0 - 6, 2)]
# ... falling as sand (x, y, RAMP index), and embers rising off the head


def noise(x: int, y: int) -> float:
    """A fixed pseudo-random number in [0, 1) for each pixel: the dither."""
    h = (x * 374761393 + y * 668265263) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((h ^ (h >> 16)) & 0xFFFF) / 65536


def letter() -> dict[tuple[int, int], str]:
    """The foreground's pixels: the letter's fill, lit up and left, its shadow and outline, sand and embers."""
    solid = {(x, y) for y in range(GRID) for x in range(GRID) if in_letter(x, y)} - CRUMBLED
    px = {}
    for (x, y) in solid:
        t = (y - Y0) / (H - 1) * 0.92 + (x - X0) / W * 0.08
        if (x - 1, y) not in solid or (x, y - 1) not in solid:
            t -= 0.16
        elif (x + 1, y) not in solid or (x, y + 1) not in solid:
            t += 0.14
        pos = min(max(t, 0), 1) * (len(RAMP) - 1)
        i = int(pos) + (noise(x, y) < pos - int(pos))
        px[(x, y)] = RAMP[min(i, len(RAMP) - 1)]
    for (x, y) in solid:
        for (dx, dy) in ((1, 1), (2, 2), (1, 2)):
            if (x + dx, y + dy) not in solid:
                px.setdefault((x + dx, y + dy), SHADOW)
    for (x, y) in solid:
        for (dx, dy) in ((-1, 0), (1, 0), (0, -1), (0, 1)):
            if (x + dx, y + dy) not in solid and px.get((x + dx, y + dy)) != SHADOW:
                px[(x + dx, y + dy)] = OUTLINE
    for (x, y, i) in SAND + EMBERS:
        px[(x, y)] = RAMP[i]
    return px


BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]  # the glow's dither: even rings


def glow(x: int, y: int) -> str:
    """The background: a dim glow behind the letter, in dithered rings."""
    d = math.hypot(x + 0.5 - (X0 + W / 2), (y + 0.5 - (Y0 + H / 2)) * 0.9) / (GRID * 0.62)
    pos = max(0.0, 1 - d) * (len(GLOW) - 1)
    i = int(pos) + ((BAYER[y % 4][x % 4] + 0.5) / 16 < pos - int(pos))
    return GLOW[min(i, len(GLOW) - 1)]



def rgba(c: str | None) -> tuple[int, int, int, int]:
    return (int(c[1:3], 16), int(c[3:5], 16), int(c[5:7], 16), 255) if c else (0, 0, 0, 0)


def png(size: int, pixel, crop: tuple[int, int] = (0, GRID)) -> bytes:
    """A size x size RGBA PNG of the grid's pixels crop[0]..crop[1] (pixel(x, y) -> RGBA), each output pixel the
    mean of 4 x 4 samples: sharp pixel edges at every density."""
    lo, span, n = crop[0], crop[1] - crop[0], 4
    rows = bytearray()
    for oy in range(size):
        rows.append(0)
        for ox in range(size):
            r = g = b = a = 0
            for sy in range(n):
                for sx in range(n):
                    pr, pg, pb, pa = pixel(lo + int((ox + (sx + 0.5) / n) * span / size),
                                           lo + int((oy + (sy + 0.5) / n) * span / size))
                    r, g, b, a = r + pr * pa, g + pg * pa, b + pb * pa, a + pa
            rows += bytes((round(r / a), round(g / a), round(b / a), round(a / n / n))) if a else b"\0\0\0\0"
    chunk = lambda kind, data: struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))  # noqa: E731
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b""))


def main():
    fg = letter()
    layers = {
        "ic_app_background": lambda x, y: rgba(glow(x, y)),
        "ic_app_foreground": lambda x, y: rgba(fg.get((x, y))),
        # themed icons: the letter, sand and embers in one colour, without the outline and shadow
        "ic_app_monochrome": lambda x, y: rgba(fg.get((x, y)) not in (None, OUTLINE, SHADOW) and "#FFFFFF" or None),
    }
    for density, scale in DENSITIES:
        (RES / f"mipmap-{density}").mkdir(parents=True, exist_ok=True)
        for name, pixel in layers.items():
            (RES / f"mipmap-{density}/{name}.png").write_bytes(png(round(108 * scale), pixel))
    (RES / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
    (RES / "mipmap-anydpi-v26/ic_app.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<!-- tools/android/app_icon.py writes this and its layers: edit the art there. -->\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@mipmap/ic_app_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_app_foreground" />\n'
        '    <monochrome android:drawable="@mipmap/ic_app_monochrome" />\n'
        '</adaptive-icon>\n')
    if sys.argv[1:2] == ["--png"]:  # the 72 dp a launcher shows, in a circle on white
        def shown(x, y):
            return rgba(fg.get((x, y)) or glow(x, y)) if math.hypot(x + 0.5 - 36, y + 0.5 - 36) <= 24 else (255,) * 4
        Path(sys.argv[2]).write_bytes(png(432, shown, (12, 60)))


if __name__ == "__main__":
    main()
