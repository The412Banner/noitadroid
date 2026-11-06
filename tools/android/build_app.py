"""Builds the APK anyone can have: build/android-app/noitadroid.apk. It holds no code or data from the game and no
FMOD. The app builds the game's code on the device from the user's own copy (tools/android/device_build.py) with the
toolchain it carries (tools/android/build_toolchain.py, which must have run), and the user brings FMOD.

Staged in build/android-app (android/app's Gradle build with -Pnoitadroid.public):
  jniLibs/arm64-v8a/        libSDL2.so and the toolchain's programs and libraries
  assets/toolchain.zip      the toolchain's data: clang's headers, the sysroot, Python's library, D8
  assets/kit.zip            what device_build.py builds with, laid out as in the repo: the tools and runtime (not ignored),
                            the Khronos registry, LuaJIT's, gl4es's and SDL2's headers, LuaJIT and gl4es built for
                            arm64
  assets/noitadroid.properties   the game build the app is for, and the zips' versions (the app extracts a zip
                            again when its version changes)

  uv run tools/android/build_app.py [--no-apk] [--release]
"""
import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

os.environ["NOITADROID_TARGET"] = "android"  # before the build modules read it
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import target  # noqa: E402
import build_all  # noqa: E402
import device_build  # noqa: E402
import game_build  # noqa: E402

ROOT = target.ROOT
OUT = ROOT / "build/android-app"
JNI = OUT / "jniLibs/arm64-v8a"
ASSETS = OUT / "assets"
TOOLCHAIN = target.DEPS / "toolchain"
DATA_WAK_SIZE = 42467033  # data/data.wak of Noita of Jan 25 2025 (GOG and Steam): a smaller one is a copy still going on, or broken off

KIT_TRACKED = ("tools/*.py", "tools/*.txt", "tools/*.profdata", "tools/android/build_game.py", "tools/android/device_build.py",
               "runtime/**", "third_party/khronos/**")
KIT_GLOBS = (("third_party/luajit/src", "*.h"), (".local-deps/src/gl4es/include", "**/*.h"),
             (".local-deps/src/SDL2/include", "**/*.h"))
KIT_BUILT = (build_all.LUAJIT_LIB, target.gl4es_lib())


def kit_files() -> list[str]:
    tracked = subprocess.run(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", *KIT_TRACKED], cwd=ROOT, capture_output=True,
                             check=True).stdout.decode().split("\0")
    files = {f for f in tracked if f and (ROOT / f).is_file()}
    for base, pattern in KIT_GLOBS:
        files |= {str(p.relative_to(ROOT)) for p in (ROOT / base).glob(pattern) if p.is_file()}
    files |= {str(p.relative_to(ROOT)) for p in KIT_BUILT}
    return sorted(files)


def write_zip(out: Path, entries: dict[str, bytes]) -> str:
    """A deflated zip with fixed times, so the same files give the same zip; its version, from the contents."""
    h = hashlib.sha256()
    for name in sorted(entries):
        h.update(name.encode() + b"\0" + hashlib.sha256(entries[name]).digest())
    version = h.hexdigest()[:16]
    tmp = out.with_name(out.name + ".tmp")
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            z.writestr(info, entries[name])
    tmp.replace(out)
    return version


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--no-apk", action="store_true", help="stage build/android-app only")
    ap.add_argument("--release", action="store_true",
                    help="a clean release build, signed with .local-deps/keys/release.properties' key when there is one: "
                         "build/android-app/noitadroid-<version>.apk")
    args = ap.parse_args()
    if not (TOOLCHAIN / "jniLibs/arm64-v8a/libnd_llvm.so").exists():
        sys.exit("no toolchain: run tools/android/build_toolchain.py first")
    for d in (JNI, ASSETS):
        shutil.rmtree(d, ignore_errors=True)
        d.mkdir(parents=True)

    build_all.build_luajit()
    for lib in (*KIT_BUILT, target.DEPS / "build/SDL2/libSDL2.so"):
        if not lib.exists():
            sys.exit(f"{lib} is missing: run tools/android/deps.sh (and tools/build_all.py's LuaJIT build)")

    for lib in [target.DEPS / "build/SDL2/libSDL2.so", *(TOOLCHAIN / "jniLibs/arm64-v8a").glob("*.so")]:
        shutil.copy2(lib, JNI / lib.name)

    files = kit_files()
    entries = {f: (ROOT / f).read_bytes() for f in files}
    kit_version = hashlib.sha256(b"".join(f.encode() + hashlib.sha256(b).digest() for f, b in sorted(entries.items())))
    entries["KIT_VERSION"] = kit_version.hexdigest()[:16].encode() + b"\n"
    kit = write_zip(ASSETS / "kit.zip", entries)
    data = TOOLCHAIN / "assets/toolchain"
    tc = write_zip(ASSETS / "toolchain.zip",
                   {str(p.relative_to(data)): p.read_bytes() for p in data.rglob("*") if p.is_file()})

    # The game builds the app takes (tools/game_build.py): their noita.exe hashes and names, in the same order.
    props = {"noita.exe.sha256": ",".join(b.exe_sha256 for b in game_build.BUILDS),
             "noita.exe.games": ",".join(b.name for b in game_build.BUILDS),
             "msvcp120.dll.sha256": game_build.MSVCP120_SHA256}
    props.update({"data.wak.size": DATA_WAK_SIZE, "native": "device", "native.abi": device_build.NATIVE_ABI, "kit.version": kit,
                  "toolchain.version": tc})
    (ASSETS / "noitadroid.properties").write_text("".join(f"{k}={v}\n" for k, v in props.items()))
    size = lambda p: f"{p.stat().st_size / 1e6:.1f} MB"  # noqa: E731
    print(f"kit.zip {size(ASSETS / 'kit.zip')} ({len(files)} files, {kit}); "
          f"toolchain.zip {size(ASSETS / 'toolchain.zip')} ({tc}); jniLibs: {', '.join(sorted(p.name for p in JNI.iterdir()))}")
    if args.no_apk:
        return
    kind = "release" if args.release else "debug"
    subprocess.run(["./gradlew", "-q", "-Pnoitadroid.public", "clean" if args.release else "help",
                    f"assemble{kind.capitalize()}"], cwd=ROOT / "android", check=True)
    version = re.search(r'versionName = "([^"]+)"', (ROOT / "android/app/build.gradle.kts").read_text()).group(1)
    apk = OUT / (f"noitadroid-{version}.apk" if args.release else "noitadroid.apk")
    shutil.copy2(ROOT / f"android/app/build/outputs/apk/{kind}/app-{kind}.apk", apk)
    print(f"{apk.relative_to(ROOT)}: {size(apk)}, sha256 {hashlib.sha256(apk.read_bytes()).hexdigest()}")


if __name__ == "__main__":
    main()
