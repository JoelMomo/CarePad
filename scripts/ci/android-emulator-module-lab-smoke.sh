#!/usr/bin/env bash
set -euo pipefail

HOST_APK="${1:?host APK path required}"
BASELINE_APK="${2:?baseline module APK path required}"
UPDATE_APK="${3:?update module APK path required}"
INCOMPATIBLE_PROTOCOL_APK="${4:?incompatible-protocol module APK path required}"
WRONG_SIGNATURE_APK="${5:?wrong-signature module APK path required}"
DEFECTIVE_UPDATE_APK="${6:?defective-update module APK path required}"

HOST_PACKAGE="com.joel.thordoctor.carepadlabhost"
MODULE_PACKAGE="com.joel.thordoctor.modulelab"
HARNESS_COMPONENT="${HOST_PACKAGE}/com.joel.thordoctor.modules.host.ModuleLabHarnessActivity"
SETTINGS_CONTROL_COMPONENT="${MODULE_PACKAGE}/com.joel.thordoctor.modulelab.LabSettingsControlReceiver"
MODULE_OPEN_ACTION="dev.carepad.action.OPEN_MODULE"
UI_DUMP_DEVICE="/sdcard/carepad-window.xml"
UI_DUMP_LOCAL="${RUNNER_TEMP:-/tmp}/carepad-window.xml"

wait_for_boot() {
    if ! timeout 180 adb wait-for-device; then
        echo "Android emulator did not become reachable through adb" >&2
        adb devices -l >&2 || true
        return 1
    fi
    for _ in $(seq 1 90); do
        if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
            adb shell input keyevent 82 >/dev/null 2>&1 || true
            return 0
        fi
        sleep 2
    done
    echo "Android emulator became reachable but did not finish booting" >&2
    adb shell getprop >&2 || true
    return 1
}

current_resumed_activity() {
    adb shell dumpsys activity activities 2>/dev/null |
        grep -m1 -E 'topResumedActivity=|mResumedActivity|ResumedActivity:' || true
}

dump_current_ui() {
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
    echo "Android UI did not produce a fresh hierarchy dump" >&2
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
        echo "UI did not contain an enabled button with expected text: $*" >&2
        cat "$UI_DUMP_LOCAL" >&2 || true
        return 1
    }
    read -r tap_x tap_y <<<"$coordinates"
    adb shell input tap "$tap_x" "$tap_y"
}

refresh_harness_dump() {
    local start_output
    local resumed_activity
    adb shell am force-stop "$HOST_PACKAGE" >/dev/null
    start_output="$(adb shell am start -n "$HARNESS_COMPONENT")"
    for _ in $(seq 1 30); do
        resumed_activity="$(current_resumed_activity)"
        if grep -Fq "$HOST_PACKAGE" <<<"$resumed_activity" &&
            grep -Fq "ModuleLabHarnessActivity" <<<"$resumed_activity"; then
            if dump_current_ui && grep -Fq "package=\"$HOST_PACKAGE\"" "$UI_DUMP_LOCAL"; then
                return 0
            fi
        fi
        sleep 0.5
    done
    echo "CarePad Lab Harness did not produce a host-owned UI dump" >&2
    echo "$start_output" >&2
    echo "Resumed activity: $(current_resumed_activity)" >&2
    [[ -f "$UI_DUMP_LOCAL" ]] && cat "$UI_DUMP_LOCAL" >&2 || true
    adb shell dumpsys activity activities >&2 || true
    return 1
}

assert_harness_contains_all() {
    local expected
    local all_present
    for _ in $(seq 1 12); do
        if refresh_harness_dump; then
            all_present=true
            for expected in "$@"; do
                if ! grep -Fq "$expected" "$UI_DUMP_LOCAL"; then
                    all_present=false
                    break
                fi
            done
            [[ "$all_present" == true ]] && return 0
        fi
        sleep 1
    done
    echo "Harness UI did not converge to the expected discovery state:" >&2
    for expected in "$@"; do echo "  - $expected" >&2; done
    [[ -f "$UI_DUMP_LOCAL" ]] && cat "$UI_DUMP_LOCAL" >&2 || true
    adb shell dumpsys package "$MODULE_PACKAGE" >&2 || true
    return 1
}

wait_for_current_ui_text() {
    local expected="$1"
    for _ in $(seq 1 30); do
        if dump_current_ui && grep -Fq "$expected" "$UI_DUMP_LOCAL"; then
            return 0
        fi
        sleep 0.5
    done
    echo "Current harness UI did not contain expected text: $expected" >&2
    [[ -f "$UI_DUMP_LOCAL" ]] && cat "$UI_DUMP_LOCAL" >&2 || true
    return 1
}

