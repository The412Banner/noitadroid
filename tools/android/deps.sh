#!/usr/bin/env bash
# Fetches and builds the Android dependencies into .local-deps/ (gitignored):
#   gl4es (desktop GL 2.1 over GLES2, static libGL.a)   .local-deps/src/gl4es  -> .local-deps/build/gl4es (and gl4es-linux)
#   SDL2 2.32.10 (libSDL2.so and its Java glue)          .local-deps/src/SDL2   -> .local-deps/build/SDL2
# LuaJIT is built by tools/build_all.py for the target. Needs the NDK at .local-deps/ndk (a symlink).
#   tools/android/deps.sh [--clean]
set -euo pipefail
cd "$(dirname "$0")/../.."
DEPS=$PWD/.local-deps
NDK=$DEPS/ndk
API=29
GL4ES_REV=bc4314d8369f169d3fa81c5ed1b46b3dba4e74a5   # 2026-10-30
SDL2_TAG=release-2.32.10
[ -f "$NDK/source.properties" ] || { echo "no NDK at $NDK (ln -s <sdk>/ndk/<version> .local-deps/ndk)"; exit 1; }
[ "${1:-}" = --clean ] && rm -rf "$DEPS/build/gl4es" "$DEPS/build/gl4es-linux" "$DEPS/build/SDL2"
mkdir -p "$DEPS/src" "$DEPS/build"

if [ ! -d "$DEPS/src/gl4es/.git" ]; then
    git clone -q https://github.com/ptitSeb/gl4es "$DEPS/src/gl4es"
fi
git -C "$DEPS/src/gl4es" fetch -q --depth 1 origin "$GL4ES_REV" 2>/dev/null || git -C "$DEPS/src/gl4es" fetch -q origin
git -C "$DEPS/src/gl4es" checkout -q "$GL4ES_REV"
(cd "$DEPS/src/gl4es" && "$NDK/ndk-build" -j"$(nproc)" NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=./Android.mk \
    APP_ABI=arm64-v8a APP_PLATFORM=android-$API NDK_OUT="$DEPS/build/gl4es/obj" NDK_LIBS_OUT="$DEPS/build/gl4es/libs" \
    >"$DEPS/build/gl4es.log" 2>&1) || { tail -20 "$DEPS/build/gl4es.log"; exit 1; }
echo "gl4es $(git -C "$DEPS/src/gl4es" rev-parse --short HEAD): $DEPS/build/gl4es/obj/local/arm64-v8a/libGL.a"
# The same gl4es for the Linux development build (tools/target.py: the desktop runs the Android GL path).
# Its CMake writes the library into the source tree's lib/, so it's moved out.
cmake -S "$DEPS/src/gl4es" -B "$DEPS/build/gl4es-linux" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=clang \
    -DCMAKE_POSITION_INDEPENDENT_CODE=ON -DNOX11=ON -DSTATICLIB=ON -DGBM=OFF -DDEFAULT_ES=2 \
    -DDEFAULT_GLES=libGLESv2.so.2 -DDEFAULT_EGL=libEGL.so.1 >"$DEPS/build/gl4es-linux.log" 2>&1 \
    && cmake --build "$DEPS/build/gl4es-linux" -j"$(nproc)" >>"$DEPS/build/gl4es-linux.log" 2>&1 \
    || { tail -20 "$DEPS/build/gl4es-linux.log"; exit 1; }
mkdir -p "$DEPS/build/gl4es-linux/out"
mv "$DEPS/src/gl4es/lib/libGL.a" "$DEPS/build/gl4es-linux/out/libGL.a" && rmdir "$DEPS/src/gl4es/lib"
echo "gl4es (Linux): $DEPS/build/gl4es-linux/out/libGL.a"

if [ ! -d "$DEPS/src/SDL2/.git" ]; then
    git -c advice.detachedHead=false clone -q --depth 1 --branch "$SDL2_TAG" https://github.com/libsdl-org/SDL "$DEPS/src/SDL2"
fi
cmake -S "$DEPS/src/SDL2" -B "$DEPS/build/SDL2" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API -DCMAKE_BUILD_TYPE=Release \
    -DSDL_SHARED=ON -DSDL_STATIC=OFF -DSDL_TEST=OFF >"$DEPS/build/SDL2.log" 2>&1 \
    && cmake --build "$DEPS/build/SDL2" -j"$(nproc)" >>"$DEPS/build/SDL2.log" 2>&1 || { tail -20 "$DEPS/build/SDL2.log"; exit 1; }
echo "SDL2 $SDL2_TAG: $DEPS/build/SDL2/libSDL2.so"
