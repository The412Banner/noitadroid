"""The build target: compiler, include paths and link flags per platform, so the build scripts stay the same.

NOITADROID_TARGET picks it:
  mac      upstream mina4mac: Apple clang, ld64, the OpenGL framework, Homebrew's SDL2
  linux    the host's clang and lld, Mesa's libGL, the distribution's SDL2 (development and testing)
  android  the NDK's clang for arm64 (see docs/ANDROID.zh.md)
Default: mac on macOS, linux elsewhere.

On Linux and Android runtime/compat/compat.h is force-included and runtime/compat/ is searched first: it stands in
for the Darwin headers. The runtime's own directories go on the quote path there (-iquote), because
runtime/sched.h would otherwise shadow the <sched.h> that glibc's <pthread.h> includes.

  uv run tools/target.py --shell    shell assignments (CC, RT_INC, PLATFORM_CFLAGS, TEST_CFLAGS, GL_LIBS, ...) for tools/check.sh
"""
import os
import shlex
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NAME = os.environ.get("NOITADROID_TARGET") or ("mac" if sys.platform == "darwin" else "linux")
if NAME not in ("mac", "linux", "android"):
    sys.exit(f"NOITADROID_TARGET={NAME}: expected mac, linux or android")
APPLE = NAME == "mac"
COMPAT = ROOT / "runtime/compat"
DEPS = ROOT / ".local-deps"
NDK = Path(os.environ.get("ANDROID_NDK_HOME") or DEPS / "ndk")
ANDROID_API = 29
NDK_BIN = NDK / "toolchains/llvm/prebuilt/linux-x86_64/bin"


def cc() -> list[str]:
    """The C compiler and its target flags. ARMv8.2 + RCpc gives the TSO-ordered accesses (cpu.h) LDAPR."""
    if NAME == "android":
        return [str(NDK_BIN / "clang"), f"--target=aarch64-linux-android{ANDROID_API}", "-march=armv8.2-a+rcpc"]
    return ["clang"]


def platform_cflags() -> list[str]:
    return [] if APPLE else ["-include", str(COMPAT / "compat.h"), "-I", str(COMPAT)]


def runtime_includes(*dirs) -> list[str]:
    flag = "-I" if APPLE else "-iquote"
    return [x for d in dirs for x in (flag, str(d))]


def test_cflags() -> list[str]:
    """Extra flags for tools/check.sh's -Werror test builds. The tests print uint64_t with %llx, which is
    unsigned long long on macOS and unsigned long (same size) on Linux and Android."""
    return [] if APPLE else ["-Wno-format"]


def linker() -> list[str]:
    return [] if APPLE else ["-fuse-ld=lld"]


def lto_cache(path) -> list[str]:
    return [f"-Wl,-cache_path_lto,{path}"] if APPLE else [f"-Wl,--thinlto-cache-dir={path}"]


def order_file(path) -> list[str]:
    if not APPLE:  # ld64 order files name Mach-O symbols (leading underscore); lld's format differs
        sys.exit("--order is only implemented for the mac target")
    return [f"-Wl,-order_file,{path}"]


def gl_header_dir() -> Path:
    """Where gl.h and glext.h are: the GL the bridge can offer is what these declare."""
    if APPLE:
        sdk = subprocess.run(["xcrun", "--show-sdk-path"], capture_output=True, text=True, check=True).stdout.strip()
        return Path(sdk) / "System/Library/Frameworks/OpenGL.framework/Headers"
    if NAME == "linux":
        return Path("/usr/include/GL")
    return DEPS / "src/gl4es/include/GL"


def gl_libs() -> list[str]:
    if APPLE:
        return ["-framework", "OpenGL"]
    if NAME == "linux":
        return ["-lGL"]
    # gl4es is static; GLES3 directly for what it lacks (sync objects, runtime/compat/OpenGL/glext.h)
    return [str(DEPS / "build/gl4es/obj/local/arm64-v8a/libGL.a"), "-lGLESv3", "-lEGL"]


def sys_libs() -> list[str]:
    return {"mac": [], "linux": ["-lm", "-ldl", "-lpthread"], "android": ["-lm", "-ldl", "-llog", "-landroid"]}[NAME]


def _sdl2_config(flag) -> list[str]:
    return subprocess.run(["sdl2-config", flag], capture_output=True, text=True, check=True).stdout.split()


def sdl_cflags() -> list[str]:
    return [f"-I{DEPS / 'src/SDL2/include'}"] if NAME == "android" else _sdl2_config("--cflags")


def sdl_libs() -> list[str]:
    return [f"-L{DEPS / 'build/SDL2'}", "-lSDL2"] if NAME == "android" else _sdl2_config("--libs")


def sdl_include() -> str:
    """The directory with SDL.h (tools/gen_sdl.py reads the declarations and layouts from it)."""
    return next(f[2:] for f in sdl_cflags() if f.startswith("-I"))


def luajit_make() -> list[str]:
    """Extra make arguments for the static LuaJIT (tools/build_all.py build_luajit)."""
    if APPLE:
        return ["MACOSX_DEPLOYMENT_TARGET=14.0"]
    if NAME == "linux":
        return []
    ndk_cc = str(NDK_BIN / f"aarch64-linux-android{ANDROID_API}-clang")
    # PIC throughout: the static library ends up inside libmain.so
    return ["HOST_CC=gcc", f"CROSS={NDK_BIN}/llvm-", f"STATIC_CC={ndk_cc} -fPIC", f"DYNAMIC_CC={ndk_cc} -fPIC",
            f"TARGET_LD={ndk_cc}", f"TARGET_AR={NDK_BIN}/llvm-ar rcus", f"TARGET_STRIP={NDK_BIN}/llvm-strip",
            "TARGET_SYS=Linux"]


def shell() -> str:
    j = shlex.join
    return "\n".join([
        f"TARGET={NAME}",
        f"CC={shlex.quote(j(cc()))}",
        f"PLATFORM_CFLAGS={shlex.quote(j(platform_cflags()))}",
        f"RT_INC={shlex.quote(j(runtime_includes(ROOT / 'runtime')))}",
        f"GEN_INC={shlex.quote(j(runtime_includes(ROOT / 'build/gen_all')))}",
        f"LINKER={shlex.quote(j(linker()))}",
        f"TEST_CFLAGS={shlex.quote(j(test_cflags()))}",
        f"GL_LIBS={shlex.quote(j(gl_libs()))}",
        f"SYS_LIBS={shlex.quote(j(sys_libs()))}",
        f"SDL_CFLAGS={shlex.quote(j(sdl_cflags()))}",
        f"SDL_LIBS={shlex.quote(j(sdl_libs()))}",
        f"DYLIB={'dylib' if APPLE else 'so'}",
    ])


if __name__ == "__main__":
    if sys.argv[1:] == ["--shell"]:
        print(shell())
    else:
        print(NAME)
