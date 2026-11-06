"""Builds the game for Android: build/android/noitadroid.apk, with libmain.so (the recompiled game, the runtime,
LuaJIT and gl4es), SDL2 and, when .local-deps/fmod has them, FMOD's libraries.

The recompiled C of the last host build (tools/build_all.py: build/<module>/gen/chunk*.c, build/gen_all/table.c and
decls.h) doesn't depend on the target, so it's reused. The SDL2 and GL bridges are generated again for Android in
build/android/gen (SDL's arm64 layouts, gl4es's functions); objects go in build/android/obj and are rebuilt when
their source, a header, the build scripts or the flags changed.

  NOITA_DIR=<GOG install> uv run tools/android/build_game.py [--lto off] [--no-apk]
"""
import argparse
import multiprocessing as mp
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

os.environ["NOITADROID_TARGET"] = "android"  # before the build modules read it
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import target  # noqa: E402
import build_all  # noqa: E402
import gen_gl  # noqa: E402
import gen_sdl  # noqa: E402
import game_build  # noqa: E402

ROOT = target.ROOT
OUT = ROOT / "build/android"
GEN = OUT / "gen"
# The profile of tools/android/pgo_train.sh: heavy scenes run about 10% faster with it, here and on the device. It holds
# function names and counts only, none of the game's code.
PGO_RAW, PGO_DATA = "/sdcard/noitadroid/pgo", ROOT / "tools/noitadroid.profdata"
OBJ = OUT / "obj"
JNI = OUT / "jniLibs/arm64-v8a"
HOST_GEN = ROOT / "build/gen_all"
FMOD_LIB = target.DEPS / "fmod/api/core/lib/arm64-v8a"
FMOD_STUDIO_LIB = target.DEPS / "fmod/api/studio/lib/arm64-v8a"
LIBCXX = target.NDK / "toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
SDL_USERS = {"gles.c", "android_main.c"}


def sources():
    chunks = [c for m in ("noita", "msvcp120") for c in sorted((ROOT / "build" / m / "gen").glob("chunk*.c"))]
    if not chunks or not (HOST_GEN / "table.c").exists():
        sys.exit("no recompiled C: run tools/build_all.py (the host build) first")
    runtime = ("rt.c", *build_all.LAUNCHER, *build_all.SDL, *build_all.LUA, *build_all.GLES, "android_main.c")
    return chunks + [HOST_GEN / "table.c", GEN / "sdl2_gen.c", GEN / "gl_gen.c"] + [ROOT / "runtime" / n for n in runtime]


def obj_of(src: Path) -> Path:
    if src.name.startswith("chunk"):
        return OBJ / f"{src.parent.parent.name}_{src.stem}.o"  # build/<module>/gen/chunkNNN.c
    return OBJ / f"{src.stem}.o"


def deps_mtime(flags: str) -> float:
    """The newest of what every object depends on besides its source (inf when the flags changed: rebuild all)."""
    stamp = OBJ / "flags.txt"
    if not stamp.exists() or stamp.read_text() != flags:
        stamp.write_text(flags)
        return float("inf")
    deps = [*(ROOT / "runtime").glob("*.h"), *(ROOT / "runtime/compat").rglob("*.h"), *GEN.glob("*.h"),
            *HOST_GEN.glob("*.h"), ROOT / "tools/target.py", Path(__file__)]
    return max(d.stat().st_mtime for d in deps)


def generate_bridges():
    """The bridges into build/android/gen, replacing a file only when it changed (so its users stay up to date)."""
    new = OUT / "gen.new"
    new.mkdir(exist_ok=True)
    gen_sdl.GEN = gen_gl.GEN = new
    n_sdl, _ = gen_sdl.generate()
    _, bridged, n_gl = gen_gl.generate()
    for f in new.iterdir():
        old = GEN / f.name
        if not old.exists() or old.read_bytes() != f.read_bytes():
            f.replace(old)
    return n_sdl, bridged, n_gl


def compile_one(job):
    src, lto, pgo, newer_than = job
    obj = obj_of(src)
    if obj.exists() and obj.stat().st_mtime >= max(src.stat().st_mtime, newer_than):
        return obj, False
    flags = [*target.cc(), "-c", "-O2", "-ffp-contract=off", "-fno-strict-aliasing", "-w", "-fPIC", *lto, *pgo,
             *target.platform_cflags(), *target.gl_cflags(), *target.runtime_includes(ROOT / "runtime", GEN, HOST_GEN)]
    if (src.stem.startswith("sdl2") and src.stem != "sdl2_stdlib") or src.name in SDL_USERS:
        flags += target.sdl_cflags()
    if src.name in build_all.LUA:
        flags += build_all.LUA_CFLAGS
    if src.name == "main.c":
        flags.append("-Dmain=noitadroid_main")  # runtime/android_main.c's SDL_main calls it
    # Relative to the repo or kit: the profile names a static function by its source file's path as given here.
    subprocess.run([*flags, os.path.relpath(src, ROOT), "-o", str(obj)], check=True, cwd=ROOT)
    return obj, True


