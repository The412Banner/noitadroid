#!/usr/bin/env bash
# Builds libnd_fg.so (frame generation: android/fg) with the NDK into .local-deps/build/fg, for
# tools/android/build_app.py. Needs the NDK at .local-deps/ndk (tools/android/deps.sh), cmake and ninja.
#   tools/android/build_fg.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
NDK=$PWD/.local-deps/ndk
OUT=$PWD/.local-deps/build/fg
[ -f "$NDK/source.properties" ] || { echo "no NDK at $NDK"; exit 1; }
cmake -S android/fg -B "$OUT" -G Ninja -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release >/dev/null
cmake --build "$OUT" -j"$(nproc)"
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$OUT/libnd_fg.so"
ls -l "$OUT/libnd_fg.so"
