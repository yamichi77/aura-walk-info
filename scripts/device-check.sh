#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
export MSYS2_ARG_CONV_EXCL='*'
export JAVA_HOME="${AURA_JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
adb="${ADB:-${LOCALAPPDATA:?set ADB or LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe}"
pkg=io.github.yamichi77.aurawalkinfo
preview=false
case "${1:-}" in
    "") ;;
    --preview) preview=true ;;
    *) echo "使い方: bash scripts/device-check.sh [--preview]" >&2; exit 1 ;;
esac

if [[ $("$adb" get-state 2>/dev/null | tr -d '\r') != device ]]; then
    echo "FAIL adb で端末を選択できません。接続または ANDROID_SERIAL を確認してください。" >&2
    exit 1
fi
if [[ $("$adb" shell getprop ro.product.model | tr -d '\r') != 'Android XR Puck' ]]; then
    echo "FAIL 選択中の端末は Android XR Puck ではありません。" >&2
    exit 1
fi

power=$("$adb" shell dumpsys power | tr -d '\r')
if ! $preview; then
    if ! grep -Eq '^[[:space:]]*mWakefulness=Awake[[:space:]]*$' <<< "$power"; then
        echo "非装着または表示スリープ中です。AURA を装着してロックを解除してください。"
        grep -E 'mWakefulness=|mLastSleepReason=|mInteractive=' <<< "$power" || true
        exit 2
    fi
    display=$("$adb" shell dumpsys display | tr -d '\r')
    if ! grep -Eq '^[[:space:]]*mScreenState=ON[[:space:]]*$' <<< "$display"; then
        echo "AURA の表示がオフです。装着してロックを解除してください。"
        exit 2
    fi
fi

stamp=$(date +%Y%m%d-%H%M%S)
out="build/device-check/$stamp"
shots="build/device-shots/$stamp"
mkdir -p "$out" "$shots"
log="$out/log.txt"
grep -E 'mWakefulness=|mLastSleepReason=|mInteractive=' <<< "$power" > "$log" || true
results=()
failed=0

record() {
    results+=("$1 $2")
    if [[ $1 == FAIL ]]; then failed=1; fi
}

summary() {
    printf '\n'
    printf '%s\n' "${results[@]}"
    printf 'ログ %s\nPNG %s\n' "$log" "$shots"
}

prepare() {
    if ./gradlew assembleDebug 2>&1 | tee -a "$log"; then
        record PASS assembleDebug
    else
        record FAIL assembleDebug
        return 1
    fi
    if ! "$adb" install -r --no-streaming app/build/outputs/apk/debug/app-debug.apk 2>&1 | tee -a "$log"; then
        record FAIL 'adb install'
        return 1
    fi
    record PASS 'adb install'
    for permission in ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION HEAD_TRACKING; do
        if ! "$adb" shell pm grant "$pkg" "android.permission.$permission" 2>&1 | tee -a "$log"; then
            record FAIL "pm grant $permission"
            return 1
        fi
    done
    record PASS 'pm grant'
}

start_run() {
    local name=$1
    shift
    printf '\n[%s]\n' "$name" >> "$log"
    start_time=$("$adb" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
    if ! "$adb" shell am start -S -n "$pkg/.MainActivity" "$@" 2>&1 | tee -a "$log"; then
        record FAIL "$name 起動"
        return 1
    fi
}

collect_run() {
    local name=$1 route=$2
    local pid crash
    pid=$("$adb" shell pidof "$pkg" | tr -d '\r') || true
    crash=$("$adb" logcat -b crash -d -t "$start_time" -s AndroidRuntime 2>&1)
    if grep -Fq "Process: $pkg," <<< "$crash"; then
        printf '%s\n' "$crash" >> "$log"
        record FAIL "$name クラッシュ"
    elif [[ $pid =~ ^[0-9]+$ ]]; then
        record PASS "$name 起動・クラッシュなし"
    else
        record FAIL "$name プロセスなし"
    fi
    run_log=
    if [[ $pid =~ ^[0-9]+$ ]]; then
        run_log=$("$adb" logcat -d --pid="$pid" -t "$start_time" -s AuraPlacement AuraHud AuraProbe AuraMap AndroidRuntime)
        printf '%s\n' "$run_log" >> "$log"
    fi
    if grep -Eq "AuraHud[[:space:]]*: route=$route[[:space:]]*$" <<< "$run_log"; then
        record PASS "$name $route"
    else
        record FAIL "$name $route の表示経路"
    fi
}

capture_panel() {
    local name=$1 panel=$2 route=$3
    local file="$shots/$name-$panel.png"
    if grep -Eq "capture hud-$route-$panel\.png: [1-9][0-9]*x[1-9][0-9]*" <<< "$run_log" &&
        "$adb" exec-out run-as "$pkg" cat "files/hud-$route-$panel.png" > "$file" &&
        [[ $(od -An -tx1 -N8 "$file" | tr -d ' \r\n') == 89504e470d0a1a0a ]]; then
        cp "$file" "$out/"
        record PASS "$name $panel PNG"
    else
        record FAIL "$name $panel PNG"
    fi
}

check_placement() {
    echo "約12秒計測します。最初の3秒は正面を向き、その後に頭をゆっくり少し向け直して止めてください。"
    start_run selfcheck --ez selfcheck true --ez grid true --ez capture true || return 0
    sleep 12
    collect_run selfcheck xr
    for check in placement follow; do
        if grep -Fq "AuraPlacement: $check PASS" <<< "$run_log"; then
            record PASS "$check"
        else
            record FAIL "$check"
        fi
    done
    for panel in left right grid; do capture_panel selfcheck "$panel" xr; done
}

map_log() {
    local pid
    pid=$("$adb" shell pidof "$pkg" | tr -d '\r') || return 1
    [[ $pid =~ ^[0-9]+$ ]] || return 1
    "$adb" logcat -d --pid="$pid" -t "$start_time" -s AuraMap AuraSession
}

wait_for_map_snapshot() {
    local deadline=$((SECONDS + 24)) logs
    # The Compose frame can be captured during a 2D preview with the display asleep.
    if ! grep -Eq '^[[:space:]]*mInteractive=true[[:space:]]*$' <<< "$power"; then return 0; fi
    while (( SECONDS < deadline )); do
        logs=$(map_log) || return 0
        if grep -Eq 'AuraMap[[:space:]]*: (PASS|FAIL) onMapLoaded=|AuraMap[[:space:]]*: SKIP key=missing' <<< "$logs"; then
            return 0
        fi
        sleep 2
    done
}

check_map_state() {
    local state=$1 diameter=8.0 opacity=0.7 file="$shots/$1-minimap.png"
    case "$state" in
        stopped) diameter=12.0; opacity=1.0 ;;
        nofix) opacity=0.3 ;;
    esac
    if grep -Fq "presentation diameter=$diameter mapOpacity=$opacity" <<< "$run_log"; then
        record PASS "$state 地図の表示値 ($diameter°, opacity=$opacity)"
    else
        record FAIL "$state 地図の表示値"
    fi
    if grep -Fq 'SKIP key=missing' <<< "$run_log"; then
        map_key_missing=true
        record FAIL "$state 地図の描画（キーなし）"
        return 0
    fi
    if $preview && ! grep -Eq '^[[:space:]]*mInteractive=true[[:space:]]*$' <<< "$power"; then
        record SKIP "$state 地図の snapshot（表示スリープ中）"
        return 0
    fi
    if grep -Fq 'PASS onMapLoaded=true' <<< "$run_log"; then
        record PASS "$state 地図の描画"
    else
        record FAIL "$state 地図の描画"
    fi
    if grep -Fq 'snapshot=minimap-snapshot.png' <<< "$run_log" &&
        "$adb" exec-out run-as "$pkg" cat files/minimap-snapshot.png > "$file" &&
        [[ $(od -An -tx1 -N8 "$file" | tr -d ' \r\n') == 89504e470d0a1a0a ]]; then
        record PASS "$state 地図の snapshot PNG"
    else
        record FAIL "$state 地図の snapshot PNG"
    fi
}

