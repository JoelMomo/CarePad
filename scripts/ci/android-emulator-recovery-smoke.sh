#!/usr/bin/env bash
set -Eeuo pipefail

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
CURRENT_STAGE="initializing recovery smoke"
CURRENT_STAGE_LINE=0
CURRENT_STAGE_COMMAND="initializing recovery smoke"
SETUP_OBSERVABILITY_ACTIVE=true
SETUP_FAILURE_REPORTED=false
INSTALL_PERMISSION_RESUMED_AFTER_BACK=""
UNINSTALL_CANCEL_RESUMED_IMMEDIATELY=""
UNINSTALL_CANCEL_RESUMED_AFTER_WAIT=""

report_recovery_setup_failure() {
    local exit_status="$1"
    local line="$2"
    local command="$3"

    SETUP_FAILURE_REPORTED=true
    trap - ERR EXIT
    set +e

    if [[ "${SETUP_OBSERVABILITY_ACTIVE:-false}" == true ]]; then
        echo "RECOVERY_SETUP_FAILURE" >&2
        echo "stage=${CURRENT_STAGE:-unknown}" >&2
        echo "exit_status=$exit_status" >&2
        echo "line=$line" >&2
        printf 'command=%s\n' "$command" >&2
        echo "normal_host_pm_path:" >&2
        adb shell pm path "$NORMAL_HOST_PACKAGE" >&2 2>&1 || true
        echo "lab_host_pm_path:" >&2
        adb shell pm path "$HOST_PACKAGE" >&2 2>&1 || true
        echo "resumed_activity: $(current_resumed_activity)" >&2
        echo "normal_host_package:" >&2
        adb shell dumpsys package "$NORMAL_HOST_PACKAGE" 2>&1 |
            grep -E -m 8 'Package \[|versionName=|versionCode=|enabled=|User 0:' >&2 || true
        echo "lab_host_package:" >&2
        adb shell dumpsys package "$HOST_PACKAGE" 2>&1 |
            grep -E -m 8 'Package \[|versionName=|versionCode=|enabled=|User 0:' >&2 || true
    fi
}

recovery_setup_failure() {
    local exit_status="$1"
    local line="$2"
    local command="$3"
    report_recovery_setup_failure "$exit_status" "$line" "$command"
    exit "$exit_status"
}

recovery_setup_exit() {
    local exit_status="$1"

    if [[ "${SETUP_OBSERVABILITY_ACTIVE:-false}" == true &&
          "$exit_status" -ne 0 &&
          "${SETUP_FAILURE_REPORTED:-false}" != true ]]; then
        report_recovery_setup_failure \
            "$exit_status" \
            "${CURRENT_STAGE_LINE:-0}" \
            "${CURRENT_STAGE_COMMAND:-$BASH_COMMAND}"
    fi

    trap - EXIT
    exit "$exit_status"
}

