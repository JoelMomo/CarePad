package com.joel.thordoctor.modules.gamesbios.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class GameLibraryScanExecution(
    val result: GameLibraryScanResult,
    val cacheUpdated: Boolean,
)

object GameLibraryService {

    fun setRootFolder(
        context: Context,
        uri: Uri,
    ): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val oldUri = GameLibraryRootPreferences.rootFolderUri(context)

        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            return false
        }

        val directory = DocumentFile.fromTreeUri(context, uri)
        if (directory == null || !directory.exists() || !directory.canRead()) {
            if (oldUri == null || oldUri != uri) {
                try {
                    context.contentResolver.releasePersistableUriPermission(uri, flags)
                } catch (_: Exception) {
                }
            }
            return false
        }

        if (oldUri != null && oldUri != uri) {
            try {
                context.contentResolver.releasePersistableUriPermission(oldUri, flags)
            } catch (_: Exception) {
            }
        }

        GameLibraryRootPreferences.setRootFolderUri(context, uri)
        GameLibraryRuntime.clearCachedScan(context)
        return true
    }

    fun rootFolderUri(context: Context): Uri? =
        GameLibraryRootPreferences.rootFolderUri(context)

    fun hasValidRootFolder(context: Context): Boolean {
        val uri = rootFolderUri(context) ?: return false
        val directory = DocumentFile.fromTreeUri(context, uri) ?: return false
        return directory.exists() && directory.canRead()
    }

    fun folderDisplayName(context: Context): String? {
        val uri = rootFolderUri(context) ?: return null
        return DocumentFile.fromTreeUri(context, uri)
            ?.name
            ?.takeIf { it.isNotBlank() }
    }

    fun cachedGameCount(context: Context): Int =
        GameLibraryRuntime.cachedGameCount(context)

    fun lastScanTimestamp(context: Context): Long =
        GameLibraryRuntime.lastScanTimestamp(context)

    fun scan(context: Context): GameLibraryScanExecution {
        val uri = rootFolderUri(context)
            ?: return emptyExecution(context)

        val root = DocumentFile.fromTreeUri(context, uri)
        if (root == null || !root.exists() || !root.canRead()) {
            return emptyExecution(context)
        }

        val result = GameLibraryRuntime.scan(root)

        // A long scan must not overwrite the cache if the selected root changed meanwhile.
        if (rootFolderUri(context) != uri) {
            return GameLibraryScanExecution(
                result = result,
                cacheUpdated = false,
            )
        }

        GameLibraryRuntime.persistScan(context, result)
        return GameLibraryScanExecution(
            result = result,
            cacheUpdated = true,
        )
    }

    private fun emptyExecution(context: Context): GameLibraryScanExecution {
        GameLibraryRuntime.clearCachedScan(context)
        return GameLibraryScanExecution(
            result = GameLibraryScanResult(
                folderName = folderDisplayName(context).orEmpty(),
                gameCount = 0,
                scannedAt = System.currentTimeMillis(),
                games = emptyList(),
            ),
            cacheUpdated = false,
        )
    }
}
