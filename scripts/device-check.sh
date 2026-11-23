#!/usr/bin/env bash
set -euo pipefail

export MSYS2_ARG_CONV_EXCL='*'
export JAVA_HOME="${AURA_JAVA_HOME:-/c/Program Files/Android/Android Studio/jbr}"
adb="${ADB:-${LOCALAPPDATA:?set ADB or LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe}"
pkg=io.github.yamichi77.aurawalkinfo
default_checks=placement,headmotion,safety,walking,stopped,fast,nofix,map-replay,input,bigmap,session
all_checks=$default_checks,heading,speech

usage() {
    echo "使い方: bash scripts/device-check.sh [--preview] [--only $all_checks]" >&2
    return 1
}

parse_args() {
    preview=false
    only=$default_checks
    while (( $# > 0 )); do
        case "$1" in
            --preview) preview=true ;;
            --only)
                case "${2:-}" in
                    ""|,*|*,|*,,*) usage; return 1 ;;
                esac
                only=$2
                shift
                ;;
            *) usage; return 1 ;;
        esac
        shift
    done
    local name names
    IFS=, read -r -a names <<< "$only"
    for name in "${names[@]}"; do
        [[ ,$all_checks, == *",$name,"* ]] || { usage; return 1; }
    done
}

selected() {
    [[ ,$only, == *",$1,"* ]]
}

adb_retry() {
    local buffer status=0
    # Discard partial stdout from failed attempts, including binary PNG data.
    buffer=$(mktemp) || return 1
    if "$adb" "$@" > "$buffer" ||
        { timeout 20s "$adb" wait-for-device >&2 && "$adb" "$@" > "$buffer"; }; then
        cat "$buffer" || status=$?
    else
        status=$?
    fi
    rm -f "$buffer"
    return "$status"
}

check_device() {
    local device_state serial model display
    if ! device_state=$(adb_retry get-state 2>/dev/null | tr -d '\r') || [[ $device_state != device ]]; then
        echo "FAIL adb で端末を選択できません。接続または ANDROID_SERIAL を確認してください。" >&2
        return 1
    fi
    if ! serial=$(adb_retry get-serialno | tr -d '\r') || [[ -z $serial || $serial == unknown ]]; then
        echo "FAIL 選択中の端末のシリアル番号を取得できません。" >&2
        return 1
    fi
    export ANDROID_SERIAL="$serial"
    if ! model=$(adb_retry shell getprop ro.product.model | tr -d '\r') || [[ $model != 'Android XR Puck' ]]; then
        echo "FAIL 選択中の端末は Android XR Puck ではありません。" >&2
        return 1
    fi
    power=$(adb_retry shell dumpsys power | tr -d '\r') || return 1
    if $preview; then return 0; fi
    if ! grep -Eq '^[[:space:]]*mWakefulness=Awake[[:space:]]*$' <<< "$power"; then
        echo "非装着または表示スリープ中です。AURA を装着してロックを解除してください。"
        grep -E 'mWakefulness=|mLastSleepReason=|mInteractive=' <<< "$power" || true
        return 2
    fi
    display=$(adb_retry shell dumpsys display | tr -d '\r') || return 1
    if ! grep -Eq '^[[:space:]]*mScreenState=ON[[:space:]]*$' <<< "$display"; then
        echo "AURA の表示がオフです。装着してロックを解除してください。"
        return 2
    fi
}

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
    if ! adb_retry install -r --no-streaming app/build/outputs/apk/debug/app-debug.apk 2>&1 | tee -a "$log"; then
        record FAIL 'adb install'
        return 1
    fi
    record PASS 'adb install'
    for permission in ACCESS_COARSE_LOCATION ACCESS_FINE_LOCATION HEAD_TRACKING; do
        if ! adb_retry shell pm grant "$pkg" "android.permission.$permission" 2>&1 | tee -a "$log"; then
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
    if ! start_time=$(adb_retry shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r'); then
        record FAIL "$name 端末時刻"
        return 1
    fi
    if ! adb_retry shell am start -S -n "$pkg/.MainActivity" "$@" 2>&1 | tee -a "$log"; then
        record FAIL "$name 起動"
        return 1
    fi
}

replay_scenario() {
    adb_retry shell am broadcast -a "$pkg.REPLAY" -p "$pkg" --es scenario "$@" >> "$log"
}

