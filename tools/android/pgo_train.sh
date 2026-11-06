#!/usr/bin/env bash
# Makes tools/noitadroid.profdata, the profile the game is compiled with (tools/android/build_game.py --pgo use, and
# always on the device). Retrain after changing the lifter or the runtime: a function whose code changed just goes
# without its profile, so a stale profile costs speed and nothing else. Needs the debuggable app's device, plugged in,
# with tools/android/perfbench.sh install done. It leaves the instrumented app installed; about 10 minutes.
#   NOITA_DIR=<GOG install> tools/android/pgo_train.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
ADB=${ADB:-adb}
command -v "$ADB" >/dev/null || ADB=/opt/android-sdk/platform-tools/adb
RAW=build/android/pgo_raw

uv run tools/android/build_game.py --pgo gen
"$ADB" install -r build/android/noitadroid.apk
"$ADB" shell rm -rf /sdcard/noitadroid/pgo
for scene in heavy jungle coal; do
    SCENE=$scene tools/android/perfbench.sh run -
done
rm -rf "$RAW"
mkdir -p "$RAW"
"$ADB" pull /sdcard/noitadroid/pgo "$RAW"
"$ADB" shell rm -rf /sdcard/noitadroid/pgo
.local-deps/ndk/toolchains/llvm/prebuilt/*/bin/llvm-profdata merge -sparse -o tools/noitadroid.profdata "$RAW"/pgo/*.profraw
ls -la tools/noitadroid.profdata
