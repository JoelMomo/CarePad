#!/usr/bin/env bash
set -Eeuo pipefail

HOST_APK="${1:?host APK path required}"
PERFORMANCE_APK="${2:?performance module APK path required}"
EMULATOR_FIXTURE_APK="${3:?performance emulator fixture APK path required}"

HOST_PACKAGE="com.joel.thordoctor.carepadlabhost"
PERFORMANCE_PACKAGE="dev.carepad.module.performance"
PERFORMANCE_SERVICE_COMPONENT="${PERFORMANCE_PACKAGE}/.PerformanceMonitorService"
PERFORMANCE_RESUME_ACTION="dev.carepad.module.performance.action.RESUME"
EMULATOR_FIXTURE_PACKAGE="org.ppsspp.ppsspp"
EMULATOR_FIXTURE_COMPONENT="${EMULATOR_FIXTURE_PACKAGE}/dev.carepad.fixture.emulator.FakeEmulatorActivity"
HARNESS_COMPONENT="${HOST_PACKAGE}/com.joel.thordoctor.modules.host.ModuleLabHarnessActivity"
OPEN_ACTION="dev.carepad.action.OPEN_MODULE"
UI_DUMP_DEVICE="/sdcard/carepad-performance-window.xml"
UI_DUMP_LOCAL="${RUNNER_TEMP:-/tmp}/carepad-performance-window.xml"
SAMPLES_LOCAL="${RUNNER_TEMP:-/tmp}/carepad-performance-samples.jsonl"
SESSION_LOCAL="${RUNNER_TEMP:-/tmp}/carepad-performance-last-session.json"

CURRENT_BUG=""
CURRENT_CHECK=""
CURRENT_EXPECTED=""
CURRENT_ACTUAL=""
CURRENT_PREVIOUS_SAMPLES="n/a"
BUG4_SERVICE_STOPPED_AT_MS="n/a"
BUG4_RESUME_REQUESTED_AT_MS="n/a"

current_resumed_activity() {
    adb shell dumpsys activity activities 2>/dev/null |
        grep -m1 -E 'topResumedActivity=|mResumedActivity|ResumedActivity:' || true
}

dump_ui() {
    rm -f "$UI_DUMP_LOCAL"
    adb shell rm -f "$UI_DUMP_DEVICE" >/dev/null 2>&1 || true
    for _ in $(seq 1 20); do
        if adb shell uiautomator dump "$UI_DUMP_DEVICE" >/dev/null 2>&1 &&
            adb pull "$UI_DUMP_DEVICE" "$UI_DUMP_LOCAL" >/dev/null 2>&1 &&
            [[ -s "$UI_DUMP_LOCAL" ]]; then
            return 0
        fi
        sleep 0.5
    done
    return 1
}

tap_ui_button() {
    local coordinates
    coordinates="$(python3 - "$UI_DUMP_LOCAL" "$@" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path = sys.argv[1]
labels = sys.argv[2:]
root = ET.parse(path).getroot()
for label in labels:
    expected = label.casefold()
    for node in root.iter("node"):
        if node.attrib.get("text", "").casefold() != expected:
            continue
        if not node.attrib.get("class", "").endswith("Button"):
            continue
        if node.attrib.get("enabled") != "true":
            continue
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib.get("bounds", ""))
        if not match:
            continue
        left, top, right, bottom = map(int, match.groups())
        if right <= left or bottom <= top:
            continue
        print((left + right) // 2, (top + bottom) // 2)
        raise SystemExit(0)
raise SystemExit(1)
PY
)" || {
        echo "Performance UI did not contain expected enabled button: $*" >&2
        cat "$UI_DUMP_LOCAL" >&2 || true
        return 1
    }

    read -r tap_x tap_y <<<"$coordinates"
    adb shell input tap "$tap_x" "$tap_y"
}

open_harness() {
    adb shell am force-stop "$HOST_PACKAGE" >/dev/null
    adb shell am start -n "$HARNESS_COMPONENT" >/dev/null
    for _ in $(seq 1 20); do
        if dump_ui && grep -Fq "package=\"$HOST_PACKAGE\"" "$UI_DUMP_LOCAL"; then
            return 0
        fi
        sleep 0.5
    done
    echo "CarePad harness did not become visible" >&2
    cat "$UI_DUMP_LOCAL" >&2 || true
    return 1
}

