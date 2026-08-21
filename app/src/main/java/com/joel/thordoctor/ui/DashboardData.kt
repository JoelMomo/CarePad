package com.joel.thordoctor.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.joel.thordoctor.GameLibraryStorage
import com.joel.thordoctor.R
import com.joel.thordoctor.core.diagnostics.CoreDiagnosticStorage
import com.joel.thordoctor.modules.performance.PerformanceSessionRecoveryStore
import org.json.JSONObject
import java.util.Locale

internal data class DiagnosticSummary(
    val device: String,
    val androidVersion: String,
    val emulatorsInstalled: Int,
    val readableConfigs: Int,
    val gameCount: Int
)

internal data class LiveMetrics(
    val cpuPercent: Double?,
    val gpuPercent: Double?,
    val ramPercent: Double?,
    val temperatureC: Double?
)

internal data class SessionSummary(
    val emulator: String,
    val durationSeconds: Double,
    val sampleCount: Int,
    val cpuAveragePercent: Double?,
    val gpuAveragePercent: Double?,
    val ramAveragePercent: Double?,
    val maximumTemperatureC: Double?
)

internal fun readSummary(context: Context): DiagnosticSummary {
    val root = JSONObject(
        CoreDiagnosticStorage.readText(
            context,
            CoreDiagnosticStorage.DIAGNOSTIC_FILENAME
        )
    )

    val device = root.getJSONObject("device")
    val emulators = root.getJSONArray("emulators")
    val configs = root.getJSONArray("configs")
    val gameLibrary = root.optJSONObject("gameLibrary")

    var installedCount = 0
    for (i in 0 until emulators.length()) {
        if (emulators.getJSONObject(i).optBoolean("installed", false)) {
            installedCount++
        }
    }

    var readableCount = 0
    for (i in 0 until configs.length()) {
        val config = configs.getJSONObject(i)
        if (
            config.optBoolean("exists", false) &&
            config.optBoolean("readable", false) &&
            !config.has("readError")
        ) {
            readableCount++
        }
    }

    val manufacturer = device.optString("manufacturer").trim()
    val model = device.optString("model").trim()
    val displayName = if (model.startsWith(manufacturer, ignoreCase = true)) {
        model
    } else {
        "$manufacturer $model".trim()
    }

    return DiagnosticSummary(
        device = displayName,
        androidVersion = device.optString("androidVersion"),
        emulatorsInstalled = installedCount,
        readableConfigs = readableCount,
        gameCount = gameLibrary?.optInt("count", -1)
            ?.takeIf { it >= 0 }
            ?: GameLibraryStorage.cachedGameCount(context)
    )
}

internal fun readLiveMetrics(context: Context): LiveMetrics? {
    val samples = PerformanceSessionRecoveryStore.readSamples(context)
    if (samples.length() == 0) return null

    val sample = samples.optJSONObject(samples.length() - 1) ?: return null
    val temperatures = sample.optJSONArray("temperatures")

    var maximumTemperature: Double? = null
    if (temperatures != null) {
        for (i in 0 until temperatures.length()) {
            val value = temperatures
                .optJSONObject(i)
                ?.optDouble("celsius", Double.NaN)
                ?.takeUnless { it.isNaN() }
                ?: continue

            maximumTemperature = maxOf(maximumTemperature ?: value, value)
        }
    }

    if (maximumTemperature == null) {
        maximumTemperature = sample
            .optDouble("batteryTemperatureC", Double.NaN)
            .takeUnless { it.isNaN() }
    }

    return LiveMetrics(
        cpuPercent = sample.optionalDouble("cpuLoadPercent"),
        gpuPercent = sample.optionalDouble("gpuBusyPercent"),
        ramPercent = sample.optionalDouble("ramUsedPercent"),
        temperatureC = maximumTemperature
    )
}

internal fun readSessionSummary(context: Context): SessionSummary {
    val root = JSONObject(
        CoreDiagnosticStorage.readText(
            context,
            CoreDiagnosticStorage.SESSION_FILENAME
        )
    )

    val emulator = root
        .getJSONObject("emulator")
        .optString("name")
        .ifBlank { "—" }

    val summary = root.optJSONObject("summary") ?: JSONObject()

    return SessionSummary(
        emulator = emulator,
        durationSeconds = root.optDouble("durationSeconds", 0.0),
        sampleCount = root.optInt("sampleCount", 0),
        cpuAveragePercent = summary.averageOf("cpuLoadPercent"),
        gpuAveragePercent = summary.averageOf("gpuBusyPercent"),
        ramAveragePercent = summary.averageOf("ramUsedPercent"),
        maximumTemperatureC = summary.maximumTemperature()
    )
}

private fun JSONObject.averageOf(key: String): Double? =
    optJSONObject(key)?.optionalDouble("average")

private fun JSONObject.maximumTemperature(): Double? {
    val temperatures = optJSONObject("temperatures") ?: return null
    val keys = temperatures.keys()
    var maximum: Double? = null

    while (keys.hasNext()) {
        val stats = temperatures.optJSONObject(keys.next()) ?: continue
        val value = stats.optionalDouble("maximum") ?: continue
        maximum = maxOf(maximum ?: value, value)
    }

    return maximum
}

private fun JSONObject.optionalDouble(key: String): Double? {
    val value = optDouble(key, Double.NaN)
    return value.takeUnless { it.isNaN() }
}

internal fun formatDuration(context: Context, seconds: Double): String {
    val totalSeconds = seconds.toInt().coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val remainingSeconds = totalSeconds % 60

    return when {
        hours > 0 -> context.getString(
            R.string.duration_hours,
            hours,
            minutes,
            remainingSeconds
        )
        minutes > 0 -> context.getString(
            R.string.duration_minutes,
            minutes,
            remainingSeconds
        )
        else -> context.getString(
            R.string.duration_seconds,
            remainingSeconds
        )
    }
}

internal fun formatLiveDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0L) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}

internal fun formatPercent(value: Double?): String =
    value?.let { String.format(Locale.getDefault(), "%.0f %%", it) } ?: "—"

internal fun formatTemperature(value: Double?): String =
    value?.let { String.format(Locale.getDefault(), "%.1f °C", it) } ?: "—"

internal fun shareDiagnostic(context: Context) {
    val uri = CoreDiagnosticStorage.shareUri(
        context,
        CoreDiagnosticStorage.DIAGNOSTIC_FILENAME
    ) ?: return

    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    context.startActivity(
        Intent.createChooser(
            intent,
            context.getString(R.string.share_diagnostic)
        )
    )
}

internal fun getDeviceDisplayName(): String {
    val manufacturer = Build.MANUFACTURER.trim()
    val model = Build.MODEL.trim()

    return if (model.startsWith(manufacturer, ignoreCase = true)) {
        model
    } else {
        "$manufacturer $model".trim()
    }
}

internal fun getAppVersionName(context: Context): String {
    return try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(0)
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }

        packageInfo.versionName ?: "—"
    } catch (_: Exception) {
        "—"
    }
}