debug_tap() {
    adb_retry shell am broadcast -a "$pkg.DEBUG_TAP" -p "$pkg" --es target "$1" >> "$log"
}

t9_log() {
    local pid
    pid=$(adb_retry shell pidof -s "$pkg" | tr -d '\r') || return 1
    [[ $pid =~ ^[0-9]+$ ]] || return 1
    adb_retry logcat -d --pid="$pid" -v threadtime -t "$start_time" -s AuraInput AuraBigMap AuraPlacement AuraHud AuraSession
}

wait_for_t9_log() {
    local pattern=$1 deadline=$((SECONDS + $2))
    run_log=
    while (( SECONDS < deadline )); do
        run_log=$(t9_log) || return 1
        if grep -Eq "$pattern" <<< "$run_log"; then return 0; fi
        sleep 0.25
    done
    return 1
}

t9_physical_click() {
    grep -Eq "AuraInput[[:space:]]*:[[:space:]]*CLICK target=$1 source=compose([[:space:]]|$)"
}

check_input() {
    start_run t9-input -W --es fakeState stopped || return 0
    local target prompt logs prior=0 size
    for target in left map outside; do
        case "$target" in
            left) prompt='手を伸ばして、左の表示をつまんでください' ;;
            map) prompt='次に右下の地図をつまんでください' ;;
            outside) prompt='大きい地図の外をつまんでください' ;;
        esac
        echo "$prompt（15秒）"
        sleep 15
        logs=$(t9_log) || logs=
        printf '%s\n' "$logs" >> "$log"
        if t9_physical_click "$target" < <(tail -n "+$((prior + 1))" <<< "$logs"); then
            record PASS "input $target"
        else
            record FAIL "input $target"
            printf '%s\n' "$logs"
            if [[ $target == map ]]; then
                echo '外側の入力を確認するため DEBUG_TAP で大きい地図を開きます。'
                if ! debug_tap bigmap || ! debug_tap map; then
                    record FAIL 'input 大きい地図の準備'
                fi
            fi
        fi
        size=$(wc -l <<< "$logs")
        prior=$size
    done
}

t9_map_expiry() {
    awk -v expected="$1" '
        /AuraBigMap[[:space:]]*: OPEN now=[0-9]+ deadline=[0-9]+[[:space:]]*$/ {
            split($(NF-1), start, "="); split($NF, end, "=")
            open = 1; elapsed = end[2] - start[2]
        }
        /AuraBigMap[[:space:]]*: CLOSED reason=timeout now=[0-9]+[[:space:]]*$/ && open {
            split($NF, closed, "=")
            late = closed[2] - end[2]
            printf "bigmap expected=%d ms scheduled=%d ms actual=%d ms\n", expected, elapsed, closed[2] - start[2]
            pass = elapsed == expected && late >= 0 && late <= 2000
        }
        END { exit !pass }
    '
}

t9_fast_map() {
    awk '
        /AuraBigMap[[:space:]]*: OPEN / { opens++ }
        /AuraBigMap[[:space:]]*: CLOSED reason=fast / && opens == 1 { closed = 1 }
        /AuraInput[[:space:]]*: CLICK target=map source=debug ignored-fast/ && closed { refused = 1 }
        END { exit !(opens == 1 && refused) }
    '
}