trap 'recovery_setup_failure "$?" "$LINENO" "$BASH_COMMAND"' ERR
trap 'recovery_setup_exit "$?"' EXIT

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
    local observe_continue_uninstall=false
    local am_stdout_file="${RUNNER_TEMP:-/tmp}/carepad-continue-uninstall.stdout"
    local am_stderr_file="${RUNNER_TEMP:-/tmp}/carepad-continue-uninstall.stderr"
    local am_start_status=0
    local am_start_stdout=""
    local am_start_stderr=""
    local host_pid_before=""
    local resumed_before=""

    if [[ "$command" == "continue_uninstall" ]]; then
        observe_continue_uninstall=true
        host_pid_before="$(adb shell pidof "$HOST_PACKAGE" 2>/dev/null | tr -d '\r' || true)"
        resumed_before="$(current_resumed_activity)"
        rm -f "$am_stdout_file" "$am_stderr_file"
    fi

    adb shell run-as "$HOST_PACKAGE" rm -f "$RESULT_FILE" >/dev/null 2>&1 || true
    if [[ "$observe_continue_uninstall" == true ]]; then
        set +e
        adb shell am start -W -n "$RECOVERY_ACTIVITY" --es command "$command" "$@" \
            >"$am_stdout_file" 2>"$am_stderr_file"
        am_start_status=$?
        set -e
        am_start_stdout="$(cat "$am_stdout_file" 2>/dev/null || true)"
        am_start_stderr="$(cat "$am_stderr_file" 2>/dev/null || true)"
    else
        adb shell am start -W -n "$RECOVERY_ACTIVITY" --es command "$command" "$@" >/dev/null
    fi

    LAST_RESULT=""
    if [[ "$am_start_status" -eq 0 ]]; then
        for _ in $(seq 1 20); do
            if adb shell run-as "$HOST_PACKAGE" test -s "$RESULT_FILE" >/dev/null 2>&1; then
                LAST_RESULT="$(adb exec-out run-as "$HOST_PACKAGE" cat "$RESULT_FILE" 2>/dev/null | tr -d '\r' || true)"
                if [[ -n "$LAST_RESULT" ]]; then
                    if [[ "$observe_continue_uninstall" == true ]]; then
                        break
                    fi
                    return 0
                fi
            fi
            sleep 0.1
        done
    fi

    if [[ "$observe_continue_uninstall" == true ]]; then
        set +e
        local host_pid_after
        local resumed_after
        local result_exists=false
        local result_size=0
        host_pid_after="$(adb shell pidof "$HOST_PACKAGE" 2>/dev/null | tr -d '\r' || true)"
        resumed_after="$(current_resumed_activity)"
        if adb shell run-as "$HOST_PACKAGE" test -e "$RESULT_FILE" >/dev/null 2>&1; then
            result_exists=true
            result_size="$(adb exec-out run-as "$HOST_PACKAGE" cat "$RESULT_FILE" 2>/dev/null | wc -c | tr -d '[:space:]' || true)"
        fi

        echo "CONTINUE_UNINSTALL_OBSERVABILITY" >&2
        echo "AM_START_EXIT_STATUS=$am_start_status" >&2
        printf 'AM_START_STDOUT=%q\n' "$am_start_stdout" >&2
        printf 'AM_START_STDERR=%q\n' "$am_start_stderr" >&2
        echo "HOST_PID_BEFORE=$host_pid_before" >&2
        echo "HOST_PID_AFTER=$host_pid_after" >&2
        echo "RESUMED_BEFORE=$resumed_before" >&2
        echo "RESUMED_AFTER=$resumed_after" >&2
        echo "RESULT_FILE_EXISTS=$result_exists" >&2
        echo "RESULT_FILE_SIZE=$result_size" >&2
        echo "CONTINUE_UNINSTALL_LOGCAT_BEGIN" >&2
        adb logcat -d -v threadtime \
            CarePadRecoveryLab:V AndroidRuntime:V ActivityTaskManager:V '*:S' 2>&1 |
            tail -n 300 >&2 || true
        echo "CONTINUE_UNINSTALL_LOGCAT_END" >&2
        set -e

        if [[ "$am_start_status" -ne 0 ]]; then
            fail "Recovery lab activity launch failed: $command"
        fi
        if [[ -n "$LAST_RESULT" ]]; then
            return 0
        fi
    fi

    echo "Recovery lab result file was not produced or remained empty: $RESULT_FILE" >&2
    echo "Resumed activity: $(current_resumed_activity)" >&2
    adb shell run-as "$HOST_PACKAGE" ls -l files >&2 2>/dev/null || true
    fail "Recovery lab command did not produce a result: $command"
}

