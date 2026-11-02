"""The build target: compiler, include paths and link flags per platform, so the build scripts stay the same.

NOITADROID_TARGET picks it:
  mac      upstream mina4mac: Apple clang, ld64, the OpenGL framework, Homebrew's SDL2
  linux    the host's clang and lld, Mesa's libGL, the distribution's SDL2 (development and testing)
  android  the NDK's clang for arm64 (see docs/ANDROID.zh.md)
Default: mac on macOS, linux elsewhere.

On the device itself (tools/android/device_build.py, run by the app) NOITADROID_TOOLCHAIN names the app's native
library directory (libnd_llvm.so, libnd_ld.so, libSDL2.so) and NOITADROID_TOOLCHAIN_DATA the toolchain's extracted
assets (clang's headers, the sysroot): the android target then builds with those (tools/android/build_toolchain.py).

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
TOOLCHAIN = os.environ.get("NOITADROID_TOOLCHAIN")  # on the device: the app's native library directory
TOOLCHAIN_DATA = os.environ.get("NOITADROID_TOOLCHAIN_DATA")
ON_DEVICE = bool(TOOLCHAIN)
if ON_DEVICE and (NAME != "android" or not TOOLCHAIN_DATA):
    sys.exit("NOITADROID_TOOLCHAIN needs NOITADROID_TARGET=android and NOITADROID_TOOLCHAIN_DATA")


def _device_clang() -> list[str]:
    """The app's clang (LLVM's multicall binary), with its headers where the app extracted them."""
    return [f"{TOOLCHAIN}/libnd_llvm.so", "clang", "-resource-dir", f"{TOOLCHAIN_DATA}/clang"]


def cc() -> list[str]:
    """The C compiler and its target flags. ARMv8.2 + RCpc gives the TSO-ordered accesses (cpu.h) LDAPR."""
    if NAME == "android":
        clang = [*_device_clang(), f"--sysroot={TOOLCHAIN_DATA}/sysroot"] if ON_DEVICE else [str(NDK_BIN / "clang")]
        return [*clang, f"--target=aarch64-linux-android{ANDROID_API}", "-march=armv8.2-a+rcpc"]
    return ["clang"]


def guest_cc() -> list[str]:
    """A clang for the guest's (32-bit Windows) layouts: tools/gen_sdl.py adds the target."""
    return _device_clang() if ON_DEVICE else ["clang"]


def nm() -> list[str]:
    if ON_DEVICE:
        return [f"{TOOLCHAIN}/libnd_llvm.so", "nm"]
    return [str(NDK_BIN / "llvm-nm")] if NAME == "android" else ["nm"]


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
    if ON_DEVICE:  # clang's driver runs its linker by path: the app's lld, under a name that works (libnd_ld.so)
        return ["-fuse-ld=lld", f"--ld-path={TOOLCHAIN}/libnd_ld.so"]
    return [] if APPLE else ["-fuse-ld=lld"]


def lto_cache(path) -> list[str]:
    return [f"-Wl,-cache_path_lto,{path}"] if APPLE else [f"-Wl,--thinlto-cache-dir={path}"]


def order_file(path) -> list[str]:
    if not APPLE:  # ld64 order files name Mach-O symbols (leading underscore); lld's format differs
        sys.exit("--order is only implemented for the mac target")
    return [f"-Wl,-order_file,{path}"]


# Off Apple the game's GL is gl4es (desktop GL 2.1 over GLES), on Linux as on Android, so the desktop build runs
# the Android graphics path: runtime/gles.c makes SDL create GLES 3.0 contexts.
def gl4es_lib() -> Path | None:
    if APPLE:
        return None
    if NAME == "linux":
        return DEPS / "build/gl4es-linux/out/libGL.a"
    return DEPS / "build/gl4es/obj/local/arm64-v8a/libGL.a"


def gl_header_dir() -> Path:
    """Where gl.h and glext.h are: the GL the bridge can offer is what these declare."""
    if APPLE:
        sdk = subprocess.run(["xcrun", "--show-sdk-path"], capture_output=True, text=True, check=True).stdout.strip()
        return Path(sdk) / "System/Library/Frameworks/OpenGL.framework/Headers"
    return DEPS / "src/gl4es/include/GL"


def gl_provided_names() -> set[str] | None:
    """The GL commands the host GL implements (gl4es's exports), or None where the headers are the answer (mac)."""
    lib = gl4es_lib()
    if lib is None:
        return None
    out = subprocess.run([*nm(), "--defined-only", str(lib)], capture_output=True, text=True, check=True).stdout
    return {line.split()[-1] for line in out.splitlines() if " T gl" in line}


def gl_cflags() -> list[str]:
    return [] if APPLE else [f"-I{DEPS / 'src/gl4es/include'}"]


def gl_libs() -> list[str]:
    """GL to link: gl4es (static) and the GLES it runs on, which also serves the bridge's sync objects."""
    if APPLE:
        return ["-framework", "OpenGL"]
    gles = "-lGLESv3" if NAME == "android" else "-lGLESv2"
    return [str(gl4es_lib()), gles, "-lEGL"]


def gl_wrap_flags() -> list[str]:
    """Link flags that route SDL's context calls and buffer swaps through runtime/gles.c (none on Apple)."""
    if APPLE:
        return []
    wrapped = ("SDL_Init", "SDL_GL_SetAttribute", "SDL_GL_CreateContext", "SDL_GL_SwapWindow")
    return ["-Wl," + ",".join(f"--wrap={f}" for f in wrapped)]


def gl_glue() -> list[str]:
    """runtime/gles.c and its link flags, for one-line builds (tools/check.sh)."""
    return [] if APPLE else [str(ROOT / "runtime/gles.c"), *gl_wrap_flags()]


def sys_libs() -> list[str]:
    return {"mac": [], "linux": ["-lm", "-ldl", "-lpthread"], "android": ["-lm", "-ldl", "-llog", "-landroid"]}[NAME]


def _sdl2_config(flag) -> list[str]:
    return subprocess.run(["sdl2-config", flag], capture_output=True, text=True, check=True).stdout.split()


def sdl_cflags() -> list[str]:
    return [f"-I{DEPS / 'src/SDL2/include'}"] if NAME == "android" else _sdl2_config("--cflags")


def sdl_libs() -> list[str]:
    if NAME == "android":  # on the device, the APK's own libSDL2.so
        return [f"-L{TOOLCHAIN if ON_DEVICE else DEPS / 'build/SDL2'}", "-lSDL2"]
    return _sdl2_config("--libs")


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
        f"GL_CFLAGS={shlex.quote(j(gl_cflags()))}",
        f"GL_GLUE={shlex.quote(j(gl_glue()))}",
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
