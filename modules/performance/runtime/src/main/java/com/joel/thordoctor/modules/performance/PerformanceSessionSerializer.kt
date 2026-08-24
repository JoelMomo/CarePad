package com.joel.thordoctor.modules.performance

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the stable JSON representation of performance samples and session documents.
 *
 * Storage, recovery and Android service lifecycle stay outside this serializer. The
 * legacy session schema is intentionally preserved for compatibility.
 */
object PerformanceSessionSerializer {

    const val SESSION_SCHEMA = "thor-doctor-session"
    const val SESSION_SCHEMA_VERSION = 2

    fun serializeSample(
        timestamp: Long,
        snapshot: PerformanceMetrics.Snapshot
    ): JSONObject =
        JSONObject().apply {
            put("timestamp", timestamp)
            putNullable("cpuLoadPercent", snapshot.cpuLoadPercent)
            put("cpuFrequenciesMHz", JSONArray(snapshot.cpuFrequenciesMHz))
            put("ramTotalMb", snapshot.ramTotalMb)
            put("ramAvailableMb", snapshot.ramAvailableMb)
            put("ramUsedPercent", snapshot.ramUsedPercent)
            putNullable("gpuFrequencyMHz", snapshot.gpuFrequencyMHz)
            putNullable("gpuBusyPercent", snapshot.gpuBusyPercent)
            putNullable("batteryLevel", snapshot.batteryLevel)
            putNullable("batteryTemperatureC", snapshot.batteryTemperatureC)
            putNullable("batteryVoltageMv", snapshot.batteryVoltageMv)
            putNullable("batteryCurrentUa", snapshot.batteryCurrentUa)
            putNullable("batteryPowerW", snapshot.batteryPowerW)
            put("thermalStatus", snapshot.thermalStatus)

            val temperatures = JSONArray()
            snapshot.temperatures.forEach { reading ->
                temperatures.put(
                    JSONObject()
                        .put("name", reading.name)
                        .put("celsius", reading.celsius)
                )
            }

            put("temperatures", temperatures)
        }

    fun serializeSession(
        sessionId: String,
        emulatorName: String,
        emulatorPackage: String,
        startedAt: Long,
        endedAt: Long,
        endReason: String,
        samples: JSONArray
    ): String {
        val root = JSONObject()

        root.put("schema", SESSION_SCHEMA)
        root.put("schemaVersion", SESSION_SCHEMA_VERSION)
        root.put("sessionId", sessionId)
        root.put(
            "emulator",
            JSONObject()
                .put("name", emulatorName)
                .put("package", emulatorPackage)
        )
        root.put("startedAt", formatTimestamp(startedAt))
        root.put("endedAt", formatTimestamp(endedAt))
        root.put("durationSeconds", (endedAt - startedAt) / 1000.0)
        root.put("endReason", endReason)
        root.put("sampleIntervalMs", PerformanceMonitoringPolicy.SAMPLE_INTERVAL_MS)
        root.put("sampleCount", samples.length())
        root.put("summary", PerformanceSessionStats.build(samples))
        root.put("samples", samples)

        return root.toString(2)
    }

    private fun formatTimestamp(timestamp: Long): String =
        SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            Locale.getDefault()
        ).format(Date(timestamp))

    private fun JSONObject.putNullable(
        key: String,
        value: Any?
    ) {
        if (value == null) {
            put(key, JSONObject.NULL)
        } else {
            put(key, value)
        }
    }
}
