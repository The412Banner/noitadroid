#!/usr/bin/env bash
# The whole device check, in order, stopping at the first failure:
#   1. hosttest and luatest in adb shell (guest memory at MEM_HOST_BASE, calling conventions, LuaJIT's arm64 JIT)
#   2. the smoke APK in an app process (memory, LuaJIT, GLES 3 context, gl4es's desktop GL 2.1, GLES3 fences)
#   3. the game APK, the game files (tools/android/push_game.sh), a launch, and its log
# Needs the builds: tools/android/build_tests.sh, tools/android/build_smoke.sh, tools/android/build_game.py.
#   tools/android/device_check.sh [--from 2|3]
set -euo pipefail
cd "$(dirname "$0")/../.."
ADB=${ADB:-$(command -v adb || echo /opt/android-sdk/platform-tools/adb)}
PKG=io.github.zhouwuqi.noitadroid
FILES=/sdcard/Android/data/$PKG/files
from=1
[ "${1:-}" = --from ] && from=$2
"$ADB" get-state >/dev/null || { echo "no device: $("$ADB" devices)"; exit 1; }
echo "device: $("$ADB" shell getprop ro.product.manufacturer) $("$ADB" shell getprop ro.product.model)," \
     "Android $("$ADB" shell getprop ro.build.version.release), $("$ADB" shell getprop ro.board.platform)," \
     "page $("$ADB" shell getconf PAGE_SIZE)"

if [ "$from" -le 1 ]; then
    echo "== 1. runtime tests in adb shell"
    "$ADB" push build/android/hosttest build/android/luatest /data/local/tmp/ >/dev/null
    "$ADB" shell 'cd /data/local/tmp && ./hosttest > hosttest.log 2>&1; echo "hosttest rc=$? ok=$(grep -c " ok " hosttest.log) fail=$(grep -c FAIL hosttest.log)";
                  ./luatest > luatest.log 2>&1; echo "luatest rc=$? ok=$(grep -c " ok " luatest.log) $(tail -1 luatest.log)"; grep "^jit:" luatest.log'
fi

if [ "$from" -le 2 ]; then
    echo "== 2. smoke APK"
    "$ADB" install -r build/android/noitadroid-smoke.apk >/dev/null
    "$ADB" shell rm -f "$FILES/smoke.txt"
    "$ADB" shell am start -W -n $PKG/.NoitaActivity >/dev/null
    for _ in $(seq 60); do "$ADB" shell "grep -q 'checks ok' $FILES/smoke.txt 2>/dev/null" && break; sleep 1; done
    "$ADB" shell cat "$FILES/smoke.txt" || "$ADB" logcat -d -s noitadroid SDL AndroidRuntime | tail -30
    "$ADB" shell am force-stop $PKG
fi

if [ "$from" -le 3 ]; then
    echo "== 3. game"
    "$ADB" install -r build/android/noitadroid.apk >/dev/null
    tools/android/push_game.sh
    "$ADB" logcat -c
    "$ADB" shell am start -W -n $PKG/.NoitaActivity >/dev/null
    sleep 60
    "$ADB" exec-out screencap -p > build/android/device_60s.png && echo "screenshot: build/android/device_60s.png"
    "$ADB" shell cat "$FILES/noitadroid.log" | tail -40
    "$ADB" logcat -d -s noitadroid DEBUG libc AndroidRuntime | grep -E -i 'fatal|signal|abort|backtrace|crash' | head -20 || true
fi
