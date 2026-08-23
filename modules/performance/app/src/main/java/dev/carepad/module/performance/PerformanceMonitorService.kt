package dev.carepad.module.performance

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.joel.thordoctor.core.emulator.ForegroundEmulatorDetector
import com.joel.thordoctor.modules.performance.PerformanceMetrics
import com.joel.thordoctor.modules.performance.PerformanceMonitoringPhase
import com.joel.thordoctor.modules.performance.PerformanceMonitoringPolicy
import com.joel.thordoctor.modules.performance.PerformanceRecoveryAction
import com.joel.thordoctor.modules.performance.PerformanceRecoveryPolicy
import com.joel.thordoctor.modules.performance.PerformanceRecoveryState
import com.joel.thordoctor.modules.performance.PerformanceSessionRecoveryStore
import com.joel.thordoctor.modules.performance.PerformanceSessionSerializer
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class PerformanceMonitorState {
    IDLE,
    WAITING_EMULATOR,
    MONITORING,
    FINISHING,
    SAVING,
    COMPLETED,
    ERROR
}

enum class PerformanceMonitorError {
    USAGE_ACCESS_REQUIRED,
    SESSION_SAVE_FAILED
}

class PerformanceMonitorService : Service() {

    companion object {
        const val ACTION_START = "dev.carepad.module.performance.action.START"
        const val ACTION_STOP = "dev.carepad.module.performance.action.STOP"
        const val ACTION_RESUME = "dev.carepad.module.performance.action.RESUME"

        private const val CHANNEL_ID = "carepad_performance_monitor"
        private const val NOTIFICATION_ID = 1201
        private const val DIAGNOSTIC_TAG = "PerformanceMonitorService"
        private const val RECOVERY_DIAGNOSTIC_CYCLES = 5

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var currentState = PerformanceMonitorState.IDLE
            private set

        @Volatile
        var currentError: PerformanceMonitorError? = null
            private set

        @Volatile
        private var currentEmulatorName: String? = null

        @Volatile
        private var currentSessionStartedAt = 0L

        @Volatile
        private var currentSessionElapsedSeconds = 0L

        @Volatile
        private var latestSnapshot: PerformanceMetrics.Snapshot? = null

        fun hasRecoverableSession(context: Context): Boolean =
            PerformanceSessionRecoveryStore.hasRecoverableSession(context)

        fun hasPendingSave(context: Context): Boolean =
            PerformanceSessionRecoveryStore.load(context) is PerformanceRecoveryState.Saving

        fun stateForUi(context: Context): PerformanceMonitorState {
            if (currentError != null) return PerformanceMonitorState.ERROR
            if (isRunning) return currentState

            return when (PerformanceSessionRecoveryStore.load(context)) {
                PerformanceRecoveryState.WaitingEmulator -> PerformanceMonitorState.WAITING_EMULATOR
                is PerformanceRecoveryState.Monitoring -> PerformanceMonitorState.MONITORING
                is PerformanceRecoveryState.Saving -> PerformanceMonitorState.SAVING
                null -> currentState
            }
        }

        fun emulatorNameForUi(context: Context): String? {
            if (isRunning) return currentEmulatorName
            return when (val recovery = PerformanceSessionRecoveryStore.load(context)) {
                is PerformanceRecoveryState.Monitoring -> recovery.emulatorName
                is PerformanceRecoveryState.Saving -> recovery.emulatorName
                PerformanceRecoveryState.WaitingEmulator,
                null -> null
            }
        }

        fun elapsedSecondsForUi(context: Context): Long {
            if (isRunning) return currentSessionElapsedSeconds
            return when (val recovery = PerformanceSessionRecoveryStore.load(context)) {
                is PerformanceRecoveryState.Monitoring ->
                    (System.currentTimeMillis() - recovery.startedAt).coerceAtLeast(0L) / 1_000L
                is PerformanceRecoveryState.Saving ->
                    (recovery.endedAt - recovery.startedAt).coerceAtLeast(0L) / 1_000L
                PerformanceRecoveryState.WaitingEmulator,
                null -> 0L
            }
        }

        fun latestSnapshotForUi(): PerformanceMetrics.Snapshot? = latestSnapshot
    }

