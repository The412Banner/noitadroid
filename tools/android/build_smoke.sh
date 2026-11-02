#!/usr/bin/env bash
# Builds the device smoke test APK (tools/android/smoke/smoke.c as libmain.so; no game code) into
# build/android/noitadroid-smoke.apk. Install it, open "Noita (noitadroid)", and read the result:
#   adb install -r build/android/noitadroid-smoke.apk
#   adb shell cat /sdcard/Android/data/io.github.zhouwuqi.noitadroid/files/smoke.txt   (or: adb logcat -s noitadroid)
set -euo pipefail
cd "$(dirname "$0")/../.."
export NOITADROID_TARGET=android
DEPS=.local-deps
[ -f $DEPS/build/SDL2/libSDL2.so ] && [ -f $DEPS/build/gl4es/obj/local/arm64-v8a/libGL.a ] || tools/android/deps.sh
eval "$(uv run tools/target.py --shell)"
uv run python -c 'import sys; sys.path.insert(0, "tools"); import build_all; build_all.build_luajit()'
JNI=build/android/jniLibs/arm64-v8a
rm -rf build/android/jniLibs && mkdir -p $JNI
# 16 KB pages: Android 15+ devices may use them.
$CC -shared -O2 -Wall -Wextra -Werror $PLATFORM_CFLAGS $SDL_CFLAGS -I $DEPS/src/gl4es/include -I third_party/luajit/src \
    tools/android/smoke/smoke.c build/android/luajit/src/libluajit.a $GL_LIBS $SDL_LIBS $SYS_LIBS \
    -Wl,-z,max-page-size=16384 -Wl,--no-undefined -o $JNI/libmain.so
cp $DEPS/build/SDL2/libSDL2.so $JNI/
(cd android && ./gradlew -q assembleDebug)
cp android/app/build/outputs/apk/debug/app-debug.apk build/android/noitadroid-smoke.apk
ls -la build/android/noitadroid-smoke.apk
