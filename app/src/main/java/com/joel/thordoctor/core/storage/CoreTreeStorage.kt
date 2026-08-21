package com.joel.thordoctor.core.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class CoreTreeDocumentInfo(
    val name: String,
    val lastModified: Long,
    val sizeBytes: Long
)

/**
 * Generic SAF document-tree primitives shared by CarePad infrastructure.
 *
 * Product-specific folder preferences, filenames, schemas and policies stay
 * outside this object.
 */
object CoreTreeStorage {

    private val readWritePermissionFlags =
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun takePersistableReadWritePermission(
        context: Context,
        uri: Uri
    ): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                readWritePermissionFlags
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun releasePersistableReadWritePermission(
        context: Context,
        uri: Uri
    ) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                readWritePermissionFlags
            )
        } catch (_: Exception) {
        }
    }

    fun directory(
        context: Context,
        uri: Uri
    ): DocumentFile? =
        DocumentFile.fromTreeUri(context, uri)

    fun writableDirectory(
        context: Context,
        uri: Uri
    ): DocumentFile? {
        val directory = directory(context, uri) ?: return null

        if (
            !directory.exists() ||
            !directory.canRead() ||
            !directory.canWrite()
        ) {
            return null
        }

        return directory
    }

    fun documentInfo(
        directory: DocumentFile,
        filename: String
    ): CoreTreeDocumentInfo? {
        val document = directory.findFile(filename) ?: return null
        if (!document.exists() || !document.canRead()) return null

        return CoreTreeDocumentInfo(
            name = document.name ?: filename,
            lastModified = document.lastModified(),
            sizeBytes = document.length()
        )
    }

    fun readText(
        context: Context,
        directory: DocumentFile,
        filename: String
    ): String {
        val document =
            directory.findFile(filename)
                ?: throw IllegalStateException("Document not found: $filename")

        val inputStream =
            context.contentResolver.openInputStream(document.uri)
                ?: throw IllegalStateException("Unable to open: $filename")

        return inputStream
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }

    fun writeText(
        context: Context,
        directory: DocumentFile,
        filename: String,
        text: String,
        mimeType: String
    ) {
        var document = directory.findFile(filename)

        if (document == null) {
            document =
                directory.createFile(mimeType, filename)
                    ?: throw IllegalStateException("Unable to create: $filename")
        }

        val outputStream =
            context.contentResolver.openOutputStream(document.uri, "wt")
                ?: throw IllegalStateException("Unable to write: $filename")

        outputStream
            .bufferedWriter(Charsets.UTF_8)
            .use { it.write(text) }
    }

    fun delete(
        directory: DocumentFile,
        filename: String
    ) {
        directory.findFile(filename)?.delete()
    }

    fun readableDocumentUri(
        directory: DocumentFile,
        filename: String
    ): Uri? =
        directory.findFile(filename)
            ?.takeIf { it.exists() && it.canRead() }
            ?.uri
}
