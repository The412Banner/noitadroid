"""Builds the toolchain the app carries so it can build the game on the device itself. Nothing in it comes from the
game: the app runs it on the user's own game files.

Output in .local-deps/toolchain:
  jniLibs/arm64-v8a/      programs and libraries. Android runs programs only from an app's native library
                          directory, and installs only lib*.so files there.
    libnd_llvm.so         clang, lld and llvm-nm 21.1.8 in one static binary (LLVM's multicall driver), for arm64
                          and x86: `libnd_llvm.so clang ...`, `libnd_llvm.so ld.lld ...`
    libnd_ld.so           lld under a name clang's driver can run (--ld-path)
    libnd_python.so       Python's command line; libpython3.14.so: python.org's Android build of CPython 3.14
    libcapstone.so        capstone 5.0.9, x86 only, for its Python binding
    libnd_innoextract.so  innoextract (static): unpacks GOG's offline installer on the device
  assets/toolchain/       extracted by the app to its own storage
    clang/                clang's headers; the NDK's compiler-rt builtins and libunwind
    sysroot/usr/          bionic's headers and API 29 libraries, from the NDK
    python/               the standard library (trimmed), with its extension modules; site-packages: capstone's
                          binding and pefile
    d8.jar                Android's D8, as dex: turns the user's fmod.jar into dex on the device
    licenses/, VERSION

  uv run tools/android/build_toolchain.py [step ...]
      steps: llvm ld sysroot python capstone pefile d8 innoextract (default: all)

NOITADROID_TOOLCHAIN_WORK=<dir> holds downloads and builds (default .local-deps/toolchain-work). LLVM's build needs
about 10 GB and an hour on 12 cores; put it on a fast disk.
"""
import hashlib
import os
import shutil
import subprocess
import sys
import tarfile
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
os.environ["NOITADROID_TARGET"] = "android"
import target  # noqa: E402

ROOT = target.ROOT
API = target.ANDROID_API
OUT = target.DEPS / "toolchain"
JNI = OUT / "jniLibs/arm64-v8a"
ASSETS = OUT / "assets/toolchain"
WORK = Path(os.environ.get("NOITADROID_TOOLCHAIN_WORK") or target.DEPS / "toolchain-work")
HERE = Path(__file__).resolve().parent / "toolchain"
NDK_SYSROOT = target.NDK / "toolchains/llvm/prebuilt/linux-x86_64/sysroot"
NDK_CLANG = target.NDK_BIN / f"aarch64-linux-android{API}-clang"
STRIP = target.NDK_BIN / "llvm-strip"

LLVM_VERSION = "21.1.8"
PYTHON_VERSION = "3.14.9"
CAPSTONE_VERSION = "5.0.9"
PEFILE_VERSION = "2024.8.26"
BUILD_TOOLS = "36.0.0"  # D8 comes from the SDK's build-tools
# innoextract 1.9 and its fixes since (Inno Setup 6.4, GOG Galaxy installers, newer Boost), 2025-02-06
INNOEXTRACT_COMMIT = "6e9e34ed0876014fdb46e684103ef8c3605e382e"
BOOST_VERSION = "1.92.0"
XZ_VERSION = "5.8.1"
BZIP2_VERSION = "1.0.8"

DOWNLOADS = {  # name: (url, sha256)
    "llvm": (f"https://github.com/llvm/llvm-project/releases/download/llvmorg-{LLVM_VERSION}/"
             f"llvm-project-{LLVM_VERSION}.src.tar.xz",
             "4633a23617fa31a3ea51242586ea7fb1da7140e426bd62fc164261fe036aa142"),
    "python": (f"https://www.python.org/ftp/python/{PYTHON_VERSION}/python-{PYTHON_VERSION}-aarch64-linux-android.tar.gz",
               "e2601ffefb36bd34f49e8743d7ba3dad793bb4478fe879d45773311868b85cc5"),
    "capstone": ("https://files.pythonhosted.org/packages/39/01/6516910f546fbb996068207b9dd0229b14bc8dae223114d5e0e27d3cad11/"
                 f"capstone-{CAPSTONE_VERSION}.tar.gz",
                 "0429af292ddc604d3c9344696807e281d5e728db029f00e6a4ea9e3bff1aac9e"),
    "pefile": ("https://files.pythonhosted.org/packages/03/4f/2750f7f6f025a1507cd3b7218691671eecfd0bbebebe8b39aa0fe1d360b8/"
               f"pefile-{PEFILE_VERSION}.tar.gz",
               "3ff6c5d8b43e8c37bb6e6dd5085658d658a7a0bdcd20b6a07b1fcfc1c4e9d632"),
    "innoextract": (f"https://github.com/dscharrer/innoextract/archive/{INNOEXTRACT_COMMIT}.tar.gz",
                    "4d885b27e452dcb137a9d97736b19478447f172bcfed1aa98d80138e9a4c08ae"),
    "boost": (f"https://github.com/boostorg/boost/releases/download/boost-{BOOST_VERSION}/boost-{BOOST_VERSION}-cmake.tar.xz",
              "9bed76128d4e46755dbe818487788c6fceb6f72b378f4daa49b7e1e600d9088d"),
    "xz": (f"https://github.com/tukaani-project/xz/releases/download/v{XZ_VERSION}/xz-{XZ_VERSION}.tar.xz",
           "0b54f79df85912504de0b14aec7971e3f964491af1812d83447005807513cd9e"),
    "bzip2": (f"https://sourceware.org/pub/bzip2/bzip2-{BZIP2_VERSION}.tar.gz",
              "ab5a03176ee106d3f0fa90e381da478ddae405918153cca248e682cd0c4a2269"),
}