wait_for_uninstall_cancel_return() {
    local resumed=""
    local module_path=""
    local host_path=""
    local recovery_status=1
    local recovery_state=""

    for _ in $(seq 1 20); do
        resumed="$(current_resumed_activity)"
        module_path="$(adb shell pm path "$MODULE_PACKAGE" 2>/dev/null | tr -d '\r' || true)"
        host_path="$(adb shell pm path "$HOST_PACKAGE" 2>/dev/null | tr -d '\r' || true)"

        if ! grep -Fq 'UninstallerActivity' <<<"$resumed" &&
            [[ -n "$module_path" ]] &&
            [[ -n "$host_path" ]]; then
            UNINSTALL_CANCEL_RESUMED_AFTER_WAIT="$resumed"
            set +e
            run_lab state
            recovery_status=$?
            set -e
            recovery_state="$LAST_RESULT"
            if [[ "$recovery_status" -eq 0 ]] &&
                grep -Fxq 'present=true' <<<"$recovery_state" &&
                grep -Fxq 'phase=WAITING_FOR_UNINSTALL_CONFIRMATION' <<<"$recovery_state"; then
                return 0
            fi
        fi
        sleep 0.25
    done

    echo "UNINSTALL_CANCEL_RETURN_FAILURE" >&2
    echo "RESUMED_IMMEDIATELY_AFTER_CANCEL=$UNINSTALL_CANCEL_RESUMED_IMMEDIATELY" >&2
    echo "RESUMED_AFTER_WAIT=$resumed" >&2
    echo "MODULE_PM_PATH=$module_path" >&2
    echo "LAB_HOST_PM_PATH=$host_path" >&2
    echo "RECOVERY_STATE_STATUS=$recovery_status" >&2
    echo "RECOVERY_STATE_BEGIN" >&2
    echo "$recovery_state" >&2
    echo "RECOVERY_STATE_END" >&2
    return 1
}

wait_for_install_permission_settings_exit() {
    local resumed=""
    local host_path=""

    for _ in $(seq 1 20); do
        resumed="$(current_resumed_activity)"
        host_path="$(adb shell pm path "$HOST_PACKAGE" 2>/dev/null | tr -d '\r' || true)"
        if [[ -n "$host_path" ]] &&
            ! grep -Fq 'com.android.settings/.spa.SpaActivity' <<<"$resumed"; then
            INSTALL_PERMISSION_RESUMED_AFTER_BACK="$resumed"
            return 0
        fi
        sleep 0.25
    done

    echo "Install-permission Settings did not leave the resumed state after BACK" >&2
    echo "REQUEST_INSTALL_PACKAGES=$(adb shell appops get "$HOST_PACKAGE" REQUEST_INSTALL_PACKAGES 2>&1 | tr -d '\r' || true)" >&2
    echo "resumed_activity=$resumed" >&2
    echo "lab_host_pm_path=$host_path" >&2
    return 1
}

