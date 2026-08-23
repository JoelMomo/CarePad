#!/usr/bin/env bash
set -euo pipefail

NORMAL_HOST_APK="${1:?normal host APK path required}"
LAB_HOST_APK="${2:?lab host APK path required}"
UPDATE_APK="${3:?recoverable module APK path required}"
DEFECTIVE_APK="${4:?defective module APK path required}"

NORMAL_HOST_PACKAGE="com.joel.thordoctor"
HOST_PACKAGE="com.joel.thordoctor.carepadlabhost"
MODULE_PACKAGE="com.joel.thordoctor.modulelab"
RECOVERY_ACTIVITY="${HOST_PACKAGE}/com.joel.thordoctor.modules.host.recovery.RecoveryLabHarnessActivity"
NORMAL_RECOVERY_ACTIVITY="${NORMAL_HOST_PACKAGE}/com.joel.thordoctor.modules.host.recovery.RecoveryLabHarnessActivity"
RESULT_FILE="files/recovery-lab-result.txt"
UPDATE_STAGE="recovery-update.apk"
DEFECTIVE_STAGE="recovery-defective.apk"
UI_DUMP_DEVICE="/sdcard/carepad-recovery-window.xml"
UI_DUMP_LOCAL="${RUNNER_TEMP:-/tmp}/carepad-recovery-window.xml"
LAST_RESULT=""

fail() {
    echo "$*" >&2
    if [[ -n "$LAST_RESULT" ]]; then
        echo "Last recovery lab result:" >&2
        echo "$LAST_RESULT" >&2
    fi
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
        print((left + right) // 2, (top + bottom) // 2)
        raise SystemExit(0)
raise SystemExit(1)
PY
)" || return 1
    read -r tap_x tap_y <<<"$coordinates"
    adb shell input tap "$tap_x" "$tap_y" >/dev/null
}

tap_button_when_visible() {
    for _ in $(seq 1 40); do
        if dump_current_ui && tap_ui_button "$@"; then
            return 0
        fi
        sleep 0.5
    done
    echo "Expected Android button was not found: $*" >&2
    echo "Resumed activity: $(current_resumed_activity)" >&2
    if [[ -f "$UI_DUMP_LOCAL" ]]; then
        cat "$UI_DUMP_LOCAL" >&2
    fi
    return 1
}

run_lab() {
    local command="$1"
    shift
    adb shell run-as "$HOST_PACKAGE" rm -f "$RESULT_FILE" >/dev/null 2>&1 || true
    adb shell am start -W -n "$RECOVERY_ACTIVITY" --es command "$command" "$@" >/dev/null

    LAST_RESULT=""
    for _ in $(seq 1 20); do
        if adb shell run-as "$HOST_PACKAGE" test -s "$RESULT_FILE" >/dev/null 2>&1; then
            LAST_RESULT="$(adb exec-out run-as "$HOST_PACKAGE" cat "$RESULT_FILE" 2>/dev/null | tr -d '\r' || true)"
            if [[ -n "$LAST_RESULT" ]]; then
                return 0
            fi
        fi
        sleep 0.1
    done
    echo "Recovery lab result file was not produced or remained empty: $RESULT_FILE" >&2
    echo "Resumed activity: $(current_resumed_activity)" >&2
    adb shell run-as "$HOST_PACKAGE" ls -l files >&2 2>/dev/null || true
    fail "Recovery lab command did not produce a result: $command"
}

assert_field() {
    local key="$1"
    local expected="$2"
    if ! grep -Fxq "$key=$expected" <<<"$LAST_RESULT"; then
        fail "Expected recovery lab field $key=$expected"
    fi
}

assert_rejected() {
    local error="$1"
    assert_field kind rejected
    assert_field error "$error"
}

assert_accepted_phase() {
    local phase="$1"
    assert_field kind accepted
    assert_field phase "$phase"
}

wait_state() {
    local expected_phase="$1"
    local expected_error="${2:-}"
    for _ in $(seq 1 50); do
        run_lab state
        if grep -Fxq "present=true" <<<"$LAST_RESULT" &&
            grep -Fxq "phase=$expected_phase" <<<"$LAST_RESULT"; then
            if [[ -z "$expected_error" ]] || grep -Fxq "error=$expected_error" <<<"$LAST_RESULT"; then
                return 0
            fi
        fi
        sleep 0.2
    done
    fail "Recovery state did not reach $expected_phase ${expected_error:+with $expected_error}"
}