# The standard library minus what a build never imports (and what needs OpenSSL, SQLite or Tk).
PY_DROP_DIRS = {"test", "idlelib", "tkinter", "turtledemo", "ensurepip", "pydoc_data", "venv", "__phello__",
                "site-packages", "__pycache__"}
PY_DROP_MODULES = ("_ssl", "_hashlib", "_sqlite3", "_tkinter", "_test", "_ctypes_test", "xxlimited", "xxsubtype",
                   "_xxtestfuzz")


def run(cmd, **kw):
    print("+", " ".join(map(str, cmd))[:300], flush=True)
    subprocess.run([str(c) for c in cmd], check=True, **kw)


def fetch(name: str) -> Path:
    """The download, checked against its pinned sha256."""
    url, sha = DOWNLOADS[name]
    path = WORK / "dl" / url.rsplit("/", 1)[1]
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != sha:
        print(f"fetch   {url}", flush=True)
        part = path.with_name(path.name + ".part")
        urllib.request.urlretrieve(url, part)
        got = hashlib.sha256(part.read_bytes()).hexdigest()
        if got != sha:
            part.unlink()
            sys.exit(f"{path.name}: sha256 {got}, expected {sha}")
        part.replace(path)
    return path


def extract(name: str) -> Path:
    """The download unpacked in WORK/src/<name> (once): its top directory, or that directory when it has several."""
    archive = fetch(name)
    dest = WORK / "src" / name
    with tarfile.open(archive) as t:
        tops = {n.removeprefix("./").split("/")[0] for n in t.getnames()} - {"", "."}
        if not (dest / ".done").exists():
            shutil.rmtree(dest, ignore_errors=True)
            dest.mkdir(parents=True)
            t.extractall(dest, filter="data")
            (dest / ".done").write_text(archive.name)
    return dest / tops.pop() if len(tops) == 1 else dest


def sdk_dir() -> Path:
    if os.environ.get("ANDROID_HOME"):
        return Path(os.environ["ANDROID_HOME"])
    for line in (ROOT / "android/local.properties").read_text().splitlines():
        if line.startswith("sdk.dir="):
            return Path(line.split("=", 1)[1])
    sys.exit("set ANDROID_HOME, or sdk.dir in android/local.properties")


def license(name: str, *files: Path):
    d = ASSETS / "licenses" / name
    d.mkdir(parents=True, exist_ok=True)
    for f in files:
        shutil.copy2(f, d / f.name)


def ndk_cmake_flags() -> list[str]:
    return [f"-DCMAKE_TOOLCHAIN_FILE={target.NDK}/build/cmake/android.toolchain.cmake", "-DANDROID_ABI=arm64-v8a",
            f"-DANDROID_PLATFORM=android-{API}", "-DCMAKE_BUILD_TYPE=Release"]