    @Volatile
    private var shouldRun = false

    @Volatile
    private var manualStopRequested = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning) startMonitor()
                if (isRunning) START_STICKY else START_NOT_STICKY
            }
            ACTION_RESUME -> {
                if (!isRunning) resumePersistedMonitor(recoveryDiagnostics = true)
                if (isRunning) START_STICKY else START_NOT_STICKY
            }
            ACTION_STOP -> {
                requestStop()
                START_NOT_STICKY
            }
            null -> {
                if (!isRunning) resumePersistedMonitor(recoveryDiagnostics = false)
                if (isRunning) START_STICKY else START_NOT_STICKY
            }
            else -> START_NOT_STICKY
        }

    private fun startMonitor() {
        if (!ForegroundEmulatorDetector.hasUsageAccess(this)) {
            currentError = PerformanceMonitorError.USAGE_ACCESS_REQUIRED
            currentState = PerformanceMonitorState.ERROR
            stopSelf()
            return
        }

        manualStopRequested = false
        shouldRun = true
        isRunning = true
        currentError = null
        currentEmulatorName = null
        currentSessionStartedAt = 0L
        currentSessionElapsedSeconds = 0L
        latestSnapshot = null
        currentState = PerformanceMonitorState.WAITING_EMULATOR

        PerformanceSessionRecoveryStore.markWaiting(this)
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_waiting))
        )

        Thread(::monitorLoop, "CarePadPerformanceMonitor").start()
    }

    private fun resumePersistedMonitor(recoveryDiagnostics: Boolean) {
        val recovery = PerformanceSessionRecoveryStore.load(this)
        if (recovery == null) {
            currentState = PerformanceMonitorState.IDLE
            stopSelf()
            return
        }

        val action = PerformanceRecoveryPolicy.actionFor(recovery)
        if (
            action != PerformanceRecoveryAction.RETRY_SAVE &&
            !ForegroundEmulatorDetector.hasUsageAccess(this)
        ) {
            currentError = PerformanceMonitorError.USAGE_ACCESS_REQUIRED
            currentState = PerformanceMonitorState.ERROR
            stopSelf()
            return
        }

        manualStopRequested = false
        shouldRun = true
        isRunning = true
        currentError = null
        latestSnapshot = null

        when (action) {
            PerformanceRecoveryAction.WAIT_FOR_EMULATOR -> {
                currentEmulatorName = null
                currentSessionStartedAt = 0L
                currentSessionElapsedSeconds = 0L
                currentState = PerformanceMonitorState.WAITING_EMULATOR
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(getString(R.string.notification_waiting))
                )
                Thread(::monitorLoop, "CarePadPerformanceMonitor").start()
            }

            PerformanceRecoveryAction.MONITOR_EMULATOR -> {
                val monitoring = recovery as PerformanceRecoveryState.Monitoring
                currentEmulatorName = monitoring.emulatorName
                currentSessionStartedAt = monitoring.startedAt
                currentSessionElapsedSeconds =
                    (System.currentTimeMillis() - monitoring.startedAt).coerceAtLeast(0L) / 1_000L
                currentState = PerformanceMonitorState.MONITORING
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        getString(R.string.notification_monitoring, monitoring.emulatorName)
                    )
                )

                val emulator = ForegroundEmulatorDetector.DetectedEmulator(
                    name = monitoring.emulatorName,
                    packageName = monitoring.emulatorPackage
                )
                val samples = PerformanceSessionRecoveryStore.readSamples(this)
                Thread(
                    {
                        monitorEmulator(
                            emulator = emulator,
                            sessionId = monitoring.sessionId,
                            sessionStartedAt = monitoring.startedAt,
                            samples = samples,
                            recoveryDiagnostics = recoveryDiagnostics
                        )
                    },
                    "CarePadPerformanceMonitor"
                ).start()
            }

            PerformanceRecoveryAction.RETRY_SAVE -> {
                val saving = recovery as PerformanceRecoveryState.Saving
                currentEmulatorName = saving.emulatorName
                currentSessionStartedAt = saving.startedAt
                currentSessionElapsedSeconds =
                    (saving.endedAt - saving.startedAt).coerceAtLeast(0L) / 1_000L
                currentState = PerformanceMonitorState.SAVING
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(getString(R.string.notification_saving))
                )

                val emulator = ForegroundEmulatorDetector.DetectedEmulator(
                    name = saving.emulatorName,
                    packageName = saving.emulatorPackage
                )
                val samples = PerformanceSessionRecoveryStore.readSamples(this)
                Thread(
                    {
                        finishSession(
                            sessionId = saving.sessionId,
                            emulator = emulator,
                            startedAt = saving.startedAt,
                            endedAt = saving.endedAt,
                            endReason = saving.endReason,
                            samples = samples
                        )
                    },
                    "CarePadPerformanceSaveRetry"
                ).start()
            }
        }
    }

    private fun monitorLoop() {
        val emulator = waitForEmulator()
        if (emulator == null) {
            if (manualStopRequested) {
                PerformanceSessionRecoveryStore.clear(this)
                currentState = PerformanceMonitorState.IDLE
                finishService()
            }
            return
        }
        monitorEmulator(emulator)
    }

    private fun waitForEmulator(): ForegroundEmulatorDetector.DetectedEmulator? {
        while (shouldRun) {
            if (stopForMissingUsageAccess()) return null
            ForegroundEmulatorDetector.currentEmulator(this)?.let { return it }
            sleepSafely(PerformanceMonitoringPolicy.CHECK_INTERVAL_MS)
        }
        return null
    }

    private fun monitorEmulator(emulator: ForegroundEmulatorDetector.DetectedEmulator) {
        val startedAt = System.currentTimeMillis()
        val sessionId = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedAt))
        monitorEmulator(
            emulator = emulator,
            sessionId = sessionId,
            sessionStartedAt = startedAt,
            samples = JSONArray(),
            recoveryDiagnostics = false
        )
    }

    private fun monitorEmulator(
        emulator: ForegroundEmulatorDetector.DetectedEmulator,
        sessionId: String,
        sessionStartedAt: Long,
        samples: JSONArray,
        recoveryDiagnostics: Boolean
    ) {
        currentSessionStartedAt = sessionStartedAt
        currentSessionElapsedSeconds = 0L
        currentEmulatorName = emulator.name
        currentState = PerformanceMonitorState.MONITORING

        PerformanceSessionRecoveryStore.markMonitoring(
            context = this,
            sessionId = sessionId,
            emulatorName = emulator.name,
            emulatorPackage = emulator.packageName,
            startedAt = sessionStartedAt
        )
        updateNotification(getString(R.string.notification_monitoring, emulator.name))

        var nextSampleAt = 0L
        val initialUsageCursor = PerformanceMonitoringPolicy.usageCursorFor(sessionStartedAt)
        val initialForegroundEvent =
            ForegroundEmulatorDetector.latestForegroundEvent(this, initialUsageCursor)
        val emulatorRemainsForeground =
            ForegroundEmulatorDetector.packageRemainsForeground(
                this,
                emulator.packageName,
                initialUsageCursor
            )
        var lastForegroundPackage =
            if (emulatorRemainsForeground) {
                emulator.packageName
            } else {
                initialForegroundEvent?.packageName ?: packageName
            }
        var lastForegroundEventTime = initialForegroundEvent?.timestamp ?: initialUsageCursor
        var usageCursor = PerformanceMonitoringPolicy.usageCursorFor(System.currentTimeMillis())
        var awaySince: Long? = null
        var endReason = "unknown"
        var recoveryDiagnosticCyclesRemaining =
            if (recoveryDiagnostics) RECOVERY_DIAGNOSTIC_CYCLES else 0

        if (recoveryDiagnostics) {
            Log.i(
                DIAGNOSTIC_TAG,
                "RECOVERY_INIT sessionId=$sessionId " +
                    "sessionStartedAt=$sessionStartedAt " +
                    "now=${System.currentTimeMillis()} " +
                    "initialUsageCursor=$initialUsageCursor " +
                    "initialForegroundEventPackage=${initialForegroundEvent?.packageName ?: "NONE"} " +
                    "initialForegroundEventTimestamp=${initialForegroundEvent?.timestamp ?: -1L} " +
                    "packageRemainsForeground=$emulatorRemainsForeground " +
                    "lastForegroundPackage=$lastForegroundPackage " +
                    "lastForegroundEventTime=$lastForegroundEventTime " +
                    "usageCursor=$usageCursor"
            )
        }

        while (shouldRun) {
            if (stopForMissingUsageAccess()) return

            val now = System.currentTimeMillis()
            val foregroundEvent = ForegroundEmulatorDetector.latestForegroundEvent(this, usageCursor)
            usageCursor = PerformanceMonitoringPolicy.usageCursorFor(now)

            if (foregroundEvent != null && foregroundEvent.timestamp > lastForegroundEventTime) {
                lastForegroundEventTime = foregroundEvent.timestamp
                lastForegroundPackage = foregroundEvent.packageName
            }

            val decision = PerformanceMonitoringPolicy.evaluateForeground(
                now = now,
                sessionStartedAt = sessionStartedAt,
                emulatorPackage = emulator.packageName,
                hostPackage = packageName,
                foregroundPackage = lastForegroundPackage,
                awaySince = awaySince
            )
            awaySince = decision.awaySince
            currentSessionElapsedSeconds = decision.elapsedSeconds
            val captureSampleDue = decision.emulatorInForeground && now >= nextSampleAt

            if (recoveryDiagnosticCyclesRemaining > 0) {
                val cycle = RECOVERY_DIAGNOSTIC_CYCLES - recoveryDiagnosticCyclesRemaining + 1
                Log.i(
                    DIAGNOSTIC_TAG,
                    "RECOVERY_CYCLE cycle=$cycle " +
                        "foregroundEventPackage=${foregroundEvent?.packageName ?: "NONE"} " +
                        "foregroundEventTimestamp=${foregroundEvent?.timestamp ?: -1L} " +
                        "lastForegroundPackage=$lastForegroundPackage " +
                        "lastForegroundEventTime=$lastForegroundEventTime " +
                        "awaySince=${awaySince ?: -1L} " +
                        "decisionPhase=${decision.phase} " +
                        "decisionEmulatorInForeground=${decision.emulatorInForeground} " +
                        "now=$now " +
                        "nextSampleAt=$nextSampleAt " +
                        "captureSampleDue=$captureSampleDue"
                )
                recoveryDiagnosticCyclesRemaining -= 1
            }

            when (decision.phase) {
                PerformanceMonitoringPhase.MONITORING -> {
                    if (currentState != PerformanceMonitorState.MONITORING) {
                        updateNotification(getString(R.string.notification_monitoring, emulator.name))
                    }
                    currentState = PerformanceMonitorState.MONITORING
                }
                PerformanceMonitoringPhase.FINISHING -> {
                    currentState = PerformanceMonitorState.FINISHING
                    updateNotification(
                        getString(R.string.notification_finishing, decision.remainingSeconds)
                    )
                    if (decision.shouldFinish) {
                        endReason = PerformanceMonitoringPolicy.END_FOREGROUND_TIMEOUT
                        break
                    }
                }
            }

            if (captureSampleDue) {
                captureSample(samples, now, recoveryDiagnostics)
                nextSampleAt = now + PerformanceMonitoringPolicy.SAMPLE_INTERVAL_MS
            }

            sleepSafely(PerformanceMonitoringPolicy.CHECK_INTERVAL_MS)
        }

        if (!shouldRun && !manualStopRequested) return
        if (manualStopRequested) endReason = PerformanceMonitoringPolicy.END_MANUAL_STOP

        val endedAt = PerformanceMonitoringPolicy.sessionEndedAt(
            endReason = endReason,
            awaySince = awaySince,
            now = System.currentTimeMillis()
        )
        currentSessionElapsedSeconds = (endedAt - sessionStartedAt).coerceAtLeast(0L) / 1_000L

        finishSession(
            sessionId = sessionId,
            emulator = emulator,
            startedAt = sessionStartedAt,
            endedAt = endedAt,
            endReason = endReason,
            samples = samples
        )
    }

    private fun stopForMissingUsageAccess(): Boolean {
        if (ForegroundEmulatorDetector.hasUsageAccess(this)) return false

        currentError = PerformanceMonitorError.USAGE_ACCESS_REQUIRED
        currentState = PerformanceMonitorState.ERROR
        finishService()
        return true
    }

    private fun captureSample(
        samples: JSONArray,
        timestamp: Long,
        recoveryDiagnostics: Boolean
    ) {
        if (recoveryDiagnostics) {
            Log.i(
                DIAGNOSTIC_TAG,
                "SAMPLE_ATTEMPT timestamp=$timestamp sampleCountBefore=${samples.length()}"
            )
        }

        try {
            val snapshot = PerformanceMetrics.capture(this)
            latestSnapshot = snapshot
            val sample = PerformanceSessionSerializer.serializeSample(timestamp, snapshot)
            samples.put(sample)
            PerformanceSessionRecoveryStore.appendSample(this, sample)
            if (recoveryDiagnostics) {
                Log.i(
                    DIAGNOSTIC_TAG,
                    "SAMPLE_SUCCESS timestamp=$timestamp sampleCountAfter=${samples.length()}"
                )
            }
        } catch (error: Exception) {
            if (recoveryDiagnostics) {
                Log.e(
                    DIAGNOSTIC_TAG,
                    "SAMPLE_FAILURE timestamp=$timestamp " +
                        "exceptionClass=${error.javaClass.name} " +
                        "exceptionMessage=${error.message ?: "NONE"}",
                    error
                )
            }
            // A missing or failed metric never invalidates the session.
        }
    }

    private fun finishSession(
        sessionId: String,
        emulator: ForegroundEmulatorDetector.DetectedEmulator,
        startedAt: Long,
        endedAt: Long,
        endReason: String,
        samples: JSONArray
    ) {
        PerformanceSessionRecoveryStore.markSaving(
            context = this,
            sessionId = sessionId,
            emulatorName = emulator.name,
            emulatorPackage = emulator.packageName,
            startedAt = startedAt,
            endedAt = endedAt,
            endReason = endReason
        )
        currentState = PerformanceMonitorState.SAVING
        updateNotification(getString(R.string.notification_saving))

        try {
            val serialized = PerformanceSessionSerializer.serializeSession(
                sessionId = sessionId,
                emulatorName = emulator.name,
                emulatorPackage = emulator.packageName,
                startedAt = startedAt,
                endedAt = endedAt,
                endReason = endReason,
                samples = samples
            )
            PerformanceLastSessionStore.write(this, serialized)
        } catch (_: Exception) {
            currentError = PerformanceMonitorError.SESSION_SAVE_FAILED
            currentState = PerformanceMonitorState.ERROR
            // Keep SAVING recovery and samples. A retry finalizes this same session;
            // it never re-enters emulator monitoring after the session already ended.
            finishService()
            return
        }

        PerformanceSessionRecoveryStore.clear(this)
        currentError = null
        currentState = PerformanceMonitorState.COMPLETED
        finishService()
    }

    private fun requestStop() {
        if (!isRunning) {
            PerformanceSessionRecoveryStore.clear(this)
            currentState = PerformanceMonitorState.IDLE
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        manualStopRequested = true
        shouldRun = false
        currentState = PerformanceMonitorState.FINISHING
        updateNotification(getString(R.string.notification_stopping))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(buildOpenModulePendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.stop_session),
                buildStopPendingIntent()
            )
            .apply {
                if (currentSessionStartedAt > 0L) {
                    setWhen(currentSessionStartedAt)
                    setShowWhen(true)
                    setUsesChronometer(true)
                } else {
                    setShowWhen(false)
                }
            }
            .build()

    private fun buildOpenModulePendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            2201,
            Intent(this, PerformanceActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildStopPendingIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            2202,
            Intent(this, PerformanceMonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun finishService() {
        shouldRun = false
        isRunning = false
        currentEmulatorName = null
        currentSessionStartedAt = 0L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sleepSafely(milliseconds: Long) {
        try {
            Thread.sleep(milliseconds)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The foreground service and persisted recovery state survive task removal.
        // Explicit STOP remains the only user-driven cancellation path.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        shouldRun = false
        isRunning = false
        currentSessionStartedAt = 0L
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}