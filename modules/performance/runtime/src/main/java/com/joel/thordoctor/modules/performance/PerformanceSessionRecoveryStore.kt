package com.joel.thordoctor.modules.performance

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

enum class PerformanceRecoveryStage {
    WAITING_EMULATOR,
    MONITORING,
    SAVING
}

enum class PerformanceRecoveryAction {
    WAIT_FOR_EMULATOR,
    MONITOR_EMULATOR,
    RETRY_SAVE
}

sealed interface PerformanceRecoveryState {
    val stage: PerformanceRecoveryStage

    object WaitingEmulator : PerformanceRecoveryState {
        override val stage = PerformanceRecoveryStage.WAITING_EMULATOR
    }

    data class Monitoring(
        val sessionId: String,
        val emulatorName: String,
        val emulatorPackage: String,
        val startedAt: Long
    ) : PerformanceRecoveryState {
        override val stage = PerformanceRecoveryStage.MONITORING
    }

    data class Saving(
        val sessionId: String,
        val emulatorName: String,
        val emulatorPackage: String,
        val startedAt: Long,
        val endedAt: Long,
        val endReason: String
    ) : PerformanceRecoveryState {
        override val stage = PerformanceRecoveryStage.SAVING
    }
}

object PerformanceRecoveryPolicy {
    fun actionFor(state: PerformanceRecoveryState): PerformanceRecoveryAction =
        when (state) {
            PerformanceRecoveryState.WaitingEmulator -> PerformanceRecoveryAction.WAIT_FOR_EMULATOR
            is PerformanceRecoveryState.Monitoring -> PerformanceRecoveryAction.MONITOR_EMULATOR
            is PerformanceRecoveryState.Saving -> PerformanceRecoveryAction.RETRY_SAVE
        }
}

object PerformanceSessionRecoveryStore {

    private const val PREFERENCES_NAME = "thor_doctor_session_recovery"
    private const val KEY_ACTIVE = "active"
    private const val KEY_STAGE = "stage"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_EMULATOR_NAME = "emulator_name"
    private const val KEY_EMULATOR_PACKAGE = "emulator_package"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_ENDED_AT = "ended_at"
    private const val KEY_END_REASON = "end_reason"
    private const val KEY_BOOT_EPOCH_MS = "boot_epoch_ms"
    private const val SAMPLES_FILENAME = "active_session_samples.jsonl"
    private const val BOOT_EPOCH_TOLERANCE_MS = 120_000L

    @Synchronized
    fun markWaiting(context: Context) {
        samplesFile(context).delete()
        saveState(
            context = context,
            stage = PerformanceRecoveryStage.WAITING_EMULATOR,
            sessionId = null,
            emulatorName = null,
            emulatorPackage = null,
            startedAt = null,
            endedAt = null,
            endReason = null
        )
    }

    @Synchronized
    fun markMonitoring(
        context: Context,
        sessionId: String,
        emulatorName: String,
        emulatorPackage: String,
        startedAt: Long
    ) {
        saveState(
            context = context,
            stage = PerformanceRecoveryStage.MONITORING,
            sessionId = sessionId,
            emulatorName = emulatorName,
            emulatorPackage = emulatorPackage,
            startedAt = startedAt,
            endedAt = null,
            endReason = null
        )
    }

    @Synchronized
    fun markSaving(
        context: Context,
        sessionId: String,
        emulatorName: String,
        emulatorPackage: String,
        startedAt: Long,
        endedAt: Long,
        endReason: String
    ) {
        saveState(
            context = context,
            stage = PerformanceRecoveryStage.SAVING,
            sessionId = sessionId,
            emulatorName = emulatorName,
            emulatorPackage = emulatorPackage,
            startedAt = startedAt,
            endedAt = endedAt,
            endReason = endReason
        )
    }