assert_module_version() {
    local expected="$1"
    local package_dump
    package_dump="$(adb shell dumpsys package "$MODULE_PACKAGE")"
    if ! grep -Fq "versionName=$expected" <<<"$package_dump"; then
        echo "$package_dump" >&2
        fail "Installed module version was not $expected"
    fi
}

assert_module_absent() {
    for _ in $(seq 1 30); do
        if [[ -z "$(adb shell pm path "$MODULE_PACKAGE" 2>/dev/null | tr -d '\r')" ]]; then
            return 0
        fi
        sleep 0.2
    done
    fail "Module package remained installed"
}

stage_apk() {
    local apk="$1"
    local name="$2"
    local remote="/data/local/tmp/$name"
    adb push "$apk" "$remote" >/dev/null
    adb shell run-as "$HOST_PACKAGE" mkdir -p files >/dev/null
    adb shell run-as "$HOST_PACKAGE" cp "$remote" "files/$name"
    adb shell rm -f "$remote" >/dev/null
}

prepare_target() {
    local staged="$1"
    local artifact_id="$2"
    local module_id="$3"
    local package_name="$4"
    local version_code="$5"
    local version_name="$6"
    local apk_sha="$7"
    local signing_sha="$8"
    local protocol_min="$9"
    shift 9
    local protocol_max="$1"
    local safety="$2"
    local backup_verified="$3"
    local risky_permitted="$4"
    local data_loss_ack="$5"

    run_lab prepare \
        --es staged_apk "$staged" \
        --es artifact_id "$artifact_id" \
        --es module_id "$module_id" \
        --es package_name "$package_name" \
        --el version_code "$version_code" \
        --es version_name "$version_name" \
        --es apk_sha256 "$apk_sha" \
        --es signing_sha256 "$signing_sha" \
        --ei protocol_min "$protocol_min" \
        --ei protocol_max "$protocol_max" \
        --es recovery_safety "$safety" \
        --ez backup_verified "$backup_verified" \
        --ez risky_reinstall_permitted "$risky_permitted" \
        --ez data_loss_acknowledged "$data_loss_ack"
}

prepare_update() {
    local safety="$1"
    local backup_verified="$2"
    local risky_permitted="$3"
    local data_loss_ack="$4"
    local artifact_id="${5:-lab@2}"
    prepare_target \
        "$UPDATE_STAGE" "$artifact_id" "lab" "$MODULE_PACKAGE" \
        2 "0.2-lab" "$UPDATE_SHA" "$SIGNING_SHA" 1 1 \
        "$safety" "$backup_verified" "$risky_permitted" "$data_loss_ack"
}

install_defective() {
    adb install -r "$DEFECTIVE_APK" >/dev/null
    assert_module_version "0.5-broken"
}

request_and_accept_uninstall() {
    run_lab request_uninstall
    assert_accepted_phase WAITING_FOR_UNINSTALL_CONFIRMATION
    tap_button_when_visible "Uninstall" "OK"
    assert_module_absent
    run_lab continue_uninstall
}

accept_install_and_wait() {
    tap_button_when_visible "Install"
    wait_state "$1"
}

clear_terminal() {
    run_lab clear_terminal
    assert_field kind boolean
    assert_field cleared true
    run_lab state
    assert_field present false
}

# Critical isolation gate: a normal CarePad debug APK must not be able to launch
# the recovery command activity. The class is not compiled there and the manifest
# component is disabled unless -PcarepadLabHost=true.
adb install -r "$NORMAL_HOST_APK" >/dev/null
set +e
normal_launch_output="$(adb shell am start -W -n "$NORMAL_RECOVERY_ACTIVITY" --es command state 2>&1)"
normal_launch_status=$?
set -e
if [[ "$normal_launch_status" -eq 0 ]] &&
    ! grep -Eqi "error|does not exist|not found|disabled|not enabled" <<<"$normal_launch_output"; then
    echo "$normal_launch_output" >&2
    fail "Recovery lab harness unexpectedly launched from normal CarePad debug build"
fi
adb uninstall "$NORMAL_HOST_PACKAGE" >/dev/null

