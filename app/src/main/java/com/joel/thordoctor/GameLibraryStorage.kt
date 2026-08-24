package com.joel.thordoctor

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

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

    private data class PendingDirectory(
        val directory: DocumentFile,
        val relativePath: String
    )

    private const val CACHE_PREFERENCES =
        "thor_doctor_game_library"

    private const val KEY_LAST_COUNT =
        "last_count"

    private const val KEY_LAST_SCAN =
        "last_scan"

    private const val CACHE_FILENAME =
        "game_library_cache.json"

    private val gameExtensions =
        setOf(
            "3ds", "cia", "cci",
            "nsp", "xci", "nro",
            "iso", "cso", "chd", "pbp", "cue",
            "rvz", "wbfs", "gcz", "ciso",
            "wud", "wux", "rpx",
            "pkg", "vpk",
            "nds", "gba", "gbc", "gb",
            "z64", "n64", "v64",
            "nes", "fds", "sfc", "smc",
            "md", "gen", "32x", "sms", "gg",
            "pce", "sgx", "a26", "a52", "a78",
            "ngp", "ngc", "ws", "wsc",
            "zip", "7z"
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
        cache(context)
            .getInt(
                KEY_LAST_COUNT,
                0
            )

    fun lastScanTimestamp(
        context: Context
    ): Long =
        cache(context)
            .getLong(
                KEY_LAST_SCAN,
                0L
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

        val entries =
            mutableListOf<GameEntry>()

        scanDirectoryTree(
            root = root,
            destination = entries
        )

        val scannedAt =
            System.currentTimeMillis()

        val result =
            ScanResult(
                folderName =
                    root.name.orEmpty(),
                gameCount =
                    entries.size,
                scannedAt =
                    scannedAt,
                games =
                    entries.sortedBy {
                        it.relativePath.lowercase()
                    }
            )

        // The user may change the selected folder while a large scan is still running.
        // In that case the old result must never overwrite the cache for the new folder.
        if (rootFolderUri(context) != uri) {
            return result
        }

        writeCachedScan(
            context,
            result
        )

        cache(context)
            .edit()
            .putInt(
                KEY_LAST_COUNT,
                result.gameCount
            )
            .putLong(
                KEY_LAST_SCAN,
                result.scannedAt
            )
            .apply()

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
            readCachedScan(context)

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

    private fun scanDirectoryTree(
        root: DocumentFile,
        destination: MutableList<GameEntry>
    ) {

        val pending =
            ArrayDeque<PendingDirectory>()

        pending.add(
            PendingDirectory(
                directory = root,
                relativePath = ""
            )
        )

        while (pending.isNotEmpty()) {
            val current =
                pending.removeLast()

            val children =
                try {
                    current.directory.listFiles()
                } catch (_: Exception) {
                    emptyArray()
                }

            children.forEach { document ->
                val name =
                    document.name
                        ?: return@forEach

                val relativePath =
                    if (
                        current.relativePath.isBlank()
                    ) {
                        name
                    } else {
                        "${current.relativePath}/$name"
                    }

                when {
                    document.isDirectory -> {
                        pending.add(
                            PendingDirectory(
                                directory = document,
                                relativePath = relativePath
                            )
                        )
                    }

                    document.isFile -> {
                        val extension =
                            name
                                .substringAfterLast(
                                    '.',
                                    ""
                                )
                                .lowercase()

                        if (
                            extension in gameExtensions
                        ) {
                            destination +=
                                GameEntry(
                                    name = name,
                                    relativePath = relativePath,
                                    extension = extension,
                                    sizeBytes = document.length()
                                )
                        }
                    }
                }
            }
        }
    }

    private fun writeCachedScan(
        context: Context,
        result: ScanResult
    ) {
        cacheFile(context)
            .writeText(
                scanResultToJson(result)
                    .toString(),
                Charsets.UTF_8
            )
    }

    private fun readCachedScan(
        context: Context
    ): ScanResult? {

        val file =
            cacheFile(context)

        if (
            !file.exists() ||
            !file.canRead()
        ) {
            return null
        }

        return try {
            val root =
                JSONObject(
                    file.readText(
                        Charsets.UTF_8
                    )
                )

            val gamesJson =
                root.optJSONArray("games")
                    ?: JSONArray()

            val games =
                mutableListOf<GameEntry>()

            for (
                index in 0 until gamesJson.length()
            ) {
                val item =
                    gamesJson.optJSONObject(index)
                        ?: continue

                games +=
                    GameEntry(
                        name =
                            item.optString("name"),
                        relativePath =
                            item.optString("relativePath"),
                        extension =
                            item.optString("extension"),
                        sizeBytes =
                            item.optLong("sizeBytes", 0L)
                    )
            }

            ScanResult(
                folderName =
                    root.optString("folderName"),
                gameCount =
                    games.size,
                scannedAt =
                    root.optLong("scannedAt", 0L),
                games = games
            )

        } catch (_: Exception) {
            null
        }
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

        cache(context)
            .edit()
            .remove(KEY_LAST_COUNT)
            .remove(KEY_LAST_SCAN)
            .apply()

        cacheFile(context)
            .delete()
    }

    private fun cacheFile(
        context: Context
    ): File =
        File(
            context.filesDir,
            CACHE_FILENAME
        )

    private fun cache(
        context: Context
    ) =
        context.getSharedPreferences(
            CACHE_PREFERENCES,
            Context.MODE_PRIVATE
        )
}