check_bigmap() {
    local state duration placement
    for state in stopped walking; do
        run_log=
        duration=10000
        if [[ $state == stopped ]]; then duration=30000; fi
        placement=false
        if [[ $state == stopped ]]; then placement=true; fi
        if start_run "t9-bigmap-$state" -W --es fakeState "$state" --ez selfcheck "$placement" &&
            debug_tap map; then
            if $placement; then
                echo '大きい地図の配置測定のため、正面を向いたまま頭を止めてください。'
            fi
            if wait_for_t9_log 'AuraBigMap[[:space:]]*: CLOSED reason=timeout' "$((duration / 1000 + 6))" &&
                t9_map_expiry "$duration" <<< "$run_log" >> "$log"; then
                record PASS "bigmap $state 自動終了"
            else
                record FAIL "bigmap $state 自動終了"
            fi
            if $placement; then
                if grep -Eq 'AuraPlacement[[:space:]]*: BIGMAP PASS ' <<< "$run_log"; then
                    record PASS 'bigmap 配置（左右0°、下4°、直径28°）'
                else
                    record FAIL 'bigmap 配置'
                fi
            fi
            printf '%s\n' "$run_log" >> "$log"
        else
            record FAIL "bigmap $state 開始"
        fi
    done

    run_log=
    if start_run t9-bigmap-fast -W --es startMode auto --es fakeState replay --ez resetNotice true &&
        debug_tap left && wait_for_t9_log 'AuraHud[[:space:]]*: notice dismissed by tap' 4 &&
        replay_scenario fast --ef speed 4 &&
        wait_for_t9_log 'AuraSession[[:space:]]*: SAMPLE 2[5-9] WALKING ' 12 &&
        debug_tap map &&
        wait_for_t9_log 'AuraBigMap[[:space:]]*: CLOSED reason=fast' 8 &&
        debug_tap map &&
        wait_for_t9_log 'AuraInput[[:space:]]*: CLICK target=map source=debug ignored-fast' 3 &&
        t9_fast_map <<< "$run_log"; then
        record PASS 'bigmap 高速移動で即座に閉じ、開き直さない'
    else
        record FAIL 'bigmap 高速移動'
    fi
    printf '%s\n' "$run_log" >> "$log"
    replay_scenario stop || record FAIL 'bigmap 再生停止'
}

t9_walk_phases() {
    local actual
    actual=$(sed -n 's/.*AuraSession[[:space:]]*:[[:space:]]*WALK phase=\([a-z]*\).*/\1/p' | paste -sd ' ' -)
    [[ $actual == 'standby starting standby starting measuring ending measuring ending summary' ]]
}

t9_walk_measured() {
    awk '
        /AuraSession[[:space:]]*: SAMPLE / {
            sub(/^.*: SAMPLE /, "")
            if ($2 == "WALKING" && $5 > 0 && $6 > 0) found = 1
        }
        END { exit !found }
    '
}

check_session() {
    local args=(-W --es startMode standby --es fakeState replay --ez resetNotice true)
    if $preview; then args+=(--ez preview2d true); fi
    run_log=
    if start_run t9-session "${args[@]}" &&
        wait_for_t9_log 'AuraSession[[:space:]]*: WALK phase=standby' 5 &&
        debug_tap left && wait_for_t9_log 'AuraHud[[:space:]]*: notice dismissed by tap' 4 &&
        debug_tap left && sleep 1 && debug_tap left &&
        debug_tap left && wait_for_t9_log 'AuraSession[[:space:]]*: WALK phase=measuring' 6 &&
        replay_scenario walk_stop --ef speed 4 &&
        wait_for_t9_log 'AuraSession[[:space:]]*: SAMPLE [0-9]+ WALKING [^ ]+ [^ ]+ [1-9][0-9.]* [1-9][0-9]* ' 12 &&
        t9_walk_measured <<< "$run_log" &&
        debug_tap left && sleep 1 && debug_tap left &&
        debug_tap left && wait_for_t9_log 'AuraSession[[:space:]]*: WALK phase=summary' 6 &&
        t9_walk_phases <<< "$run_log"; then
        record PASS 'session 待機・開始と終了のキャンセル・walk_stop の計測・まとめ'
    else
        record FAIL 'session 待機からまとめまでの通し確認'
    fi
    printf '%s\n' "$run_log" >> "$log"
    replay_scenario stop || record FAIL 'session 再生停止'
}

collect_run() {
    local name=$1 route=$2
    local pid crash
    pid=$(adb_retry shell pidof "$pkg" | tr -d '\r') || true
    if ! crash=$(adb_retry logcat -b crash -d -t "$start_time" -s AndroidRuntime 2>> "$log"); then
        record FAIL "$name クラッシュログを取得できません"
    elif grep -Fq "Process: $pkg," <<< "$crash"; then
        printf '%s\n' "$crash" >> "$log"
        record FAIL "$name クラッシュ"
    elif [[ $pid =~ ^[0-9]+$ ]]; then
        record PASS "$name 起動・クラッシュなし"
    else
        record FAIL "$name プロセスなし"
    fi
    run_log=
    if [[ $pid =~ ^[0-9]+$ ]]; then
        if run_log=$(adb_retry logcat -d --pid="$pid" -t "$start_time" -s \
            AuraPlacement AuraHeadMotion AuraHud AuraProbe AuraMap AndroidRuntime 2>> "$log"); then
            printf '%s\n' "$run_log" >> "$log"
        else
            record FAIL "$name 表示ログを取得できません"
        fi
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
        adb_retry exec-out run-as "$pkg" cat "files/hud-$route-$panel.png" > "$file" &&
        [[ $(od -An -tx1 -N8 "$file" | tr -d ' \r\n') == 89504e470d0a1a0a ]]; then
        cp "$file" "$out/"
        record PASS "$name $panel PNG"
    else
        record FAIL "$name $panel PNG"
    fi
}