map_replay_turn() {
    awk -v key="$1" '
        /AuraSession[[:space:]]*: REPLAY walk_stop[[:space:]]*$/ { replay = 1 }
        replay && /AuraSession[[:space:]]*: SAMPLE 120 / { turned = 1 }
        replay && $0 ~ ("AuraMap[[:space:]]*: camera " key "=[0-9.]+[[:space:]]*$") {
            split($NF, values, "=")
            angle = values[2] + 0
            if (angle <= 2 || angle >= 358) north = 1
            if (north && angle >= 88 && angle <= 92) east = 1
        }
        END { exit !(turned && east) }
    '
}

check_map_replay() {
    if "$map_key_missing"; then
        record SKIP 'walk_stop 地図の方位（キーなし）'
        return 0
    fi
    local args=(-W --ez selfcheck true --es fakeState replay --ez capture true)
    if $preview; then args+=(--ez preview2d true); fi
    start_run map-replay "${args[@]}" || return 0
    if ! "$adb" shell am broadcast -a "$pkg.REPLAY" -p "$pkg" --es scenario walk_stop --ef speed 4 >> "$log"; then
        record FAIL 'walk_stop 地図の再生開始'
        return 0
    fi
    local deadline=$((SECONDS + 48)) logs=
    while (( SECONDS < deadline )); do
        logs=$(map_log) || break
        if map_replay_turn bearing <<< "$logs"; then break; fi
        sleep 2
    done
    printf '%s\n' "$logs" >> "$log"
    if map_replay_turn targetBearing <<< "$logs"; then
        record PASS 'walk_stop カメラへの方位入力（約90°）'
    else
        record FAIL 'walk_stop カメラへの方位入力'
    fi
    if map_replay_turn bearing <<< "$logs"; then
        record PASS 'walk_stop カメラの方位（約90°）'
    elif $preview && ! grep -Eq '^[[:space:]]*mInteractive=true[[:space:]]*$' <<< "$power"; then
        record SKIP 'walk_stop カメラの方位（表示スリープ中）'
    else
        record FAIL 'walk_stop カメラの方位'
    fi
    if ! "$adb" shell am broadcast -a "$pkg.REPLAY" -p "$pkg" --es scenario stop >> "$log"; then
        record FAIL 'walk_stop 地図の再生停止'
    fi
}

check_state() {
    local state=$1 route=xr
    local args=(--es fakeState "$state" --ez capture true)
    if $preview; then
        args+=(--ez preview2d true)
        route=2d
    fi
    if [[ $state == stopped ]]; then args+=(--ez grid true); fi
    if [[ $state != fast ]]; then args+=(--ez selfcheck true); fi
    start_run "$state" "${args[@]}" || return 0
    sleep 7
    if [[ $state != fast ]]; then wait_for_map_snapshot; fi
    collect_run "$state" "$route"
    capture_panel "$state" left "$route"
    capture_panel "$state" right "$route"
    if [[ $state == stopped ]]; then capture_panel "$state" grid "$route"; fi
    if [[ $state != fast ]]; then check_map_state "$state"; fi
}

if ! prepare; then summary; exit 1; fi
if ! $preview; then check_placement; fi
map_key_missing=false
for state in walking stopped fast nofix; do check_state "$state"; done
check_map_replay
summary
exit "$failed"
