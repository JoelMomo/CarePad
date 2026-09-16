package dev.carepad.module.controls.internalui

/** Release never evaluates or records diagnostic details. */
object ControlsFocusTrace {
    @Suppress("UNUSED_PARAMETER")
    fun log(stage: String, details: () -> String) = Unit
}
