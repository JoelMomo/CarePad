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
 * Conservative ROM platform classifier for Juegos y BIOS.
 *
 * This class performs no file IO and makes no storage decisions. Callers may optionally provide an
 * already-read header prefix. Recognized binary signatures take precedence over filename evidence;
 * otherwise ambiguous extensions remain unresolved instead of guessing a destination.
 */
object RomPlatformClassifier {
    /** Covers every header offset currently inspected, including the Game Boy header checksum. */
    const val RECOMMENDED_HEADER_PREFIX_BYTES = 0x150

    private val GAME_BOY_NINTENDO_LOGO = intArrayOf(
        0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B,
        0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D,
        0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E,
        0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99,
        0xBB, 0xBB, 0x67, 0x63, 0x6E, 0x0E, 0xEC, 0xCC,
        0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E,
    )

    fun classify(fileName: String): RomClassification =
        classify(fileName, headerPrefix = null)

    fun classify(fileName: String, headerPrefix: ByteArray?): RomClassification {
        detectByHeader(headerPrefix)?.let { headerCandidate ->
            return RomClassification(
                fileName = fileName,
                candidates = listOf(headerCandidate),
            )
        }

        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
            .trim()
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
            "gdi" -> single(
                RomPlatform.DREAMCAST,
                "Extension .gdi is strongly associated with Dreamcast",
            )
            "iso", "bin", "cue", "chd" -> emptyList()
            else -> emptyList()
        }

        return RomClassification(fileName = fileName, candidates = candidates)
    }

    private fun detectByHeader(header: ByteArray?): RomPlatformCandidate? {
        if (header == null) return null

        if (header.matches(0, 0x4E, 0x45, 0x53, 0x1A)) {
            return candidate(
                RomPlatform.NES,
                ClassificationConfidence.HIGH,
                "iNES header signature",
            )
        }

        if (
            header.matches(0, 0x80, 0x37, 0x12, 0x40) ||
            header.matches(0, 0x37, 0x80, 0x40, 0x12) ||
            header.matches(0, 0x12, 0x40, 0x80, 0x37) ||
            header.matches(0, 0x40, 0x12, 0x37, 0x80)
        ) {
            return candidate(
                RomPlatform.NINTENDO_64,
                ClassificationConfidence.HIGH,
                "Nintendo 64 byte-order header signature",
            )
        }

        if (header.matches(4, 0x24, 0xFF, 0xAE, 0x51, 0x69, 0x9A, 0xA2, 0x21)) {
            return candidate(
                RomPlatform.GAME_BOY_ADVANCE,
                ClassificationConfidence.MEDIUM,
                "Partial Game Boy Advance Nintendo logo signature",
            )
        }

        if (header.matches(0xC0, 0x24, 0xFF, 0xAE, 0x51, 0x69, 0x9A, 0xA2, 0x21)) {
            return candidate(
                RomPlatform.NINTENDO_DS,
                ClassificationConfidence.MEDIUM,
                "Partial Nintendo DS Nintendo logo signature",
            )
        }

        val colorFlag = header.getOrNull(0x143)
        if (colorFlag != null && header.matches(0x104, *GAME_BOY_NINTENDO_LOGO)) {
            val isColor = colorFlag.toInt() and 0x80 == 0x80
            val checksumIsValid = header.hasValidGameBoyHeaderChecksum()
            return candidate(
                if (isColor) RomPlatform.GAME_BOY_COLOR else RomPlatform.GAME_BOY,
                if (checksumIsValid) ClassificationConfidence.HIGH else ClassificationConfidence.MEDIUM,
                if (checksumIsValid) {
                    "Game Boy Nintendo logo and header checksum"
                } else {
                    "Game Boy Nintendo logo; header checksum unavailable or invalid"
                },
            )
        }

        if (
            colorFlag != null &&
            header.matches(0x104, 0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B)
        ) {
            val isColor = colorFlag.toInt() and 0x80 == 0x80
            return candidate(
                if (isColor) RomPlatform.GAME_BOY_COLOR else RomPlatform.GAME_BOY,
                ClassificationConfidence.MEDIUM,
                "Partial Game Boy cartridge Nintendo logo signature plus color flag",
            )
        }

        return null
    }

    private fun ByteArray.hasValidGameBoyHeaderChecksum(): Boolean {
        val expected = getOrNull(0x14D)?.toInt()?.and(0xFF) ?: return false
        var calculated = 0
        for (offset in 0x134..0x14C) {
            calculated = (calculated - (this[offset].toInt() and 0xFF) - 1) and 0xFF
        }
        return calculated == expected
    }

    private fun ByteArray.matches(offset: Int, vararg expected: Int): Boolean {
        if (offset < 0 || size < offset + expected.size) return false
        return expected.indices.all { index ->
            (this[offset + index].toInt() and 0xFF) == expected[index]
        }
    }

    private fun candidate(
        platform: RomPlatform,
        confidence: ClassificationConfidence,
        reason: String,
    ) = RomPlatformCandidate(
        platform = platform,
        confidence = confidence,
        reason = reason,
    )

    private fun single(platform: RomPlatform, reason: String) = listOf(
        candidate(
            platform = platform,
            confidence = ClassificationConfidence.MEDIUM,
            reason = reason,
        ),
    )
}
