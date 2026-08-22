package com.joel.thordoctor.modules.performance

enum class PerformanceMonitoringPhase {
    MONITORING,
    FINISHING
}

data class PerformanceMonitoringDecision(
    val phase: PerformanceMonitoringPhase,
    val emulatorInForeground: Boolean,
    val awaySince: Long?,
    val elapsedSeconds: Long,
    val remainingSeconds: Long,
    val shouldFinish: Boolean
)

/**
 * Pure timing and foreground-state policy for a performance monitoring session.
 *
 * Android service lifecycle, notifications, usage-event reads and persistence stay
 * outside this class. This keeps the policy reusable and independently testable.
 */
object PerformanceMonitoringPolicy {

    const val SAMPLE_INTERVAL_MS = 5_000L
    const val CHECK_INTERVAL_MS = 1_000L
    const val USAGE_EVENT_OVERLAP_MS = 15_000L
    const val AWAY_GRACE_MS = 20_000L
    const val HOST_APP_GRACE_MS = 15_000L

    const val END_FOREGROUND_TIMEOUT = "foreground_timeout"
    const val END_MANUAL_STOP = "manual_stop"

    fun usageCursorFor(now: Long): Long =
        now - USAGE_EVENT_OVERLAP_MS

    fun evaluateForeground(
        now: Long,
        sessionStartedAt: Long,
        emulatorPackage: String,
        hostPackage: String,
        foregroundPackage: String,
        awaySince: Long?
    ): PerformanceMonitoringDecision {
        val emulatorInForeground =
            foregroundPackage == emulatorPackage

        if (emulatorInForeground) {
            return PerformanceMonitoringDecision(
                phase = PerformanceMonitoringPhase.MONITORING,
                emulatorInForeground = true,
                awaySince = null,
                elapsedSeconds = elapsedSeconds(now, sessionStartedAt),
                remainingSeconds = 0L,
                shouldFinish = false
            )
        }

        val effectiveAwaySince = awaySince ?: now
        val graceMs =
            if (foregroundPackage == hostPackage) {
                HOST_APP_GRACE_MS
            } else {
                AWAY_GRACE_MS
            }
        val awayDuration =
            (now - effectiveAwaySince).coerceAtLeast(0L)

        return PerformanceMonitoringDecision(
            phase = PerformanceMonitoringPhase.FINISHING,
            emulatorInForeground = false,
            awaySince = effectiveAwaySince,
            elapsedSeconds = elapsedSeconds(effectiveAwaySince, sessionStartedAt),
            remainingSeconds =
                (graceMs - awayDuration)
                    .coerceAtLeast(0L) / 1_000L,
            shouldFinish = awayDuration >= graceMs
        )
    }

    fun sessionEndedAt(
        endReason: String,
        awaySince: Long?,
        now: Long
    ): Long =
        if (endReason == END_FOREGROUND_TIMEOUT) {
            awaySince ?: now
        } else {
            now
        }

    private fun elapsedSeconds(
        timestamp: Long,
        sessionStartedAt: Long
    ): Long =
        (timestamp - sessionStartedAt)
            .coerceAtLeast(0L) / 1_000L
}
