#!/usr/bin/env bash
set -euo pipefail

HOST_APK="${1:?host APK path required}"
PERFORMANCE_APK="${2:?performance module APK path required}"

HOST_PACKAGE="com.joel.thordoctor.carepadlabhost"
PERFORMANCE_PACKAGE="dev.carepad.module.performance"
HARNESS_COMPONENT="${HOST_PACKAGE}/com.joel.thordoctor.modules.host.ModuleLabHarnessActivity"
OPEN_ACTION="dev.carepad.action.OPEN_MODULE"
UI_DUMP_DEVICE="/sdcard/carepad-performance-window.xml"
UI_DUMP_LOCAL="${RUNNER_TEMP:-/tmp}/carepad-performance-window.xml"

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

# The previous generic module smoke can leave its own host installation in place.
adb install -r "$HOST_APK"
adb install "$PERFORMANCE_APK"

assert_harness_contains "Accepted: performance" "Version: 0.1.0"

adb shell am force-stop "$PERFORMANCE_PACKAGE" >/dev/null
adb shell am start -a "$OPEN_ACTION" -p "$PERFORMANCE_PACKAGE" >/dev/null
for _ in $(seq 1 20); do
    resumed="$(current_resumed_activity)"
    if grep -Fq "$PERFORMANCE_PACKAGE" <<<"$resumed" &&
        grep -Fq "PerformanceActivity" <<<"$resumed"; then
        if dump_ui && grep -Eq "Usage access required|Falta acceso de uso" "$UI_DUMP_LOCAL"; then
            break
        fi
    fi
    sleep 0.5
done

resumed="$(current_resumed_activity)"
if ! grep -Fq "$PERFORMANCE_PACKAGE" <<<"$resumed" ||
    ! grep -Fq "PerformanceActivity" <<<"$resumed"; then
    echo "Performance module did not open through OPEN_MODULE" >&2
    echo "$resumed" >&2
    exit 1
fi

# Removing the real module must return the host to a healthy no-module state.
adb uninstall "$PERFORMANCE_PACKAGE"
assert_harness_contains "No compatible trusted modules discovered."

echo "CarePad Performance module emulator smoke test passed."
