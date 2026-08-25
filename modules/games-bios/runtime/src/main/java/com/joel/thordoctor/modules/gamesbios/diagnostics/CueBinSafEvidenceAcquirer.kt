package com.joel.thordoctor.modules.gamesbios.diagnostics

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryEntry
import java.util.ArrayDeque

internal interface CueBinSafNode {
    val name: String?
    val isDirectory: Boolean
    val isFile: Boolean
    val sizeBytes: Long

    fun listChildren(): List<CueBinSafNode>?

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

/**
 * Read-only acquisition of the minimum SAF evidence required by [CueBinDiagnosticEvaluator].
 *
 * The authorized tree is traversed independently from the game-library scan so auxiliary files
 * such as `.bin` remain part of the evidence without changing scan counts or persisted cache data.
 * If a directory cannot be listed completely, diagnostics are suppressed rather than inferred from
 * partial evidence. Unreadable CUE text is skipped conservatively.
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
        evaluate(
            DocumentFileCueBinSafNode(
                context = context,
                document = root,
            )
        )

    internal fun evaluate(
        root: CueBinSafNode,
    ): List<CueBinDiagnostic> {
        val evidence =
            acquire(root)

        if (!evidence.complete) {
            return emptyList()
        }

        return evidence.cues.flatMap { cue ->
            CueBinDiagnosticEvaluator.evaluate(
                cueEntry = cue.entry,
                cueContents = cue.contents,
                availableRelativePaths = evidence.availableRelativePaths,
            )
        }
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
                current.node.listChildren()
                    ?: return incompleteEvidence(
                        availablePaths = availablePaths,
                        cues = cues,
                    )

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
    private val document: DocumentFile,
) : CueBinSafNode {

    override val name: String?
        get() = document.name

    override val isDirectory: Boolean
        get() = document.isDirectory

    override val isFile: Boolean
        get() = document.isFile

    override val sizeBytes: Long
        get() = document.length()

    override fun listChildren(): List<CueBinSafNode>? {
        if (
            !document.isDirectory ||
            !document.canRead()
        ) {
            return null
        }

        return try {
            document.listFiles()
                .map { child ->
                    DocumentFileCueBinSafNode(
                        context = context,
                        document = child,
                    )
                }
        } catch (_: Exception) {
            null
        }
    }

    override fun readText(): String? {
        if (
            !document.isFile ||
            !document.canRead()
        ) {
            return null
        }

        return try {
            context.contentResolver
                .openInputStream(document.uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { reader ->
                    reader.readText()
                }
        } catch (_: Exception) {
            null
        }
    }
}
