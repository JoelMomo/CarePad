package dev.carepad.module.controls.internalui

import android.os.SystemClock
import android.util.Log

/** Opt-in, debug-only trace. Values are input/focus metadata, never user content. */
object ControlsFocusTrace {
    fun log(stage: String, details: () -> String) {
        if (Log.isLoggable("CarePadT1Focus", Log.DEBUG)) {
            Log.d("CarePadT1Focus", "t=${SystemClock.uptimeMillis()} stage=$stage ${details()}")
        }
    }
}
