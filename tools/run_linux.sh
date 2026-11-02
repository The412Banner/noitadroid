#!/usr/bin/env bash
# Runs the Linux development build (build/mina4mac, tools/build_all.py with NOITADROID_TARGET=linux) against the
# unpacked GOG install. Without $DISPLAY it starts a private Xvfb on :99 (no GLX: SDL makes an EGL GLES context
# there, runtime/gles.c) and uses Mesa's EGL (llvmpipe), since NVIDIA's EGL needs an NVIDIA X screen.
#   tools/run_linux.sh [game args]
#   tools/run_linux.sh --shot <file.png> <seconds> [game args]   also saves the screen after <seconds>
# Environment: NOITA_DIR (default .local-deps/noita-gog), MINA4MAC_DATA (saves, default ~/.local/share/noitadroid),
# and the launcher's own (runtime/main.c: MINA4MAC_TRACE, MINA4MAC_COUNT_IMPORTS, MINA4MAC_FPS, ...).
set -euo pipefail
cd "$(dirname "$0")/.."
shot= shot_after=
if [ "${1:-}" = --shot ]; then shot=$2 shot_after=$3; shift 3; fi
export NOITA_DIR=${NOITA_DIR:-$PWD/.local-deps/noita-gog}
export MINA4MAC_DATA=${MINA4MAC_DATA:-$HOME/.local/share/noitadroid}
mkdir -p "$MINA4MAC_DATA"
[ -x build/mina4mac ] || { echo "build/mina4mac is missing: NOITA_DIR=$NOITA_DIR uv run tools/build_all.py"; exit 1; }

if [ -z "${DISPLAY:-}" ]; then
    export DISPLAY=:99
    if ! xdpyinfo >/dev/null 2>&1; then
        setsid Xvfb :99 -screen 0 1280x720x24 -nolisten tcp -extension GLX >/tmp/noitadroid-xvfb.log 2>&1 &
        for _ in $(seq 50); do xdpyinfo >/dev/null 2>&1 && break; sleep 0.1; done
    fi
    export __EGL_VENDOR_LIBRARY_FILENAMES=${__EGL_VENDOR_LIBRARY_FILENAMES:-/usr/share/glvnd/egl_vendor.d/50_mesa.json}
fi

if [ -n "$shot" ]; then
    (sleep "$shot_after"; xwd -root -silent | ffmpeg -loglevel error -y -f xwd_pipe -i - "$shot" && echo "screenshot: $shot") &
fi
exec build/mina4mac "$@"
