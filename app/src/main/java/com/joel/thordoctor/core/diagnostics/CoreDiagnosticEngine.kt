package com.joel.thordoctor.core.diagnostics

import android.content.Context
import android.os.Environment
import com.joel.thordoctor.core.device.DeviceEngine
import com.joel.thordoctor.core.emulator.EmulatorEngine
import com.joel.thordoctor.core.emulator.EmulatorRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CoreDiagnosticEngine {

    fun generate(context: Context) {
        val storageRoot = storageRoot()

        val root = JSONObject().apply {
            put("schema", "docthor-diagnostic")
            put("schemaVersion", 4)
            put("generatedAt", formatTimestamp(System.currentTimeMillis()))
            put("device", buildDeviceInfo())
            put("emulators", buildEmulatorInfo(context))
            put("configs", buildConfigInfo(storageRoot))
            put("session", readLatestSession(context))
        }

        CoreDiagnosticStorage.writeText(
            context = context,
            filename = CoreDiagnosticStorage.DIAGNOSTIC_FILENAME,
            text = root.toString(2)
        )
    }

    private fun storageRoot(): File {
        @Suppress("DEPRECATION")
        return Environment.getExternalStorageDirectory()
    }

    private fun formatTimestamp(timestamp: Long): String =
        SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            Locale.getDefault()
        ).format(Date(timestamp))

    private fun readLatestSession(context: Context): Any {
        val info = CoreDiagnosticStorage.documentInfo(
            context,
            CoreDiagnosticStorage.SESSION_FILENAME
        ) ?: return JSONObject.NULL

        if (info.sizeBytes <= 0L) return JSONObject.NULL

        return try {
            JSONObject(
                CoreDiagnosticStorage.readText(
                    context,
                    CoreDiagnosticStorage.SESSION_FILENAME
                )
            )
        } catch (_: Exception) {
            JSONObject.NULL
        }
    }

    private fun buildDeviceInfo(): JSONObject {
        val profile = DeviceEngine.currentProfile()

        return JSONObject().apply {
            put("manufacturer", profile.manufacturer)
            put("model", profile.model)
            put("device", profile.device)
            put("androidVersion", profile.androidVersion)
            put("sdk", profile.sdk)
            put("hardware", profile.hardware)
            put("board", profile.board)
        }
    }

    private fun buildEmulatorInfo(context: Context): JSONArray {
        val result = JSONArray()

        EmulatorRegistry.definitions.forEach { definition ->
            val installed = EmulatorEngine.findInstalled(context, definition)

            val item = JSONObject().apply {
                put("name", definition.displayName)
                put(
                    "package",
                    installed?.packageName ?: definition.packageNames.first()
                )
                put("installed", installed != null)

                if (installed != null) {
                    put("version", installed.versionName)
                }

                if (definition.packageNames.size > 1) {
                    put("packageAliases", JSONArray(definition.packageNames))
                }
            }

            result.put(item)
        }

        return result
    }

    private fun buildConfigInfo(storageRoot: File): JSONArray {
        val androidData = File(storageRoot, "Android/data")

        val configs = listOf(
            ConfigTarget(
                name = "RetroArch",
                file = File(
                    androidData,
                    "com.retroarch.aarch64/files/retroarch.cfg"
                ),
                reportedPath =
                    "Android/data/com.retroarch.aarch64/files/retroarch.cfg",
                interestingKeys = listOf(
                    "video_driver",
                    "audio_driver",
                    "video_vsync",
                    "video_fullscreen"
                )
            ),
            ConfigTarget(
                name = "Dolphin Graphics",
                file = File(
                    androidData,
                    "org.dolphinemu.dolphinemu/files/Config/GFX.ini"
                ),
                reportedPath =
                    "Android/data/org.dolphinemu.dolphinemu/files/Config/GFX.ini",
                interestingKeys = listOf(
                    "Backend",
                    "InternalResolution",
                    "VSync"
                )
            ),
            ConfigTarget(
                name = "Dolphin General",
                file = File(
                    androidData,
                    "org.dolphinemu.dolphinemu/files/Config/Dolphin.ini"
                ),
                reportedPath =
                    "Android/data/org.dolphinemu.dolphinemu/files/Config/Dolphin.ini",
                interestingKeys = listOf(
                    "CPUCore",
                    "EnableCheats"
                )
            ),
            ConfigTarget(
                name = "Vita3K",
                file = File(
                    androidData,
                    "org.vita3k.emulator/files/config.yml"
                ),
                reportedPath =
                    "Android/data/org.vita3k.emulator/files/config.yml"
            ),
            ConfigTarget(
                name = "Eden",
                file = File(
                    androidData,
                    "dev.eden.eden_emulator/files/config/config.ini"
                ),
                reportedPath =
                    "Android/data/dev.eden.eden_emulator/files/config/config.ini"
            ),
            ConfigTarget(
                name = "Azahar",
                file = File(storageRoot, "AzaharDatos/config/config.ini"),
                reportedPath = "AzaharDatos/config/config.ini"
            ),
            ConfigTarget(
                name = "PPSSPP",
                file = File(
                    storageRoot,
                    "PPSSPPDatos/PSP/SYSTEM/ppsspp.ini"
                ),
                reportedPath =
                    "PPSSPPDatos/PSP/SYSTEM/ppsspp.ini"
            )
        )

        return JSONArray().apply {
            configs.forEach { target ->
                put(readConfig(target))
            }
        }
    }

    private fun readConfig(target: ConfigTarget): JSONObject {
        val exists = target.file.exists()
        val readable = target.file.canRead()

        val result = JSONObject().apply {
            put("name", target.name)
            put("path", target.reportedPath)
            put("exists", exists)
            put("readable", readable)
        }

        if (!exists || !readable) return result

        try {
            val text = target.file.readText(Charsets.UTF_8)
            result.put("sizeBytes", target.file.length())
            result.put("lineCount", text.lineSequence().count())

            val detectedValues = JSONObject()
            target.interestingKeys.forEach { key ->
                findValue(text, key)?.let { value ->
                    detectedValues.put(key, value)
                }
            }

            result.put("detectedValues", detectedValues)
        } catch (e: Exception) {
            result.put("readError", e.javaClass.simpleName)
        }

        return result
    }

    private fun findValue(text: String, key: String): String? {
        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()

            if (
                line.isBlank() ||
                line.startsWith("#") ||
                line.startsWith(";")
            ) {
                return@forEach
            }

            val separator = when {
                '=' in line -> "="
                ':' in line -> ":"
                else -> return@forEach
            }

            val parts = line.split(separator, limit = 2)
            if (
                parts.size == 2 &&
                parts[0].trim().equals(key, ignoreCase = true)
            ) {
                return parts[1].trim().trim('"')
            }
        }

        return null
    }

    private data class ConfigTarget(
        val name: String,
        val file: File,
        val reportedPath: String,
        val interestingKeys: List<String> = emptyList()
    )
}