assert_harness_contains() {
    open_harness
    for expected in "$@"; do
        if ! grep -Fq "$expected" "$UI_DUMP_LOCAL"; then
            echo "Harness did not contain expected text: $expected" >&2
            cat "$UI_DUMP_LOCAL" >&2 || true
            return 1
        fi
    done
}

open_performance() {
    adb shell am start -a "$OPEN_ACTION" -p "$PERFORMANCE_PACKAGE" >/dev/null
    for _ in $(seq 1 20); do
        resumed="$(current_resumed_activity)"
        if grep -Fq "$PERFORMANCE_PACKAGE" <<<"$resumed" &&
            grep -Fq "PerformanceActivity" <<<"$resumed" &&
            dump_ui &&
            grep -Fq "package=\"$PERFORMANCE_PACKAGE\"" "$UI_DUMP_LOCAL"; then
            return 0
        fi
        sleep 0.5
    done

    echo "Performance module did not open through OPEN_MODULE" >&2
    echo "Resumed activity: $(current_resumed_activity)" >&2
    cat "$UI_DUMP_LOCAL" >&2 || true
    return 1
}

launch_emulator_fixture() {
    adb shell am start -n "$EMULATOR_FIXTURE_COMPONENT" >/dev/null
    for _ in $(seq 1 20); do
        resumed="$(current_resumed_activity)"
        if grep -Fq "$EMULATOR_FIXTURE_PACKAGE" <<<"$resumed" &&
            grep -Fq "FakeEmulatorActivity" <<<"$resumed"; then
            return 0
        fi
        sleep 0.5
    done

    echo "Emulator fixture never became foreground" >&2
    echo "$(current_resumed_activity)" >&2
    return 1
}

read_private_file() {
    local relative_path="$1"
    local destination="$2"
    adb shell run-as "$PERFORMANCE_PACKAGE" cat "$relative_path" >"$destination" 2>/dev/null
}

sample_count() {
    if ! read_private_file files/active_session_samples.jsonl "$SAMPLES_LOCAL" ||
        [[ ! -s "$SAMPLES_LOCAL" ]]; then
        echo 0
        return
    fi
    wc -l < "$SAMPLES_LOCAL" | tr -d ' '
}

performance_service_running() {
    adb shell dumpsys activity services 2>/dev/null |
        grep -Fq "PerformanceMonitorService"
}

dump_recovery_diagnostics() {
    adb shell dumpsys activity services >&2 || true
    adb shell appops get "$PERFORMANCE_PACKAGE" GET_USAGE_STATS >&2 || true
    adb logcat -d -v brief 2>/dev/null |
        grep -E 'dev\.carepad\.module\.performance|PerformanceMonitorService|ForegroundService|AndroidRuntime|ActivityManager' >&2 || true
    adb shell run-as "$PERFORMANCE_PACKAGE" \
        cat shared_prefs/thor_doctor_session_recovery.xml >&2 2>/dev/null || true
}

recovery_stage() {
    adb shell run-as "$PERFORMANCE_PACKAGE" \
        cat shared_prefs/thor_doctor_session_recovery.xml 2>/dev/null |
        sed -n 's/.*<string name="stage">\([^<]*\)<\/string>.*/\1/p' |
        head -n1 || true
}

recovery_started_at() {
    adb shell run-as "$PERFORMANCE_PACKAGE" \
        cat shared_prefs/thor_doctor_session_recovery.xml 2>/dev/null |
        sed -n 's/.*<long name="started_at" value="\([0-9]*\)".*/\1/p' |
        head -n1 || true
}

usage_stats_evidence() {
    adb shell dumpsys usagestats 2>/dev/null |
        grep -E -C 3 "${EMULATOR_FIXTURE_PACKAGE}|${PERFORMANCE_PACKAGE}" |
        tail -n 160 || true
}

set_bug_check() {
    CURRENT_CHECK="$1"
    CURRENT_EXPECTED="$2"
    CURRENT_ACTUAL="${3:-runtime state below}"
    CURRENT_PREVIOUS_SAMPLES="${4:-n/a}"
}

