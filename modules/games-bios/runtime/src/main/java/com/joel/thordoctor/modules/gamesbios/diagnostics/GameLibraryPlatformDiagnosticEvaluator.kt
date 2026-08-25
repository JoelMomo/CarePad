package com.joel.thordoctor.modules.gamesbios.diagnostics

import com.joel.thordoctor.modules.gamesbios.library.GameLibraryEntry
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryScanResult
import com.joel.thordoctor.modules.gamesbios.organization.RomClassification
import com.joel.thordoctor.modules.gamesbios.organization.RomPlatformClassifier

enum class GameLibraryPlatformDiagnosticKind {
    UNRESOLVED,
}

data class GameLibraryPlatformDiagnostic(
    val entry: GameLibraryEntry,
    val classification: RomClassification,
    val kind: GameLibraryPlatformDiagnosticKind,
)

/**
 * Pure evaluator for conservative platform diagnostics over an existing game-library scan.
 *
 * It does not perform IO or mutate/persist scan results. Classification is derived only from the
 * entry name through the existing [RomPlatformClassifier]; unresolved evidence remains unresolved.
 */
object GameLibraryPlatformDiagnosticEvaluator {

    fun evaluate(
        scanResult: GameLibraryScanResult,
    ): List<GameLibraryPlatformDiagnostic> =
        scanResult.games.mapNotNull(::evaluate)

    fun evaluate(
        entry: GameLibraryEntry,
    ): GameLibraryPlatformDiagnostic? {
        val classification = RomPlatformClassifier.classify(entry.name)
        if (classification.isUnambiguous) {
            return null
        }

        return GameLibraryPlatformDiagnostic(
            entry = entry,
            classification = classification,
            kind = GameLibraryPlatformDiagnosticKind.UNRESOLVED,
        )
    }
}
