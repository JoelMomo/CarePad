package com.joel.thordoctor.modules.performance

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File
import kotlin.math.abs

object PerformanceMetrics {

    private const val CPU_COUNT_LIMIT = 16
    private const val CPU_LOAD_SAMPLE_MS = 250L
    private const val THERMAL_MIN_C = 1.0
    private const val THERMAL_MAX_C = 130.0

    data class ThermalReading(
        val name: String,
        val celsius: Double
    )

    data class Snapshot(
        val cpuLoadPercent: Double?,
        val cpuFrequenciesMHz: List<Int>,
        val ramTotalMb: Long,
        val ramAvailableMb: Long,
        val ramUsedPercent: Double,
        val batteryLevel: Int?,
        val batteryTemperatureC: Double?,
        val batteryVoltageMv: Int?,
        val batteryCurrentUa: Int?,
        val batteryPowerW: Double?,
        val thermalStatus: String,
        val gpuFrequencyMHz: Double?,
        val gpuBusyPercent: Double?,
        val temperatures: List<ThermalReading>
    )

    private data class BatteryData(
        val level: Int?,
        val temperatureC: Double?,
        val voltageMv: Int?,
        val currentUa: Int?,
        val powerW: Double?
    )

    private data class CpuTimes(
        val total: Long,
        val idle: Long
    )

    fun capture(context: Context): Snapshot {
        val memory = readMemory(context)
        val battery = readBattery(context)

        return Snapshot(
            cpuLoadPercent = readCpuLoad(),
            cpuFrequenciesMHz = readCpuFrequencies(),
            ramTotalMb = memory.first,
            ramAvailableMb = memory.second,
            ramUsedPercent = memory.third,
            batteryLevel = battery.level,
            batteryTemperatureC = battery.temperatureC,
            batteryVoltageMv = battery.voltageMv,
            batteryCurrentUa = battery.currentUa,
            batteryPowerW = battery.powerW,
            thermalStatus = readThermalStatus(context),
            gpuFrequencyMHz = readGpuFrequency(),
            gpuBusyPercent = readGpuBusy(),
            temperatures = readThermalZones()
        )
    }

    private fun readMemory(context: Context): Triple<Long, Long, Double> {
        val manager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)

        val totalMb = info.totalMem / 1024L / 1024L
        val availableMb = info.availMem / 1024L / 1024L
        val usedPercent =
            if (info.totalMem > 0L) {
                ((info.totalMem - info.availMem).toDouble() / info.totalMem.toDouble()) * 100.0
            } else {
                0.0
            }

