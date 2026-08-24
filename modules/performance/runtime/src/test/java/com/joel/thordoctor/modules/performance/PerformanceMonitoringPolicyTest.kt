package com.joel.thordoctor.modules.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceMonitoringPolicyTest {

    @Test
    fun emulatorForegroundKeepsMonitoring() {
        val decision = PerformanceMonitoringPolicy.evaluateForeground(
            now = 20_000L,
            sessionStartedAt = 10_000L,
            emulatorPackage = "emulator",
            hostPackage = "performance",
            foregroundPackage = "emulator",
            awaySince = 12_000L
        )

        assertEquals(PerformanceMonitoringPhase.MONITORING, decision.phase)
        assertTrue(decision.emulatorInForeground)
        assertNull(decision.awaySince)
        assertFalse(decision.shouldFinish)
        assertEquals(10L, decision.elapsedSeconds)
    }

    @Test
    fun leavingEmulatorUsesGraceBeforeFinishing() {
        val first = PerformanceMonitoringPolicy.evaluateForeground(
            now = 30_000L,
            sessionStartedAt = 10_000L,
            emulatorPackage = "emulator",
            hostPackage = "performance",
            foregroundPackage = "other",
            awaySince = null
        )
        val timedOut = PerformanceMonitoringPolicy.evaluateForeground(
            now = 50_000L,
            sessionStartedAt = 10_000L,
            emulatorPackage = "emulator",
            hostPackage = "performance",
            foregroundPackage = "other",
            awaySince = first.awaySince
        )

        assertEquals(PerformanceMonitoringPhase.FINISHING, first.phase)
        assertFalse(first.shouldFinish)
        assertTrue(timedOut.shouldFinish)
        assertEquals(20L, timedOut.elapsedSeconds)
    }

    @Test
    fun timeoutEndUsesFirstAwayTimestamp() {
        assertEquals(
            42_000L,
            PerformanceMonitoringPolicy.sessionEndedAt(
                endReason = PerformanceMonitoringPolicy.END_FOREGROUND_TIMEOUT,
                awaySince = 42_000L,
                now = 60_000L
            )
        )
    }
}
