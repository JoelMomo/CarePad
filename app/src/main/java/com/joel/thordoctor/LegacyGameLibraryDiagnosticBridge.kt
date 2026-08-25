package com.joel.thordoctor

import android.content.Context
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryRuntime
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryScanResult
import org.json.JSONArray
import org.json.JSONObject

internal object LegacyGameLibraryDiagnosticBridge {

    fun buildDiagnosticJson(
        context: Context
    ): JSONObject {

        if (!GameLibraryStorage.hasValidRootFolder(context)) {
            return JSONObject().apply {
                put("configured", false)
                put("count", 0)
                put("games", JSONArray())
            }
        }

        val cached =
            GameLibraryRuntime.readCachedScan(
                context
            )

        if (cached == null) {
            return JSONObject().apply {
                put("configured", true)
                put(
                    "folderName",
                    GameLibraryStorage
                        .folderDisplayName(context)
                        .orEmpty()
                )
                put("scannedAt", JSONObject.NULL)
                put("count", 0)
                put("games", JSONArray())
            }
        }

        return scanResultToJson(
            cached
        )
    }

    fun refreshIfPresent(
        context: Context
    ) {

        val info =
            DiagnosticStorage.documentInfo(
                context,
                DiagnosticStorage.DIAGNOSTIC_FILENAME
            )
                ?: return

        if (info.sizeBytes <= 0L) {
            return
        }

        try {
            val diagnostic =
                JSONObject(
                    DiagnosticStorage.readText(
                        context,
                        DiagnosticStorage.DIAGNOSTIC_FILENAME
                    )
                )

            diagnostic.put(
                "schemaVersion",
                maxOf(
                    diagnostic.optInt(
                        "schemaVersion",
                        3
                    ),
                    3
                )
            )

            diagnostic.put(
                "gameLibrary",
                buildDiagnosticJson(context)
            )

            DiagnosticStorage.writeText(
                context = context,
                filename =
                    DiagnosticStorage.DIAGNOSTIC_FILENAME,
                text =
                    diagnostic.toString(2)
            )

        } catch (_: Exception) {
        }
    }

    private fun scanResultToJson(
        result: GameLibraryScanResult
    ): JSONObject {

        val games =
            JSONArray()

        result.games.forEach { game ->
            games.put(
                JSONObject().apply {
                    put("name", game.name)
                    put("relativePath", game.relativePath)
                    put("extension", game.extension)
                    put("sizeBytes", game.sizeBytes)
                }
            )
        }

        return JSONObject().apply {
            put("configured", true)
            put("folderName", result.folderName)
            put("scannedAt", result.scannedAt)
            put("count", result.gameCount)
            put("games", games)
        }
    }
}
