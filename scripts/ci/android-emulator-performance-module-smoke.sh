#!/usr/bin/env bash
set -euo pipefail

HOST_APK="${1:?host APK path required}"
PERFORMANCE_APK="${2:?performance module APK path required}"
EMULATOR_FIXTURE_APK="${3:?performance emulator fixture APK path required}"

HOST_PACKAGE="com.joel.thordoctor.carepadlabhost"
PERFORMANCE_PACKAGE="dev.carepad.module.performance"
EMULATOR_FIXTURE_PACKAGE="org.ppsspp.ppsspp"
EMULATOR_FIXTURE_COMPONENT="${EMULATOR_FIXTURE_PACKAGE}/dev.carepad.fixture.emulator.FakeEmulatorActivity"
HARNESS_COMPONENT="${HOST_PACKAGE}/com.joel.thordoctor.modules.host.ModuleLabHarnessActivity"
OPEN_ACTION="dev.carepad.action.OPEN_MODULE"
UI_DUMP_DEVICE="/sdcard/carepad-performance-window.xml"
UI_DUMP_LOCAL="${RUNNER_TEMP:-/tmp}/carepad-performance-window.xml"
SAMPLES_LOCAL="${RUNNER_TEMP:-/tmp}/carepad-performance-samples.jsonl"
SESSION_LOCAL="${RUNNER_TEMP:-/tmp}/carepad-performance-last-session.json"

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

read_private_file() {
    local relative_path="$1"
    local destination="$2"
    adb shell run-as "$PERFORMANCE_PACKAGE" cat "$relative_path" >"$destination" 2>/dev/null
}

wait_for_samples() {
    rm -f "$SAMPLES_LOCAL"
    for _ in $(seq 1 30); do
        if read_private_file files/active_session_samples.jsonl "$SAMPLES_LOCAL" &&
            [[ -s "$SAMPLES_LOCAL" ]]; then
            return 0
        fi
        sleep 1
    done

    echo "Performance module did not capture a sample while the emulator fixture was foreground" >&2
    adb shell dumpsys activity activities >&2 || true
    adb shell dumpsys appops "$PERFORMANCE_PACKAGE" >&2 || true
    return 1
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
    python3 - "$SESSION_LOCAL" <<'PY'
import json
import sys

path = sys.argv[1]
with open(path, encoding="utf-8") as handle:
    session = json.load(handle)

assert session["schema"] == "thor-doctor-session", session
assert session["schemaVersion"] == 2, session
assert session["emulator"]["name"] == "PPSSPP", session
assert session["emulator"]["package"] == "org.ppsspp.ppsspp", session
assert session["sampleCount"] >= 1, session
assert len(session["samples"]) == session["sampleCount"], session
assert session["durationSeconds"] > 0, session
assert session["endReason"] == "foreground_timeout", session
assert isinstance(session["summary"], dict), session
PY
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

# Reopen after the app-op grant and initiate the session through the real UI.
open_performance
if ! grep -Eq "Start session|Iniciar sesión" "$UI_DUMP_LOCAL"; then
    echo "Performance module did not expose the session start action after Usage Access was granted" >&2
    cat "$UI_DUMP_LOCAL" >&2 || true
    exit 1
fi
tap_ui_button "Start session" "Iniciar sesión"

# Put a known emulator package in the foreground. The fixture is a CI-only app
# whose package matches PPSSPP so the real Emulator Engine and UsageStats path run.
adb shell am start -n "$EMULATOR_FIXTURE_COMPONENT" >/dev/null
for _ in $(seq 1 20); do
    resumed="$(current_resumed_activity)"
    if grep -Fq "$EMULATOR_FIXTURE_PACKAGE" <<<"$resumed" &&
        grep -Fq "FakeEmulatorActivity" <<<"$resumed"; then
        break
    fi
    sleep 0.5
done

resumed="$(current_resumed_activity)"
if ! grep -Fq "$EMULATOR_FIXTURE_PACKAGE" <<<"$resumed"; then
    echo "Emulator fixture never became foreground" >&2
    echo "$resumed" >&2
    exit 1
fi

wait_for_samples

# Returning to Performance triggers the existing foreground grace/timeout path.
# The persisted session must end at the first-away timestamp and produce a summary.
open_performance
wait_for_last_session
assert_session_json

if ! dump_ui ||
    ! grep -Fq "PPSSPP" "$UI_DUMP_LOCAL" ||
    ! grep -Eq "Samples: [1-9]|Muestras: [1-9]" "$UI_DUMP_LOCAL"; then
    echo "Performance UI did not render the completed session summary" >&2
    cat "$UI_DUMP_LOCAL" >&2 || true
    exit 1
fi

# Successful finalization clears recoverable samples/state rather than leaving a
# stale session that could be resumed later.
if adb shell run-as "$PERFORMANCE_PACKAGE" test -e files/active_session_samples.jsonl; then
    echo "Performance recovery samples remained after successful finalization" >&2
    adb shell run-as "$PERFORMANCE_PACKAGE" ls -la files >&2 || true
    exit 1
fi

# Removing the real module must return the host to a healthy no-module state.
adb uninstall "$PERFORMANCE_PACKAGE"
adb uninstall "$EMULATOR_FIXTURE_PACKAGE"
assert_harness_contains "No compatible trusted modules discovered."

echo "CarePad Performance module complete-session emulator smoke test passed."
