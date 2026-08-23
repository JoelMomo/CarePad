package dev.carepad.module.performance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

class PerformanceLabControlReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_COMMAND = "command"
        const val COMMAND_STOP_SERVICE = "STOP_SERVICE"
        const val COMMAND_RESUME_SERVICE = "RESUME_SERVICE"
        const val RESULT_FILE = "performance_lab_control_result.txt"

        private const val TAG = "PerformanceLabControl"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val command = intent.getStringExtra(EXTRA_COMMAND) ?: "MISSING"
        val result = try {
            when (command) {
                COMMAND_STOP_SERVICE -> {
                    val stopped = context.stopService(
                        Intent(context, PerformanceMonitorService::class.java)
                    )
                    LabResult(
                        command = command,
                        success = stopped,
                        detail = "stopService returned $stopped"
                    )
                }

                COMMAND_RESUME_SERVICE -> {
                    val component = ContextCompat.startForegroundService(
                        context,
                        Intent(context, PerformanceMonitorService::class.java)
                            .setAction(PerformanceMonitorService.ACTION_RESUME)
                    )
                    LabResult(
                        command = command,
                        success = component != null,
                        detail = "startForegroundService returned ${component?.flattenToShortString() ?: "null"}"
                    )
                }

                else -> LabResult(
                    command = command,
                    success = false,
                    detail = "unknown command"
                )
            }
        } catch (error: Throwable) {
            LabResult(
                command = command,
                success = false,
                detail = "${error.javaClass.name}: ${error.message ?: "no message"}"
            )
        }

        writeResult(context, result)
        Log.i(TAG, "command=${result.command} success=${result.success} detail=${result.detail}")
    }

    private fun writeResult(context: Context, result: LabResult) {
        val safeDetail = result.detail.replace('\n', ' ').replace('\r', ' ')
        try {
            context.openFileOutput(RESULT_FILE, Context.MODE_PRIVATE).bufferedWriter().use { writer ->
                writer.appendLine("command=${result.command}")
                writer.appendLine("success=${result.success}")
                writer.appendLine("detail=$safeDetail")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to persist LAB result", error)
            throw error
        }
    }

    private data class LabResult(
        val command: String,
        val success: Boolean,
        val detail: String
    )
}
