package com.joel.thordoctor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.joel.thordoctor.modules.performance.PerformanceMetrics
import com.joel.thordoctor.modules.performance.PerformanceMonitoringPhase
import com.joel.thordoctor.modules.performance.PerformanceMonitoringPolicy
import com.joel.thordoctor.modules.performance.PerformanceRecoveryState
import com.joel.thordoctor.modules.performance.PerformanceSessionRecoveryStore
import com.joel.thordoctor.modules.performance.PerformanceSessionSerializer
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class MonitorState {
    IDLE,
    WAITING_EMULATOR,
    MONITORING,
    FINISHING,
    GENERATING,
    COMPLETED,
    ERROR
}

enum class MonitorError {
    SESSION_SAVE_FAILED,
    DIAGNOSTIC_GENERATION_FAILED
}

class SessionMonitorService : Service() {

    companion object {

        const val ACTION_START =
            "com.joel.thordoctor.START_MONITOR"

        const val ACTION_STOP =
            "com.joel.thordoctor.STOP_MONITOR"

        const val ACTION_RESUME =
            "com.joel.thordoctor.RESUME_MONITOR"

        fun hasRecoverableSession(
            context: android.content.Context
        ): Boolean =
            PerformanceSessionRecoveryStore
                .hasRecoverableSession(
                    context
                )

        fun isActiveForUi(
            context: android.content.Context
        ): Boolean =
            isRunning ||
                    hasRecoverableSession(
                        context
                    )

        fun stateForUi(
            context: android.content.Context
        ): MonitorState {

            if (isRunning) {
                return currentState
            }

            return when (
                PerformanceSessionRecoveryStore
                    .load(context)
            ) {

                null ->
                    currentState

                PerformanceRecoveryState
                    .WaitingEmulator ->
                    MonitorState.WAITING_EMULATOR

                is PerformanceRecoveryState
                    .Monitoring ->
                    MonitorState.MONITORING

                is PerformanceRecoveryState
                    .Saving ->
                    currentState
            }
        }

        fun emulatorNameForUi(
            context: android.content.Context
        ): String? {

            if (isRunning) {
                return currentEmulatorName
            }

            return (
                    PerformanceSessionRecoveryStore
                        .load(context) as?
                            PerformanceRecoveryState.Monitoring
                    )
                ?.emulatorName
        }

        fun elapsedSecondsForUi(
            context: android.content.Context
        ): Long {

            if (isRunning) {
                return currentSessionElapsedSeconds
            }

            val startedAt =
                (
                        PerformanceSessionRecoveryStore
                            .load(context) as?
                                PerformanceRecoveryState.Monitoring
                        )
                    ?.startedAt
                    ?: return 0L

            return (
                    System.currentTimeMillis() -
                            startedAt
                    )
                .coerceAtLeast(0L) /
                    1_000L
        }

        private const val CHANNEL_ID =
            "thor_doctor_monitor"

        private const val NOTIFICATION_ID =
            1001

        @Volatile
        var isRunning =
            false
            private set

        @Volatile
        var currentState =
            MonitorState.IDLE
            private set

        @Volatile
        var currentEmulatorName: String? =
            null
            private set

        @Volatile
        var currentRemainingSeconds =
            0L
            private set

        @Volatile
        var currentError: MonitorError? =
            null
            private set

        @Volatile
        var currentSessionElapsedSeconds =
            0L
            private set

    }

    @Volatile
    private var shouldRun =
        false

    @Volatile
    private var manualStopRequested =
        false

    @Volatile
    private var currentSessionStartedAt =
        0L

