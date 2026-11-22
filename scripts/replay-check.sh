#!/usr/bin/env bash
# Run from Git Bash after merging T4 and building the debug APK.
set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

package=io.github.yamichi77.aurawalkinfo
action=$package.REPLAY
apk=${1:-app/build/outputs/apk/debug/app-debug.apk}
adb=${ADB:-adb}
if [[ $adb == adb ]] && ! command -v adb >/dev/null; then
    sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}
    adb=$sdk/platform-tools/adb.exe
fi

out=build/device-check/$(date '+%Y%m%d-%H%M%S')
mkdir -p "$out" || exit 1

# Read TRANSITION records for timing and per-second SAMPLE records for the totals.
check_scenario() {
    awk -v scenario="$1" '
        function result(ok, name, detail) {
            printf "%s %s %s (%s)\n", ok ? "PASS" : "FAIL", scenario, name, detail
            if (!ok) failed = 1
        }
        function in_range(value, first, last) { return value >= first && value <= last }
        function frozen(first, last, expected, t) {
            if (!state[first] || first > last) return 0
            for (t = first; t <= last; t++)
                if (state[t] != expected || distance[t] != distance[first] || walking[t] != walking[first]) return 0
            return 1
        }
        $1 == "TRANSITION" && NF == 6 {
            key = $2 "->" $4
            if (!(key in transition)) transition[key] = $6
        }
        $1 == "SAMPLE" && NF == 8 {
            t = $2 + 0
            state[t] = $3
            accuracy[t] = $5
            distance[t] = $6 + 0
            walking[t] = $7 + 0
            bearing[t] = $8
        }
        END {
            duration = scenario == "fast" ? 90 : (scenario == "walk_stop" ? 150 : 85)
            complete = 1
            for (t = 0; t <= duration; t++) if (!(t in state)) complete = 0
            result(complete, "one sample per replay second", "0.." duration)
            if (scenario == "fast") {
                entered = transition["WALKING->FAST"] + 0
                resumed = transition["FAST->WALKING"] + 0
                result(in_range(entered - 30, 5, 9), "enter FAST", entered - 30 " s after speeding up, expected 5..9")
                result(in_range(resumed - 60, 5, 9), "leave FAST", resumed - 60 " s after slowing down, expected 5..9")
                result(frozen(entered, resumed - 1, "FAST"), "frozen distance and walk time", entered ".." resumed - 1)
                result(walking[89] > walking[resumed], "walking time resumes", "by 89 s")
            } else if (scenario == "walk_stop") {
                entered = transition["WALKING->STOPPED"] + 0
                resumed = transition["STOPPED->WALKING"] + 0
                result(in_range(entered - 60, 5, 9), "enter STOPPED", entered - 60 " s after stopping, expected 5..9")
                result(in_range(resumed, 90, 92), "leave STOPPED", "WALKING at " resumed " s, expected 90..92")
                result(frozen(entered, resumed - 1, "STOPPED"), "frozen distance and walk time", entered ".." resumed - 1)
                turn = bearing[120] - bearing[119]
                result(bearing[119] ~ /^[0-9.]+$/ && bearing[120] ~ /^[0-9.]+$/ && in_range(turn, 88, 92),
                    "bearing change", turn " degrees")
            } else {
                entered = transition["WALKING->NO_FIX"] + 0
                result(in_range(entered - 29, 9, 11), "NO_FIX timeout", entered - 29 " s after last fix, expected 9..11")
                result(frozen(entered, 54, "NO_FIX"), "frozen distance and walk time", entered "..54")
                poor = 1
                for (t = 45; t <= 54; t++) if (accuracy[t] != 40) poor = 0
                result(poor, "poor accuracy fixes", "40 m at 45..54 s")
                result(state[55] == "WALKING", "fix restored", "55 s")
            }
            exit failed ? 1 : 0
        }
    ' "$2"
}