run_continue_install_permission_with_diagnostics() {
    local appop_before="$1"
    local resumed_before_back="$2"
    local resumed_after_back="$3"
    local recovery_before="$4"
    local stdout_file="${RUNNER_TEMP:-/tmp}/carepad-continue-install-permission.stdout"
    local stderr_file="${RUNNER_TEMP:-/tmp}/carepad-continue-install-permission.stderr"
    local start_status=0
    local start_stdout=""
    local start_stderr=""

    adb shell run-as "$HOST_PACKAGE" rm -f "$RESULT_FILE" >/dev/null 2>&1 || true
    rm -f "$stdout_file" "$stderr_file"

    set +e
    adb shell am start -W -n "$RECOVERY_ACTIVITY" \
        --es command continue_install_permission \
        >"$stdout_file" 2>"$stderr_file"
    start_status=$?
    set -e

    start_stdout="$(cat "$stdout_file" 2>/dev/null || true)"
    start_stderr="$(cat "$stderr_file" 2>/dev/null || true)"
    LAST_RESULT=""

    if [[ "$start_status" -eq 0 ]]; then
        for _ in $(seq 1 20); do
            if adb shell run-as "$HOST_PACKAGE" test -s "$RESULT_FILE" >/dev/null 2>&1; then
                LAST_RESULT="$(adb exec-out run-as "$HOST_PACKAGE" cat "$RESULT_FILE" 2>/dev/null | tr -d '\r' || true)"
                if [[ -n "$LAST_RESULT" ]]; then
                    return 0
                fi
            fi
            sleep 0.1
        done
    fi

    local failure_status="$start_status"
    if [[ "$failure_status" -eq 0 ]]; then
        failure_status=1
    fi

    set +e
    local appop_after
    local resumed_after_continue
    local lab_host_path
    local recovery_after_status
    local recovery_after
    appop_after="$(adb shell appops get "$HOST_PACKAGE" REQUEST_INSTALL_PACKAGES 2>&1 | tr -d '\r' || true)"
    resumed_after_continue="$(current_resumed_activity)"
    lab_host_path="$(adb shell pm path "$HOST_PACKAGE" 2>&1 | tr -d '\r' || true)"
    run_lab state
    recovery_after_status=$?
    recovery_after="$LAST_RESULT"

    echo "CONTINUE_INSTALL_PERMISSION_FAILURE" >&2
    echo "REQUEST_INSTALL_PACKAGES_BEFORE=$appop_before" >&2
    echo "REQUEST_INSTALL_PACKAGES_AFTER=$appop_after" >&2
    echo "RESUMED_BEFORE_BACK=$resumed_before_back" >&2
    echo "RESUMED_AFTER_BACK=$resumed_after_back" >&2
    echo "RESUMED_AFTER_CONTINUE=$resumed_after_continue" >&2
    echo "LAB_HOST_PM_PATH=$lab_host_path" >&2
    echo "AM_START_EXIT_STATUS=$start_status" >&2
    printf 'AM_START_STDOUT=%q\n' "$start_stdout" >&2
    printf 'AM_START_STDERR=%q\n' "$start_stderr" >&2
    echo "RECOVERY_BEFORE_CONTINUE_BEGIN" >&2
    echo "$recovery_before" >&2
    echo "RECOVERY_BEFORE_CONTINUE_END" >&2
    echo "RECOVERY_AFTER_CONTINUE_STATUS=$recovery_after_status" >&2
    echo "RECOVERY_AFTER_CONTINUE_BEGIN" >&2
    echo "$recovery_after" >&2
    echo "RECOVERY_AFTER_CONTINUE_END" >&2
    echo "RECOVERY_RELEVANT_LOGCAT_BEGIN" >&2
    adb logcat -d -v threadtime \
        CarePadRecoveryLab:V AndroidRuntime:V ActivityTaskManager:V '*:S' 2>&1 |
        tail -n 200 >&2 || true
    echo "RECOVERY_RELEVANT_LOGCAT_END" >&2
    set -e

    return "$failure_status"
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
CURRENT_STAGE="install normal host"
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='adb install -r "$NORMAL_HOST_APK" >/dev/null'
adb install -r "$NORMAL_HOST_APK" >/dev/null

CURRENT_STAGE="normal harness isolation probe"
trap - ERR
set +e
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='normal_launch_output="$(adb shell am start -W -n "$NORMAL_RECOVERY_ACTIVITY" --es command state 2>&1)"'
normal_launch_output="$(adb shell am start -W -n "$NORMAL_RECOVERY_ACTIVITY" --es command state 2>&1)"
normal_launch_status=$?
set -e
trap 'recovery_setup_failure "$?" "$LINENO" "$BASH_COMMAND"' ERR
if [[ "$normal_launch_status" -eq 0 ]] &&
    ! grep -Eqi "error|does not exist|not found|disabled|not enabled" <<<"$normal_launch_output"; then
    echo "$normal_launch_output" >&2
    fail "Recovery lab harness unexpectedly launched from normal CarePad debug build"
fi

CURRENT_STAGE="uninstall normal host"
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='adb uninstall "$NORMAL_HOST_PACKAGE" >/dev/null'
adb uninstall "$NORMAL_HOST_PACKAGE" >/dev/null

CURRENT_STAGE="uninstall previous lab host"
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='adb uninstall "$HOST_PACKAGE" >/dev/null 2>&1 || true'
adb uninstall "$HOST_PACKAGE" >/dev/null 2>&1 || true

CURRENT_STAGE="install lab host"
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='adb install "$LAB_HOST_APK" >/dev/null'
adb install "$LAB_HOST_APK" >/dev/null

CURRENT_STAGE="pm clear lab host"
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='adb shell pm clear "$HOST_PACKAGE" >/dev/null'
adb shell pm clear "$HOST_PACKAGE" >/dev/null

CURRENT_STAGE="first run_lab state"
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='run_lab state'
run_lab state
assert_field present false
echo "RECOVERY_SETUP_CHECKPOINT first_run_lab_state=passed" >&2

CURRENT_STAGE="locate apksigner"
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n 1)"'
APKSIGNER="$(find "$ANDROID_HOME/build-tools" -type f -name apksigner | sort -V | tail -n 1)"

