#!/usr/bin/env bash
set -euo pipefail

HOST_APK="${1:?host APK path required}"
CONTROLS_APK="${2:?controls module APK path required}"

HOST_PACKAGE="com.joel.thordoctor.carepadlabhost"
CONTROLS_PACKAGE="dev.carepad.module.controls"
HARNESS_COMPONENT="${HOST_PACKAGE}/com.joel.thordoctor.modules.host.ModuleLabHarnessActivity"
OPEN_ACTION="dev.carepad.action.OPEN_MODULE"
UI_DUMP_DEVICE="/sdcard/carepad-controls-window.xml"
UI_DUMP_LOCAL="${RUNNER_TEMP:-/tmp}/carepad-controls-window.xml"

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

controls_product_surface_visible() {
    grep -Eq 'text="(Guided test|Prueba guiada)"' "$UI_DUMP_LOCAL" &&
        grep -Eq 'text="(Detected inputs|Entradas detectadas)"' "$UI_DUMP_LOCAL" &&
        grep -Eq '(text|content-desc)="(Home|Inicio)"' "$UI_DUMP_LOCAL" &&
        grep -Eq '(text|content-desc)="(Add modules|Añadir módulos)"' "$UI_DUMP_LOCAL" &&
        grep -Eq '(text|content-desc)="(Settings|Ajustes)"' "$UI_DUMP_LOCAL" &&
        ! grep -Eq 'text="(Refresh controllers|Actualizar mandos)"' "$UI_DUMP_LOCAL"
}

restore_rotation() {
    adb shell settings put system accelerometer_rotation 1 >/dev/null 2>&1 || true
}

adb install -r "$HOST_APK" >/dev/null
adb install -r "$CONTROLS_APK" >/dev/null

adb shell am force-stop "$HOST_PACKAGE" >/dev/null
adb shell am start -n "$HARNESS_COMPONENT" >/dev/null

for _ in $(seq 1 20); do
    if dump_ui &&
        grep -Fq "Accepted: controls" "$UI_DUMP_LOCAL" &&
        grep -Fq "Package: $CONTROLS_PACKAGE" "$UI_DUMP_LOCAL"; then
        break
    fi
    sleep 0.5
done

if ! grep -Fq "Accepted: controls" "$UI_DUMP_LOCAL" ||
    ! grep -Fq "Package: $CONTROLS_PACKAGE" "$UI_DUMP_LOCAL"; then
    echo "CarePad host did not discover the Controls product module" >&2
    cat "$UI_DUMP_LOCAL" >&2 || true
    exit 1
fi

# The UX gate under test is the horizontal CarePad rail. Force landscape so the
# module crosses the canonical >=600dp && width>=height breakpoint and renders it.
adb shell settings put system accelerometer_rotation 0 >/dev/null
adb shell settings put system user_rotation 1 >/dev/null
trap restore_rotation EXIT
sleep 1

adb shell am start -a "$OPEN_ACTION" -p "$CONTROLS_PACKAGE" >/dev/null

for _ in $(seq 1 20); do
    resumed="$(current_resumed_activity)"
    if grep -Fq "$CONTROLS_PACKAGE" <<<"$resumed" &&
        grep -Fq "ControlsActivity" <<<"$resumed" &&
        dump_ui &&
        grep -Fq "package=\"$CONTROLS_PACKAGE\"" "$UI_DUMP_LOCAL" &&
        controls_product_surface_visible; then
        echo "Controls product module discovery/open + landscape rail UI smoke passed"
        exit 0
    fi
    sleep 0.5
done

echo "Controls product module did not expose the expected landscape rail UI" >&2
echo "Resumed activity: $(current_resumed_activity)" >&2
cat "$UI_DUMP_LOCAL" >&2 || true
exit 1
