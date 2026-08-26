package com.joel.thordoctor.modules.gamesbios.diagnostics

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryEntry
import java.util.ArrayDeque

internal interface CueBinSafNode {
    val name: String?
    val isDirectory: Boolean
    val isFile: Boolean
    val sizeBytes: Long

    fun enumerateChildren(
        onChild: (CueBinSafNode) -> Unit,
    ): Boolean

    fun readText(): String?
}

internal data class CueBinCueEvidence(
    val entry: GameLibraryEntry,
    val contents: String,
)

internal data class CueBinSafEvidence(
    val availableRelativePaths: Set<String>,
    val cues: List<CueBinCueEvidence>,
    val complete: Boolean,
)

data class CueBinSafEvaluationResult(
    val complete: Boolean,
    val diagnostics: List<CueBinDiagnostic>,
)

/**
 * Read-only acquisition of the minimum SAF evidence required by [CueBinDiagnosticEvaluator].
 *
 * The authorized tree is traversed independently from the game-library scan so auxiliary files
 * such as `.bin` remain part of the evidence without changing scan counts or persisted cache data.
 * Directory enumeration is controlled directly through ContentResolver/DocumentsContract so a
 * provider/query failure remains observable. Any incomplete enumeration suppresses diagnostics
 * rather than allowing missing-file conclusions from partial evidence. Unreadable CUE text is
 * skipped conservatively.
 */
object CueBinSafEvidenceAcquirer {

    private data class PendingNode(
        val node: CueBinSafNode,
        val relativePath: String,
    )

    fun evaluate(
        context: Context,
        root: DocumentFile,
    ): List<CueBinDiagnostic> =
        evaluateWithStatus(
            context = context,
            root = root,
        ).diagnostics

    fun evaluateWithStatus(
        context: Context,
        root: DocumentFile,
    ): CueBinSafEvaluationResult =
        evaluateWithStatus(
            DocumentFileCueBinSafNode(
                context = context,
                document = root,
            )
        )

    internal fun evaluate(
        root: CueBinSafNode,
    ): List<CueBinDiagnostic> =
        evaluateWithStatus(root).diagnostics

    internal fun evaluateWithStatus(
        root: CueBinSafNode,
    ): CueBinSafEvaluationResult {
        val evidence =
            acquire(root)

        if (!evidence.complete) {
            return CueBinSafEvaluationResult(
                complete = false,
                diagnostics = emptyList(),
            )
        }

        return CueBinSafEvaluationResult(
            complete = true,
            diagnostics =
                evidence.cues.flatMap { cue ->
                    CueBinDiagnosticEvaluator.evaluate(
                        cueEntry = cue.entry,
                        cueContents = cue.contents,
                        availableRelativePaths = evidence.availableRelativePaths,
                    )
                },
        )
    }

    internal fun acquire(
        root: CueBinSafNode,
    ): CueBinSafEvidence {
        if (!root.isDirectory) {
            return CueBinSafEvidence(
                availableRelativePaths = emptySet(),
                cues = emptyList(),
                complete = false,
            )
        }

        val availablePaths =
            linkedSetOf<String>()

        val cues =
            mutableListOf<CueBinCueEvidence>()

        val pending =
            ArrayDeque<PendingNode>()

        pending.add(
            PendingNode(
                node = root,
                relativePath = "",
            )
        )

        while (pending.isNotEmpty()) {
            val current =
                pending.removeLast()

            val children =
                mutableListOf<CueBinSafNode>()

            val enumerationComplete =
                current.node.enumerateChildren { child ->
                    children += child
                }

            if (!enumerationComplete) {
                return incompleteEvidence(
                    availablePaths = availablePaths,
                    cues = cues,
                )
            }

            val sortedChildren =
                children.sortedWith(
                    compareBy<CueBinSafNode> {
                        it.name
                            ?.lowercase()
                            .orEmpty()
                    }.thenBy {
                        it.name.orEmpty()
                    }
                )

            for (child in sortedChildren) {
                val name =
                    child.name
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?: return incompleteEvidence(
                            availablePaths = availablePaths,
                            cues = cues,
                        )

                val relativePath =
                    if (current.relativePath.isBlank()) {
                        name
                    } else {
                        "${current.relativePath}/$name"
                    }

                when {
                    child.isDirectory -> {
                        pending.add(
                            PendingNode(
                                node = child,
                                relativePath = relativePath,
                            )
                        )
                    }

                    child.isFile -> {
                        availablePaths +=
                            relativePath

                        if (
                            name.substringAfterLast('.', "")
                                .equals("cue", ignoreCase = true)
                        ) {
                            val contents =
                                child.readText()

                            if (contents != null) {
                                cues +=
                                    CueBinCueEvidence(
                                        entry =
                                            GameLibraryEntry(
                                                name = name,
                                                relativePath = relativePath,
                                                extension = "cue",
                                                sizeBytes = child.sizeBytes,
                                            ),
                                        contents = contents,
                                    )
                            }
                        }
                    }

                    else -> {
                        return incompleteEvidence(
                            availablePaths = availablePaths,
                            cues = cues,
                        )
                    }
                }
            }
        }

        return CueBinSafEvidence(
            availableRelativePaths =
                availablePaths
                    .sortedWith(relativePathComparator)
                    .toCollection(linkedSetOf()),
            cues =
                cues.sortedWith(
                    compareBy<CueBinCueEvidence> {
                        it.entry.relativePath.lowercase()
                    }.thenBy {
                        it.entry.relativePath
                    }
                ),
            complete = true,
        )
    }