check_placement() {
    local check panel hide_disabled=false
    start_run selfcheck -W --es fakeState stopped --ez selfcheck true --ez grid true --ez capture true || return 0
    echo "約12秒計測します。最初の3秒は正面を向き、その後に頭を左右に振ってから止めてください。"
    sleep 12
    collect_run selfcheck xr
    if grep -Eq 'AuraPlacement[[:space:]]*: follow SKIP head-motion hide disabled[[:space:]]*$' <<< "$run_log"; then
        hide_disabled=true
    fi
    for check in placement follow clearzone; do
        if grep -Eq "AuraPlacement[[:space:]]*: $check PASS" <<< "$run_log"; then
            record PASS "$check"
        elif $hide_disabled && [[ $check == follow ]]; then
            record SKIP 'follow head-motion hide disabled'
        elif $hide_disabled && [[ $check == clearzone ]] &&
            grep -Eq 'AuraPlacement[[:space:]]*: clearzone FAIL[[:space:]]*$' <<< "$run_log"; then
            results+=('FAIL clearzone（参考値）')
        else
            record FAIL "$check"
        fi
    done
    for panel in left right grid; do capture_panel selfcheck "$panel" xr; done
}

headmotion_result() {
    awk '
        /AuraHeadMotion[[:space:]]*: HIDE speed=/ { hide = 1 }
        /AuraHeadMotion[[:space:]]*: SHOW speed=/ { show = 1 }
        /AuraHeadMotion[[:space:]]*: (HIDE|SHOW|SPEED) speed=/ {
            for (i = 1; i <= NF; i++) {
                if ($i ~ /^(peak|speed)=[0-9]+(\.[0-9]+)?$/) {
                    split($i, value, "=")
                    if (!measured || value[2] + 0 > peak) peak = value[2] + 0
                    measured = 1
                }
            }
        }
        END {
            if (measured) printf "最大角速度 %.2f°/秒", peak
            else printf "最大角速度 未取得"
            exit !(hide && show)
        }
    '
}

check_headmotion() {
    local result
    start_run headmotion -W --es fakeState stopped || return 0
    echo "頭を左右に2〜3回、普通の速さで振ってください（10秒）"
    sleep 10
    collect_run headmotion xr
    if result=$(headmotion_result <<< "$run_log"); then
        record PASS "headmotion HIDE / SHOW（$result）"
    else
        record FAIL "headmotion HIDE / SHOW（$result）"
    fi
    printf '%s\n' "${results[-1]}" >> "$log"
}

map_log() {
    local pid
    pid=$(adb_retry shell pidof "$pkg" | tr -d '\r') || return 1
    [[ $pid =~ ^[0-9]+$ ]] || return 1
    adb_retry logcat -d --pid="$pid" -t "$start_time" -s AuraMap AuraSession
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
        adb_retry exec-out run-as "$pkg" cat files/minimap-snapshot.png > "$file" &&
        [[ $(od -An -tx1 -N8 "$file" | tr -d ' \r\n') == 89504e470d0a1a0a ]]; then
        record PASS "$state 地図の snapshot PNG"
    else
        record FAIL "$state 地図の snapshot PNG"
    fi
}

map_replay_turn() {
    awk '
        /AuraSession[[:space:]]*: REPLAY walk_stop[[:space:]]*$/ { replay = 1 }
        replay && /AuraSession[[:space:]]*: SAMPLE 120 / { turned = 1 }
        replay && /AuraMap[[:space:]]*: marker arrowAngle=[0-9.]+[[:space:]]*$/ {
            split($NF, values, "=")
            angle = values[2] + 0
            if (angle <= 2 || angle >= 358) north = 1
            if (turned && north && angle >= 88 && angle <= 92) east = 1
        }
        END { exit !(turned && east) }
    '
}

