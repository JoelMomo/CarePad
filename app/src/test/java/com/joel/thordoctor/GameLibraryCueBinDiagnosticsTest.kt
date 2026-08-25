package com.joel.thordoctor

import com.joel.thordoctor.modules.gamesbios.diagnostics.CueBinDiagnostic
import com.joel.thordoctor.modules.gamesbios.diagnostics.CueBinDiagnosticKind
import com.joel.thordoctor.modules.gamesbios.diagnostics.CueBinSafEvaluationResult
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLibraryCueBinDiagnosticsTest {

    @Test
    fun unavailableRootDoesNotInvokeEvaluator() {
        var invoked = false

        val result =
            GameLibraryCueBinDiagnostics.evaluate(
                rootReadable = false,
                evaluator = {
                    invoked = true
                    CueBinSafEvaluationResult(
                        complete = true,
                        diagnostics = emptyList(),
                    )
                },
            )

        assertFalse(invoked)
        assertEquals(
            GameLibraryCueBinDiagnosticsState.ROOT_UNAVAILABLE,
            result.state,
        )
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(
            "root_unavailable",
            GameLibraryCueBinDiagnostics.qaLogMessage(result),
        )
    }

    @Test
    fun incompleteEvidenceRemainsObservableAndProducesNoDiagnostic() {
        val result =
            GameLibraryCueBinDiagnostics.evaluate(
                rootReadable = true,
                evaluator = {
                    CueBinSafEvaluationResult(
                        complete = false,
                        diagnostics = emptyList(),
                    )
                },
            )

        assertEquals(
            GameLibraryCueBinDiagnosticsState.EVIDENCE_INCOMPLETE,
            result.state,
        )
        assertTrue(result.diagnostics.isEmpty())
        assertEquals(
            "evidence_incomplete",
            GameLibraryCueBinDiagnostics.qaLogMessage(result),
        )
    }

    @Test
    fun readableRootForwardsConservativeDiagnostics() {
        val diagnostic =
            CueBinDiagnostic(
                cueEntry =
                    GameLibraryEntry(
                        name = "disc.cue",
                        relativePath = "PS1/disc.cue",
                        extension = "cue",
                        sizeBytes = 42L,
                    ),
                referencedBinPath = "PS1/track01.bin",
                kind = CueBinDiagnosticKind.MISSING_REFERENCED_BIN,
            )

        val result =
            GameLibraryCueBinDiagnostics.evaluate(
                rootReadable = true,
                evaluator = {
                    CueBinSafEvaluationResult(
                        complete = true,
                        diagnostics = listOf(diagnostic),
                    )
                },
            )

        assertEquals(
            GameLibraryCueBinDiagnosticsState.EVALUATED,
            result.state,
        )
        assertEquals(
            listOf(diagnostic),
            result.diagnostics,
        )
        assertEquals(1, result.missingReferencedBinCount)
        assertEquals(
            "evaluation_finished missing_referenced_bin=1",
            GameLibraryCueBinDiagnostics.qaLogMessage(result),
        )
    }
}