    private fun incompleteEvidence(
        availablePaths: Set<String>,
        cues: List<CueBinCueEvidence>,
    ): CueBinSafEvidence =
        CueBinSafEvidence(
            availableRelativePaths =
                availablePaths
                    .sortedWith(relativePathComparator)
                    .toCollection(linkedSetOf()),
            cues =
                cues.sortedWith(
                    compareBy<CueBinCueEvidence> {
                        it.entry.relativePath.lowercase()
                    }.thenBy {
                        it.entry.relativePath
                    }
                ),
            complete = false,
        )

    private val relativePathComparator =
        compareBy<String> {
            it.lowercase()
        }.thenBy {
            it
        }
}

private class DocumentFileCueBinSafNode(
    private val context: Context,
    private val uri: Uri,
    override val name: String?,
    override val isDirectory: Boolean,
    override val isFile: Boolean,
    override val sizeBytes: Long,
) : CueBinSafNode {

    constructor(
        context: Context,
        document: DocumentFile,
    ) : this(
        context = context,
        uri = document.uri,
        name = document.name,
        isDirectory = document.isDirectory,
        isFile = document.isFile,
        sizeBytes = document.length(),
    )

    override fun enumerateChildren(
        onChild: (CueBinSafNode) -> Unit,
    ): Boolean {
        if (!isDirectory) {
            return false
        }

        val childrenUri =
            try {
                DocumentsContract.buildChildDocumentsUriUsingTree(
                    uri,
                    DocumentsContract.getDocumentId(uri),
                )
            } catch (_: Exception) {
                return false
            }

        val cursor =
            try {
                context.contentResolver.query(
                    childrenUri,
                    CHILD_PROJECTION,
                    null,
                    null,
                    null,
                )
            } catch (_: Exception) {
                null
            }
                ?: return false

        return cursor.use { children ->
            try {
                if (
                    children.extras.getBoolean(
                        DocumentsContract.EXTRA_LOADING,
                        false,
                    )
                ) {
                    return@use false
                }

                val documentIdColumn =
                    children.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID
                    )
                val displayNameColumn =
                    children.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    )
                val mimeTypeColumn =
                    children.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    )
                val sizeColumn =
                    children.getColumnIndexOrThrow(
                        DocumentsContract.Document.COLUMN_SIZE
                    )

                while (children.moveToNext()) {
                    val documentId =
                        children.getString(documentIdColumn)
                            ?: return@use false
                    val displayName =
                        children.getString(displayNameColumn)
                    val mimeType =
                        children.getString(mimeTypeColumn)
                            ?: return@use false
                    val childUri =
                        DocumentsContract.buildDocumentUriUsingTree(
                            uri,
                            documentId,
                        )
                    val childIsDirectory =
                        mimeType == DocumentsContract.Document.MIME_TYPE_DIR
                    val childIsFile =
                        mimeType.isNotBlank() && !childIsDirectory
                    val childSize =
                        if (children.isNull(sizeColumn)) {
                            0L
                        } else {
                            children.getLong(sizeColumn)
                        }

                    onChild(
                        DocumentFileCueBinSafNode(
                            context = context,
                            uri = childUri,
                            name = displayName,
                            isDirectory = childIsDirectory,
                            isFile = childIsFile,
                            sizeBytes = childSize,
                        )
                    )
                }

                !children.extras.getBoolean(
                    DocumentsContract.EXTRA_LOADING,
                    false,
                )
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun readText(): String? {
        if (!isFile) {
            return null
        }

        return try {
            context.contentResolver
                .openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { reader ->
                    reader.readText()
                }
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val CHILD_PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
            )
    }
}
