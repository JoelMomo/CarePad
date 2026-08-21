package com.joel.thordoctor.core.diagnostics

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.joel.thordoctor.AppPreferences
import com.joel.thordoctor.core.storage.CoreTreeStorage
import java.io.File

data class StoredDiagnosticDocument(
    val name: String,
    val lastModified: Long,
    val sizeBytes: Long
)

object CoreDiagnosticStorage {

    const val SESSION_FILENAME = "session.json"
    const val DIAGNOSTIC_FILENAME = "diagnostic.json"

    private const val DEFAULT_DIRECTORY = "Download/DocThor"

    fun setCustomFolder(context: Context, uri: Uri): Boolean {
        val oldUri = AppPreferences.getDiagnosticFolderUri(context)

        if (!CoreTreeStorage.takePersistableReadWritePermission(context, uri)) {
            return false
        }

        if (CoreTreeStorage.writableDirectory(context, uri) == null) {
            if (oldUri == null || oldUri != uri) {
                CoreTreeStorage.releasePersistableReadWritePermission(context, uri)
            }
            return false
        }

        AppPreferences.setDiagnosticFolderUri(context, uri)

        if (oldUri != null && oldUri != uri) {
            CoreTreeStorage.releasePersistableReadWritePermission(context, oldUri)
        }

        return true
    }

    fun useDefaultFolder(context: Context) {
        val oldUri = AppPreferences.getDiagnosticFolderUri(context)

        if (oldUri != null) {
            CoreTreeStorage.releasePersistableReadWritePermission(context, oldUri)
        }

        AppPreferences.clearDiagnosticFolder(context)
    }

    fun customFolderUri(context: Context): Uri? =
        AppPreferences.getDiagnosticFolderUri(context)

    fun folderDisplayName(context: Context): String {
        val uri = customFolderUri(context) ?: return DEFAULT_DIRECTORY

        return CoreTreeStorage.directory(context, uri)
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_DIRECTORY
    }

    fun hasValidCustomFolder(context: Context): Boolean {
        val uri = customFolderUri(context) ?: return false
        return CoreTreeStorage.writableDirectory(context, uri) != null
    }

    fun documentInfo(
        context: Context,
        filename: String
    ): StoredDiagnosticDocument? {
        val uri = customFolderUri(context)

        if (uri != null) {
            val directory =
                CoreTreeStorage.writableDirectory(context, uri)
                    ?: return null

            val info =
                CoreTreeStorage.documentInfo(directory, filename)
                    ?: return null

            return StoredDiagnosticDocument(
                name = info.name,
                lastModified = info.lastModified,
                sizeBytes = info.sizeBytes
            )
        }

        val file = defaultFile(filename)
        if (!file.exists() || !file.canRead()) return null

        return StoredDiagnosticDocument(
            name = file.name,
            lastModified = file.lastModified(),
            sizeBytes = file.length()
        )
    }

    fun readText(context: Context, filename: String): String {
        val uri = customFolderUri(context)

        if (uri != null) {
            val directory =
                CoreTreeStorage.writableDirectory(context, uri)
                    ?: throw IllegalStateException("Document not found: $filename")

            return CoreTreeStorage.readText(context, directory, filename)
        }

        return defaultFile(filename).readText(Charsets.UTF_8)
    }

    fun writeText(
        context: Context,
        filename: String,
        text: String
    ) {
        val uri = customFolderUri(context)

        if (uri != null) {
            val directory =
                CoreTreeStorage.writableDirectory(context, uri)
                    ?: throw IllegalStateException("Diagnostic folder unavailable")

            CoreTreeStorage.writeText(
                context = context,
                directory = directory,
                filename = filename,
                text = text,
                mimeType = "application/json"
            )
            return
        }

        val file = defaultFile(filename)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    fun delete(context: Context, filename: String) {
        val uri = customFolderUri(context)

        if (uri != null) {
            CoreTreeStorage.writableDirectory(context, uri)
                ?.let { directory ->
                    CoreTreeStorage.delete(directory, filename)
                }
            return
        }

        defaultFile(filename).delete()
    }

    fun shareUri(context: Context, filename: String): Uri? {
        val treeUri = customFolderUri(context)

        if (treeUri != null) {
            val directory =
                CoreTreeStorage.writableDirectory(context, treeUri)
                    ?: return null

            return CoreTreeStorage.readableDocumentUri(directory, filename)
        }

        val file = defaultFile(filename)
        if (!file.exists() || !file.canRead()) return null

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    private fun defaultFile(filename: String): File {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory()
        return File(root, "$DEFAULT_DIRECTORY/$filename")
    }
}