def step_llvm():
    """clang, lld and llvm-nm as LLVM's multicall driver, static (libc++ inside), arm64 and x86 targets (the x86
    one for the SDL bridge's guest layouts). clang runs -cc1 in its own process (CLANG_SPAWN_CC1 is off)."""
    src = extract("llvm")
    build = WORK / "llvm-build"
    flags = "-ffunction-sections -fdata-sections"
    run(["cmake", "-G", "Ninja", "-S", src / "llvm", "-B", build, *ndk_cmake_flags(), "-DANDROID_STL=c++_static",
         f"-DCMAKE_C_FLAGS={flags}", f"-DCMAKE_CXX_FLAGS={flags}",
         "-DCMAKE_EXE_LINKER_FLAGS=-Wl,--gc-sections -Wl,-z,max-page-size=16384",
         "-DLLVM_ENABLE_PROJECTS=clang;lld", "-DLLVM_TARGETS_TO_BUILD=AArch64;X86",
         "-DLLVM_TOOL_LLVM_DRIVER_BUILD=ON",
         "-DLLVM_DISTRIBUTION_COMPONENTS=clang;lld;llvm-nm;llvm-driver;clang-resource-headers",
         f"-DLLVM_HOST_TRIPLE=aarch64-unknown-linux-android{API}",
         f"-DLLVM_DEFAULT_TARGET_TRIPLE=aarch64-unknown-linux-android{API}",
         "-DCROSS_TOOLCHAIN_FLAGS_NATIVE=-DCMAKE_C_COMPILER=clang;-DCMAKE_CXX_COMPILER=clang++",
         "-DLLVM_ENABLE_ZLIB=OFF", "-DLLVM_ENABLE_ZSTD=OFF", "-DLLVM_ENABLE_LIBXML2=OFF", "-DLLVM_ENABLE_LIBEDIT=OFF",
         "-DLLVM_ENABLE_LIBPFM=OFF", "-DLLVM_ENABLE_CURL=OFF", "-DLLVM_ENABLE_HTTPLIB=OFF",
         "-DLLVM_INCLUDE_TESTS=OFF", "-DLLVM_INCLUDE_EXAMPLES=OFF", "-DLLVM_INCLUDE_BENCHMARKS=OFF",
         "-DLLVM_INCLUDE_DOCS=OFF", "-DCLANG_INCLUDE_TESTS=OFF", "-DCLANG_ENABLE_ARCMT=OFF",
         "-DCLANG_ENABLE_STATIC_ANALYZER=OFF", "-DCLANG_PLUGIN_SUPPORT=OFF"])
    run(["ninja", "-C", build, "llvm-driver", "clang-resource-headers"])
    JNI.mkdir(parents=True, exist_ok=True)
    run([STRIP, "--strip-all", build / "bin/llvm", "-o", JNI / "libnd_llvm.so"])
    inc = ASSETS / "clang/include"
    shutil.rmtree(inc, ignore_errors=True)
    shutil.copytree(build / f"lib/clang/{LLVM_VERSION.split('.')[0]}/include", inc)
    license("llvm", src / "llvm/LICENSE.TXT")


def step_ld():
    JNI.mkdir(parents=True, exist_ok=True)
    run([NDK_CLANG, "-O2", "-Wall", "-Wextra", "-Werror", "-Wl,-z,max-page-size=16384", HERE / "nd_ld.c",
         "-o", JNI / "libnd_ld.so"])
    run([STRIP, JNI / "libnd_ld.so"])


def step_sysroot():
    """What compiling and linking the game for arm64 needs from the NDK: bionic's C headers (not C++), the API level's
    libraries and startup objects, and the compiler's runtime (builtins, libunwind) where clang's driver looks."""
    dest = ASSETS / "sysroot/usr"
    shutil.rmtree(dest, ignore_errors=True)
    shutil.copytree(NDK_SYSROOT / "usr/include", dest / "include", ignore=shutil.ignore_patterns("c++"))
    lib = dest / "lib/aarch64-linux-android" / str(API)
    lib.mkdir(parents=True)
    for f in (NDK_SYSROOT / "usr/lib/aarch64-linux-android" / str(API)).iterdir():
        if f.suffix in (".so", ".o"):
            shutil.copy2(f, lib / f.name)
    rt = next((target.NDK / "toolchains/llvm/prebuilt/linux-x86_64/lib/clang").iterdir()) / "lib/linux"
    out = ASSETS / "clang/lib/linux"
    (out / "aarch64").mkdir(parents=True, exist_ok=True)
    shutil.copy2(rt / "libclang_rt.builtins-aarch64-android.a", out)
    shutil.copy2(rt / "aarch64/libunwind.a", out / "aarch64")
    license("ndk", target.NDK / "NOTICE")


