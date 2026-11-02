"""The app's launcher icon: an original pixel-art flask, drawn below as a character map and written as Android vector
drawables (an adaptive icon: a dark background, this foreground, and a monochrome layer for themed icons).

  python3 tools/android/app_icon.py            writes android/app/src/main/res/drawable/ic_app_*.xml
  python3 tools/android/app_icon.py --png out.png   also a 432 px preview
"""
import struct
import sys
import zlib
from pathlib import Path

RES = Path(__file__).resolve().parent.parent.parent / "android/app/src/main/res"

# 16 x 16 pixels; '.' is empty.
ART = """
......kkkk......
......kkkk......
.....gwwwwg.....
......g..g...s..
......g..g..sSs.
......g..g...s..
.....g....g.....
....g......g....
...g.hLLLLl.g...
..gLLLLLLLLLLg..
..gLLhLLLLLLLg..
.gLLLLLLLLLhLLg.
.gLLLLLLLLLLLLg.
.gLhLLLLLLLLLLg.
.gdLLLLLLLLLLdg.
..gggggggggggg..
"""
COLORS = {
    "k": "#8A5A3C",  # the cork
    "g": "#C9D3E0",  # glass
    "w": "#E9EFF7",  # glass, lit
    "L": "#F2B33D",  # the potion
    "l": "#F7D06B",  # the potion, lit
    "h": "#FFF2C2",  # bubbles
    "d": "#C27A1C",  # the potion, in shadow
    "s": "#B892FF",  # sparks
    "S": "#E4D4FF",
}
BACKGROUND = "#17131F"
# The art fills 60 of the 108 dp: inside the 66 dp circle every launcher's mask keeps.
SIZE, ART_DP = 108, 60


def pixels():
    rows = [r for r in ART.strip("\n").splitlines()]
    assert len(rows) == 16 and all(len(r) == 16 for r in rows), "the art is 16 x 16"
    return rows


def vector(colors: dict[str, str]) -> str:
    """A vector drawable of the art, one path per colour."""
    px = ART_DP / 16
    off = (SIZE - ART_DP) / 2
    paths = []
    for ch, color in colors.items():
        d = []
        for y, row in enumerate(pixels()):
            for x, c in enumerate(row):
                if c == ch:
                    d.append(f"M{off + x * px:g},{off + y * px:g}h{px:g}v{px:g}h-{px:g}z")
        if d:
            paths.append(f'    <path android:fillColor="{color}" android:pathData="{"".join(d)}" />')
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- tools/android/app_icon.py writes this: edit the art there. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{SIZE}dp" android:height="{SIZE}dp"\n'
            f'    android:viewportWidth="{SIZE}" android:viewportHeight="{SIZE}">\n'
            + "\n".join(paths) + "\n</vector>\n")


def png(size: int) -> bytes:
    """A preview: the art on the background, nearest neighbour."""
    rows = pixels()
    bg = bytes.fromhex(BACKGROUND[1:])
    out = bytearray()
    scale = ART_DP / SIZE * size / 16
    off = (size - 16 * scale) / 2
    for y in range(size):
        out.append(0)
        for x in range(size):
            ax, ay = int((x - off) // scale), int((y - off) // scale)
            c = rows[ay][ax] if 0 <= ax < 16 and 0 <= ay < 16 else "."
            out += bytes.fromhex(COLORS[c][1:]) if c != "." else bg
    chunk = lambda kind, data: struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))  # noqa: E731
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(out), 9)) + chunk(b"IEND", b""))


def main():
    (RES / "drawable").mkdir(parents=True, exist_ok=True)
    (RES / "values").mkdir(parents=True, exist_ok=True)
    (RES / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
    (RES / "drawable/ic_app_foreground.xml").write_text(vector(COLORS))
    (RES / "drawable/ic_app_monochrome.xml").write_text(vector({c: "#FFFFFFFF" for c in COLORS}))
    (RES / "values/ic_app_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
        f'    <color name="ic_app_background">{BACKGROUND}</color>\n</resources>\n')
    (RES / "mipmap-anydpi-v26/ic_app.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_app_background" />\n'
        '    <foreground android:drawable="@drawable/ic_app_foreground" />\n'
        '    <monochrome android:drawable="@drawable/ic_app_monochrome" />\n'
        '</adaptive-icon>\n')
    if sys.argv[1:2] == ["--png"]:
        Path(sys.argv[2]).write_bytes(png(432))


if __name__ == "__main__":
    main()
