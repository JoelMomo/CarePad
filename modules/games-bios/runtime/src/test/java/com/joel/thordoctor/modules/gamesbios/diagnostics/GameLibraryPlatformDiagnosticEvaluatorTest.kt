package com.joel.thordoctor.modules.gamesbios.diagnostics

import com.joel.thordoctor.modules.gamesbios.library.GameLibraryEntry
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryScanResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameLibraryPlatformDiagnosticEvaluatorTest {

    @Test
    fun resolvedClassificationProducesNoDiagnostic() {
        val entry = entry("game.gba")

        val diagnostic = GameLibraryPlatformDiagnosticEvaluator.evaluate(entry)

        assertNull(diagnostic)
    }

    @Test
    fun unresolvedClassificationProducesUnresolvedDiagnostic() {
        val entry = entry("disc.iso")

        val diagnostic = GameLibraryPlatformDiagnosticEvaluator.evaluate(entry)

        assertNotNull(diagnostic)
        requireNotNull(diagnostic)
        assertEquals(GameLibraryPlatformDiagnosticKind.UNRESOLVED, diagnostic.kind)
        assertEquals(entry, diagnostic.entry)
        assertEquals("disc.iso", diagnostic.classification.fileName)
        assertTrue(diagnostic.classification.candidates.isEmpty())
    }

    @Test
    fun mixedScanReturnsOnlyUnresolvedEntriesDeterministically() {
        val scanResult = GameLibraryScanResult(
            folderName = "Games",
            gameCount = 3,
            scannedAt = 1234L,
            games = listOf(
                entry("resolved.gba", "Nintendo/resolved.gba"),
                entry("disc.iso", "Sony/disc.iso"),
                entry("archive.chd", "Arcade/archive.chd"),
            ),
        )

        val first = GameLibraryPlatformDiagnosticEvaluator.evaluate(scanResult)
        val second = GameLibraryPlatformDiagnosticEvaluator.evaluate(scanResult)

        assertEquals(first, second)
        assertEquals(
            listOf("Sony/disc.iso", "Arcade/archive.chd"),
            first.map { it.entry.relativePath },
        )
        assertTrue(first.all { it.kind == GameLibraryPlatformDiagnosticKind.UNRESOLVED })
        assertEquals(3, scanResult.games.size)
        assertEquals(3, scanResult.gameCount)
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