def step_python():
    """python.org's Android build: libpython3.14.so in jniLibs, the standard library in assets (its extension modules
    are loaded from the app's storage, which Android allows for libraries), and a command line built here."""
    src = extract("python")
    prefix = src / "prefix"
    ver = ".".join(PYTHON_VERSION.split(".")[:2])
    JNI.mkdir(parents=True, exist_ok=True)
    shutil.copy2(prefix / f"lib/libpython{ver}.so", JNI)
    run([NDK_CLANG, "-O2", "-Wall", "-Werror", f"-I{prefix}/include/python{ver}", HERE / "nd_python.c",
         f"-L{prefix}/lib", f"-lpython{ver}", "-Wl,-rpath,$ORIGIN", "-Wl,-z,max-page-size=16384",
         "-o", JNI / "libnd_python.so"])
    stdlib = ASSETS / f"python/lib/python{ver}"
    shutil.rmtree(stdlib, ignore_errors=True)

    def ignore(d, names):
        drop = {n for n in names if n in PY_DROP_DIRS or n.startswith("config-")}
        if Path(d).name == "lib-dynload":
            drop |= {n for n in names if n.split(".")[0].startswith(PY_DROP_MODULES)}
        return drop
    shutil.copytree(prefix / f"lib/python{ver}", stdlib, ignore=ignore)
    for so in (stdlib / "lib-dynload").glob("*.so"):  # anything they need besides the system and libpython?
        needed = subprocess.run([target.NDK_BIN / "llvm-readelf", "-d", so], capture_output=True, text=True).stdout
        for line in needed.splitlines():
            if "(NEEDED)" in line:
                lib = line.split("[")[1].rstrip("]")
                if lib not in ("libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so", f"libpython{ver}.so"):
                    sys.exit(f"{so.name} needs {lib}")
    license("python", next(prefix.glob(f"lib/python{ver}/LICENSE.txt")))


def step_capstone():
    """capstone's C library, x86 only, for the Python binding (which loads it with ctypes from LIBCAPSTONE_PATH)."""
    src = extract("capstone")
    build = WORK / "capstone-build"
    run(["cmake", "-G", "Ninja", "-S", src / "src", "-B", build, *ndk_cmake_flags(), "-DBUILD_SHARED_LIBS=ON",
         "-DBUILD_STATIC_LIBS=OFF", "-DCAPSTONE_ARCHITECTURE_DEFAULT=OFF", "-DCAPSTONE_X86_SUPPORT=ON",
         "-DCAPSTONE_BUILD_TESTS=OFF", "-DCAPSTONE_BUILD_CSTOOL=OFF", "-DCAPSTONE_INSTALL=OFF",
         "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"])
    run(["ninja", "-C", build])
    JNI.mkdir(parents=True, exist_ok=True)
    lib = next(p for p in build.glob("libcapstone.so*") if not p.is_symlink())
    run([STRIP, lib, "-o", JNI / "libcapstone.so"])
    pkg = ASSETS / "python/site-packages/capstone"
    shutil.rmtree(pkg, ignore_errors=True)
    shutil.copytree(src / "capstone", pkg, ignore=shutil.ignore_patterns("lib", "include", "__pycache__"))
    license("capstone", src / "src/LICENSE.TXT", src / "src/LICENSE_LLVM.TXT")


def step_pefile():
    src = extract("pefile")
    site = ASSETS / "python/site-packages"
    site.mkdir(parents=True, exist_ok=True)
    for f in ("pefile.py", "peutils.py"):
        shutil.copy2(src / f, site)
    shutil.rmtree(site / "ordlookup", ignore_errors=True)
    shutil.copytree(src / "ordlookup", site / "ordlookup", ignore=shutil.ignore_patterns("__pycache__"))
    license("pefile", src / "LICENSE")


def step_d8():
    """D8 from the SDK's build-tools, itself turned into dex so the app can load it (DexClassLoader)."""
    sdk = sdk_dir()
    jar = sdk / f"build-tools/{BUILD_TOOLS}/lib/d8.jar"
    ASSETS.mkdir(parents=True, exist_ok=True)
    out = ASSETS / "d8.jar"
    out.unlink(missing_ok=True)
    run(["java", "-cp", jar, "com.android.tools.r8.D8", "--release", "--min-api", API, "--lib",
         sdk / "platforms/android-36/android.jar", "--output", out, jar])
    license("d8", sdk / f"build-tools/{BUILD_TOOLS}/NOTICE.txt")


def ndk_cmake(src: Path, build: Path, prefix: Path, extra: list[str]):
    """Configure, build and install a CMake project for arm64 into prefix. The NDK's toolchain file searches only
    under its find roots: prefix joins them, so the libraries built here are found, and the host's never are."""
    run(["cmake", "-G", "Ninja", "-S", src, "-B", build, *ndk_cmake_flags(), f"-DCMAKE_INSTALL_PREFIX={prefix}",
         f"-DCMAKE_FIND_ROOT_PATH={prefix}", "-DCMAKE_POSITION_INDEPENDENT_CODE=ON", *extra])
    run(["cmake", "--build", build])
    run(["cmake", "--install", build])