check_fieldlog() {
    awk -F, '
        function metric(value) { return value ~ /^[0-9]+\.[0-9][0-9][0-9]$/ }
        NR == 1 {
            if ($0 != "elapsed_s,state,avg_speed_mps,accuracy_m,added_distance_m,fix_decision,provider") invalid = 1
            next
        }
        {
            if (NF != 7 || $1 !~ /^[0-9]+$/ || $1 != NR - 1 ||
                $2 !~ /^(WALKING|STOPPED|FAST|NO_FIX)$/ ||
                ($3 != "" && !metric($3)) || ($4 != "" && !metric($4)) || !metric($5) ||
                $6 !~ /^(ACCEPTED|POOR_ACCURACY|TOO_LITTLE_MOVEMENT|NOT_WALKING|NO_SAMPLE)$/ ||
                $7 !~ /^(fused|gps)$/ || $0 ~ /[+-]?[0-9][0-9]?[0-9]?\.[0-9][0-9][0-9][0-9]+/) invalid = 1
        }
        END { exit invalid || NR < 21 ? 1 : 0 }
    ' "$1"
}

replay_command() {
    "$adb" shell am broadcast -a "$action" -p "$package" --es scenario "$1" --ef speed 4
}

list_fieldlogs() {
    "$adb" exec-out run-as "$package" ls files/fieldlog | tr -d '\r'
}

run_checks() {
    local failed=0 pid scenario duration trace deadline name fieldlog before after
    if [[ ! -f $apk ]]; then
        printf 'FAIL APK not found: %s\n' "$apk"
        return 1
    fi
    if ! "$adb" install -r --no-streaming "$apk" ||
        ! "$adb" shell am start -S -n "$package/.MainActivity"; then
        echo 'FAIL install or launch'
        return 1
    fi
    pid=$("$adb" shell pidof -s "$package" | tr -d '\r')
    if [[ ! $pid =~ ^[0-9]+$ ]]; then
        echo 'FAIL application process not found'
        return 1
    fi
    trap 'replay_command stop >/dev/null 2>&1 || true' EXIT

    for scenario in walk_stop fast nofix; do
        case "$scenario" in
            walk_stop) duration=150 ;;
            fast) duration=90 ;;
            nofix) duration=85 ;;
        esac
        trace=$out/replay-$scenario.log
        if ! replay_command "$scenario"; then
            echo "FAIL $scenario broadcast"
            failed=1
            continue
        fi
        deadline=$((SECONDS + (duration + 3) / 4 + 15))
        until (( SECONDS >= deadline )); do
            if ! "$adb" logcat -d --pid="$pid" -v raw -s AuraSession:I |
                awk -v name="$scenario" '$1 == "REPLAY" { active = $2 == name } active && /^(REPLAY|SAMPLE|TRANSITION) /' > "$trace"; then
                break
            fi
            if grep -q "^SAMPLE $duration " "$trace"; then
                break
            fi
            sleep 2
        done
        if [[ -s $trace ]]; then
            awk '$1 == "TRANSITION"' "$trace"
            check_scenario "$scenario" "$trace" || failed=1
        else
            echo "FAIL $scenario no replay output"
            failed=1
        fi
    done

    if ! before=$(list_fieldlogs) || ! replay_command stop; then
        echo 'FAIL could not list existing field logs or restore real location'
        return 1
    fi
    sleep 30
    if ! after=$(list_fieldlogs); then
        echo 'FAIL could not list field logs after restoring real location'
        return 1
    fi
    name=$(comm -13 <(printf '%s\n' "$before" | sort) <(printf '%s\n' "$after" | sort) | tail -n 1)
    if [[ ! $name =~ ^[0-9]{8}T[0-9]{9}Z(_[0-9]+)?\.csv$ ]]; then
        echo 'FAIL no new field log after restoring real location'
        return 1
    fi
    fieldlog=$out/$name
    if "$adb" exec-out run-as "$package" cat "files/fieldlog/$name" > "$fieldlog" &&
        check_fieldlog "$fieldlog"; then
        echo "PASS field log contains at least 20 one-second rows with the expected fields ($fieldlog)"
    else
        echo "FAIL field log missing rows or contains unexpected values ($fieldlog)"
        failed=1
    fi
    return "$failed"
}

run_checks 2>&1 | tee "$out/replay.txt"
status=$?
printf 'Report: %s/replay.txt\n' "$out"
if (( status != 0 )); then exit 1; fi
