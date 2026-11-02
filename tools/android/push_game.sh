#!/usr/bin/env bash
# Copies your Noita (GOG 20250125-1640) to the device, where the APK's launcher reads it:
#   /sdcard/Android/data/io.github.zhouwuqi.noitadroid/files/Noita
# Only what the launcher needs: the two binaries it maps (noita.exe, msvcp120.dll; the other DLLs are stood in for
# by the runtime), data/, mods/ and the files tools/setup_game.sh copies. adb push --sync skips unchanged files.
#   tools/android/push_game.sh [game dir]   (default: $NOITA_DIR, else .local-deps/noita-gog)
set -euo pipefail
cd "$(dirname "$0")/../.."
SRC=${1:-${NOITA_DIR:-.local-deps/noita-gog}}
DST=/sdcard/Android/data/io.github.zhouwuqi.noitadroid/files/Noita
ADB=${ADB:-$(command -v adb || echo /opt/android-sdk/platform-tools/adb)}
[ -f "$SRC/noita.exe" ] && [ -f "$SRC/data/data.wak" ] || { echo "no Noita install at $SRC"; exit 1; }
[ "$(sha256sum "$SRC/noita.exe" | cut -d' ' -f1)" = f0f5ffd1c64558663ac7cffaf689f4899732a56494c3530b806bf3357d094363 ] \
    || { echo "$SRC/noita.exe is not the supported build (GOG 20250125-1640)"; exit 1; }
"$ADB" shell mkdir -p "$DST"
for f in noita.exe msvcp120.dll config.xml _branch.txt _version_hash.txt _release_notes.txt screenshot_paths.txt; do
    [ -f "$SRC/$f" ] && "$ADB" push --sync "$SRC/$f" "$DST/$f" >/dev/null
done
"$ADB" push --sync "$SRC/data" "$SRC/mods" "$DST/"
"$ADB" shell du -sh "$DST"
