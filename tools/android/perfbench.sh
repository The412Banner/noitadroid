#!/usr/bin/env bash
# tools/perfbench.sh's benchmark on the Android device, over adb: the same mod (tools/perfbench) and scenes, in the
# app that's installed (a debuggable build: NoitaActivity takes the game's arguments and env vars from its intent).
# Each run starts the game with `-no_logo_splashes -gamemode 0` and MINA4MAC_FRAMELOG, waits for the mod's
# "PERFBENCH done", and prints its summary with the frame log's work_ms/cpu_ms/swap_ms (median, p95), plus the
# device's state during the run: the hottest CPU sensor, the top clocks of the small, big and prime cores, and the
# battery. install backs up save00 (a run deletes the world in progress) and turns the mod sandbox off; remove
# puts both back. The mod is enabled only during a run.
#   tools/android/perfbench.sh install
#   SCENE=flood|heavy|jungle|coal tools/android/perfbench.sh run <out|-> [NAME=VALUE ...]   the summary to <out>; env
#                                                                                     vars for the game
#   tools/android/perfbench.sh remove
# Keep the device on its charger, its screen on and untouched, and compare interleaved runs: thermals drift.
set -euo pipefail
cd "$(dirname "$0")/../.."

ADB=${ADB:-adb}
command -v "$ADB" >/dev/null || ADB=/opt/android-sdk/platform-tools/adb
PKG=io.github.zhouwuqi.noitadroid
HOME_DIR=/sdcard/noitadroid
GAME=$HOME_DIR/Noita
SAVE=$HOME_DIR/save/AppData/LocalLow/Nolla_Games_Noita
BACKUP=$HOME_DIR/save00.perfbench-backup
TMP=build/perfbench-android
mkdir -p "$TMP"

sh_() { "$ADB" shell "$@"; }

set_mod() {  # set_mod <0|1>: perfbench's entry in save00/mod_config.xml
    local cfg=$TMP/mod_config.xml
    "$ADB" pull -q "$SAVE/save00/mod_config.xml" "$cfg" >/dev/null
    if grep -q 'name="perfbench"' "$cfg"; then
        sed -i -E "s/<Mod enabled=\"[01]\" name=\"perfbench\"/<Mod enabled=\"$1\" name=\"perfbench\"/" "$cfg"
    elif [ "$1" = 1 ]; then
        sed -i 's#</Mods>#  <Mod enabled="1" name="perfbench" settings_fold_open="0" workshop_item_id="0" >\n  </Mod>\n</Mods>#' "$cfg"
    fi
    "$ADB" push -q "$cfg" "$SAVE/save00/mod_config.xml" >/dev/null
}

sample() {  # one line: hottest CPU sensor (°C), clock of cpu0/cpu3/cpu7 (MHz), battery %, prime cores awake
    sh_ 'm=0; for z in /sys/class/thermal/thermal_zone*; do case $(cat $z/type) in cpu-*) t=$(cat $z/temp); [ $t -gt $m ] && m=$t;; esac; done;
         f=""; for c in 0 3 7; do f="$f $(( $(cat /sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq) / 1000 ))"; done;
         a=$(cat /sys/devices/system/cpu/cpu7/core_ctl/active_cpus 2>/dev/null || echo 1);
         echo "$((m / 1000))$f $(cat /sys/class/power_supply/battery/capacity) $a"' 2>/dev/null || true
}

case "${1:-}" in
install)
    sh_ "[ -d $SAVE/save00 ]" || { echo "no save00 on the device: start the game once first"; exit 1; }
    sh_ "[ -d $BACKUP ] || cp -r $SAVE/save00 $BACKUP"
    "$ADB" push -q tools/perfbench "$GAME/mods/" >/dev/null
    sh_ "sed -i -E 's/mods_disclaimer_accepted=\"0\"/mods_disclaimer_accepted=\"1\"/; s/mods_sandbox_enabled=\"1\"/mods_sandbox_enabled=\"0\"/' $SAVE/save_shared/config.xml"
    echo "installed perfbench in $GAME; save00 backed up to $BACKUP" ;;