emit_bug_failure() {
    local status="${1:-1}"
    local command="${2:-explicit assertion}"
    local resumed
    local service_state="stopped"
    local foreground_detected="false"
    local pid
    local stage
    local started_at
    local current_samples
    local diagnostic_artifact="${RUNNER_TEMP:-/tmp}/carepad-emulator.log"

    set +e
    trap - ERR
    resumed="$(current_resumed_activity)"
    current_samples="$(sample_count)"
    pid="$(adb shell pidof "$PERFORMANCE_PACKAGE" 2>/dev/null | tr -d '\r' || true)"
    stage="$(recovery_stage)"
    started_at="$(recovery_started_at)"
    if performance_service_running; then
        service_state="running"
    fi
    if grep -Fq "$EMULATOR_FIXTURE_PACKAGE" <<<"$resumed"; then
        foreground_detected="true"
    fi

    {
        echo "=== CAREPAD_SMOKE_FAILURE_BEGIN ==="
        echo "FAIL ${CURRENT_BUG}"
        echo "scenario=${CURRENT_BUG}"
        echo "check=${CURRENT_CHECK:-unknown}"
        echo "expected=${CURRENT_EXPECTED:-unspecified}"
        echo "actual=${CURRENT_ACTUAL:-runtime state below}"
        echo "failing_command=${command}"
        echo "session_phase=${stage:-NONE}"
        echo "sample_count=${current_samples}"
        echo "previous_sample_count=${CURRENT_PREVIOUS_SAMPLES}"
        echo "foreground_detected=${foreground_detected}"
        echo "observed_resumed_activity=${resumed:-NONE}"
        echo "observed_emulator_package=${EMULATOR_FIXTURE_PACKAGE}"
        echo "performance_process_pid=${pid:-NONE}"
        echo "performance_service_state=${service_state}"
        echo "usage_access_state=$(adb shell appops get "$PERFORMANCE_PACKAGE" GET_USAGE_STATS 2>/dev/null | tr '\n' ' ' || true)"
        echo "wall_clock_ms=$(date +%s%3N)"
        echo "recovery_started_at_ms=${started_at:-NONE}"
        echo "policy_usage_event_overlap_ms=15000"
        echo "legacy_current_foreground_lookback_ms=60000"
        echo "bug4_service_stopped_at_ms=${BUG4_SERVICE_STOPPED_AT_MS}"
        echo "bug4_resume_requested_at_ms=${BUG4_RESUME_REQUESTED_AT_MS}"
        if [[ "$BUG4_SERVICE_STOPPED_AT_MS" =~ ^[0-9]+$ && "$BUG4_RESUME_REQUESTED_AT_MS" =~ ^[0-9]+$ ]]; then
            echo "bug4_recovery_gap_ms=$((BUG4_RESUME_REQUESTED_AT_MS - BUG4_SERVICE_STOPPED_AT_MS))"
        else
            echo "bug4_recovery_gap_ms=n/a"
        fi
        echo "usage_stats_evidence_begin"
        usage_stats_evidence
        echo "usage_stats_evidence_end"
        echo "recovery_state_begin"
        adb shell run-as "$PERFORMANCE_PACKAGE" \
            cat shared_prefs/thor_doctor_session_recovery.xml 2>/dev/null || true
        echo "recovery_state_end"
        echo "last_session_begin"
        adb shell run-as "$PERFORMANCE_PACKAGE" cat files/last_session.json 2>/dev/null || true
        echo "last_session_end"
        dump_recovery_diagnostics
        echo "=== CAREPAD_SMOKE_FAILURE_END ==="
    } 2>&1 | tee -a "$diagnostic_artifact" >&2
    exit "$status"
}

on_bug_error() {
    local status="$1"
    local command="$2"
    if [[ -n "$CURRENT_BUG" ]]; then
        emit_bug_failure "$status" "$command"
    fi
    return "$status"
}

trap 'on_bug_error "$?" "$BASH_COMMAND"' ERR

wait_for_samples() {
    for _ in $(seq 1 30); do
        if (( $(sample_count) >= 1 )); then
            return 0
        fi
        sleep 1
    done

    echo "Performance module did not capture a sample while the emulator fixture was foreground" >&2
    adb shell dumpsys activity activities >&2 || true
    adb shell appops get "$PERFORMANCE_PACKAGE" GET_USAGE_STATS >&2 || true
    return 1
}

wait_for_sample_count_greater_than() {
    local previous_count="$1"
    for _ in $(seq 1 30); do
        current_count="$(sample_count)"
        if (( current_count > previous_count )); then
            return 0
        fi
        sleep 1
    done

    echo "Performance module did not append samples after recovery; previous=$previous_count current=$(sample_count)" >&2
    adb shell dumpsys activity activities >&2 || true
    dump_recovery_diagnostics
    return 1
}

