#!/usr/bin/env bash
set -euo pipefail

HOST_APK="${1:?host APK path required}"
BASELINE_APK="${2:?baseline module APK path required}"
UPDATE_APK="${3:?update module APK path required}"
INCOMPATIBLE_PROTOCOL_APK="${4:?incompatible-protocol module APK path required}"
WRONG_SIGNATURE_APK="${5:?wrong-signature module APK path required}"
DEFECTIVE_UPDATE_APK="${6:?defective-update module APK path required}"
PERFORMANCE_APK="${7:?performance module APK path required}"
PERFORMANCE_EMULATOR_FIXTURE_APK="${8:?performance emulator fixture APK path required}"

chmod +x scripts/ci/android-emulator-module-lab-smoke.sh
scripts/ci/android-emulator-module-lab-smoke.sh \
    "$HOST_APK" \
    "$BASELINE_APK" \
    "$UPDATE_APK" \
    "$INCOMPATIBLE_PROTOCOL_APK" \
    "$WRONG_SIGNATURE_APK" \
    "$DEFECTIVE_UPDATE_APK"

chmod +x scripts/ci/android-emulator-performance-module-smoke.sh
scripts/ci/android-emulator-performance-module-smoke.sh \
    "$HOST_APK" \
    "$PERFORMANCE_APK" \
    "$PERFORMANCE_EMULATOR_FIXTURE_APK"
