package com.joel.thordoctor.modules.performance

import org.junit.Assert.assertEquals
import org.junit.Test

class PerformanceRecoveryPolicyTest {

    @Test
    fun waitingRecoveryReturnsToEmulatorWait() {
        assertEquals(
            PerformanceRecoveryAction.WAIT_FOR_EMULATOR,
            PerformanceRecoveryPolicy.actionFor(PerformanceRecoveryState.WaitingEmulator)
        )
    }

    @Test
    fun monitoringRecoveryReturnsToMonitoring() {
        val recovery = PerformanceRecoveryState.Monitoring(
            sessionId = "session",
            emulatorName = "PPSSPP",
            emulatorPackage = "org.ppsspp.ppsspp",
            startedAt = 1_000L
        )

        assertEquals(
            PerformanceRecoveryAction.MONITOR_EMULATOR,
            PerformanceRecoveryPolicy.actionFor(recovery)
        )
    }

    @Test
    fun completedSessionWaitingForSaveRetriesSaveInsteadOfMonitoring() {
        val recovery = PerformanceRecoveryState.Saving(
            sessionId = "session",
            emulatorName = "PPSSPP",
            emulatorPackage = "org.ppsspp.ppsspp",
            startedAt = 1_000L,
            endedAt = 5_000L,
            endReason = PerformanceMonitoringPolicy.END_MANUAL_STOP
        )

        assertEquals(
            PerformanceRecoveryAction.RETRY_SAVE,
            PerformanceRecoveryPolicy.actionFor(recovery)
        )
    }
}
