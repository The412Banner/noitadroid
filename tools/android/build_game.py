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

ROOT = target.ROOT
OUT = ROOT / "build/android"
GEN = OUT / "gen"
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
    src, lto, newer_than = job
    obj = obj_of(src)
    if obj.exists() and obj.stat().st_mtime >= max(src.stat().st_mtime, newer_than):
        return obj, False
    flags = [*target.cc(), "-c", "-O2", "-ffp-contract=off", "-fno-strict-aliasing", "-w", "-fPIC", *lto,
             *target.platform_cflags(), *target.gl_cflags(), *target.runtime_includes(ROOT / "runtime", GEN, HOST_GEN)]
    if (src.stem.startswith("sdl2") and src.stem != "sdl2_stdlib") or src.name in SDL_USERS:
        flags += target.sdl_cflags()
    if src.name in build_all.LUA:
        flags += build_all.LUA_CFLAGS
    if src.name == "main.c":
        flags.append("-Dmain=noitadroid_main")  # runtime/android_main.c's SDL_main calls it
    subprocess.run([*flags, str(src), "-o", str(obj)], check=True)
    return obj, True


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
    args = ap.parse_args()
    if not os.environ.get("NOITA_DIR"):
        sys.exit("set NOITA_DIR to the GOG install (the bridges are generated from noita.exe's imports)")
    for d in (GEN, OBJ, JNI):
        d.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    n_sdl, bridged, n_gl = generate_bridges()
    luajit = build_all.build_luajit()
    lto = [f"-flto={args.lto}"] if args.lto != "off" else []
    srcs = sources()
    newer_than = deps_mtime(" ".join([*target.cc(), *lto]))
    t1 = time.time()
    with mp.Pool(os.cpu_count()) as pool:
        done = pool.map(compile_one, [(s, lto, newer_than) for s in srcs])
    objs = [o for o, _ in done]
    t2 = time.time()

    exports = OUT / "exports.map"  # only SDL_main: SDL's Java side looks it up; everything else stays local
    exports.write_text("{ global: SDL_main; local: *; };\n")
    libmain = JNI / "libmain.so"
    link = [*target.cc(), "-shared", *target.linker(), *lto]
    if lto:
        (OUT / "lto_cache").mkdir(exist_ok=True)
        link += target.lto_cache(OUT / "lto_cache")
    subprocess.run([*link, *map(str, objs), str(luajit), *target.gl_libs(), *target.sdl_libs(), *target.gl_wrap_flags(),
                    *target.sys_libs(), f"-Wl,--version-script={exports}", "-Wl,--gc-sections", "-Wl,--no-undefined",
                    "-Wl,-z,max-page-size=16384", "-o", str(libmain)], check=True)
    t3 = time.time()
    print(f"bridges: SDL {n_sdl} thunks, GL {n_gl} generated of {len(bridged)}; {len(srcs)} sources, "
          f"{sum(c for _, c in done)} compiled; "
          f"compile {t2 - t1:.0f}s, link {t3 - t2:.0f}s; libmain.so {libmain.stat().st_size / 1e6:.1f} MB")
    print("jniLibs:", ", ".join(stage(libmain)))
    if args.no_apk:
        return
    subprocess.run(["./gradlew", "-q", "assembleDebug"], cwd=ROOT / "android", check=True)
    apk = OUT / "noitadroid.apk"
    shutil.copy2(ROOT / "android/app/build/outputs/apk/debug/app-debug.apk", apk)
    print(f"{apk.relative_to(ROOT)}: {apk.stat().st_size / 1e6:.1f} MB ({time.time() - t0:.0f}s in all)")


if __name__ == "__main__":
    main()
