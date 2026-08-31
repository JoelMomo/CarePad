#!/usr/bin/env bash
set -euo pipefail

HOST_APK="${1:?normal CarePad host APK required}"
TEST_APK="${2:?CarePad androidTest APK required}"
MODULE_APK="${3:-}"
PERFORMANCE_PACKAGE="dev.carepad.module.performance"
PERFORMANCE_FIXTURE_INSTALLED=false

cleanup_performance_fixture() {
  if [[ "$PERFORMANCE_FIXTURE_INSTALLED" == true ]]; then
    adb uninstall "$PERFORMANCE_PACKAGE" >/dev/null 2>&1 || true
  fi
}

trap cleanup_performance_fixture EXIT

if [[ -z "$MODULE_APK" && -d artifacts/performance-module ]]; then
  MODULE_APK="$(find artifacts/performance-module -name '*.apk' -print -quit)"
fi

adb wait-for-device

for _ in $(seq 1 180); do
  if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
    break
  fi
  sleep 1
done

test "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1"

adb uninstall com.joel.thordoctor.test >/dev/null 2>&1 || true
adb uninstall com.joel.thordoctor >/dev/null 2>&1 || true
adb install -r "$HOST_APK"
if [[ -n "$MODULE_APK" ]]; then
  adb install -r "$MODULE_APK"
  PERFORMANCE_FIXTURE_INSTALLED=true
fi
adb install -r "$TEST_APK"

RESULT_FILE="${RUNNER_TEMP:-/tmp}/carepad-focus-compose-result.txt"
adb shell am instrument -w -r \
  -e class com.joel.thordoctor.ui.CarePadFocusIntegrationTest \
  com.joel.thordoctor.test/androidx.test.runner.AndroidJUnitRunner \
  | tee "$RESULT_FILE"

grep -Eq '^OK \([0-9]+ tests?\)$' "$RESULT_FILE"