adb uninstall "$HOST_PACKAGE" >/dev/null 2>&1 || true
adb install "$LAB_HOST_APK" >/dev/null
adb shell pm clear "$HOST_PACKAGE" >/dev/null
run_lab state
assert_field present false

APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n 1)"
test -n "$APKSIGNER"
UPDATE_SHA="$(sha256sum "$UPDATE_APK" | awk '{print tolower($1)}')"
DEFECTIVE_SHA="$(sha256sum "$DEFECTIVE_APK" | awk '{print tolower($1)}')"
SIGNING_SHA="$("$APKSIGNER" verify --print-certs "$UPDATE_APK" |
    awk -F': ' '/Signer #1 certificate SHA-256 digest:/ {print tolower($2); exit}')"
[[ "$UPDATE_SHA" =~ ^[0-9a-f]{64}$ ]]
[[ "$DEFECTIVE_SHA" =~ ^[0-9a-f]{64}$ ]]
[[ "$SIGNING_SHA" =~ ^[0-9a-f]{64}$ ]]

stage_apk "$UPDATE_APK" "$UPDATE_STAGE"
stage_apk "$DEFECTIVE_APK" "$DEFECTIVE_STAGE"
install_defective

# Fail closed before Android removal: missing bytes and every exact identity axis.
prepare_target "missing.apk" "lab@2" "lab" "$MODULE_PACKAGE" 2 "0.2-lab" \
    "$UPDATE_SHA" "$SIGNING_SHA" 1 1 REGENERABLE false false false
assert_rejected APK_MISSING
assert_module_version "0.5-broken"

BAD_SHA="$(printf '0%.0s' $(seq 1 64))"
prepare_target "$UPDATE_STAGE" "lab@2" "lab" "$MODULE_PACKAGE" 2 "0.2-lab" \
    "$BAD_SHA" "$SIGNING_SHA" 1 1 REGENERABLE false false false
assert_rejected APK_HASH_MISMATCH
assert_module_version "0.5-broken"

prepare_target "$UPDATE_STAGE" "lab@2" "lab" "${MODULE_PACKAGE}.wrong" 2 "0.2-lab" \
    "$UPDATE_SHA" "$SIGNING_SHA" 1 1 REGENERABLE false false false
assert_rejected APK_PACKAGE_MISMATCH

prepare_target "$UPDATE_STAGE" "lab@2" "lab" "$MODULE_PACKAGE" 1 "0.1-wrong-target" \
    "$UPDATE_SHA" "$SIGNING_SHA" 1 1 REGENERABLE false false false
assert_rejected APK_VERSION_MISMATCH

prepare_target "$UPDATE_STAGE" "lab@2" "lab" "$MODULE_PACKAGE" 2 "0.2-lab" \
    "$UPDATE_SHA" "$BAD_SHA" 1 1 REGENERABLE false false false
assert_rejected APK_SIGNING_MISMATCH

prepare_target "$UPDATE_STAGE" "lab@2" "wrong-module" "$MODULE_PACKAGE" 2 "0.2-lab" \
    "$UPDATE_SHA" "$SIGNING_SHA" 1 1 REGENERABLE false false false
assert_rejected APK_MODULE_MISMATCH

prepare_target "$UPDATE_STAGE" "lab@2" "lab" "$MODULE_PACKAGE" 2 "0.2-lab" \
    "$UPDATE_SHA" "$SIGNING_SHA" 2 2 REGENERABLE false false false
assert_rejected APK_PROTOCOL_MISMATCH

prepare_target "$DEFECTIVE_STAGE" "lab@5" "lab" "$MODULE_PACKAGE" 5 "0.5-broken" \
    "$DEFECTIVE_SHA" "$SIGNING_SHA" 1 1 REGENERABLE false false false
assert_rejected INSTALLED_VERSION_NOT_NEWER
assert_module_version "0.5-broken"

# Data-safety gates reject before touching Android package state.
prepare_update BACKUP_RESTORE_REQUIRED false false false "lab@2-backup-blocked"
assert_rejected BACKUP_REQUIRED
prepare_update DATA_LOSS_POSSIBLE false false true "lab@2-risk-policy-blocked"
assert_rejected RISKY_REINSTALL_NOT_PERMITTED
prepare_update DATA_LOSS_POSSIBLE false true false "lab@2-risk-ack-blocked"
assert_rejected DATA_LOSS_ACKNOWLEDGEMENT_REQUIRED
assert_module_version "0.5-broken"

