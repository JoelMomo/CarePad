package com.joel.thordoctor.modules.gamesbios.diagnostics

import com.joel.thordoctor.modules.gamesbios.library.GameLibraryEntry

enum class CueBinDiagnosticKind {
    MISSING_REFERENCED_BIN,
}

data class CueBinDiagnostic(
    val cueEntry: GameLibraryEntry,
    val referencedBinPath: String,
    val kind: CueBinDiagnosticKind,
)

/**
 * Pure, read-only evaluator for unambiguous CUE -> BIN inconsistencies.
 *
 * [cueContents] must contain the readable CUE text and [availableRelativePaths] must represent a
 * complete readable file listing for the authorized library root. If either piece of evidence is
 * unavailable, the evaluator returns no diagnostic rather than guessing.
 *
 * Only explicit `FILE ... BINARY` references ending in `.bin` are considered. Absolute paths,
 * parent traversal and malformed/unsupported FILE statements are ignored conservatively.
 */
object CueBinDiagnosticEvaluator {

    private val quotedFilePattern =
        Regex(
            pattern = """^\s*FILE\s+"([^"]+)"\s+BINARY\s*$""",
            option = RegexOption.IGNORE_CASE,
        )

    private val unquotedFilePattern =
        Regex(
            pattern = """^\s*FILE\s+([^\s"]+)\s+BINARY\s*$""",
            option = RegexOption.IGNORE_CASE,
        )

    private val windowsAbsolutePathPattern =
        Regex("^[A-Za-z]:/")

    fun evaluate(
        cueEntry: GameLibraryEntry,
        cueContents: String?,
        availableRelativePaths: Set<String>?,
    ): List<CueBinDiagnostic> {
        if (!cueEntry.extension.equals("cue", ignoreCase = true)) {
            return emptyList()
        }

        if (cueContents == null || availableRelativePaths == null) {
            return emptyList()
        }

        val cuePath =
            normalizeRelativePath(cueEntry.relativePath)
                ?: return emptyList()

        val cueDirectory =
            cuePath.substringBeforeLast('/', "")

        val availablePaths =
            availableRelativePaths
                .mapNotNull(::normalizeRelativePath)
                .map { it.lowercase() }
                .toSet()

        val missingPaths =
            linkedSetOf<String>()

        cueContents.lineSequence().forEach { line ->
            val reference =
                parseReferencedBin(line)
                    ?: return@forEach

            val resolvedPath =
                normalizeRelativePath(
                    if (cueDirectory.isBlank()) {
                        reference
                    } else {
                        "$cueDirectory/$reference"
                    }
                )
                    ?: return@forEach

            if (resolvedPath.lowercase() !in availablePaths) {
                missingPaths += resolvedPath
            }
        }

        return missingPaths.map { path ->
            CueBinDiagnostic(
                cueEntry = cueEntry,
                referencedBinPath = path,
                kind = CueBinDiagnosticKind.MISSING_REFERENCED_BIN,
            )
        }
    }

    private fun parseReferencedBin(
        line: String,
    ): String? {
        val rawReference =
            quotedFilePattern
                .matchEntire(line)
                ?.groupValues
                ?.get(1)
                ?: unquotedFilePattern
                    .matchEntire(line)
                    ?.groupValues
                    ?.get(1)
                ?: return null

        val normalizedReference =
            normalizeRelativePath(rawReference)
                ?: return null

        return normalizedReference.takeIf {
            it.substringAfterLast('/')
                .endsWith(".bin", ignoreCase = true)
        }
    }

    private fun normalizeRelativePath(
        rawPath: String,
    ): String? {
        val path =
            rawPath
                .trim()
                .replace('\\', '/')

        if (
            path.isBlank() ||
            path.startsWith('/') ||
            path.contains("://") ||
            windowsAbsolutePathPattern.containsMatchIn(path)
        ) {
            return null
        }

        val segments =
            path.split('/')

        if (
            segments.any {
                it.isBlank() ||
                    it == "." ||
                    it == ".."
            }
        ) {
            return null
        }

        return segments.joinToString("/")
    }
}
