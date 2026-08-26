package com.joel.thordoctor

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.joel.thordoctor.modules.gamesbios.diagnostics.CueBinDiagnostic
import com.joel.thordoctor.modules.gamesbios.diagnostics.CueBinDiagnosticKind
import com.joel.thordoctor.modules.gamesbios.diagnostics.CueBinSafEvaluationResult
import com.joel.thordoctor.modules.gamesbios.diagnostics.CueBinSafEvidenceAcquirer

internal enum class GameLibraryCueBinDiagnosticsState {
    ROOT_UNAVAILABLE,
    EVIDENCE_INCOMPLETE,
    EVALUATED,
}

internal data class GameLibraryCueBinDiagnosticsResult(
    val state: GameLibraryCueBinDiagnosticsState,
    val diagnostics: List<CueBinDiagnostic>,
) {
    val missingReferencedBinCount: Int
        get() =
            diagnostics.count {
                it.kind == CueBinDiagnosticKind.MISSING_REFERENCED_BIN
            }
}

/**
 * Minimal host entry point that evaluates CUE/BIN diagnostics against the already-authorized
 * game-library SAF root. It does not acquire or release grants, persist results, modify the library
 * scan/cache, or write user files.
 */
internal object GameLibraryCueBinDiagnostics {

    fun evaluate(
        context: Context,
        rootUri: Uri,
    ): GameLibraryCueBinDiagnosticsResult {
        val root =
            try {
                DocumentFile.fromTreeUri(
                    context,
                    rootUri,
                )
            } catch (_: Exception) {
                null
            }

        if (
            root == null ||
            !safeExists(root) ||
            !safeCanRead(root)
        ) {
            return evaluate(
                rootReadable = false,
                evaluator = {
                    CueBinSafEvaluationResult(
                        complete = false,
                        diagnostics = emptyList(),
                    )
                },
            )
        }

        return evaluate(
            rootReadable = true,
            evaluator = {
                CueBinSafEvidenceAcquirer.evaluateWithStatus(
                    context = context,
                    root = root,
                )
            },
        )
    }

    internal fun evaluate(
        rootReadable: Boolean,
        evaluator: () -> CueBinSafEvaluationResult,
    ): GameLibraryCueBinDiagnosticsResult {
        if (!rootReadable) {
            return GameLibraryCueBinDiagnosticsResult(
                state = GameLibraryCueBinDiagnosticsState.ROOT_UNAVAILABLE,
                diagnostics = emptyList(),
            )
        }

        val evaluation =
            evaluator()

        if (!evaluation.complete) {
            return GameLibraryCueBinDiagnosticsResult(
                state = GameLibraryCueBinDiagnosticsState.EVIDENCE_INCOMPLETE,
                diagnostics = emptyList(),
            )
        }

        return GameLibraryCueBinDiagnosticsResult(
            state = GameLibraryCueBinDiagnosticsState.EVALUATED,
            diagnostics = evaluation.diagnostics,
        )
    }

    internal fun qaLogMessage(
        result: GameLibraryCueBinDiagnosticsResult,
    ): String =
        when (result.state) {
            GameLibraryCueBinDiagnosticsState.ROOT_UNAVAILABLE ->
                "root_unavailable"

            GameLibraryCueBinDiagnosticsState.EVIDENCE_INCOMPLETE ->
                "evidence_incomplete"

            GameLibraryCueBinDiagnosticsState.EVALUATED ->
                "evaluation_finished missing_referenced_bin=${result.missingReferencedBinCount}"
        }

    private fun safeExists(
        root: DocumentFile,
    ): Boolean =
        try {
            root.exists()
        } catch (_: Exception) {
            false
        }

    private fun safeCanRead(
        root: DocumentFile,
    ): Boolean =
        try {
            root.canRead()
        } catch (_: Exception) {
            false
        }
}
