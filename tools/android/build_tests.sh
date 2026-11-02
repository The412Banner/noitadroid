#!/usr/bin/env bash
# Builds the game-independent runtime tests (hosttest, luatest) as Android arm64 executables in build/android/,
# to check guest memory at MEM_HOST_BASE, the calling conventions and LuaJIT's arm64 JIT on a device:
#   adb push build/android/hosttest build/android/luatest /data/local/tmp/
#   adb shell 'cd /data/local/tmp && ./hosttest && ./luatest'
set -euo pipefail
cd "$(dirname "$0")/../.."
export NOITADROID_TARGET=android
eval "$(uv run tools/target.py --shell)"
mkdir -p build/android
uv run python -c 'import sys; sys.path.insert(0, "tools"); import build_all; print("luajit:", build_all.build_luajit())'
FLAGS="-O2 -ffp-contract=off -fno-strict-aliasing -Wall -Wextra -Werror $TEST_CFLAGS"
$CC $PLATFORM_CFLAGS $LINKER $FLAGS $RT_INC runtime/host_test.c runtime/rt.c $SYS_LIBS -o build/android/hosttest
$CC $PLATFORM_CFLAGS $LINKER $FLAGS -fexceptions $RT_INC -I third_party/luajit/src \
    runtime/lua_test.c runtime/lua51.c runtime/rt.c runtime/proc.c runtime/heap.c runtime/hle.c \
    build/android/luajit/src/libluajit.a $SYS_LIBS -o build/android/luatest
file build/android/hosttest build/android/luatest | cut -c1-140