    override fun onCreate() {

        super.onCreate()

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        return when (
            intent?.action
        ) {

            ACTION_START -> {

                if (!isRunning) {
                    startMonitor()
                }

                START_STICKY
            }

            ACTION_RESUME -> {

                if (!isRunning) {
                    resumePersistedMonitor()
                }

                if (isRunning) {
                    START_STICKY
                } else {
                    START_NOT_STICKY
                }
            }

            ACTION_STOP -> {

                requestStop()
                START_NOT_STICKY
            }

            null -> {

                if (!isRunning) {
                    resumePersistedMonitor()
                }

                if (isRunning) {
                    START_STICKY
                } else {
                    START_NOT_STICKY
                }
            }

            else ->
                START_NOT_STICKY
        }
    }

    private fun startMonitor() {

        try {

            DiagnosticStorage.delete(
                this,
                DiagnosticStorage.SESSION_FILENAME
            )

        } catch (_: Exception) {
        }

        manualStopRequested =
            false

        currentSessionElapsedSeconds =
            0L

        currentSessionStartedAt =
            0L

        currentEmulatorName =
            null

        currentRemainingSeconds =
            0L

        currentError =
            null

        shouldRun =
            true

        isRunning =
            true

        currentState =
            MonitorState.WAITING_EMULATOR

        PerformanceSessionRecoveryStore
            .markWaiting(
                this
            )

        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                getString(
                    R.string
                        .notification_waiting_emulator
                )
            )
        )

        Thread {

            monitorLoop()

        }.apply {

            name =
                "ThorDoctorSessionMonitor"

            start()
        }
    }

    private fun resumePersistedMonitor() {

        val recovery =
            PerformanceSessionRecoveryStore
                .load(this)

        if (recovery == null) {

            currentState =
                MonitorState.IDLE

            stopSelf()
            return
        }

        manualStopRequested =
            false

        shouldRun =
            true

        isRunning =
            true

        currentError =
            null

        when (recovery) {

            PerformanceRecoveryState
                .WaitingEmulator -> {

                currentSessionElapsedSeconds =
                    0L

                currentSessionStartedAt =
                    0L
                currentEmulatorName =
                    null

                currentRemainingSeconds =
                    0L

                currentState =
                    MonitorState.WAITING_EMULATOR

                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        getString(
                            R.string
                                .notification_waiting_emulator
                        )
                    )
                )

                Thread {

                    monitorLoop()

                }.apply {

                    name =
                        "ThorDoctorSessionMonitor"

                    start()
                }
            }

            is PerformanceRecoveryState
                .Monitoring -> {

                val emulator =
                    UsageDetector
                        .DetectedEmulator(
                            name = recovery.emulatorName,
                            packageName = recovery.emulatorPackage
                        )

                currentSessionStartedAt =
                    recovery.startedAt

                currentEmulatorName =
                    recovery.emulatorName

                currentRemainingSeconds =
                    0L

                currentSessionElapsedSeconds =
                    (
                            System.currentTimeMillis() -
                                    recovery.startedAt
                            )
                        .coerceAtLeast(0L) /
                            1_000L

                currentState =
                    MonitorState.MONITORING

                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(
                        getString(
                            R.string.notification_monitoring,
                            recovery.emulatorName
                        )
                    )
                )

                val samples =
                    PerformanceSessionRecoveryStore
                        .readSamples(this)

                Thread {

                    monitorEmulator(
                        emulator = emulator,
                        sessionId = recovery.sessionId,
                        sessionStartedAt = recovery.startedAt,
                        samples = samples
                    )

                }.apply {

                    name =
                        "ThorDoctorSessionMonitor"

                    start()
                }
            }

            is PerformanceRecoveryState
                .Saving -> {

                // SAVING is owned by the independent Performance APK.
                // The legacy host never creates this recovery state.
                PerformanceSessionRecoveryStore
                    .clear(this)

                shouldRun =
                    false

                isRunning =
                    false

                currentState =
                    MonitorState.IDLE

                stopSelf()
            }
        }
    }

    private fun monitorLoop() {

        val emulator =
            waitForEmulator()

        if (emulator == null) {

            if (manualStopRequested) {

                PerformanceSessionRecoveryStore
                    .clear(this)

                currentState =
                    MonitorState.IDLE

                finishService()
            }

            return
        }

        monitorEmulator(
            emulator
        )
    }

    private fun waitForEmulator():
            UsageDetector.DetectedEmulator? {

        while (shouldRun) {

            val emulator =
                UsageDetector
                    .currentEmulator(
                        this
                    )

            if (emulator != null) {
                return emulator
            }

            sleepSafely(
                PerformanceMonitoringPolicy.CHECK_INTERVAL_MS
            )
        }

        return null
    }

    private fun monitorEmulator(
        emulator:
        UsageDetector.DetectedEmulator
    ) {

        val sessionStartedAt =
            System.currentTimeMillis()

        val sessionId =
            SimpleDateFormat(
                "yyyyMMdd_HHmmss",
                Locale.US
            ).format(
                Date(
                    sessionStartedAt
                )
            )

        monitorEmulator(
            emulator = emulator,
            sessionId = sessionId,
            sessionStartedAt = sessionStartedAt,
            samples = JSONArray()
        )
    }

    private fun monitorEmulator(
        emulator:
        UsageDetector.DetectedEmulator,
        sessionId: String,
        sessionStartedAt: Long,
        samples: JSONArray
    ) {

        currentSessionStartedAt =
            sessionStartedAt

        currentEmulatorName =
            emulator.name

        currentRemainingSeconds =
            0L

        currentSessionElapsedSeconds =
            0L

        currentState =
            MonitorState.MONITORING

        PerformanceSessionRecoveryStore
            .markMonitoring(
                context = this,
                sessionId = sessionId,
                emulatorName = emulator.name,
                emulatorPackage = emulator.packageName,
                startedAt = sessionStartedAt
            )

        updateNotification(
            getString(
                R.string.notification_monitoring,
                emulator.name
            )
        )

        var nextSampleAt =
            0L

        val monitorStartedAt =
            System.currentTimeMillis()

        var lastForegroundPackage =
            if (
                UsageDetector
                    .currentEmulator(this)
                    ?.packageName ==
                emulator.packageName
            ) {

                emulator.packageName

            } else {

                packageName
            }

        var lastForegroundEventTime =
            PerformanceMonitoringPolicy
                .usageCursorFor(
                    monitorStartedAt
                )

        var usageCursor =
            PerformanceMonitoringPolicy
                .usageCursorFor(
                    monitorStartedAt
                )

        var awaySince: Long? =
            null

        var endReason =
            "unknown"

        while (shouldRun) {

            val now =
                System.currentTimeMillis()

            val foregroundEvent =
                UsageDetector
                    .latestForegroundEvent(
                        this,
                        usageCursor
                    )

            usageCursor =
                PerformanceMonitoringPolicy
                    .usageCursorFor(
                        now
                    )

            if (
                foregroundEvent != null &&
                foregroundEvent.timestamp >
                lastForegroundEventTime
            ) {

                lastForegroundEventTime =
                    foregroundEvent.timestamp

                lastForegroundPackage =
                    foregroundEvent.packageName
            }

            val decision =
                PerformanceMonitoringPolicy
                    .evaluateForeground(
                        now = now,
                        sessionStartedAt =
                            sessionStartedAt,
                        emulatorPackage =
                            emulator.packageName,
                        hostPackage =
                            packageName,
                        foregroundPackage =
                            lastForegroundPackage,
                        awaySince =
                            awaySince
                    )

            awaySince =
                decision.awaySince

            currentRemainingSeconds =
                decision.remainingSeconds

            currentSessionElapsedSeconds =
                decision.elapsedSeconds

            when (
                decision.phase
            ) {

                PerformanceMonitoringPhase
                    .MONITORING -> {

                    if (
                        currentState !=
                        MonitorState.MONITORING
                    ) {

                        currentState =
                            MonitorState.MONITORING

                        updateNotification(
                            getString(
                                R.string.notification_monitoring,
                                emulator.name
                            )
                        )

                    } else {

                        currentState =
                            MonitorState.MONITORING
                    }
                }

                PerformanceMonitoringPhase
                    .FINISHING -> {

                    currentState =
                        MonitorState.FINISHING

                    updateNotification(
                        getString(
                            R.string.notification_background,
                            currentRemainingSeconds
                        )
                    )

                    if (
                        decision.shouldFinish
                    ) {

                        endReason =
                            PerformanceMonitoringPolicy
                                .END_FOREGROUND_TIMEOUT

                        break
                    }
                }
            }

            if (
                decision.emulatorInForeground &&
                now >= nextSampleAt
            ) {

                captureSample(
                    samples,
                    now
                )

                nextSampleAt =
                    now +
                            PerformanceMonitoringPolicy
                                .SAMPLE_INTERVAL_MS
            }

            sleepSafely(
                PerformanceMonitoringPolicy
                    .CHECK_INTERVAL_MS
            )
        }

        if (
            !shouldRun &&
            !manualStopRequested
        ) {
            return
        }

        if (manualStopRequested) {

            endReason =
                PerformanceMonitoringPolicy
                    .END_MANUAL_STOP
        }

        val sessionEndedAt =
            PerformanceMonitoringPolicy
                .sessionEndedAt(
                    endReason =
                        endReason,
                    awaySince =
                        awaySince,
                    now =
                        System.currentTimeMillis()
                )

        currentSessionElapsedSeconds =
            (
                    sessionEndedAt -
                            sessionStartedAt
                    )
                .coerceAtLeast(0L) /
                    1_000L

        currentRemainingSeconds =
            0L

        finishSession(
            sessionId =
                sessionId,

            emulator =
                emulator,

            startedAt =
                sessionStartedAt,

            endedAt =
                sessionEndedAt,

            endReason =
                endReason,

            samples =
                samples
        )
    }

    private fun captureSample(
        samples: JSONArray,
        timestamp: Long
    ) {

        try {

            val snapshot =
                PerformanceMetrics.capture(
                    this
                )

            val sample =
                PerformanceSessionSerializer
                    .serializeSample(
                        timestamp = timestamp,
                        snapshot = snapshot
                    )

            samples.put(
                sample
            )

            PerformanceSessionRecoveryStore
                .appendSample(
                    context = this,
                    sample = sample
                )

        } catch (_: Exception) {

            // Una muestra fallida no invalida la sesión.
        }
    }

    private fun finishSession(
        sessionId: String,
        emulator:
        UsageDetector.DetectedEmulator,
        startedAt: Long,
        endedAt: Long,
        endReason: String,
        samples: JSONArray
    ) {

        currentState =
            MonitorState.GENERATING

        updateNotification(
            getString(
                R.string.notification_generating
            )
        )

        try {

            writeSession(
                sessionId =
                    sessionId,

                emulator =
                    emulator,

                startedAt =
                    startedAt,

                endedAt =
                    endedAt,

                endReason =
                    endReason,

                samples =
                    samples
            )

        } catch (_: Exception) {

            currentError =
                MonitorError.SESSION_SAVE_FAILED

            currentState =
                MonitorState.ERROR

            PerformanceSessionRecoveryStore
                .clear(this)

            finishService()

            return
        }

        PerformanceSessionRecoveryStore
            .clear(this)

        try {

            DiagnosticStorage.delete(
                this,
                DiagnosticStorage.DIAGNOSTIC_FILENAME
            )

        } catch (_: Exception) {
        }

        try {

            DiagnosticEngine.generate(
                this
            )

            currentError =
                null

            currentState =
                MonitorState.COMPLETED

        } catch (_: Exception) {

            currentError =
                MonitorError
                    .DIAGNOSTIC_GENERATION_FAILED

            currentState =
                MonitorState.ERROR
        }

        finishService()
    }

    private fun writeSession(
        sessionId: String,
        emulator:
        UsageDetector.DetectedEmulator,
        startedAt: Long,
        endedAt: Long,
        endReason: String,
        samples: JSONArray
    ) {

        DiagnosticStorage.writeText(
            context =
                this,

            filename =
                DiagnosticStorage.SESSION_FILENAME,

            text =
                PerformanceSessionSerializer
                    .serializeSession(
                        sessionId = sessionId,
                        emulatorName = emulator.name,
                        emulatorPackage = emulator.packageName,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        endReason = endReason,
                        samples = samples
                    )
        )
    }

    private fun requestStop() {

        if (!isRunning) {

            PerformanceSessionRecoveryStore
                .clear(this)

            currentState =
                MonitorState.IDLE

            stopForeground(
                STOP_FOREGROUND_REMOVE
            )

            stopSelf()

            return
        }

        manualStopRequested =
            true

        shouldRun =
            false

        currentRemainingSeconds =
            0L

        currentState =
            MonitorState.FINISHING

        updateNotification(
            getString(
                R.string.notification_finishing
            )
        )
    }

    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.O
        ) {
            return
        }

        val channel =
            NotificationChannel(
                CHANNEL_ID,

                getString(
                    R.string.notification_channel_name
                ),

                NotificationManager.IMPORTANCE_LOW
            ).apply {

                description =
                    getString(
                        R.string
                            .notification_channel_description
                    )
            }

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.createNotificationChannel(
            channel
        )
    }

    private fun buildNotification(
        text: String
    ) =
        NotificationCompat
            .Builder(
                this,
                CHANNEL_ID
            )
            .setContentTitle(
                getString(
                    R.string.app_name
                )
            )
            .setContentText(
                text
            )
            .setSmallIcon(
                R.mipmap.ic_launcher
            )
            .setContentIntent(
                buildOpenAppPendingIntent()
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(
                NotificationCompat.CATEGORY_SERVICE
            )
            .addAction(
                R.mipmap.ic_launcher,
                getString(
                    R.string.stop_diagnostic
                ),
                buildStopPendingIntent()
            )
            .apply {

                val sessionStartedAt =
                    currentSessionStartedAt

                if (
                    sessionStartedAt > 0L
                ) {

                    setWhen(
                        sessionStartedAt
                    )

                    setShowWhen(
                        true
                    )

                    setUsesChronometer(
                        true
                    )

                } else {

                    setShowWhen(
                        false
                    )
                }
            }
            .build()

    private fun buildOpenAppPendingIntent():
            PendingIntent {

        val intent =
            Intent(
                this,
                MainActivity::class.java
            ).apply {

                flags =
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

        return PendingIntent.getActivity(
            this,
            2001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun buildStopPendingIntent():
            PendingIntent {

        val intent =
            Intent(
                this,
                SessionMonitorService::class.java
            ).apply {

                action =
                    ACTION_STOP
            }

        return PendingIntent.getService(
            this,
            2002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification(
        text: String
    ) {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            NOTIFICATION_ID,
            buildNotification(
                text
            )
        )
    }

    private fun finishService() {

        shouldRun =
            false

        isRunning =
            false

        currentRemainingSeconds =
            0L

        currentSessionStartedAt =
            0L

        currentEmulatorName =
            null

        stopForeground(
            STOP_FOREGROUND_REMOVE
        )

        stopSelf()
    }

    private fun sleepSafely(
        milliseconds: Long
    ) {

        try {

            Thread.sleep(
                milliseconds
            )

        } catch (_: InterruptedException) {

            Thread
                .currentThread()
                .interrupt()
        }
    }

    override fun onTaskRemoved(
        rootIntent: Intent?
    ) {

        PerformanceSessionRecoveryStore
            .clear(this)

        if (isRunning) {
            requestStop()
        }

        super.onTaskRemoved(
            rootIntent
        )
    }

    override fun onDestroy() {

        shouldRun =
            false

        isRunning =
            false

        currentSessionStartedAt =
            0L

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? =
        null
}