        return Triple(totalMb, availableMb, usedPercent)
    }

    private fun readBattery(context: Context): BatteryData {
        val intent =
            context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

        val level =
            intent
                ?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                ?.takeIf { it >= 0 }

        val temperatureC =
            intent
                ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                ?.takeIf { it != Int.MIN_VALUE }
                ?.div(10.0)

        val voltageMv =
            intent
                ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                ?.takeIf { it > 0 }

        val manager =
            context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        val currentUa =
            manager
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                .takeIf { it != Int.MIN_VALUE }

        val powerW =
            if (currentUa != null && voltageMv != null) {
                abs(currentUa.toDouble() * voltageMv.toDouble()) / 1_000_000_000.0
            } else {
                null
            }

        return BatteryData(
            level = level,
            temperatureC = temperatureC,
            voltageMv = voltageMv,
            currentUa = currentUa,
            powerW = powerW
        )
    }

    private fun readThermalStatus(context: Context): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "No disponible"
        }

        val manager =
            context.getSystemService(Context.POWER_SERVICE) as PowerManager

        return when (manager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Sin limitación"
            PowerManager.THERMAL_STATUS_LIGHT -> "Ligera"
            PowerManager.THERMAL_STATUS_MODERATE -> "Moderada"
            PowerManager.THERMAL_STATUS_SEVERE -> "Severa"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Crítica"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergencia"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Apagado"
            else -> "Desconocido"
        }
    }

    private fun readCpuLoad(): Double? {
        val first = readCpuTimes() ?: return null

        try {
            Thread.sleep(CPU_LOAD_SAMPLE_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }

        val second = readCpuTimes() ?: return null
        val totalDelta = second.total - first.total
        val idleDelta = second.idle - first.idle

        if (totalDelta <= 0L) return null

        return (1.0 - idleDelta.toDouble() / totalDelta.toDouble()) * 100.0
    }

    private fun readCpuTimes(): CpuTimes? {
        return try {
            val line =
                File("/proc/stat")
                    .useLines { lines -> lines.firstOrNull() }
                    ?: return null

            val parts = line.trim().split(Regex("\\s+"))
            if (parts.firstOrNull() != "cpu") return null

            val values = parts.drop(1).mapNotNull { it.toLongOrNull() }
            if (values.size < 4) return null

            val idle = values[3] + values.getOrElse(4) { 0L }
            CpuTimes(total = values.sum(), idle = idle)
        } catch (_: Exception) {
            null
        }
    }

    private fun readCpuFrequencies(): List<Int> {
        val result = mutableListOf<Int>()

        for (cpu in 0 until CPU_COUNT_LIMIT) {
            val frequency =
                safeReadLong(
                    File(
                        "/sys/devices/system/cpu/" +
                            "cpu$cpu/cpufreq/" +
                            "scaling_cur_freq"
                    )
                ) ?: continue

            result += (frequency / 1000L).toInt()
        }

        return result
    }

    private fun readGpuFrequency(): Double? {
        val paths =
            listOf(
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
                "/sys/class/kgsl/kgsl-3d0/gpuclk"
            )

        for (path in paths) {
            val value = safeReadLong(File(path)) ?: continue

            return when {
                value > 10_000_000L -> value / 1_000_000.0
                value > 10_000L -> value / 1000.0
                else -> value.toDouble()
            }
        }

        return null
    }

    private fun readGpuBusy(): Double? {
        val text =
            safeReadText(
                File("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
            ) ?: return null

        return Regex("[0-9]+(?:\\.[0-9]+)?")
            .find(text)
            ?.value
            ?.toDoubleOrNull()
    }

    private fun readThermalZones(): List<ThermalReading> {
        val zones =
            File("/sys/class/thermal")
                .listFiles()
                ?.filter { it.name.startsWith("thermal_zone") }
                ?: return emptyList()

        val readings = mutableListOf<ThermalReading>()

        zones.forEach { zone ->
            val name =
                safeReadText(File(zone, "type"))
                    ?.lowercase()
                    ?: return@forEach

            val raw = safeReadLong(File(zone, "temp")) ?: return@forEach
            val celsius = normalizeTemperature(raw)

            if (celsius in THERMAL_MIN_C..THERMAL_MAX_C) {
                readings += ThermalReading(name = name, celsius = celsius)
            }
        }

        val result = mutableListOf<ThermalReading>()

        addThermalMaximum(result, readings, "cpuMax") { name ->
            name.startsWith("cpu-") || name.startsWith("cpuss")
        }
        addThermalMaximum(result, readings, "gpuMax") { name ->
            name.startsWith("gpu") || name.startsWith("gpuss")
        }
        addThermalMaximum(result, readings, "soc") { name ->
            name == "soc" || name.contains("soc-")
        }
        addThermalMaximum(result, readings, "ddr") { name ->
            name == "ddr" || name.startsWith("ddr")
        }
        addThermalMaximum(result, readings, "usb") { name ->
            name == "usb" || name.startsWith("usb-")
        }

        return result
    }

    private fun normalizeTemperature(raw: Long): Double {
        return when {
            raw > 10_000L -> raw / 1000.0
            raw > 200L -> raw / 10.0
            else -> raw.toDouble()
        }
    }

    private fun addThermalMaximum(
        destination: MutableList<ThermalReading>,
        readings: List<ThermalReading>,
        name: String,
        predicate: (String) -> Boolean
    ) {
        val maximum =
            readings
                .asSequence()
                .filter { predicate(it.name) }
                .maxOfOrNull { it.celsius }
                ?: return

        destination += ThermalReading(name = name, celsius = maximum)
    }

    private fun safeReadText(file: File): String? {
        return try {
            if (!file.exists() || !file.canRead()) {
                null
            } else {
                file.readText().trim().takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun safeReadLong(file: File): Long? =
        safeReadText(file)?.toLongOrNull()
}