def step_innoextract():
    """innoextract, static, so the app can unpack GOG's offline installer itself. Its libraries are built here, static:
    liblzma (xz), bzip2 and the Boost libraries it uses. zlib and iconv are Android's own."""
    prefix = WORK / "ie-prefix"
    shutil.rmtree(prefix, ignore_errors=True)
    (prefix / "lib").mkdir(parents=True)
    (prefix / "include").mkdir()

    xz = extract("xz")
    ndk_cmake(xz, WORK / "xz-build", prefix, [
        "-DBUILD_SHARED_LIBS=OFF", "-DXZ_NLS=OFF", "-DXZ_DOC=OFF", "-DXZ_TOOL_XZ=OFF", "-DXZ_TOOL_XZDEC=OFF",
        "-DXZ_TOOL_LZMADEC=OFF", "-DXZ_TOOL_LZMAINFO=OFF", "-DXZ_TOOL_SCRIPTS=OFF"])

    bz = extract("bzip2")
    objs = []
    for name in ("blocksort", "huffman", "crctable", "randtable", "compress", "decompress", "bzlib"):
        obj = WORK / "bzip2-build" / f"{name}.o"
        obj.parent.mkdir(parents=True, exist_ok=True)
        run([NDK_CLANG, "-O2", "-fPIC", "-D_FILE_OFFSET_BITS=64", "-w", "-c", bz / f"{name}.c", "-o", obj])
        objs.append(obj)
    run([target.NDK_BIN / "llvm-ar", "rcs", prefix / "lib/libbz2.a", *objs])
    shutil.copy2(bz / "bzlib.h", prefix / "include")

    boost = extract("boost")
    ndk_cmake(boost, WORK / "boost-build", prefix, [
        "-DBUILD_SHARED_LIBS=OFF", "-DBUILD_TESTING=OFF",
        "-DBOOST_INCLUDE_LIBRARIES=iostreams;filesystem;date_time;system;program_options",
        "-DBOOST_IOSTREAMS_ENABLE_ZLIB=ON", "-DBOOST_IOSTREAMS_ENABLE_BZIP2=ON", "-DBOOST_IOSTREAMS_ENABLE_LZMA=OFF",
        "-DBOOST_IOSTREAMS_ENABLE_ZSTD=OFF"])
    # The install has the built libraries' headers and their dependencies'; innoextract also uses header-only
    # libraries (foreach, ...) outside those: all of Boost's headers.
    for inc in [*boost.glob("libs/*/include"), *boost.glob("libs/numeric/*/include")]:
        shutil.copytree(inc, prefix / "include", dirs_exist_ok=True)

    ie = extract("innoextract")
    build = WORK / "innoextract-build"
    run(["cmake", "-G", "Ninja", "-S", ie, "-B", build, *ndk_cmake_flags(), f"-DCMAKE_FIND_ROOT_PATH={prefix}",
         "-DUSE_STATIC_LIBS=ON", "-DUSE_LDGOLD=OFF", "-DUSE_LTO=OFF", "-DSTRICT_USE=ON",
         "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384"])
    run(["cmake", "--build", build])
    JNI.mkdir(parents=True, exist_ok=True)
    run([STRIP, build / "innoextract", "-o", JNI / "libnd_innoextract.so"])
    license("innoextract", ie / "LICENSE")
    license("boost", boost / "LICENSE_1_0.txt")
    license("xz", xz / "COPYING", xz / "COPYING.0BSD")
    license("bzip2", bz / "LICENSE")


STEPS = {"llvm": step_llvm, "ld": step_ld, "sysroot": step_sysroot, "python": step_python, "capstone": step_capstone,
         "pefile": step_pefile, "d8": step_d8, "innoextract": step_innoextract}


def main():
    steps = sys.argv[1:] or list(STEPS)
    for s in steps:
        if s not in STEPS:
            sys.exit(f"unknown step {s}: {' '.join(STEPS)}")
    for s in steps:
        print(f"== {s}", flush=True)
        STEPS[s]()
    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / "VERSION").write_text(f"llvm {LLVM_VERSION}\npython {PYTHON_VERSION}\ncapstone {CAPSTONE_VERSION}\n"
                                    f"pefile {PEFILE_VERSION}\nd8 build-tools {BUILD_TOOLS}\nandroid-api {API}\n")
    for f in sorted(JNI.glob("*.so")):
        print(f"{f.name:22} {f.stat().st_size / 1e6:6.1f} MB")
    size = sum(f.stat().st_size for f in ASSETS.rglob("*") if f.is_file())
    print(f"assets/toolchain       {size / 1e6:6.1f} MB")


if __name__ == "__main__":
    main()