CURRENT_STAGE="validate apksigner"
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='test -n "$APKSIGNER"'
test -n "$APKSIGNER"

CURRENT_STAGE="hash update apk"
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='UPDATE_SHA="$(sha256sum "$UPDATE_APK" | awk '\''{print tolower($1)}'\'')"'
UPDATE_SHA="$(sha256sum "$UPDATE_APK" | awk '{print tolower($1)}')"

CURRENT_STAGE="hash defective apk"
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='DEFECTIVE_SHA="$(sha256sum "$DEFECTIVE_APK" | awk '\''{print tolower($1)}'\'')"'
DEFECTIVE_SHA="$(sha256sum "$DEFECTIVE_APK" | awk '{print tolower($1)}')"

CURRENT_STAGE="extract signing sha"
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='APKSIGNER_OUTPUT="$(apksigner verify --print-certs "$UPDATE_APK")"'
APKSIGNER_STDERR_FILE="${RUNNER_TEMP:-/tmp}/carepad-apksigner.stderr"
rm -f "$APKSIGNER_STDERR_FILE"
APKSIGNER_OUTPUT="$("$APKSIGNER" verify --print-certs "$UPDATE_APK" 2>"$APKSIGNER_STDERR_FILE")"
APKSIGNER_STDERR="$(cat "$APKSIGNER_STDERR_FILE" 2>/dev/null || true)"
CERT_DIGEST_LINE="$(printf '%s\n' "$APKSIGNER_OUTPUT" |
    grep -E -m1 '^(Signer #1 certificate|V2 Signer: certificate) SHA-256 digest: ' || true)"
SIGNING_SHA="$(printf '%s\n' "$CERT_DIGEST_LINE" |
    sed -E 's/^(Signer #1 certificate|V2 Signer: certificate) SHA-256 digest: //' |
    tr '[:upper:]' '[:lower:]')"
printf 'APKSIGNER_PATH=%q\n' "$APKSIGNER" >&2
printf 'APKSIGNER_STDOUT_QUOTED=%q\n' "$APKSIGNER_OUTPUT" >&2
printf 'APKSIGNER_STDERR_QUOTED=%q\n' "$APKSIGNER_STDERR" >&2
printf 'APKSIGNER_STDOUT_BEGIN\n%s\nAPKSIGNER_STDOUT_END\n' "$APKSIGNER_OUTPUT" >&2
printf 'APKSIGNER_STDERR_BEGIN\n%s\nAPKSIGNER_STDERR_END\n' "$APKSIGNER_STDERR" >&2
printf 'APKSIGNER_SIGNER_SHA256_LINE=%s\n' "$CERT_DIGEST_LINE" >&2
printf 'SIGNING_SHA=%s\n' "$SIGNING_SHA" >&2
printf 'SIGNING_SHA_LENGTH=%d\n' "${#SIGNING_SHA}" >&2
printf 'SIGNING_SHA_QUOTED=%q\n' "$SIGNING_SHA" >&2
printf 'SIGNING_SHA_BYTES=' >&2
printf '%s' "$SIGNING_SHA" | od -An -tx1 | tr -d '\n' >&2
printf '\n' >&2

