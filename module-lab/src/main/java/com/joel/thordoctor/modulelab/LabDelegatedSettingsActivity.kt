package com.joel.thordoctor.modulelab

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView

/**
 * Fixture Activity launched for the delegated settings entry point (OPEN_MODULE_SETTINGS).
 */
class LabDelegatedSettingsActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = TextView(this).apply {
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            textSize = 20f
            text = "CarePad Module Lab\n\nDelegated Settings Activity\nAction: ${intent?.action ?: "none"}"
        }

        setContentView(text)
    }
}