assert_sample_count_stable() {
    local expected_count="$1"
    local wait_seconds="${2:-6}"
    sleep "$wait_seconds"
    current_count="$(sample_count)"
    if (( current_count != expected_count )); then
        echo "Performance module kept sampling without Usage Access; expected=$expected_count current=$current_count" >&2
        return 1
    fi
}

clear_last_session() {
    adb shell run-as "$PERFORMANCE_PACKAGE" rm -f files/last_session.json >/dev/null 2>&1 || true
    rm -f "$SESSION_LOCAL"
}

wait_for_last_session() {
    rm -f "$SESSION_LOCAL"
    for _ in $(seq 1 40); do
        if read_private_file files/last_session.json "$SESSION_LOCAL" &&
            [[ -s "$SESSION_LOCAL" ]]; then
            return 0
        fi
        sleep 1
    done

    echo "Performance module did not persist a completed last_session.json" >&2
    adb shell run-as "$PERFORMANCE_PACKAGE" ls -la files >&2 || true
    return 1
}

assert_session_json() {
    local expected_end_reason="$1"
    local minimum_samples="${2:-1}"
    python3 - "$SESSION_LOCAL" "$expected_end_reason" "$minimum_samples" <<'PY'
import json
import sys

path = sys.argv[1]
expected_end_reason = sys.argv[2]
minimum_samples = int(sys.argv[3])
with open(path, encoding="utf-8") as handle:
    session = json.load(handle)

assert session["schema"] == "thor-doctor-session", session
assert session["schemaVersion"] == 2, session
assert session["emulator"]["name"] == "PPSSPP", session
assert session["emulator"]["package"] == "org.ppsspp.ppsspp", session
assert session["sampleCount"] >= minimum_samples, session
assert len(session["samples"]) == session["sampleCount"], session
assert session["durationSeconds"] > 0, session
assert session["endReason"] == expected_end_reason, session
assert isinstance(session["summary"], dict), session
PY
}

assert_recovery_cleared() {
    if adb shell run-as "$PERFORMANCE_PACKAGE" test -e files/active_session_samples.jsonl; then
        echo "Performance recovery samples remained after successful finalization" >&2
        adb shell run-as "$PERFORMANCE_PACKAGE" ls -la files >&2 || true
        exit 1
    fi
}

start_session_with_fixture() {
    open_performance
    tap_ui_button "Start session" "Iniciar sesión"
    launch_emulator_fixture
    wait_for_samples
}

# The previous generic module smoke can leave its own host installation in place.
adb install -r "$HOST_APK"
adb install "$PERFORMANCE_APK"
adb install "$EMULATOR_FIXTURE_APK"

assert_harness_contains "Accepted: performance" "Version: 0.1.0"

# The first open proves the user-facing missing-permission state before CI grants it.
adb shell am force-stop "$PERFORMANCE_PACKAGE" >/dev/null
open_performance
if ! grep -Eq "Usage access required|Falta acceso de uso" "$UI_DUMP_LOCAL"; then
    echo "Performance module did not explain missing Usage Access" >&2
    cat "$UI_DUMP_LOCAL" >&2 || true
    exit 1
fi

adb shell appops set "$PERFORMANCE_PACKAGE" GET_USAGE_STATS allow
adb shell pm grant "$PERFORMANCE_PACKAGE" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true

# Session 1: automatic foreground timeout after the user returns to Performance.
open_performance
if ! grep -Eiq "Start session|Iniciar sesión" "$UI_DUMP_LOCAL"; then
    echo "Performance module did not expose the session start action after Usage Access was granted" >&2
    cat "$UI_DUMP_LOCAL" >&2 || true
    exit 1
fi
tap_ui_button "Start session" "Iniciar sesión"
launch_emulator_fixture
wait_for_samples

open_performance
wait_for_last_session
assert_session_json "foreground_timeout" 1

if ! dump_ui ||
    ! grep -Fq "PPSSPP" "$UI_DUMP_LOCAL" ||
    ! grep -Eiq "Samples: [1-9]|Muestras: [1-9]" "$UI_DUMP_LOCAL"; then
    echo "Performance UI did not render the completed session summary" >&2
    cat "$UI_DUMP_LOCAL" >&2 || true
    exit 1
fi
assert_recovery_cleared

# Session 2: explicit stop from the real Performance UI must save exactly as a
# manual stop and clear recovery state rather than depending on the timeout path.
clear_last_session
start_session_with_fixture
open_performance
tap_ui_button "Finish session" "Terminar sesión"
wait_for_last_session
assert_session_json "manual_stop" 1
assert_recovery_cleared