LTO_CACHE = OUT / "lto_cache"


def link(objs, luajit: Path, out: Path, lto: list[str], pgo: list[str] = [], lto_jobs: int = 0):
    """libmain.so from the objects, LuaJIT, gl4es and SDL2 (also tools/android/device_build.py's link). It exports
    SDL_main, which SDL's Java side looks up, and NoitaActivity's natives. lto_jobs: ThinLTO's threads, when not one
    per core."""
    exports = OUT / "exports.map"
    exports.write_text("{ global: SDL_main; Java_io_github_zhouwuqi_noitadroid_*; local: *; };\n")
    cmd = [*target.cc(), "-shared", *target.linker(), *lto, *pgo[:1]]  # the instrumented build links the profile runtime
    if lto:
        LTO_CACHE.mkdir(exist_ok=True)
        cmd += target.lto_cache(LTO_CACHE)
        if lto_jobs:
            cmd.append(f"-Wl,--thinlto-jobs={lto_jobs}")
    subprocess.run([*cmd, *map(str, objs), str(luajit), *target.gl_libs(), *target.sdl_libs(), *target.gl_wrap_flags(),
                    *target.sys_libs(), f"-Wl,--version-script={exports}", "-Wl,--gc-sections", "-Wl,--no-undefined",
                    "-Wl,-z,max-page-size=16384", "-o", str(out)], check=True)


def write_build_info(noita_dir: Path, fmod_staged: bool):
    """assets/noitadroid.properties: what the app's SetupActivity (GameCheck) compares the game files on the device
    with. The recompiled code is for the noita.exe and msvcp120.dll of this build's game, so their hashes; data.wak's
    size tells a copy still going on, or broken off; FMOD's version, when its libraries are in."""
    import hashlib
    info = {f"{name}.sha256": hashlib.sha256((noita_dir / name).read_bytes()).hexdigest()
            for name in ("noita.exe", "msvcp120.dll")}
    info["data.wak.size"] = (noita_dir / "data/data.wak").stat().st_size
    header = target.DEPS / "fmod/api/core/inc/fmod_common.h"
    if fmod_staged and header.exists():
        v = int(re.search(r"#define FMOD_VERSION\s+0x([0-9a-fA-F]+)", header.read_text()).group(1), 16)
        info["fmod"] = f"{v >> 16:x}.{v >> 8 & 0xff:02x}.{v & 0xff:02x}"
    (OUT / "assets").mkdir(exist_ok=True)
    (OUT / "assets/noitadroid.properties").write_text("".join(f"{k}={v}\n" for k, v in info.items()))


ICON_DP = 56  # the glyph's height in the 108 dp adaptive icon: inside the 66 dp circle every launcher mask keeps