CURRENT_STAGE="validate update sha"
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='[[ "$UPDATE_SHA" =~ ^[0-9a-f]{64}$ ]]'
[[ "$UPDATE_SHA" =~ ^[0-9a-f]{64}$ ]]

CURRENT_STAGE="validate defective sha"
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='[[ "$DEFECTIVE_SHA" =~ ^[0-9a-f]{64}$ ]]'
[[ "$DEFECTIVE_SHA" =~ ^[0-9a-f]{64}$ ]]

CURRENT_STAGE="validate signing sha"
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='[[ "$SIGNING_SHA" =~ ^[0-9a-f]{64}$ ]]'
[[ "$SIGNING_SHA" =~ ^[0-9a-f]{64}$ ]]

CURRENT_STAGE="stage update apk"
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='stage_apk "$UPDATE_APK" "$UPDATE_STAGE"'
stage_apk "$UPDATE_APK" "$UPDATE_STAGE"

CURRENT_STAGE="stage defective apk"
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='stage_apk "$DEFECTIVE_APK" "$DEFECTIVE_STAGE"'
stage_apk "$DEFECTIVE_APK" "$DEFECTIVE_STAGE"

CURRENT_STAGE="install defective fixture"
CURRENT_STAGE_LINE=$((LINENO + 2))
CURRENT_STAGE_COMMAND='install_defective'
install_defective

echo "RECOVERY_SETUP_COMPLETE" >&2
SETUP_OBSERVABILITY_ACTIVE=false
trap - ERR EXIT

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
UNINSTALL_CANCEL_RESUMED_IMMEDIATELY="$(current_resumed_activity)"
echo "RESUMED_IMMEDIATELY_AFTER_UNINSTALL_CANCEL=$UNINSTALL_CANCEL_RESUMED_IMMEDIATELY" >&2
wait_for_uninstall_cancel_return
echo "RESUMED_AFTER_UNINSTALL_CANCEL_WAIT=$UNINSTALL_CANCEL_RESUMED_AFTER_WAIT" >&2
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
INSTALL_PERMISSION_APPOP="$(adb shell appops get "$HOST_PACKAGE" REQUEST_INSTALL_PACKAGES 2>&1 | tr -d '\r')"
echo "REQUEST_INSTALL_PACKAGES_AFTER_ALLOW=$INSTALL_PERMISSION_APPOP" >&2
if ! grep -Eq 'REQUEST_INSTALL_PACKAGES:[[:space:]]*allow([;[:space:]]|$)' <<<"$INSTALL_PERMISSION_APPOP"; then
    fail "REQUEST_INSTALL_PACKAGES AppOp was not allow after grant"
fi
INSTALL_PERMISSION_RESUMED_BEFORE_BACK="$(current_resumed_activity)"
echo "RESUMED_BEFORE_INSTALL_PERMISSION_BACK=$INSTALL_PERMISSION_RESUMED_BEFORE_BACK" >&2
adb shell input keyevent KEYCODE_BACK >/dev/null
wait_for_install_permission_settings_exit
echo "RESUMED_AFTER_INSTALL_PERMISSION_BACK=$INSTALL_PERMISSION_RESUMED_AFTER_BACK" >&2
run_lab state
assert_field present true
assert_field phase WAITING_FOR_INSTALL_PERMISSION
INSTALL_PERMISSION_RECOVERY_BEFORE_CONTINUE="$LAST_RESULT"
run_continue_install_permission_with_diagnostics \
    "$INSTALL_PERMISSION_APPOP" \
    "$INSTALL_PERMISSION_RESUMED_BEFORE_BACK" \
    "$INSTALL_PERMISSION_RESUMED_AFTER_BACK" \
    "$INSTALL_PERMISSION_RECOVERY_BEFORE_CONTINUE"
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
