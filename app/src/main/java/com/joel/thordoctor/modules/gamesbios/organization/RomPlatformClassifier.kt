package com.joel.thordoctor.modules.gamesbios.organization

import java.util.Locale

enum class RomPlatform {
    GAME_BOY,
    GAME_BOY_COLOR,
    GAME_BOY_ADVANCE,
    NINTENDO_DS,
    NINTENDO_3DS,
    NINTENDO_64,
    GAMECUBE,
    WII,
    NES,
    SNES,
    PLAYSTATION,
    PLAYSTATION_2,
    PSP,
    SEGA_GENESIS,
    SEGA_CD,
    DREAMCAST,
}

enum class ClassificationConfidence {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN,
}

data class RomPlatformCandidate(
    val platform: RomPlatform,
    val confidence: ClassificationConfidence,
    val reason: String,
)

data class RomClassification(
    val fileName: String,
    val candidates: List<RomPlatformCandidate>,
) {
    val isUnambiguous: Boolean
        get() = candidates.size == 1 &&
            candidates.first().confidence in setOf(
                ClassificationConfidence.HIGH,
                ClassificationConfidence.MEDIUM,
            )
}

/**
 * Conservative first-pass ROM platform classifier for Juegos y BIOS.
 *
 * This class deliberately performs no file IO and makes no storage decisions. It only evaluates
 * filename evidence. Ambiguous container formats return multiple low-confidence candidates instead
 * of guessing a destination. Header/magic-byte inspection can be layered on later without changing
 * callers of [classify].
 */
object RomPlatformClassifier {
    fun classify(fileName: String): RomClassification {
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase(Locale.ROOT)

        val candidates = when (extension) {
            "gb" -> single(RomPlatform.GAME_BOY, "Extension .gb is platform-specific")
            "gbc" -> single(RomPlatform.GAME_BOY_COLOR, "Extension .gbc is platform-specific")
            "gba" -> single(RomPlatform.GAME_BOY_ADVANCE, "Extension .gba is platform-specific")
            "nds" -> single(RomPlatform.NINTENDO_DS, "Extension .nds is platform-specific")
            "3ds", "cci", "cxi" -> single(
                RomPlatform.NINTENDO_3DS,
                "Nintendo 3DS container extension .$extension",
            )
            "n64", "z64", "v64" -> single(
                RomPlatform.NINTENDO_64,
                "Nintendo 64 ROM extension .$extension",
            )
            "nes" -> single(RomPlatform.NES, "Extension .nes is platform-specific")
            "sfc", "smc" -> single(RomPlatform.SNES, "Common SNES ROM extension .$extension")
            "md", "gen" -> single(
                RomPlatform.SEGA_GENESIS,
                "Common Sega Genesis / Mega Drive ROM extension .$extension",
            )
            "gdi" -> single(RomPlatform.DREAMCAST, "Extension .gdi is strongly associated with Dreamcast")
            "iso" -> ambiguous(
                listOf(RomPlatform.PLAYSTATION_2, RomPlatform.PSP, RomPlatform.GAMECUBE, RomPlatform.WII),
                "Extension .iso is shared by several disc-based platforms; header inspection is required",
            )
            "bin", "cue" -> ambiguous(
                listOf(RomPlatform.PLAYSTATION, RomPlatform.SEGA_CD),
                "Extension .$extension is shared by multiple CD-based platforms; companion files or disc metadata are required",
            )
            "chd" -> ambiguous(
                listOf(
                    RomPlatform.PLAYSTATION,
                    RomPlatform.PLAYSTATION_2,
                    RomPlatform.SEGA_CD,
                    RomPlatform.DREAMCAST,
                ),
                "CHD is a multi-platform container; internal metadata is required",
            )
            else -> emptyList()
        }

        return RomClassification(fileName = fileName, candidates = candidates)
    }

    private fun single(platform: RomPlatform, reason: String) = listOf(
        RomPlatformCandidate(
            platform = platform,
            confidence = ClassificationConfidence.MEDIUM,
            reason = reason,
        ),
    )

    private fun ambiguous(platforms: List<RomPlatform>, reason: String) = platforms.map { platform ->
        RomPlatformCandidate(
            platform = platform,
            confidence = ClassificationConfidence.LOW,
            reason = reason,
        )
    }
}
