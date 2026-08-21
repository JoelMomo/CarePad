package com.joel.thordoctor.core.emulator

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

object ForegroundEmulatorDetector {

    private const val LEGACY_MOVE_TO_FOREGROUND_EVENT = 1

    data class DetectedEmulator(
        val name: String,
        val packageName: String
    )

    data class ForegroundEventInfo(
        val packageName: String,
        val timestamp: Long
    )

    fun hasUsageAccess(context: Context): Boolean {
        val appOps =
            context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

        val mode =
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )

        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun currentEmulator(context: Context): DetectedEmulator? {
        val packageName = currentForegroundPackage(context) ?: return null
        val definition = EmulatorEngine.definitionForPackage(packageName) ?: return null

        return DetectedEmulator(
            name = definition.displayName,
            packageName = packageName
        )
    }

    fun latestForegroundEvent(
        context: Context,
        sinceTimestamp: Long
    ): ForegroundEventInfo? {
        if (!hasUsageAccess(context)) return null

        val manager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val events =
            manager.queryEvents(
                sinceTimestamp,
                System.currentTimeMillis()
            )

        val event = UsageEvents.Event()
        var latestTimestamp = 0L
        var latestPackage: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            if (
                isForegroundEvent(event) &&
                event.timeStamp > latestTimestamp
            ) {
                latestTimestamp = event.timeStamp
                latestPackage = event.packageName
            }
        }

        val packageName = latestPackage ?: return null

        return ForegroundEventInfo(
            packageName = packageName,
            timestamp = latestTimestamp
        )
    }

    private fun currentForegroundPackage(
        context: Context,
        lookbackMs: Long = 60_000L
    ): String? {
        if (!hasUsageAccess(context)) return null

        val manager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val end = System.currentTimeMillis()
        val events = manager.queryEvents(end - lookbackMs, end)
        val event = UsageEvents.Event()

        var latestTimestamp = 0L
        var latestPackage: String? = null

        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            if (
                isForegroundEvent(event) &&
                event.timeStamp > latestTimestamp
            ) {
                latestTimestamp = event.timeStamp
                latestPackage = event.packageName
            }
        }

        return latestPackage
    }

    private fun isForegroundEvent(event: UsageEvents.Event): Boolean {
        if (event.eventType == LEGACY_MOVE_TO_FOREGROUND_EVENT) return true

        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
    }
}
