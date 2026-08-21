package dev.carepad.module.performance

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.joel.thordoctor.core.emulator.ForegroundEmulatorDetector
import com.joel.thordoctor.modules.performance.PerformanceMetrics
import org.json.JSONObject
import java.util.Locale

class PerformanceActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var sessionText: TextView
    private lateinit var metricsText: TextView
    private lateinit var primaryButton: Button
    private lateinit var stopButton: Button
    private lateinit var summaryTitle: TextView
    private lateinit var summaryText: TextView
    private var pendingStartAfterNotificationPermission = false

    private val refreshRunnable = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())

        primaryButton.setOnClickListener {
            if (
                PerformanceMonitorService.currentError == PerformanceMonitorError.SESSION_SAVE_FAILED &&
                PerformanceMonitorService.hasPendingSave(this)
            ) {
                resumeSession()
            } else if (!ForegroundEmulatorDetector.hasUsageAccess(this)) {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } else {
                startSessionWithNotificationCheck()
            }
        }

        stopButton.setOnClickListener {
            ContextCompat.startForegroundService(
                this,
                Intent(this, PerformanceMonitorService::class.java)
                    .setAction(PerformanceMonitorService.ACTION_STOP)
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val hasRecovery = PerformanceMonitorService.hasRecoverableSession(this)
        val pendingSave = PerformanceMonitorService.hasPendingSave(this)
        val mayAutoResume =
            PerformanceMonitorService.currentError != PerformanceMonitorError.SESSION_SAVE_FAILED &&
                (pendingSave || ForegroundEmulatorDetector.hasUsageAccess(this))

        if (hasRecovery && mayAutoResume && !PerformanceMonitorService.isRunning) {
            resumeSession()
        }
        handler.post(refreshRunnable)
    }

    override fun onStop() {
        handler.removeCallbacks(refreshRunnable)
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS && pendingStartAfterNotificationPermission) {
            pendingStartAfterNotificationPermission = false
            startSession()
        }
    }

    private fun buildContent(): View {
        val padding = dp(24)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        container.addView(TextView(this).apply {
            text = getString(R.string.performance_title)
            textSize = 28f
        })
        container.addView(TextView(this).apply {
            text = getString(R.string.performance_intro)
            textSize = 16f
            setPadding(0, dp(8), 0, dp(20))
        })

        statusText = TextView(this).apply { textSize = 20f }
        sessionText = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(8), 0, dp(8))
        }
        metricsText = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(8), 0, dp(16))
        }
        primaryButton = Button(this)
        stopButton = Button(this).apply { text = getString(R.string.stop_session) }
        summaryTitle = TextView(this).apply {
            text = getString(R.string.last_session)
            textSize = 20f
            setPadding(0, dp(24), 0, dp(8))
        }
        summaryText = TextView(this).apply { textSize = 16f }

        container.addView(statusText)
        container.addView(sessionText)
        container.addView(metricsText)
        container.addView(primaryButton)
        container.addView(stopButton)
        container.addView(summaryTitle)
        container.addView(summaryText)

        return ScrollView(this).apply { addView(container) }
    }

    private fun render() {
        val hasUsageAccess = ForegroundEmulatorDetector.hasUsageAccess(this)
        val state = PerformanceMonitorService.stateForUi(this)
        val active = state in setOf(
            PerformanceMonitorState.WAITING_EMULATOR,
            PerformanceMonitorState.MONITORING,
            PerformanceMonitorState.FINISHING,
            PerformanceMonitorState.SAVING
        )
        val saveRetryPending =
            state == PerformanceMonitorState.ERROR &&
                PerformanceMonitorService.currentError == PerformanceMonitorError.SESSION_SAVE_FAILED &&
                PerformanceMonitorService.hasPendingSave(this)

        if (saveRetryPending) {
            statusText.text = stateLabel(state)
            sessionText.text = getString(R.string.save_failed)
            metricsText.text = liveMetrics(PerformanceMonitorService.latestSnapshotForUi())
            primaryButton.text = getString(R.string.retry_save)
            primaryButton.visibility = View.VISIBLE
            stopButton.visibility = View.GONE
        } else if (!hasUsageAccess && !active) {
            statusText.text = getString(R.string.usage_access_required)
            sessionText.text = getString(R.string.usage_access_explanation)
            metricsText.text = ""
            primaryButton.text = getString(R.string.open_usage_access)
            primaryButton.visibility = View.VISIBLE
            stopButton.visibility = View.GONE
        } else {
            statusText.text = stateLabel(state)
            sessionText.text = sessionDetails(state)
            metricsText.text = liveMetrics(PerformanceMonitorService.latestSnapshotForUi())
            primaryButton.text = getString(R.string.start_session)
            primaryButton.visibility = if (active) View.GONE else View.VISIBLE
            stopButton.visibility = if (active) View.VISIBLE else View.GONE
        }

        val lastSession = PerformanceLastSessionStore.load(this)
        if (lastSession == null) {
            summaryTitle.visibility = View.GONE
            summaryText.visibility = View.GONE
        } else {
            summaryTitle.visibility = View.VISIBLE
            summaryText.visibility = View.VISIBLE
            summaryText.text = summarize(lastSession)
        }
    }

    private fun stateLabel(state: PerformanceMonitorState): String = when (state) {
        PerformanceMonitorState.IDLE -> getString(R.string.state_ready)
        PerformanceMonitorState.WAITING_EMULATOR -> getString(R.string.state_waiting)
        PerformanceMonitorState.MONITORING -> getString(R.string.state_monitoring)
        PerformanceMonitorState.FINISHING -> getString(R.string.state_finishing)
        PerformanceMonitorState.SAVING -> getString(R.string.state_saving)
        PerformanceMonitorState.COMPLETED -> getString(R.string.state_completed)
        PerformanceMonitorState.ERROR -> getString(R.string.state_error)
    }

    private fun sessionDetails(state: PerformanceMonitorState): String {
        if (state == PerformanceMonitorState.WAITING_EMULATOR) {
            return getString(R.string.waiting_instruction)
        }

        if (state == PerformanceMonitorState.ERROR) {
            return when (PerformanceMonitorService.currentError) {
                PerformanceMonitorError.USAGE_ACCESS_REQUIRED ->
                    getString(R.string.usage_access_explanation)
                PerformanceMonitorError.SESSION_SAVE_FAILED ->
                    getString(R.string.save_failed)
                null -> getString(R.string.generic_error)
            }
        }

        val emulator = PerformanceMonitorService.emulatorNameForUi(this)
        val elapsed = PerformanceMonitorService.elapsedSecondsForUi(this)
        if (emulator.isNullOrBlank()) return getString(R.string.ready_explanation)

        return getString(
            R.string.session_details,
            emulator,
            formatDuration(elapsed)
        )
    }

    private fun liveMetrics(snapshot: PerformanceMetrics.Snapshot?): String {
        if (snapshot == null) return getString(R.string.metrics_waiting)

        val maximumTemperature = snapshot.temperatures.maxByOrNull { it.celsius }
        return listOf(
            getString(R.string.metric_cpu, percent(snapshot.cpuLoadPercent)),
            getString(R.string.metric_gpu, percent(snapshot.gpuBusyPercent)),
            getString(R.string.metric_ram, percent(snapshot.ramUsedPercent)),
            getString(
                R.string.metric_temperature,
                maximumTemperature?.let {
                    String.format(Locale.getDefault(), "%.1f °C (%s)", it.celsius, it.name)
                } ?: getString(R.string.unavailable)
            ),
            getString(R.string.metric_battery, snapshot.batteryLevel?.let { "$it %" } ?: getString(R.string.unavailable))
        ).joinToString("\n")
    }

    private fun summarize(root: JSONObject): String {
        val emulator = root.optJSONObject("emulator")?.optString("name")
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.unavailable)
        val duration = root.optDouble("durationSeconds", Double.NaN)
        val sampleCount = root.optInt("sampleCount", 0)
        val summary = root.optJSONObject("summary") ?: JSONObject()
        val cpu = average(summary.optJSONObject("cpuLoadPercent"))
        val gpu = average(summary.optJSONObject("gpuBusyPercent"))
        val ram = average(summary.optJSONObject("ramUsedPercent"))
        val maxTemp = maximumTemperature(summary.optJSONObject("temperatures"))
        val battery = summary.optJSONObject("battery")
        val batteryText = if (battery != null && battery.has("startPercent") && battery.has("endPercent")) {
            "${battery.optInt("startPercent")} % → ${battery.optInt("endPercent")} %"
        } else {
            getString(R.string.unavailable)
        }

        return listOf(
            getString(R.string.summary_emulator, emulator),
            getString(R.string.summary_duration, if (duration.isNaN()) getString(R.string.unavailable) else formatDuration(duration.toLong())),
            getString(R.string.summary_samples, sampleCount),
            getString(R.string.summary_cpu, percent(cpu)),
            getString(R.string.summary_gpu, percent(gpu)),
            getString(R.string.summary_ram, percent(ram)),
            getString(R.string.summary_temperature, maxTemp ?: getString(R.string.unavailable)),
            getString(R.string.summary_battery, batteryText)
        ).joinToString("\n")
    }

    private fun average(stats: JSONObject?): Double? =
        stats?.optDouble("average", Double.NaN)?.takeUnless { it.isNaN() }

    private fun maximumTemperature(temperatures: JSONObject?): String? {
        if (temperatures == null) return null
        var bestName: String? = null
        var bestValue: Double? = null
        val keys = temperatures.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val value = temperatures.optJSONObject(name)
                ?.optDouble("maximum", Double.NaN)
                ?.takeUnless { it.isNaN() }
                ?: continue
            if (bestValue == null || value > bestValue) {
                bestValue = value
                bestName = name
            }
        }
        return if (bestValue != null && bestName != null) {
            String.format(Locale.getDefault(), "%.1f °C (%s)", bestValue, bestName)
        } else null
    }

    private fun percent(value: Double?): String =
        value?.let { String.format(Locale.getDefault(), "%.1f %%", it) }
            ?: getString(R.string.unavailable)

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60L
        val remainder = seconds % 60L
        return String.format(Locale.getDefault(), "%d:%02d", minutes, remainder)
    }

    private fun startSessionWithNotificationCheck() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartAfterNotificationPermission = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            return
        }
        startSession()
    }

    private fun startSession() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, PerformanceMonitorService::class.java)
                .setAction(PerformanceMonitorService.ACTION_START)
        )
    }

    private fun resumeSession() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, PerformanceMonitorService::class.java)
                .setAction(PerformanceMonitorService.ACTION_RESUME)
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_NOTIFICATIONS = 4101
    }
}
