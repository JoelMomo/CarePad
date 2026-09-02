package com.joel.thordoctor

import android.content.Context
import android.net.Uri
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryEntry
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryService
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryScanResult
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
    ): Boolean =
        GameLibraryService.setRootFolder(
            context,
            uri
        )

    fun rootFolderUri(
        context: Context
    ): Uri? =
        GameLibraryService.rootFolderUri(
            context
        )

    fun hasValidRootFolder(
        context: Context
    ): Boolean =
        GameLibraryService.hasValidRootFolder(
            context
        )

    fun folderDisplayName(
        context: Context
    ): String? =
        GameLibraryService.folderDisplayName(
            context
        )

    fun cachedGameCount(
        context: Context
    ): Int =
        GameLibraryService.cachedGameCount(
            context
        )

    fun lastScanTimestamp(
        context: Context
    ): Long =
        GameLibraryService.lastScanTimestamp(
            context
        )

    fun scan(
        context: Context
    ): ScanResult {
        val execution =
            GameLibraryService.scan(
                context
            )

        if (execution.cacheUpdated) {
            LegacyGameLibraryDiagnosticBridge.refreshIfPresent(
                context
            )
        }

        return execution.result.toFacade()
    }

    fun buildDiagnosticJson(
        context: Context
    ): JSONObject =
        LegacyGameLibraryDiagnosticBridge.buildDiagnosticJson(
            context
        )

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