    @Synchronized
    fun load(context: Context): PerformanceRecoveryState? {
        val preferences = preferences(context)

        if (!preferences.getBoolean(KEY_ACTIVE, false)) return null

        val stage =
            try {
                PerformanceRecoveryStage.valueOf(
                    preferences.getString(KEY_STAGE, null) ?: return null
                )
            } catch (_: IllegalArgumentException) {
                clear(context)
                return null
            }

        // A completed session waiting only for its summary to be written is safe
        // to recover after a reboot. Live waiting/monitoring state is not.
        if (stage != PerformanceRecoveryStage.SAVING) {
            val storedBootEpoch =
                preferences.getLong(KEY_BOOT_EPOCH_MS, Long.MIN_VALUE)

            if (
                storedBootEpoch == Long.MIN_VALUE ||
                abs(storedBootEpoch - currentBootEpochMs()) > BOOT_EPOCH_TOLERANCE_MS
            ) {
                clear(context)
                return null
            }
        }

        if (stage == PerformanceRecoveryStage.WAITING_EMULATOR) {
            return PerformanceRecoveryState.WaitingEmulator
        }

        val sessionId = preferences.getString(KEY_SESSION_ID, null)
        val emulatorName = preferences.getString(KEY_EMULATOR_NAME, null)
        val emulatorPackage = preferences.getString(KEY_EMULATOR_PACKAGE, null)
        val startedAt = preferences.getLong(KEY_STARTED_AT, 0L)

        if (
            sessionId.isNullOrBlank() ||
            emulatorName.isNullOrBlank() ||
            emulatorPackage.isNullOrBlank() ||
            startedAt <= 0L
        ) {
            clear(context)
            return null
        }

        if (stage == PerformanceRecoveryStage.MONITORING) {
            return PerformanceRecoveryState.Monitoring(
                sessionId = sessionId,
                emulatorName = emulatorName,
                emulatorPackage = emulatorPackage,
                startedAt = startedAt
            )
        }

        val endedAt = preferences.getLong(KEY_ENDED_AT, 0L)
        val endReason = preferences.getString(KEY_END_REASON, null)

        if (endedAt < startedAt || endReason.isNullOrBlank()) {
            clear(context)
            return null
        }

        return PerformanceRecoveryState.Saving(
            sessionId = sessionId,
            emulatorName = emulatorName,
            emulatorPackage = emulatorPackage,
            startedAt = startedAt,
            endedAt = endedAt,
            endReason = endReason
        )
    }

    fun hasRecoverableSession(context: Context): Boolean =
        load(context) != null

    @Synchronized
    fun appendSample(context: Context, sample: JSONObject) {
        samplesFile(context).appendText(
            sample.toString() + "\n",
            Charsets.UTF_8
        )
    }

    @Synchronized
    fun readSamples(context: Context): JSONArray {
        val result = JSONArray()
        val file = samplesFile(context)

        if (!file.exists() || !file.canRead()) return result

        file.forEachLine(Charsets.UTF_8) { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                try {
                    result.put(JSONObject(trimmed))
                } catch (_: Exception) {
                    // Ignore only the damaged sample.
                }
            }
        }

        return result
    }

    @Synchronized
    fun clear(context: Context) {
        preferences(context).edit().clear().apply()
        samplesFile(context).delete()
    }

    private fun saveState(
        context: Context,
        stage: PerformanceRecoveryStage,
        sessionId: String?,
        emulatorName: String?,
        emulatorPackage: String?,
        startedAt: Long?,
        endedAt: Long?,
        endReason: String?
    ) {
        preferences(context)
            .edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_STAGE, stage.name)
            .putString(KEY_SESSION_ID, sessionId)
            .putString(KEY_EMULATOR_NAME, emulatorName)
            .putString(KEY_EMULATOR_PACKAGE, emulatorPackage)
            .putLong(KEY_STARTED_AT, startedAt ?: 0L)
            .putLong(KEY_ENDED_AT, endedAt ?: 0L)
            .putString(KEY_END_REASON, endReason)
            .putLong(KEY_BOOT_EPOCH_MS, currentBootEpochMs())
            .apply()
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )

    private fun samplesFile(context: Context): File =
        File(context.filesDir, SAMPLES_FILENAME)

    private fun currentBootEpochMs(): Long =
        System.currentTimeMillis() - SystemClock.elapsedRealtime()
}
