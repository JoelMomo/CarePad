package com.joel.thordoctor.modules.gamesbios.diagnostics

import com.joel.thordoctor.modules.gamesbios.library.GameLibraryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CueBinDiagnosticEvaluatorTest {

    @Test
    fun existingReferencedBinProducesNoDiagnostic() {
        val cue = entry("disc.cue", "Sony/Game/disc.cue")

        val diagnostics =
            CueBinDiagnosticEvaluator.evaluate(
                cueEntry = cue,
                cueContents = """FILE "track01.bin" BINARY""",
                availableRelativePaths =
                    setOf(
                        "Sony/Game/disc.cue",
                        "Sony/Game/track01.bin",
                    ),
            )

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun missingReferencedBinProducesDiagnostic() {
        val cue = entry("disc.cue", "Sony/Game/disc.cue")

        val diagnostics =
            CueBinDiagnosticEvaluator.evaluate(
                cueEntry = cue,
                cueContents = """FILE "track01.bin" BINARY""",
                availableRelativePaths =
                    setOf("Sony/Game/disc.cue"),
            )

        assertEquals(1, diagnostics.size)
        assertEquals(cue, diagnostics.single().cueEntry)
        assertEquals("Sony/Game/track01.bin", diagnostics.single().referencedBinPath)
        assertEquals(
            CueBinDiagnosticKind.MISSING_REFERENCED_BIN,
            diagnostics.single().kind,
        )
    }

    @Test
    fun mixedCueReportsOnlyDistinctMissingBinReferencesDeterministically() {
        val cue = entry("disc.cue", "Sony/Game/disc.cue")
        val cueContents =
            """
            FILE "track01.bin" BINARY
              TRACK 01 MODE2/2352
            FILE "track02.bin" BINARY
              TRACK 02 AUDIO
            FILE "track02.bin" BINARY
            FILE "audio.wav" WAVE
            """.trimIndent()
        val availablePaths =
            setOf(
                "Sony/Game/disc.cue",
                "sony/game/TRACK01.BIN",
            )

        val first =
            CueBinDiagnosticEvaluator.evaluate(
                cueEntry = cue,
                cueContents = cueContents,
                availableRelativePaths = availablePaths,
            )
        val second =
            CueBinDiagnosticEvaluator.evaluate(
                cueEntry = cue,
                cueContents = cueContents,
                availableRelativePaths = availablePaths,
            )

        assertEquals(first, second)
        assertEquals(
            listOf("Sony/Game/track02.bin"),
            first.map { it.referencedBinPath },
        )
    }

    @Test
    fun insufficientEvidenceProducesNoDiagnostic() {
        val cue = entry("disc.cue", "Sony/Game/disc.cue")

        assertTrue(
            CueBinDiagnosticEvaluator.evaluate(
                cueEntry = cue,
                cueContents = null,
                availableRelativePaths = setOf("Sony/Game/disc.cue"),
            ).isEmpty()
        )

        assertTrue(
            CueBinDiagnosticEvaluator.evaluate(
                cueEntry = cue,
                cueContents = """FILE "track01.bin" BINARY""",
                availableRelativePaths = null,
            ).isEmpty()
        )
    }

    @Test
    fun ambiguousOrUnsafeFileReferencesAreIgnored() {
        val cue = entry("disc.cue", "Sony/Game/disc.cue")
        val cueContents =
            """
            FILE "../track01.bin" BINARY
            FILE "/absolute/track02.bin" BINARY
            FILE "C:\\Games\\track03.bin" BINARY
            FILE "track four.bin" WAVE
            FILE "unterminated.bin BINARY
            """.trimIndent()

        val diagnostics =
            CueBinDiagnosticEvaluator.evaluate(
                cueEntry = cue,
                cueContents = cueContents,
                availableRelativePaths = emptySet(),
            )

        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun nonCueEntryIsIgnored() {
        val diagnostics =
            CueBinDiagnosticEvaluator.evaluate(
                cueEntry = entry("disc.iso"),
                cueContents = """FILE "track01.bin" BINARY""",
                availableRelativePaths = emptySet(),
            )

        assertTrue(diagnostics.isEmpty())
    }

    private fun entry(
        name: String,
        relativePath: String = name,
    ) = GameLibraryEntry(
        name = name,
        relativePath = relativePath,
        extension = name.substringAfterLast('.', ""),
        sizeBytes = 1024L,
    )
}
