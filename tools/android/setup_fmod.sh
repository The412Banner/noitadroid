#!/usr/bin/env bash
# Puts FMOD Engine 2.01.x for Android (the user's own download from fmod.com; its license forbids redistributing it,
# so never commit it) into .local-deps/fmod, where tools/android/build_game.py and android/app/build.gradle.kts look:
#   api/core/lib/arm64-v8a/libfmod.so  api/studio/lib/arm64-v8a/libfmodstudio.so  api/core/lib/fmod.jar
# The game ships FMOD 2.01.05 and checks the major.minor version, so it must be 2.01.x (2.01.23 is the tested one).
#
#   tools/android/setup_fmod.sh [fmodstudioapi201XXandroid.tar.gz]
#
# Without an archive, it looks for fmodstudioapi201*android.tar.gz in ~/Downloads and .local-deps; when there is
# none, it signs in to fmod.com with your account (asked for here, sent only to fmod.com, never stored) and
# downloads 2.01.23. Run that in your own terminal: the password prompt needs one.
set -euo pipefail
cd "$(dirname "$0")/../.."
VERSION=20123  # 2.01.23
DEPS=.local-deps
DST=$DEPS/fmod
git check-ignore -q "$DEPS" || { echo "refusing: $DEPS is not gitignored (FMOD must never be committed)"; exit 1; }
mkdir -p "$DEPS"

download() {
    local out=$1 user pass token url
    [ -t 0 ] || { echo "no archive found, and signing in to fmod.com needs a terminal for the password prompt"; exit 1; }
    echo "No FMOD archive found. Signing in to fmod.com to download FMOD Engine 2.01.23 for Android"
    echo "(a free account: fmod.com -> Sign in -> Register)."
    read -r -p "fmod.com username or email: " user
    read -r -s -p "fmod.com password: " pass && echo
    # The credentials go to curl on stdin (-K -), not on its command line where ps would show them.
    token=$(printf 'user = "%s:%s"\n' "$(esc "$user")" "$(esc "$pass")" |
        curl -fsS -K - -X POST https://www.fmod.com/api-login | json token) && [ -n "$token" ] ||
        { echo "fmod.com sign-in failed (wrong username or password?)"; exit 1; }
    unset pass
    url=$(curl -fsS -G https://www.fmod.com/api-get-download-link -H "Authorization: Bearer $token" \
        --data-urlencode "path=files/fmodstudio/api/Android/" \
        --data-urlencode "filename=fmodstudioapi${VERSION}android.tar.gz" --data-urlencode "user=$user" | json url) &&
        [ -n "$url" ] || { echo "fmod.com gave no download link for 2.01.23 Android"; exit 1; }
    curl -fL --progress-bar -o "$out.part" "$url"
    mv "$out.part" "$out"
}
json() { python3 -I -c 'import json, sys; print(json.load(sys.stdin).get(sys.argv[1]) or "")' "$1"; }
esc() { local s=${1//\\/\\\\}; printf '%s' "${s//\"/\\\"}"; }  # for a quoted value in a curl config

archive=${1:-}
if [ -z "$archive" ]; then
    archive=$(ls -t ~/Downloads/fmodstudioapi201*android.tar.gz "$DEPS"/fmodstudioapi201*android.tar.gz 2>/dev/null | head -1) || true
fi
if [ -z "$archive" ]; then
    archive=$DEPS/fmodstudioapi${VERSION}android.tar.gz
    download "$archive"
fi
[ -f "$archive" ] || { echo "no such file: $archive"; exit 1; }

tmp=$(mktemp -d "$DEPS/fmod.XXXXXX")
trap 'rm -rf "$tmp"' EXIT
tar -xzf "$archive" -C "$tmp"
api=$(find "$tmp" -maxdepth 3 -type d -name api -path '*/api' | head -1)
[ -n "$api" ] || { echo "$archive has no api/ folder: is it FMOD Engine for Android?"; exit 1; }
for f in core/lib/arm64-v8a/libfmod.so studio/lib/arm64-v8a/libfmodstudio.so core/lib/fmod.jar core/inc/fmod_common.h; do
    [ -f "$api/$f" ] || { echo "$archive lacks api/$f"; exit 1; }
done
version=$(sed -nE 's/^#define FMOD_VERSION +0x([0-9a-fA-F]+).*/\1/p' "$api/core/inc/fmod_common.h")
case "$version" in
    000201??) ;;
    *) echo "$archive is FMOD 0x$version: the game needs 2.01.x (0x000201xx)"; exit 1 ;;
esac
rm -rf "$DST"
mkdir -p "$DST"
mv "$api" "$DST/api"
echo "FMOD 0x$version: $archive -> $DST"
echo "next: NOITA_DIR=.local-deps/noita-gog uv run tools/android/build_game.py"
