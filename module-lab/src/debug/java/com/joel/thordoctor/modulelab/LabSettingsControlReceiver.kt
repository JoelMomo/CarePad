package com.joel.thordoctor.modulelab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only broadcast receiver for controlling the test state of LabSettingsProvider
 * in automated smoke/CI tests without polluting the production wire contract.
 */
class LabSettingsControlReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_CONTROL = "dev.carepad.modulelab.CONTROL_SETTINGS"
        const val EXTRA_COMMAND = "command"
        const val RESULT_FILE = "lab_settings_control_result.txt"
        private const val TAG = "LabSettingsControl"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: "MISSING"
        var success = true
        var detail = "Executed $command"

        when (command) {
            "RESET" -> LabSettingsProvider.State.reset()
            "FORCE_REJECT" -> LabSettingsProvider.State.mode = LabSettingsProvider.SimulationMode.FORCE_REJECT
            "FORCE_STALE" -> LabSettingsProvider.State.mode = LabSettingsProvider.SimulationMode.FORCE_STALE
            "FORCE_INCOMPATIBLE" -> LabSettingsProvider.State.mode = LabSettingsProvider.SimulationMode.FORCE_INCOMPATIBLE
            "FORCE_UNAVAILABLE" -> LabSettingsProvider.State.mode = LabSettingsProvider.SimulationMode.FORCE_UNAVAILABLE
            "ADVANCE_REVISION" -> {
                val newRev = LabSettingsProvider.State.advanceRevision()
                detail = "Advanced revision to $newRev"
            }
            else -> {
                success = false
                detail = "Unknown control command: $command"
            }
        }

        try {
            context.openFileOutput(RESULT_FILE, Context.MODE_PRIVATE).bufferedWriter().use { writer ->
                writer.appendLine("command=$command")
                writer.appendLine("success=$success")
                writer.appendLine("detail=$detail")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to persist control result", e)
        }

        Log.i(TAG, "command=$command success=$success detail=$detail")
    }
}