# Session 3 / BUG-5: killing the Performance process during active monitoring must keep
# persisted recovery. Reopening the module auto-resumes that same session; putting
# PPSSPP back in foreground must append another sample before the user stops it.
CURRENT_BUG="BUG-5"
echo "START BUG-5"
set_bug_check "establish active session before process interruption" \
    "PPSSPP foreground with at least one persisted sample"
clear_last_session
start_session_with_fixture
samples_before_restart="$(sample_count)"
set_bug_check "persisted sample exists before interruption" \
    "sample_count >= 1" \
    "sample_count=${samples_before_restart}" \
    "$samples_before_restart"
if (( samples_before_restart < 1 )); then
    emit_bug_failure 1 "samples_before_restart < 1"
fi

set_bug_check "recovery samples survive force-stop" \
    "files/active_session_samples.jsonl exists and is non-empty" \
    "recovery_samples_file_missing" \
    "$samples_before_restart"
adb shell am force-stop "$PERFORMANCE_PACKAGE" >/dev/null
if ! adb shell run-as "$PERFORMANCE_PACKAGE" test -s files/active_session_samples.jsonl; then
    emit_bug_failure 1 "active_session_samples.jsonl missing after force-stop"
fi

set_bug_check "recovered session appends a new sample" \
    "sample_count > ${samples_before_restart}" \
    "sample_count=$(sample_count)" \
    "$samples_before_restart"
open_performance
launch_emulator_fixture
wait_for_sample_count_greater_than "$samples_before_restart"
minimum_recovered_samples=$((samples_before_restart + 1))
set_bug_check "recovered session finalizes as manual_stop" \
    "endReason=manual_stop and sampleCount >= ${minimum_recovered_samples}" \
    "last_session pending" \
    "$samples_before_restart"
open_performance
tap_ui_button "Finish session" "Terminar sesión"
wait_for_last_session
assert_session_json "manual_stop" "$minimum_recovered_samples"
set_bug_check "recovery state cleared after BUG-5 finalization" \
    "active_session_samples.jsonl absent" \
    "recovery cleanup check" \
    "$samples_before_restart"
assert_recovery_cleared
echo "PASS BUG-5"
CURRENT_BUG=""

# Session 4 / BUG-3: revoking Usage Access while PPSSPP remains foreground must
# stop sampling immediately, keep recoverable state and expose the permission error.
# Regranting the permission resumes the same session rather than starting over.
CURRENT_BUG="BUG-3"
echo "START BUG-3"
set_bug_check "establish active session before Usage Access revocation" \
    "PPSSPP foreground with at least one persisted sample"
clear_last_session
start_session_with_fixture
samples_before_revoke="$(sample_count)"
set_bug_check "Usage Access removal is surfaced in UI" \
    "Usage access required/Falta acceso de uso visible" \
    "UI text did not contain permission error" \
    "$samples_before_revoke"
adb shell appops set "$PERFORMANCE_PACKAGE" GET_USAGE_STATS ignore
sleep 3
open_performance
if ! grep -Eq "Usage access required|Falta acceso de uso" "$UI_DUMP_LOCAL"; then
    CURRENT_ACTUAL="$(tr '\n' ' ' < "$UI_DUMP_LOCAL" | cut -c1-600)"
    emit_bug_failure 1 "Usage Access error text missing"
fi
set_bug_check "sampling stops while Usage Access is revoked" \
    "sample_count remains ${samples_before_revoke} for 6 seconds" \
    "sample_count=$(sample_count)" \
    "$samples_before_revoke"
assert_sample_count_stable "$samples_before_revoke" 6
set_bug_check "recovery state survives Usage Access revocation" \
    "files/active_session_samples.jsonl exists and is non-empty" \
    "recovery samples file check" \
    "$samples_before_revoke"
if ! adb shell run-as "$PERFORMANCE_PACKAGE" test -s files/active_session_samples.jsonl; then
    emit_bug_failure 1 "active_session_samples.jsonl missing after Usage Access revocation"
fi

set_bug_check "same session resumes sampling after Usage Access restore" \
    "sample_count > ${samples_before_revoke}" \
    "sample_count=$(sample_count)" \
    "$samples_before_revoke"
adb shell appops set "$PERFORMANCE_PACKAGE" GET_USAGE_STATS allow
open_performance
launch_emulator_fixture
wait_for_sample_count_greater_than "$samples_before_revoke"
minimum_permission_recovery_samples=$((samples_before_revoke + 1))
set_bug_check "Usage Access recovered session finalizes as manual_stop" \
    "endReason=manual_stop and sampleCount >= ${minimum_permission_recovery_samples}" \
    "last_session pending" \
    "$samples_before_revoke"
