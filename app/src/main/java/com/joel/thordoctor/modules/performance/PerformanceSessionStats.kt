package com.joel.thordoctor.modules.performance

import org.json.JSONArray
import org.json.JSONObject

object PerformanceSessionStats {

    fun build(samples: JSONArray): JSONObject {
        if (samples.length() == 0) return JSONObject()

        return JSONObject().apply {
            put("cpuLoadPercent", numericStats(samples, "cpuLoadPercent"))
            put("gpuBusyPercent", numericStats(samples, "gpuBusyPercent"))
            put("gpuFrequencyMHz", numericStats(samples, "gpuFrequencyMHz"))
            put("ramUsedPercent", numericStats(samples, "ramUsedPercent"))
            put("batteryPowerW", numericStats(samples, "batteryPowerW"))
            put(
                "batteryTemperatureC",
                numericStats(samples, "batteryTemperatureC")
            )
            put("cpuFrequencyMHz", cpuFrequencyStats(samples))
            put("temperatures", thermalStats(samples))
            put("battery", batteryStats(samples))
        }
    }

    private fun numericStats(
        samples: JSONArray,
        key: String
    ): JSONObject {
        val values = mutableListOf<Double>()

        for (i in 0 until samples.length()) {
            val value =
                samples.getJSONObject(i)
                    .optDouble(key, Double.NaN)

            if (!value.isNaN()) values += value
        }

        return stats(values)
    }

    private fun cpuFrequencyStats(samples: JSONArray): JSONObject {
        val values = mutableListOf<Double>()

        for (i in 0 until samples.length()) {
            val frequencies =
                samples.getJSONObject(i)
                    .optJSONArray("cpuFrequenciesMHz")
                    ?: continue

            for (j in 0 until frequencies.length()) {
                val value = frequencies.optDouble(j, Double.NaN)
                if (!value.isNaN()) values += value
            }
        }

        return stats(values)
    }

    private fun thermalStats(samples: JSONArray): JSONObject {
        val groups =
            mutableMapOf<String, MutableList<Double>>()

        for (i in 0 until samples.length()) {
            val temperatures =
                samples.getJSONObject(i)
                    .optJSONArray("temperatures")
                    ?: continue

            for (j in 0 until temperatures.length()) {
                val reading = temperatures.getJSONObject(j)
                val name = reading.optString("name")
                val value = reading.optDouble("celsius", Double.NaN)

                if (name.isNotBlank() && !value.isNaN()) {
                    groups.getOrPut(name) { mutableListOf() }.add(value)
                }
            }
        }

        return JSONObject().apply {
            groups.forEach { (name, values) ->
                put(name, stats(values))
            }
        }
    }

    private fun batteryStats(samples: JSONArray): JSONObject {
        val first = samples.getJSONObject(0)
        val last = samples.getJSONObject(samples.length() - 1)

        val start =
            first.optInt("batteryLevel", -1)
                .takeIf { it >= 0 }

        val end =
            last.optInt("batteryLevel", -1)
                .takeIf { it >= 0 }

        return JSONObject().apply {
            if (start != null) put("startPercent", start)
            if (end != null) put("endPercent", end)
            if (start != null && end != null) {
                put("deltaPercent", end - start)
            }
        }
    }

    private fun stats(values: List<Double>): JSONObject {
        if (values.isEmpty()) return JSONObject()

        return JSONObject().apply {
            put("average", values.average())
            put("minimum", values.minOrNull())
            put("maximum", values.maxOrNull())
        }
    }
}