map_replay_north_up() {
    awk -v key="$1" '
        /AuraSession[[:space:]]*: REPLAY walk_stop[[:space:]]*$/ { replay = 1 }
        replay && /AuraSession[[:space:]]*: SAMPLE 120 / { turned = 1 }
        replay && $0 ~ ("AuraMap[[:space:]]*: camera " key "=[-0-9.]+[[:space:]]*$") {
            split($NF, values, "=")
            if (turned) seen = 1
            if (values[2] + 0 != 0) rotated = 1
        }
        END { exit (rotated ? 2 : !seen) }
    '
}

check_map_replay() {
    if "$map_key_missing"; then
        record SKIP 'walk_stop 地図の方位（キーなし）'
        return 0
    fi
    local args=(-W --ez selfcheck true --es fakeState replay --ez capture true)
    local sleeping_preview=false refreshed_north=false refreshed_east=false
    if $preview; then args+=(--ez preview2d true); fi
    if $preview && ! grep -Eq '^[[:space:]]*mInteractive=true[[:space:]]*$' <<< "$power"; then
        sleeping_preview=true
    fi
    start_run map-replay "${args[@]}" || return 0
    if ! replay_scenario walk_stop --ef speed 4; then
        record FAIL 'walk_stop 地図の再生開始'
        return 0
    fi
    local deadline=$((SECONDS + 48)) logs=
    while (( SECONDS < deadline )); do
        logs=$(map_log) || break
        if grep -Fq 'SKIP key=missing' <<< "$logs"; then
            printf '%s\n' "$logs" >> "$log"
            record SKIP 'walk_stop 地図の方位（キーなし）'
            replay_scenario stop || record FAIL 'walk_stop 地図の再生停止'
            return 0
        fi
        if map_replay_turn <<< "$logs" && map_replay_north_up bearing <<< "$logs"; then break; fi
        if $sleeping_preview; then
            if ! $refreshed_east && grep -Eq 'AuraSession[[:space:]]*: SAMPLE 120 ' <<< "$logs"; then
                refresh_preview --ez selfcheck true --es fakeState replay || break
                refreshed_east=true
            elif ! $refreshed_north && grep -Eq 'AuraSession[[:space:]]*: SAMPLE 0 ' <<< "$logs"; then
                refresh_preview --ez selfcheck true --es fakeState replay || break
                refreshed_north=true
            fi
        fi
        sleep 2
    done
    printf '%s\n' "$logs" >> "$log"
    if map_replay_turn <<< "$logs"; then
        record PASS 'walk_stop 現在地の矢印の回転（約90°）'
    else
        record FAIL 'walk_stop 現在地の矢印の回転'
    fi
    if map_replay_north_up targetBearing <<< "$logs"; then
        record PASS 'walk_stop カメラへの方位入力（0°）'
    else
        record FAIL 'walk_stop カメラへの方位入力'
    fi
    local camera_status=0
    map_replay_north_up bearing <<< "$logs" || camera_status=$?
    if (( camera_status == 0 )); then
        record PASS 'walk_stop カメラの方位（0°）'
    elif (( camera_status == 1 )) && $sleeping_preview; then
        record SKIP 'walk_stop カメラの方位（表示スリープ中）'
    else
        record FAIL 'walk_stop カメラの方位'
    fi
    if ! replay_scenario stop; then
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

wait_for_safety_log() {
    local pattern=$1 deadline=$((SECONDS + $2))
    local pid
    pid=$(adb_retry shell pidof -s "$pkg" | tr -d '\r')
    [[ $pid =~ ^[0-9]+$ ]] || return 1
    run_log=
    while (( SECONDS < deadline )); do
        run_log=$(adb_retry logcat -d --pid="$pid" -v threadtime -t "$start_time" -s AuraProbe AuraSession AuraHud) || return 1
        if grep -Eq "$pattern" <<< "$run_log"; then return 0; fi
        sleep 0.25
    done
    return 1
}

check_t15_probe() {
    local mode=$1 timeout result status
    if [[ $mode == heading ]]; then
        echo 'heading: 装着して正面を向き、60秒間静止してください。Geospatial を試す場合は画面で同意してください。'
        timeout=75
    else
        echo 'speech: マイクの許可を求められたら許可し、画面に指示が出たら「東京駅」と話してください。'
        timeout=60
    fi
    start_run "$mode" -W --es mode "$mode" || return 0
    run_log=
    if wait_for_safety_log "AuraProbe[[:space:]]*: $mode result (PASS|FAIL|SKIP) " "$timeout"; then
        result=$(awk -v mode="$mode" '$0 ~ ("AuraProbe[[:space:]]*: " mode " result ") { sub(/^.* result /, ""); print }' <<< "$run_log" | tail -n 1)
        status=${result%% *}
        record "$status" "$mode ${result#* }"
    else
        record FAIL "$mode 診断の終了ログを取得できません"
    fi
    printf '%s\n' "$run_log" >> "$log"
}

refresh_preview() {
    if $preview; then
        # A sleeping display draws at launch, but may skip later frames.
        # Recreate only the Activity so the in-process replay keeps running.
        adb_retry shell am start -W --activity-single-top -n "$pkg/.MainActivity" \
            --ez preview2d true --ez capture true "$@" >> "$log"
    fi
}

save_safety_log() {
    grep -E 'capture hud-|REPLAY |TRANSITION |notice dismissed by timeout' <<< "$run_log" >> "$log" || true
}

check_safety() {
    local route=xr phase
    local args=(-W --ez capture true --es startMode auto)
    if $preview; then
        route=2d
        args+=(--ez preview2d true)
    fi
    run_log=
    if start_run t6b-notice "${args[@]}" --ez resetNotice true && replay_scenario walk_stop --ef speed 4 &&
        wait_for_safety_log 'notice dismissed by timeout' 22 &&
        refresh_preview --es startMode auto && wait_for_safety_log "capture hud-$route-left-stats\.png: [1-9]" 6; then
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
    if start_run t6b-fast "${args[@]}" && replay_scenario fast --ef speed 4 &&
        wait_for_safety_log 'TRANSITION .* -> FAST at ' 15 &&
        refresh_preview --es startMode auto && wait_for_safety_log "capture hud-$route-left-fast\.png: [1-9]" 6; then
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
    replay_scenario stop || record FAIL 'T6b 再生停止'
}

main() {
    parse_args "$@" || return 1
    cd "$(dirname "${BASH_SOURCE[0]}")/.." || return 1
    local stamp state
    results=()
    failed=0
    map_key_missing=false
    if $preview; then
        if selected placement; then record SKIP 'placement（装着が必要）'; fi
        if selected headmotion; then record SKIP 'headmotion（装着が必要）'; fi
        if selected input; then record SKIP 'input（装着が必要）'; fi
        if selected bigmap; then record SKIP 'bigmap（装着が必要）'; fi
        if [[ $only =~ ^(placement|headmotion|input|bigmap)(,(placement|headmotion|input|bigmap))*$ ]]; then
            printf '%s\n' "${results[@]}"
            return 0
        fi
    fi

    check_device || return "$?"
    stamp=$(date +%Y%m%d-%H%M%S)
    out="build/device-check/$stamp"
    shots="build/device-shots/$stamp"
    mkdir -p "$out" "$shots"
    log="$out/log.txt"
    grep -E 'mWakefulness=|mLastSleepReason=|mInteractive=' <<< "$power" > "$log" || true
    if ! prepare; then summary; return 1; fi
    if ! $preview && selected placement; then check_placement; fi
    if ! $preview && selected headmotion; then check_headmotion; fi
    if selected safety; then check_safety; fi
    for state in walking stopped fast nofix; do
        if selected "$state"; then check_state "$state"; fi
    done
    if selected map-replay; then check_map_replay; fi
    if ! $preview && selected input; then check_input; fi
    if ! $preview && selected bigmap; then check_bigmap; fi
    if selected session; then check_session; fi
    if selected heading; then check_t15_probe heading; fi
    if selected speech; then check_t15_probe speech; fi
    summary
    return "$failed"
}

if [[ ${BASH_SOURCE[0]} == "$0" ]]; then main "$@"; fi
