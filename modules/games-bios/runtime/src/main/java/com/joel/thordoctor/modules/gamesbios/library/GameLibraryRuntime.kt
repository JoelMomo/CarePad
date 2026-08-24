package com.joel.thordoctor.modules.gamesbios.library

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque

data class GameLibraryScanResult(
    val folderName: String,
    val gameCount: Int,
    val scannedAt: Long,
    val games: List<GameLibraryEntry>
)

data class GameLibraryEntry(
    val name: String,
    val relativePath: String,
    val extension: String,
    val sizeBytes: Long
)

object GameLibraryRuntime {

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
        root: DocumentFile
    ): GameLibraryScanResult {

        val entries =
            mutableListOf<GameLibraryEntry>()

        scanDirectoryTree(
            root = root,
            destination = entries
        )

        val scannedAt =
            System.currentTimeMillis()

        return GameLibraryScanResult(
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
    }

    fun persistScan(
        context: Context,
        result: GameLibraryScanResult
    ) {
        cacheFile(context)
            .writeText(
                scanResultToJson(result)
                    .toString(),
                Charsets.UTF_8
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
    }

    fun readCachedScan(
        context: Context
    ): GameLibraryScanResult? {

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
                mutableListOf<GameLibraryEntry>()

            for (
                index in 0 until gamesJson.length()
            ) {
                val item =
                    gamesJson.optJSONObject(index)
                        ?: continue

                games +=
                    GameLibraryEntry(
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

            GameLibraryScanResult(
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

    fun clearCachedScan(
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

    private fun scanDirectoryTree(
        root: DocumentFile,
        destination: MutableList<GameLibraryEntry>
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
                                GameLibraryEntry(
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