open_performance
tap_ui_button "Finish session" "Terminar sesión"
wait_for_last_session
assert_session_json "manual_stop" "$minimum_permission_recovery_samples"
set_bug_check "recovery state cleared after BUG-3 finalization" \
    "active_session_samples.jsonl absent" \
    "recovery cleanup check" \
    "$samples_before_revoke"
assert_recovery_cleared
echo "PASS BUG-3"
CURRENT_BUG=""

# Session 5 / BUG-4: interrupt monitoring without force-stopping the package,
# keep PPSSPP continuously foreground for longer than the old 60 s lookback, then
# resume the service directly. This simulates recoverable process/service loss
# without putting the app into Android's user-requested stopped state.
CURRENT_BUG="BUG-4"
echo "START BUG-4"
set_bug_check "establish active session before late recovery" \
    "PPSSPP foreground with at least one persisted sample"
clear_last_session
start_session_with_fixture
samples_before_late_recovery="$(sample_count)"
CURRENT_PREVIOUS_SAMPLES="$samples_before_late_recovery"
set_bug_check "persisted samples survive service/process interruption" \
    "files/active_session_samples.jsonl exists and is non-empty" \
    "recovery samples file check" \
    "$samples_before_late_recovery"
adb shell am stopservice -n "$PERFORMANCE_SERVICE_COMPONENT" >/dev/null
BUG4_SERVICE_STOPPED_AT_MS="$(date +%s%3N)"
adb shell am kill "$PERFORMANCE_PACKAGE" >/dev/null || true
if ! adb shell run-as "$PERFORMANCE_PACKAGE" test -s files/active_session_samples.jsonl; then
    emit_bug_failure 1 "active_session_samples.jsonl missing before late recovery"
fi
set_bug_check "PPSSPP remains foreground during late recovery setup" \
    "resumed activity contains ${EMULATOR_FIXTURE_PACKAGE}" \
    "resumed_activity=$(current_resumed_activity)" \
    "$samples_before_late_recovery"
if ! grep -Fq "$EMULATOR_FIXTURE_PACKAGE" <<<"$(current_resumed_activity)"; then
    emit_bug_failure 1 "PPSSPP not foreground during late recovery setup"
fi
sleep 65
adb logcat -c
BUG4_RESUME_REQUESTED_AT_MS="$(date +%s%3N)"
set_bug_check "Performance service starts for late recovery" \
    "PerformanceMonitorService running after ACTION_RESUME" \
    "service_state=$(performance_service_running && echo running || echo stopped)" \
    "$samples_before_late_recovery"
adb shell am start-foreground-service \
    -n "$PERFORMANCE_SERVICE_COMPONENT" \
    -a "$PERFORMANCE_RESUME_ACTION" >/dev/null
sleep 2
if ! performance_service_running; then
    emit_bug_failure 1 "PerformanceMonitorService not running after ACTION_RESUME"
fi
set_bug_check "late recovery appends a new sample" \
    "sample_count > ${samples_before_late_recovery}" \
    "sample_count=$(sample_count)" \
    "$samples_before_late_recovery"
wait_for_sample_count_greater_than "$samples_before_late_recovery"
minimum_late_recovery_samples=$((samples_before_late_recovery + 1))
set_bug_check "late recovered session finalizes as manual_stop" \
    "endReason=manual_stop and sampleCount >= ${minimum_late_recovery_samples}" \
    "last_session pending" \
    "$samples_before_late_recovery"
open_performance
tap_ui_button "Finish session" "Terminar sesión"
wait_for_last_session
assert_session_json "manual_stop" "$minimum_late_recovery_samples"
set_bug_check "recovery state cleared after BUG-4 finalization" \
    "active_session_samples.jsonl absent" \
    "recovery cleanup check" \
    "$samples_before_late_recovery"
assert_recovery_cleared
echo "PASS BUG-4"
CURRENT_BUG=""

# Removing the real module must return the host to a healthy no-module state.
adb uninstall "$PERFORMANCE_PACKAGE"
adb uninstall "$EMULATOR_FIXTURE_PACKAGE"
assert_harness_contains "No compatible trusted modules discovered."

echo "CarePad Performance timeout, manual-stop, process-recovery, Usage-Access and late-recovery emulator smoke tests passed."
