package com.joel.thordoctor.core.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.joel.thordoctor.AppPreferences
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
        val flags =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION

        val oldUri = AppPreferences.getDiagnosticFolderUri(context)

        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            return false
        }

        val directory = DocumentFile.fromTreeUri(context, uri)

        if (
            directory == null ||
            !directory.exists() ||
            !directory.canRead() ||
            !directory.canWrite()
        ) {
            if (oldUri == null || oldUri != uri) {
                releaseFolderPermission(context, uri)
            }
            return false
        }

        AppPreferences.setDiagnosticFolderUri(context, uri)

        if (oldUri != null && oldUri != uri) {
            releaseFolderPermission(context, oldUri)
        }

        return true
    }

    fun useDefaultFolder(context: Context) {
        val oldUri = AppPreferences.getDiagnosticFolderUri(context)

        if (oldUri != null) {
            releaseFolderPermission(context, oldUri)
        }

        AppPreferences.clearDiagnosticFolder(context)
    }

    fun customFolderUri(context: Context): Uri? =
        AppPreferences.getDiagnosticFolderUri(context)

    fun folderDisplayName(context: Context): String {
        val uri = customFolderUri(context) ?: return DEFAULT_DIRECTORY

        return DocumentFile.fromTreeUri(context, uri)
            ?.name
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_DIRECTORY
    }

    fun hasValidCustomFolder(context: Context): Boolean {
        val uri = customFolderUri(context) ?: return false
        val directory = DocumentFile.fromTreeUri(context, uri) ?: return false

        return directory.exists() &&
            directory.canRead() &&
            directory.canWrite()
    }

    fun documentInfo(
        context: Context,
        filename: String
    ): StoredDiagnosticDocument? {
        val uri = customFolderUri(context)

        if (uri != null) {
            val document =
                customDirectory(context, uri)
                    ?.findFile(filename)
                    ?: return null

            if (!document.exists() || !document.canRead()) return null

            return StoredDiagnosticDocument(
                name = document.name ?: filename,
                lastModified = document.lastModified(),
                sizeBytes = document.length()
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
            val document =
                customDirectory(context, uri)
                    ?.findFile(filename)
                    ?: throw IllegalStateException("Document not found: $filename")

            val inputStream =
                context.contentResolver.openInputStream(document.uri)
                    ?: throw IllegalStateException("Unable to open: $filename")

            return inputStream
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
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
                customDirectory(context, uri)
                    ?: throw IllegalStateException("Diagnostic folder unavailable")

            var document = directory.findFile(filename)

            if (document == null) {
                document =
                    directory.createFile("application/json", filename)
                        ?: throw IllegalStateException("Unable to create: $filename")
            }

            val outputStream =
                context.contentResolver.openOutputStream(document.uri, "wt")
                    ?: throw IllegalStateException("Unable to write: $filename")

            outputStream
                .bufferedWriter(Charsets.UTF_8)
                .use { it.write(text) }

            return
        }

        val file = defaultFile(filename)
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }

    fun delete(context: Context, filename: String) {
        val uri = customFolderUri(context)

        if (uri != null) {
            customDirectory(context, uri)
                ?.findFile(filename)
                ?.delete()
            return
        }

        defaultFile(filename).delete()
    }

    fun shareUri(context: Context, filename: String): Uri? {
        val treeUri = customFolderUri(context)

        if (treeUri != null) {
            return customDirectory(context, treeUri)
                ?.findFile(filename)
                ?.takeIf { it.exists() && it.canRead() }
                ?.uri
        }

        val file = defaultFile(filename)
        if (!file.exists() || !file.canRead()) return null

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    private fun customDirectory(
        context: Context,
        uri: Uri
    ): DocumentFile? {
        val directory = DocumentFile.fromTreeUri(context, uri) ?: return null

        if (
            !directory.exists() ||
            !directory.canRead() ||
            !directory.canWrite()
        ) {
            return null
        }

        return directory
    }

    private fun releaseFolderPermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {
        }
    }

    private fun defaultFile(filename: String): File {
        @Suppress("DEPRECATION")
        val root = Environment.getExternalStorageDirectory()
        return File(root, "$DEFAULT_DIRECTORY/$filename")
    }
}