# Only one in-flight operation; pre-removal cancellation is terminal and clearable.
prepare_update REGENERABLE false false false "lab@2-simultaneous-a"
assert_accepted_phase PREPARED
prepare_update REGENERABLE false false false "lab@2-simultaneous-b"
assert_rejected OPERATION_ALREADY_ACTIVE
assert_field current_phase PREPARED
run_lab cancel_prepared
assert_accepted_phase CANCELLED
wait_state CANCELLED
clear_terminal
assert_module_version "0.5-broken"

# Cancelling Android's uninstall dialog must never create an install session.
prepare_update REGENERABLE false false false "lab@2-uninstall-cancel"
assert_accepted_phase PREPARED
run_lab request_uninstall
assert_accepted_phase WAITING_FOR_UNINSTALL_CONFIRMATION
tap_button_when_visible "Cancel"
run_lab continue_uninstall
assert_accepted_phase CANCELLED
assert_module_version "0.5-broken"
clear_terminal

# Regenerable E2E, including the explicit unknown-source permission handoff.
adb shell appops set "$HOST_PACKAGE" REQUEST_INSTALL_PACKAGES ignore >/dev/null
prepare_update REGENERABLE false false false "lab@2-regenerable"
assert_accepted_phase PREPARED
request_and_accept_uninstall
assert_accepted_phase WAITING_FOR_INSTALL_PERMISSION
run_lab request_install_permission
assert_accepted_phase WAITING_FOR_INSTALL_PERMISSION
adb shell appops set "$HOST_PACKAGE" REQUEST_INSTALL_PACKAGES allow >/dev/null
run_lab continue_install_permission
assert_accepted_phase INSTALLING
accept_install_and_wait VERIFIED
assert_module_version "0.2-lab"
clear_terminal

# backup_restore_required reaches WAITING_FOR_DATA_RESTORE and only verifies after
# the lab explicitly supplies a verified restore result.
install_defective
prepare_update BACKUP_RESTORE_REQUIRED true false false "lab@2-backup-success"
assert_accepted_phase PREPARED
request_and_accept_uninstall
assert_accepted_phase INSTALLING
accept_install_and_wait WAITING_FOR_DATA_RESTORE
run_lab complete_data_restore --ez restored_and_verified true
assert_accepted_phase VERIFIED
assert_module_version "0.2-lab"
clear_terminal

# Negative restore result is terminal FAILED and cannot be reported as recovered.
install_defective
prepare_update BACKUP_RESTORE_REQUIRED true false false "lab@2-backup-failed"
assert_accepted_phase PREPARED
request_and_accept_uninstall
assert_accepted_phase INSTALLING
accept_install_and_wait WAITING_FOR_DATA_RESTORE
run_lab complete_data_restore --ez restored_and_verified false --es detail "lab restore rejected"
assert_accepted_phase FAILED
assert_field error DATA_RESTORE_FAILED
assert_module_version "0.2-lab"
clear_terminal

# data_loss_possible remains policy-gated; both explicit inputs select only the
# existing risky route. The CI smoke cancels before removal because the unique
# behavior under test is authorization, not another copy of the install flow.
install_defective
prepare_update DATA_LOSS_POSSIBLE false true true "lab@2-risk-authorized"
assert_accepted_phase PREPARED
assert_field route REINSTALL_WITH_POSSIBLE_LOSS
run_lab cancel_prepared
assert_accepted_phase CANCELLED
clear_terminal
assert_module_version "0.5-broken"

# Cancel installation after the defective module is already gone. Recovery must
# become FAILED while the host stays operable, then retry only the install step.
prepare_update REGENERABLE false false false "lab@2-install-cancel-retry"
assert_accepted_phase PREPARED
request_and_accept_uninstall
assert_accepted_phase INSTALLING
tap_button_when_visible "Cancel"
wait_state FAILED INSTALL_FAILED
assert_module_absent
run_lab retry_install
assert_accepted_phase INSTALLING
accept_install_and_wait VERIFIED
assert_module_version "0.2-lab"
clear_terminal

# The command trampoline itself remains available after all destructive cases.
run_lab state
assert_field present false
adb shell pm path "$HOST_PACKAGE" >/dev/null

echo "CarePad AndroidModuleRecovery lab smoke test passed."