click_harness_button() {
    dump_current_ui
    tap_ui_button "$1"
}

control_settings() {
    local command="$1"
    local output
    output="$(adb shell am broadcast \
        -n "$SETTINGS_CONTROL_COMPONENT" \
        -a dev.carepad.modulelab.CONTROL_SETTINGS \
        --es command "$command")"
    if ! grep -Eq 'result=0|Broadcast completed' <<<"$output"; then
        echo "Unable to control Module Lab settings fixture: $command" >&2
        echo "$output" >&2
        return 1
    fi
    sleep 0.5
}

assert_inline_settings_transport() {
    control_settings RESET
    refresh_harness_dump

    click_harness_button "C0 Snapshot"
    wait_for_current_ui_text "C0 snapshot OK revision=1 boolean=false choice=opengl info=Lab-Fixture v0.1 (Debug)"

    click_harness_button "C0 Boolean=true"
    wait_for_current_ui_text "C0 boolean APPLIED revision=1 effective=true"

    click_harness_button "C0 Snapshot"
    wait_for_current_ui_text "C0 snapshot OK revision=1 boolean=true choice=opengl info=Lab-Fixture v0.1 (Debug)"

    click_harness_button "C0 Choice=vulkan"
    wait_for_current_ui_text "C0 choice APPLIED revision=1 effective=vulkan"

    click_harness_button "C0 Snapshot"
    wait_for_current_ui_text "C0 snapshot OK revision=1 boolean=true choice=vulkan info=Lab-Fixture v0.1 (Debug)"

    control_settings ADVANCE_REVISION
    click_harness_button "C0 Boolean=true"
    wait_for_current_ui_text "C0 boolean STALE current=2"

    control_settings FORCE_INCOMPATIBLE
    click_harness_button "C0 Snapshot"
    wait_for_current_ui_text "C0 snapshot INCOMPATIBLE"

    control_settings RESET
}

assert_module_version() {
    local expected="$1"
    local package_dump
    package_dump="$(adb shell dumpsys package "$MODULE_PACKAGE")"
    if ! grep -Fq "versionName=$expected" <<<"$package_dump"; then
        echo "Installed module version was not $expected" >&2
        echo "$package_dump" >&2
        return 1
    fi
}

assert_module_opens() {
    local start_output
    local resumed_activity
    adb shell am force-stop "$MODULE_PACKAGE" >/dev/null
    start_output="$(adb shell am start -a "$MODULE_OPEN_ACTION" -p "$MODULE_PACKAGE")"
    for _ in $(seq 1 20); do
        resumed_activity="$(current_resumed_activity)"
        if grep -Fq "$MODULE_PACKAGE" <<<"$resumed_activity" &&
            grep -Fq "LabModuleActivity" <<<"$resumed_activity"; then
            return 0
        fi
        sleep 0.5
    done
    echo "Module OPEN_MODULE action did not make LabModuleActivity the resumed activity" >&2
    echo "$start_output" >&2
    echo "Resumed activity: $(current_resumed_activity)" >&2
    return 1
}

assert_module_delegated_settings_opens_through_host() {
    local resumed_activity
    refresh_harness_dump
    click_harness_button "Open lab settings"
    for _ in $(seq 1 20); do
        resumed_activity="$(current_resumed_activity)"
        if grep -Fq "$MODULE_PACKAGE" <<<"$resumed_activity" &&
            grep -Fq "LabDelegatedSettingsActivity" <<<"$resumed_activity"; then
            return 0
        fi
        sleep 0.5
    done
    echo "CarePad host did not launch the delegated module settings Activity" >&2
    echo "Resumed activity: $(current_resumed_activity)" >&2
    return 1
}

assert_module_crash_isolated() {
    local logcat_output
    adb logcat -c
    adb shell am force-stop "$MODULE_PACKAGE" >/dev/null
    adb shell am start -a "$MODULE_OPEN_ACTION" -p "$MODULE_PACKAGE" --ez carepad.lab.crash true >/dev/null
    for _ in $(seq 1 20); do
        logcat_output="$(adb logcat -d 2>/dev/null || true)"
        if grep -Fq "CarePad module lab intentional crash" <<<"$logcat_output"; then
            assert_harness_contains_all "Accepted: lab" "Version: 0.2-lab"
            assert_module_opens
            return 0
        fi
        sleep 0.5
    done
    echo "Intentional module crash was not observed in logcat" >&2
    adb logcat -d >&2 || true
    return 1
}

