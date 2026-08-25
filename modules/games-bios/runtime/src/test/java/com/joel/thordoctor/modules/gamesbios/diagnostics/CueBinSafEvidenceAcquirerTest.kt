package com.joel.thordoctor.modules.gamesbios.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CueBinSafEvidenceAcquirerTest {

    @Test
    fun completeEvidenceIncludesAuxiliaryBinAndFeedsEvaluator() {
        val root =
            directory(
                "root",
                directory(
                    "Sony",
                    directory(
                        "Game",
                        file(
                            "disc.cue",
                            text = """FILE "track01.bin" BINARY""",
                        ),
                        file("track01.bin"),
                    ),
                ),
            )

        val evidence =
            CueBinSafEvidenceAcquirer.acquire(root)

        assertTrue(evidence.complete)
        assertEquals(
            linkedSetOf(
                "Sony/Game/disc.cue",
                "Sony/Game/track01.bin",
            ),
            evidence.availableRelativePaths,
        )
        assertEquals(
            listOf("Sony/Game/disc.cue"),
            evidence.cues.map {
                it.entry.relativePath
            },
        )
        assertTrue(
            CueBinSafEvidenceAcquirer.evaluate(root)
                .isEmpty()
        )
    }

    @Test
    fun missingReferencedBinProducesDiagnosticFromAcquiredEvidence() {
        val root =
            directory(
                "root",
                directory(
                    "Sony",
                    directory(
                        "Game",
                        file(
                            "disc.cue",
                            text = """FILE "track01.bin" BINARY""",
                        ),
                    ),
                ),
            )

        val diagnostics =
            CueBinSafEvidenceAcquirer.evaluate(root)

        assertEquals(1, diagnostics.size)
        assertEquals(
            "Sony/Game/track01.bin",
            diagnostics.single().referencedBinPath,
        )
        assertEquals(
            CueBinDiagnosticKind.MISSING_REFERENCED_BIN,
            diagnostics.single().kind,
        )
    }

    @Test
    fun unreadableCueTextProducesNoDiagnostic() {
        val root =
            directory(
                "root",
                file(
                    "disc.cue",
                    text = null,
                ),
            )

        val evidence =
            CueBinSafEvidenceAcquirer.acquire(root)

        assertTrue(evidence.complete)
        assertEquals(
            linkedSetOf("disc.cue"),
            evidence.availableRelativePaths,
        )
        assertTrue(evidence.cues.isEmpty())
        assertTrue(
            CueBinSafEvidenceAcquirer.evaluate(root)
                .isEmpty()
        )
    }

    @Test
    fun failureBeforeObtainingChildrenMarksEvidenceIncomplete() {
        val root =
            failingDirectory(
                name = "root",
                failAfterChildren = 0,
                file(
                    "disc.cue",
                    text = """FILE "missing.bin" BINARY""",
                ),
            )

        val evidence =
            CueBinSafEvidenceAcquirer.acquire(root)

        assertFalse(evidence.complete)
        assertTrue(evidence.availableRelativePaths.isEmpty())
        assertTrue(evidence.cues.isEmpty())
    }

    @Test
    fun failureAfterObtainingSomeChildrenMarksEvidenceIncomplete() {
        val root =
            failingDirectory(
                name = "root",
                failAfterChildren = 1,
                file(
                    "disc.cue",
                    text = """FILE "track01.bin" BINARY""",
                ),
                file("track01.bin"),
            )

        val evidence =
            CueBinSafEvidenceAcquirer.acquire(root)

        assertFalse(evidence.complete)
        assertTrue(evidence.availableRelativePaths.isEmpty())
        assertTrue(evidence.cues.isEmpty())
    }

    @Test
    fun partialEnumerationNeverProducesMissingReferencedBinDiagnostic() {
        val root =
            failingDirectory(
                name = "root",
                failAfterChildren = 1,
                file(
                    "disc.cue",
                    text = """FILE "track01.bin" BINARY""",
                ),
                file("track01.bin"),
            )

        val diagnostics =
            CueBinSafEvidenceAcquirer.evaluate(root)

        assertTrue(diagnostics.isEmpty())
        assertTrue(
            diagnostics.none {
                it.kind == CueBinDiagnosticKind.MISSING_REFERENCED_BIN
            }
        )
    }

    @Test
    fun incompleteDirectoryListingSuppressesDiagnostics() {
        val root =
            directory(
                "root",
                file(
                    "disc.cue",
                    text = """FILE "missing.bin" BINARY""",
                ),
                unreadableDirectory("unknown"),
            )

        val evidence =
            CueBinSafEvidenceAcquirer.acquire(root)

        assertFalse(evidence.complete)
        assertTrue(
            CueBinSafEvidenceAcquirer.evaluate(root)
                .isEmpty()
        )
    }

    @Test
    fun diagnosticsRemainDeterministicAcrossTreeTraversal() {
        val root =
            directory(
                "root",
                directory(
                    "B",
                    file(
                        "disc.cue",
                        text = """FILE "b.bin" BINARY""",
                    ),
                ),
                directory(
                    "A",
                    file(
                        "disc.cue",
                        text = """FILE "a.bin" BINARY""",
                    ),
                ),
            )

        val first =
            CueBinSafEvidenceAcquirer.evaluate(root)

        val second =
            CueBinSafEvidenceAcquirer.evaluate(root)

        assertEquals(first, second)
        assertEquals(
            listOf(
                "A/a.bin",
                "B/b.bin",
            ),
            first.map {
                it.referencedBinPath
            },
        )
    }

    private fun directory(
        name: String,
        vararg children: CueBinSafNode,
    ): CueBinSafNode =
        FakeCueBinSafNode(
            name = name,
            isDirectory = true,
            isFile = false,
            children = children.toList(),
        )

    private fun failingDirectory(
        name: String,
        failAfterChildren: Int,
        vararg children: CueBinSafNode,
    ): CueBinSafNode =
        FakeCueBinSafNode(
            name = name,
            isDirectory = true,
            isFile = false,
            children = children.toList(),
            failAfterChildren = failAfterChildren,
        )

    private fun unreadableDirectory(
        name: String,
    ): CueBinSafNode =
        FakeCueBinSafNode(
            name = name,
            isDirectory = true,
            isFile = false,
            children = emptyList(),
            failAfterChildren = 0,
        )

    private fun file(
        name: String,
        text: String? = null,
    ): CueBinSafNode =
        FakeCueBinSafNode(
            name = name,
            isDirectory = false,
            isFile = true,
            sizeBytes = 1024L,
            children = emptyList(),
            text = text,
        )

    private data class FakeCueBinSafNode(
        override val name: String?,
        override val isDirectory: Boolean,
        override val isFile: Boolean,
        override val sizeBytes: Long = 0L,
        val children: List<CueBinSafNode>,
        val text: String? = null,
        val failAfterChildren: Int? = null,
    ) : CueBinSafNode {

        override fun enumerateChildren(
            onChild: (CueBinSafNode) -> Unit,
        ): Boolean {
            children.forEachIndexed { index, child ->
                if (
                    failAfterChildren != null &&
                    index >= failAfterChildren
                ) {
                    return false
                }

                onChild(child)
            }

            return failAfterChildren == null
        }

        override fun readText(): String? =
            text
    }
}
