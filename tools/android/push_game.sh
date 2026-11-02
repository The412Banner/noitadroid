#!/usr/bin/env bash
# Copies your Noita (GOG 20250125-1640) to the device, where the APK's launcher reads it:
#   /sdcard/Android/data/io.github.zhouwuqi.noitadroid/files/Noita
# Only what the launcher needs: the two binaries it maps (noita.exe, msvcp120.dll; the other DLLs are stood in for
# by the runtime), data/, mods/ and the files tools/setup_game.sh copies.
#   tools/android/push_game.sh [game dir] [--force]   (default: $NOITA_DIR, else .local-deps/noita-gog)
set -euo pipefail
cd "$(dirname "$0")/../.."
force=0 src=
for a in "$@"; do [ "$a" = --force ] && force=1 || src=$a; done
SRC=${src:-${NOITA_DIR:-.local-deps/noita-gog}}
DST=/sdcard/Android/data/io.github.zhouwuqi.noitadroid/files/Noita
ADB=${ADB:-$(command -v adb || echo /opt/android-sdk/platform-tools/adb)}
[ -f "$SRC/noita.exe" ] && [ -f "$SRC/data/data.wak" ] || { echo "no Noita install at $SRC"; exit 1; }
[ "$(sha256sum "$SRC/noita.exe" | cut -d' ' -f1)" = f0f5ffd1c64558663ac7cffaf689f4899732a56494c3530b806bf3357d094363 ] \
    || { echo "$SRC/noita.exe is not the supported build (GOG 20250125-1640)"; exit 1; }
# One tar stream through adb exec-in: adb push of directories from the NTFS disk fails on the device
# ("secure_mkdirs failed: Operation not permitted", then the client aborts), and a stream is quicker anyway.
# Skipped when the device already has this noita.exe and an equally large data.wak.
files=(noita.exe msvcp120.dll data mods)
for f in config.xml _branch.txt _version_hash.txt _release_notes.txt screenshot_paths.txt; do
    [ -f "$SRC/$f" ] && files+=("$f")
done
have=$("$ADB" shell "sha256sum $DST/noita.exe 2>/dev/null | cut -d' ' -f1; stat -c %s $DST/data/data.wak 2>/dev/null; true" | tr '\n' ' ')
want="f0f5ffd1c64558663ac7cffaf689f4899732a56494c3530b806bf3357d094363 $(stat -c %s "$SRC/data/data.wak") "
if [ "$have" = "$want" ] && [ $force = 0 ]; then
    echo "already on the device: $DST (--force to copy again)"
else
    echo "copying $(du -shc "${files[@]/#/$SRC/}" | tail -1 | cut -f1) to $DST"
    tar -C "$SRC" -cf - "${files[@]}" | "$ADB" exec-in "mkdir -p $DST && tar -xf - -C $DST"
fi
"$ADB" shell du -sh "$DST"