assert_failed_replacement_preserves_module() {
    local install_output="${RUNNER_TEMP:-/tmp}/carepad-failed-replacement.txt"
    local install_status
    set +e
    adb install -r "$WRONG_SIGNATURE_APK" >"$install_output" 2>&1
    install_status=$?
    set -e
    if [[ "$install_status" -eq 0 ]]; then
        echo "Android unexpectedly replaced the trusted module with a differently signed APK" >&2
        cat "$install_output" >&2
        return 1
    fi
    if ! grep -Eq "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match|signature" "$install_output"; then
        echo "Replacement failed, but not for a recognizable signing mismatch" >&2
        cat "$install_output" >&2
        return 1
    fi
    assert_module_version "0.2-lab"
    assert_harness_contains_all "Accepted: lab" "Version: 0.2-lab"
    assert_module_opens
}

assert_defective_update_recovery_constraints() {
    local logcat_output
    local rollback_output="${RUNNER_TEMP:-/tmp}/carepad-defective-update-rollback.txt"
    local rollback_status
    local crash_observed=false
    adb install -r "$DEFECTIVE_UPDATE_APK"
    assert_module_version "0.5-broken"
    assert_harness_contains_all "Accepted: lab" "Version: 0.5-broken"
    adb logcat -c
    adb shell am force-stop "$MODULE_PACKAGE" >/dev/null
    adb shell am start -a "$MODULE_OPEN_ACTION" -p "$MODULE_PACKAGE" >/dev/null || true
    for _ in $(seq 1 20); do
        logcat_output="$(adb logcat -d 2>/dev/null || true)"
        if grep -Fq "CarePad module lab always-crash fixture" <<<"$logcat_output"; then
            crash_observed=true
            break
        fi
        sleep 0.5
    done
    if [[ "$crash_observed" != true ]]; then
        echo "Defective installed module did not produce the expected normal-open crash" >&2
        adb logcat -d >&2 || true
        return 1
    fi
    assert_harness_contains_all "Accepted: lab" "Version: 0.5-broken"
    set +e
    adb install -r "$UPDATE_APK" >"$rollback_output" 2>&1
    rollback_status=$?
    set -e
    if [[ "$rollback_status" -eq 0 ]]; then
        echo "Android unexpectedly accepted rollback from defective 0.5 to 0.2" >&2
        cat "$rollback_output" >&2
        return 1
    fi
    if ! grep -Eq "INSTALL_FAILED_VERSION_DOWNGRADE|version downgrade|downgrade" "$rollback_output"; then
        echo "Rollback attempt failed, but not for a recognizable version-downgrade reason" >&2
        cat "$rollback_output" >&2
        return 1
    fi
    assert_module_version "0.5-broken"
    assert_harness_contains_all "Accepted: lab" "Version: 0.5-broken"
}

remove_module_through_carepad() {
    refresh_harness_dump
    tap_ui_button "Remove lab"
    for _ in $(seq 1 20); do
        if dump_current_ui && tap_ui_button "Uninstall" "OK"; then break; fi
        sleep 0.5
    done
    for _ in $(seq 1 40); do
        if ! adb shell pm path "$MODULE_PACKAGE" >/dev/null 2>&1; then return 0; fi
        sleep 0.5
    done
    echo "Module remained installed after CarePad-initiated Android removal flow" >&2
    adb shell dumpsys package "$MODULE_PACKAGE" >&2 || true
    return 1
}

wait_for_boot
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0

adb install "$HOST_APK"

adb install "$INCOMPATIBLE_PROTOCOL_APK"
assert_module_version "0.3-incompatible"
assert_harness_contains_all \
    "No compatible trusted modules discovered." \
    "Rejected: $MODULE_PACKAGE" \
    "Reason: Protocol 2..2 does not include host 1"
adb uninstall "$MODULE_PACKAGE"
assert_harness_contains_all "No compatible trusted modules discovered."

adb install "$WRONG_SIGNATURE_APK"
assert_module_version "0.4-wrong-signature"
assert_harness_contains_all \
    "No compatible trusted modules discovered." \
    "Rejected: $MODULE_PACKAGE" \
    "Reason: Signing certificate does not match host"
adb uninstall "$MODULE_PACKAGE"
assert_harness_contains_all "No compatible trusted modules discovered."

adb install "$BASELINE_APK"
assert_module_version "0.1-lab"
assert_harness_contains_all "Accepted: lab" "Version: 0.1-lab"
assert_module_opens

adb install -r "$UPDATE_APK"
assert_module_version "0.2-lab"
assert_harness_contains_all "Accepted: lab" "Version: 0.2-lab"
assert_module_opens
assert_inline_settings_transport
assert_module_delegated_settings_opens_through_host

assert_module_crash_isolated
assert_failed_replacement_preserves_module
assert_defective_update_recovery_constraints

remove_module_through_carepad
assert_harness_contains_all "No compatible trusted modules discovered."

echo "CarePad Android emulator module smoke test passed."
