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
    printf 'ログ %s\nPNG %s\n' "$log" "$out"
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
        run_log=$("$adb" logcat -d --pid="$pid" -t "$start_time" -s AuraPlacement AuraHud AuraProbe AndroidRuntime)
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

check_state() {
    local state=$1 route=xr
    local args=(--es fakeState "$state" --ez capture true)
    if $preview; then
        args+=(--ez preview2d true)
        route=2d
    fi
    if [[ $state == stopped ]]; then args+=(--ez grid true); fi
    start_run "$state" "${args[@]}" || return 0
    sleep 7
    collect_run "$state" "$route"
    capture_panel "$state" left "$route"
    capture_panel "$state" right "$route"
    if [[ $state == stopped ]]; then capture_panel "$state" grid "$route"; fi
}

replay_safety() {
    "$adb" shell am broadcast -a "$pkg.REPLAY" -p "$pkg" --es scenario "$1" --ef speed 4 >> "$log"
}

wait_for_safety_log() {
    local pattern=$1 deadline=$((SECONDS + $2))
    local pid
    pid=$("$adb" shell pidof -s "$pkg" | tr -d '\r')
    [[ $pid =~ ^[0-9]+$ ]] || return 1
    run_log=
    while (( SECONDS < deadline )); do
        run_log=$("$adb" logcat -d --pid="$pid" -v threadtime -t "$start_time" -s AuraProbe AuraSession AuraHud) || return 1
        if grep -Eq "$pattern" <<< "$run_log"; then return 0; fi
        sleep 0.25
    done
    return 1
}

refresh_safety_preview() {
    if $preview; then
        # A sleeping display draws at launch, but may skip later frames.
        # Recreate only the Activity so the in-process replay keeps running.
        "$adb" shell am start -W --activity-single-top -n "$pkg/.MainActivity" \
            --ez preview2d true --ez capture true >> "$log"
    fi
}

save_safety_log() {
    grep -E 'capture hud-|REPLAY |TRANSITION |notice dismissed by timeout' <<< "$run_log" >> "$log" || true
}

check_safety() {
    local route=xr phase
    local args=(-W --ez capture true)
    if $preview; then
        route=2d
        args+=(--ez preview2d true)
    fi
    run_log=
    if start_run t6b-notice "${args[@]}" --ez resetNotice true && replay_safety walk_stop &&
        wait_for_safety_log 'notice dismissed by timeout' 22 &&
        refresh_safety_preview && wait_for_safety_log "capture hud-$route-left-stats\.png: [1-9]" 6; then
        for phase in notice stats; do capture_panel t6b-notice "left-$phase" "$route"; done
        if awk -v route="$route" '
            function seconds(time, parts) {
                split(time, parts, ":")
                return parts[1] * 3600 + parts[2] * 60 + parts[3]
            }
            index($0, "capture hud-" route "-left-notice.png: ") { notice = seconds($2); seen = 1 }
            /notice dismissed by timeout/ && seen {
                delta = seconds($2) - notice
                if (delta < 0) delta += 86400
                printf "T6b notice -> timeout: %.3f s\n", delta
                exit delta >= 14 && delta <= 16 ? 0 : 1
            }
            END { if (!seen || delta == "") exit 1 }
        ' <<< "$run_log" >> "$log"; then
            record PASS 'T6b 注意文の表示から消去まで約15秒'
        else
            record FAIL 'T6b 注意文の表示から消去まで約15秒'
        fi
    else
        record FAIL 'T6b 注意文の時間切替'
    fi
    save_safety_log

    run_log=
    if start_run t6b-fast "${args[@]}" && replay_safety fast &&
        wait_for_safety_log 'TRANSITION .* -> FAST at ' 15 &&
        refresh_safety_preview && wait_for_safety_log "capture hud-$route-left-fast\.png: [1-9]" 6; then
        if awk -v route="$route" '
            /AuraSession[[:space:]]*:[[:space:]]*TRANSITION / {
                split($0, fields, " -> ")
                split(fields[2], nextState, " ")
                state = nextState[1]
            }
            index($0, "capture hud-" route "-left-fast.png: ") && state == "FAST" { found = 1 }
            END { exit !found }
        ' <<< "$run_log"; then
            capture_panel t6b-fast left-fast "$route"
            echo "T6b: $shots/t6b-fast-left-fast.png を開き、高速移動中のメッセージだけであることを確認してください。"
        else
            record FAIL 'T6b FAST 遷移中のキャプチャ'
        fi
    else
        record FAIL 'T6b FAST 再生キャプチャ'
    fi
    save_safety_log
    replay_safety stop || record FAIL 'T6b 再生停止'
}

if ! prepare; then summary; exit 1; fi
if ! $preview; then check_placement; fi
check_safety
for state in walking stopped fast nofix; do check_state "$state"; done
summary
exit "$failed"