def make_icon(noita_dir: Path) -> bool:
    """The launcher icon from the game's own window icon (data/icon.bmp, Nolla's pixel-art N): it's the builder's
    copy of the game, so the repo has none of its art. An adaptive icon in build/android/res (android/app's Gradle
    build uses it when present): the glyph scaled by nearest neighbour on black, at each density."""
    import struct
    import zlib

    try:
        bmp = (noita_dir / "data/icon.bmp").read_bytes()
    except OSError:
        return False
    offset = struct.unpack_from("<I", bmp, 10)[0]
    w, h = struct.unpack_from("<ii", bmp, 18)
    if bmp[:2] != b"BM" or struct.unpack_from("<H", bmp, 28)[0] != 24:
        return False
    stride = (w * 3 + 3) & ~3

    def pixel(x: int, y: int) -> bytes:  # RGB; rows are bottom-up when the height is positive, pixels BGR
        i = offset + (abs(h) - 1 - y if h > 0 else y) * stride + x * 3
        return bmp[i:i + 3][::-1]

    lit = [(x, y) for y in range(abs(h)) for x in range(w) if sum(pixel(x, y)) > 30]
    x0, x1 = min(x for x, _ in lit), max(x for x, _ in lit) + 1
    y0, y1 = min(y for _, y in lit), max(y for _, y in lit) + 1

    def png(size: int) -> bytes:
        scale = ICON_DP / 108 * size / (y1 - y0)  # output pixels per source pixel
        cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
        rows = bytearray()
        for oy in range(size):
            rows.append(0)
            sy = int(cy + (oy + 0.5 - size / 2) / scale)
            for ox in range(size):
                sx = int(cx + (ox + 0.5 - size / 2) / scale)
                rgb = pixel(sx, sy) if x0 <= sx < x1 and y0 <= sy < y1 else b"\0\0\0"
                rows += rgb + (b"\xff" if sum(rgb) > 30 else b"\0")
        chunk = lambda kind, data: struct.pack(">I", len(data)) + kind + data + \
            struct.pack(">I", zlib.crc32(kind + data))
        return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)) + \
            chunk(b"IDAT", zlib.compress(bytes(rows), 9)) + chunk(b"IEND", b"")

    res = OUT / "res"
    for density, size in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
        (res / f"mipmap-{density}").mkdir(parents=True, exist_ok=True)
        (res / f"mipmap-{density}/ic_launcher_foreground.png").write_bytes(png(size))
    (res / "mipmap-anydpi-v26").mkdir(parents=True, exist_ok=True)
    (res / "mipmap-anydpi-v26/ic_launcher.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@android:color/black" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n')
    return True


def needed(lib: Path) -> list[str]:
    out = subprocess.run([str(target.NDK_BIN / "llvm-readelf"), "-d", str(lib)], capture_output=True, text=True,
                         check=True).stdout
    return [line.split("[")[1].rstrip("]") for line in out.splitlines() if "(NEEDED)" in line]


def stage(libmain: Path):
    """jniLibs: libmain.so, libSDL2.so, FMOD's libraries when present, and libc++_shared.so if one needs it."""
    for f in JNI.glob("*.so"):
        if f != libmain:
            f.unlink()
    libs = [libmain, target.DEPS / "build/SDL2/libSDL2.so"]
    fmod = [FMOD_LIB / "libfmod.so", FMOD_STUDIO_LIB / "libfmodstudio.so"]
    if all(f.exists() for f in fmod):
        libs += fmod
    else:
        print(f"no FMOD in {target.DEPS / 'fmod'}: the game runs silent (runtime/fmod_stub.c)")
    for lib in libs:
        if lib.parent != JNI:
            shutil.copy2(lib, JNI / lib.name)
    if any("libc++_shared.so" in needed(JNI / lib.name) for lib in libs):
        shutil.copy2(LIBCXX, JNI / "libc++_shared.so")
    return sorted(f.name for f in JNI.glob("*.so"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lto", choices=("thin", "off"), default="thin")
    ap.add_argument("--no-apk", action="store_true", help="stop after libmain.so")
    ap.add_argument("--pgo", choices=("gen", "use"), help="gen: writes its profile to PGO_RAW on the device; use: PGO_DATA")
    args = ap.parse_args()
    if not os.environ.get("NOITA_DIR"):
        sys.exit("set NOITA_DIR to the game folder (the bridges are generated from noita.exe's imports)")
    for d in (GEN, OBJ, JNI):
        d.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    n_sdl, bridged, n_gl = generate_bridges()
    luajit = build_all.build_luajit()
    lto = [f"-flto={args.lto}"] if args.lto != "off" else []
    pgo = [f"-fprofile-generate={PGO_RAW}", "-DMINA4MAC_PGO_GEN"] if args.pgo == "gen" else []
    if args.pgo == "use":
        profile = game_build.speed_file(PGO_DATA.name)  # this build's own (tools/game_build.py)
        if not profile:
            sys.exit(f"no {PGO_DATA.name} for this noita.exe's build")
        pgo = [f"-fprofile-use={profile}"]
    srcs = sources()
    newer_than = deps_mtime(" ".join([*target.cc(), *lto, *pgo]))
    t1 = time.time()
    with mp.Pool(os.cpu_count()) as pool:
        done = pool.map(compile_one, [(s, lto, pgo, newer_than) for s in srcs])
    objs = [o for o, _ in done]
    t2 = time.time()

    libmain = JNI / "libmain.so"
    link(objs, luajit, libmain, lto, pgo)
    t3 = time.time()
    print(f"bridges: SDL {n_sdl} thunks, GL {n_gl} generated of {len(bridged)}; {len(srcs)} sources, "
          f"{sum(c for _, c in done)} compiled; "
          f"compile {t2 - t1:.0f}s, link {t3 - t2:.0f}s; libmain.so {libmain.stat().st_size / 1e6:.1f} MB")
    staged = stage(libmain)
    print("jniLibs:", ", ".join(staged))
    write_build_info(Path(os.environ["NOITA_DIR"]), fmod_staged="libfmodstudio.so" in staged)
    if not make_icon(Path(os.environ["NOITA_DIR"])):
        print("no data/icon.bmp in NOITA_DIR: the app gets Android's default icon")
    if args.no_apk:
        return
    subprocess.run(["./gradlew", "-q", "assembleDebug"], cwd=ROOT / "android", check=True)
    apk = OUT / "noitadroid.apk"
    shutil.copy2(ROOT / "android/app/build/outputs/apk/debug/app-debug.apk", apk)
    print(f"{apk.relative_to(ROOT)}: {apk.stat().st_size / 1e6:.1f} MB ({time.time() - t0:.0f}s in all)")


if __name__ == "__main__":
    main()
