package com.joel.thordoctor

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.joel.thordoctor.core.emulator.ForegroundEmulatorDetector

/** DocThor compatibility facade for Core foreground emulator detection. */
object UsageDetector {

    data class DetectedEmulator(
        val name: String,
        val packageName: String
    )

    data class ForegroundEventInfo(
        val packageName: String,
        val timestamp: Long
    )

    fun openUsageSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        )
    }

    fun hasUsageAccess(context: Context): Boolean =
        ForegroundEmulatorDetector.hasUsageAccess(context)

    fun currentEmulator(context: Context): DetectedEmulator? =
        ForegroundEmulatorDetector.currentEmulator(context)?.let { emulator ->
            DetectedEmulator(
                name = emulator.name,
                packageName = emulator.packageName
            )
        }

    fun latestForegroundEvent(
        context: Context,
        sinceTimestamp: Long
    ): ForegroundEventInfo? =
        ForegroundEmulatorDetector.latestForegroundEvent(
            context,
            sinceTimestamp
        )?.let { event ->
            ForegroundEventInfo(
                packageName = event.packageName,
                timestamp = event.timestamp
            )
        }
}
