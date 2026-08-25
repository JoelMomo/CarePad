package com.joel.thordoctor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryEntry
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryRuntime
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryScanResult
import org.json.JSONArray
import org.json.JSONObject

object GameLibraryStorage {

    data class ScanResult(
        val folderName: String,
        val gameCount: Int,
        val scannedAt: Long,
        val games: List<GameEntry>
    )

    data class GameEntry(
        val name: String,
        val relativePath: String,
        val extension: String,
        val sizeBytes: Long
    )

    fun setRootFolder(
        context: Context,
        uri: Uri
    ): Boolean {

        val flags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION

        val oldUri =
            AppPreferences.getGameFolderUri(
                context
            )

        try {
            context.contentResolver
                .takePersistableUriPermission(
                    uri,
                    flags
                )
        } catch (_: SecurityException) {
            return false
        }

        val directory =
            DocumentFile.fromTreeUri(
                context,
                uri
            )

        if (
            directory == null ||
            !directory.exists() ||
            !directory.canRead()
        ) {
            if (
                oldUri == null ||
                oldUri != uri
            ) {
                try {
                    context.contentResolver
                        .releasePersistableUriPermission(
                            uri,
                            flags
                        )
                } catch (_: Exception) {
                }
            }

            return false
        }

        if (
            oldUri != null &&
            oldUri != uri
        ) {
            try {
                context.contentResolver
                    .releasePersistableUriPermission(
                        oldUri,
                        flags
                    )
            } catch (_: Exception) {
            }
        }

        AppPreferences.setGameFolderUri(
            context,
            uri
        )

        clearCachedScan(context)

        return true
    }

    fun rootFolderUri(
        context: Context
    ): Uri? =
        AppPreferences.getGameFolderUri(
            context
        )

    fun hasValidRootFolder(
        context: Context
    ): Boolean {

        val uri =
            rootFolderUri(context)
                ?: return false

        val directory =
            DocumentFile.fromTreeUri(
                context,
                uri
            )
                ?: return false

        return directory.exists() &&
            directory.canRead()
    }

    fun folderDisplayName(
        context: Context
    ): String? {

        val uri =
            rootFolderUri(context)
                ?: return null

        return DocumentFile.fromTreeUri(
            context,
            uri
        )
            ?.name
            ?.takeIf {
                it.isNotBlank()
            }
    }

    fun cachedGameCount(
        context: Context
    ): Int =
        GameLibraryRuntime.cachedGameCount(
            context
        )

    fun lastScanTimestamp(
        context: Context
    ): Long =
        GameLibraryRuntime.lastScanTimestamp(
            context
        )

    fun scan(
        context: Context
    ): ScanResult {

        val uri =
            rootFolderUri(context)
                ?: return emptyResult(
                    context
                )

        val root =
            DocumentFile.fromTreeUri(
                context,
                uri
            )

        if (
            root == null ||
            !root.exists() ||
            !root.canRead()
        ) {
            return emptyResult(
                context
            )
        }

        val runtimeResult =
            GameLibraryRuntime.scan(
                root
            )

        val result =
            runtimeResult.toFacade()

        // The user may change the selected folder while a large scan is still running.
        // In that case the old result must never overwrite the cache for the new folder.
        if (rootFolderUri(context) != uri) {
            return result
        }

        GameLibraryRuntime.persistScan(
            context,
            runtimeResult
        )

        refreshDiagnosticIfPresent(
            context
        )

        return result
    }

    fun buildDiagnosticJson(
        context: Context
    ): JSONObject {

        if (!hasValidRootFolder(context)) {
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
                ?.toFacade()

        if (cached == null) {
            return JSONObject().apply {
                put("configured", true)
                put(
                    "folderName",
                    folderDisplayName(context).orEmpty()
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

    private fun scanResultToJson(
        result: ScanResult
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

    private fun refreshDiagnosticIfPresent(
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

    private fun emptyResult(
        context: Context
    ): ScanResult {

        clearCachedScan(context)

        return ScanResult(
            folderName =
                folderDisplayName(context)
                    .orEmpty(),
            gameCount = 0,
            scannedAt =
                System.currentTimeMillis(),
            games =
                emptyList()
        )
    }

    private fun clearCachedScan(
        context: Context
    ) {
        GameLibraryRuntime.clearCachedScan(
            context
        )
    }

    private fun GameLibraryScanResult.toFacade(): ScanResult =
        ScanResult(
            folderName = folderName,
            gameCount = gameCount,
            scannedAt = scannedAt,
            games = games.map {
                it.toFacade()
            }
        )

    private fun GameLibraryEntry.toFacade(): GameEntry =
        GameEntry(
            name = name,
            relativePath = relativePath,
            extension = extension,
            sizeBytes = sizeBytes
        )
}
