package com.joel.thordoctor.modules.gamesbios.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CueBinSafEvaluationStatusTest {

    @Test
    fun incompleteEnumerationIsObservableAndSuppressesDiagnostics() {
        val root =
            FakeNode(
                name = "root",
                isDirectory = true,
                isFile = false,
                enumerationComplete = false,
                children =
                    listOf(
                        FakeNode(
                            name = "disc.cue",
                            isDirectory = false,
                            isFile = true,
                            text = """FILE "missing.bin" BINARY""",
                        )
                    ),
            )

        val result =
            CueBinSafEvidenceAcquirer.evaluateWithStatus(root)

        assertFalse(result.complete)
        assertTrue(result.diagnostics.isEmpty())
    }

    @Test
    fun completeEnumerationReportsConservativeDiagnostic() {
        val root =
            FakeNode(
                name = "root",
                isDirectory = true,
                isFile = false,
                children =
                    listOf(
                        FakeNode(
                            name = "disc.cue",
                            isDirectory = false,
                            isFile = true,
                            text = """FILE "missing.bin" BINARY""",
                        )
                    ),
            )

        val result =
            CueBinSafEvidenceAcquirer.evaluateWithStatus(root)

        assertTrue(result.complete)
        assertEquals(1, result.diagnostics.size)
        assertEquals(
            CueBinDiagnosticKind.MISSING_REFERENCED_BIN,
            result.diagnostics.single().kind,
        )
    }

    private data class FakeNode(
        override val name: String?,
        override val isDirectory: Boolean,
        override val isFile: Boolean,
        override val sizeBytes: Long = 0L,
        val children: List<CueBinSafNode> = emptyList(),
        val text: String? = null,
        val enumerationComplete: Boolean = true,
    ) : CueBinSafNode {

        override fun enumerateChildren(
            onChild: (CueBinSafNode) -> Unit,
        ): Boolean {
            children.forEach(onChild)
            return enumerationComplete
        }

        override fun readText(): String? =
            text
    }
}