remove)
    "$ADB" shell am force-stop $PKG
    set_mod 0
    sh_ "sed -i -E 's/mods_sandbox_enabled=\"0\"/mods_sandbox_enabled=\"1\"/' $SAVE/save_shared/config.xml"
    sh_ "rm -r /sdcard/noitadroid/Noita/mods/perfbench"
    if sh_ "[ -d $BACKUP ]"; then
        sh_ "rm -r /sdcard/noitadroid/save/AppData/LocalLow/Nolla_Games_Noita/save00 && mv $BACKUP $SAVE/save00"
        echo "save00 restored"
    fi
    echo "removed perfbench" ;;
run)
    out=${2:--}
    [ "$out" != - ] || out=
    shift $(( $# >= 2 ? 2 : 1 ))
    sh_ "[ -d $GAME/mods/perfbench ]" || { echo "run tools/android/perfbench.sh install first"; exit 1; }
    "$ADB" shell am force-stop $PKG
    sleep 1
    # -gamemode 0 keeps the saved world, so every run would start where the last ended: start from a fresh one
    sh_ "rm -f /sdcard/noitadroid/Noita/perfbench.txt /sdcard/noitadroid/Noita/perfbench_frames.txt"
    sh_ "rm -rf /sdcard/noitadroid/save/AppData/LocalLow/Nolla_Games_Noita/save00/world /sdcard/noitadroid/save/AppData/LocalLow/Nolla_Games_Noita/save00/player.xml /sdcard/noitadroid/save/AppData/LocalLow/Nolla_Games_Noita/save00/world_state.xml"
    sh_ "echo ${SCENE:-flood} > $GAME/mods/perfbench/scene.txt"
    set_mod 1
    trap '"$ADB" shell am force-stop $PKG; set_mod 0' EXIT
    env="MINA4MAC_FRAMELOG=$GAME/perfbench_frames.txt $*"
    "$ADB" shell am start -n $PKG/.NoitaActivity --es noitadroid.args "'-no_logo_splashes -gamemode 0'" \
        --es noitadroid.env "'$env'" >/dev/null
    : >"$TMP/samples.txt"
    start=$SECONDS
    while [ $((SECONDS - start)) -lt "${TIMEOUT:-420}" ]; do
        sleep 5
        sh_ "grep -q 'PERFBENCH done' $GAME/perfbench.txt 2>/dev/null" && break
        sh_ "pidof $PKG >/dev/null" || { echo "the game stopped"; break; }
        sample >>"$TMP/samples.txt"
    done
    "$ADB" shell am force-stop $PKG
    "$ADB" pull -q "$GAME/perfbench.txt" "$TMP/perfbench.txt" >/dev/null 2>&1 || { echo "no perfbench.txt"; exit 1; }
    "$ADB" pull -q "$GAME/perfbench_frames.txt" "$TMP/frames.txt" >/dev/null 2>&1 || : >"$TMP/frames.txt"
    {
        tr -d '\r' <"$TMP/perfbench.txt" | sed 's/^PERFBENCH //'
        stat() {  # stat <column> <name>: median and p95 of that column over the marked frames
            awk '/^mark start/{on=1;next} /^mark end/{on=0} on&&/^f /{print $'"$1"'}' "$TMP/frames.txt" |
                sort -n | awk -v n="$2" '{v[NR]=$1} END{if(NR) printf "%s median %.2f p95 %.2f frames %d\n",
                    n, v[int((NR+1)/2)], v[int(NR*0.95+0.999)], NR}'
        }
        stat 2 work_ms; stat 3 cpu_ms; stat 4 swap_ms
        awk 'NF==6{n++; t+=$1; if($1>tm)tm=$1; f0+=$2; f3+=$3; f7+=$4; if(!b0)b0=$5; b1=$5; a+=$6}
             END{if(n) printf "device cpu %.0f C (max %d), MHz small %.0f big %.0f prime %.0f (awake %.0f%%), battery %d%% -> %d%%\n",
                 t/n, tm, f0/n, f3/n, f7/n, 100*a/n, b0, b1}' "$TMP/samples.txt"
        echo "env $*"
    } | tee "$TMP/summary.txt"
    [ -z "$out" ] || cp "$TMP/summary.txt" "$out"
    grep -q "PERFBENCH done" "$TMP/perfbench.txt" || { echo "(incomplete)"; exit 1; } ;;
*) sed -n '2,14p' "$0"; exit 2 ;;
esac
