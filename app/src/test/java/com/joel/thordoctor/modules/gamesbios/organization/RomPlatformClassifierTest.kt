package com.joel.thordoctor.modules.gamesbios.organization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomPlatformClassifierTest {

    @Test
    fun singleLowConfidenceCandidateIsNotUnambiguous() {
        val classification = RomClassification(
            fileName = "ambiguous.rom",
            candidates = listOf(
                RomPlatformCandidate(
                    platform = RomPlatform.PLAYSTATION,
                    confidence = ClassificationConfidence.LOW,
                    reason = "insufficient evidence",
                )
            ),
        )

        assertFalse(classification.isUnambiguous)
    }

    @Test
    fun singleMediumConfidenceCandidateIsUnambiguous() {
        val classification = RomClassification(
            fileName = "game.gba",
            candidates = listOf(
                RomPlatformCandidate(
                    platform = RomPlatform.GAME_BOY_ADVANCE,
                    confidence = ClassificationConfidence.MEDIUM,
                    reason = "platform-specific extension",
                )
            ),
        )

        assertTrue(classification.isUnambiguous)
    }

    @Test
    fun genericContainersStayUnresolved() {
        listOf("disc.iso", "disc.bin", "disc.cue", "disc.chd").forEach { fileName ->
            val classification = RomPlatformClassifier.classify(fileName)

            assertFalse(classification.isUnambiguous)
            assertTrue(classification.candidates.isEmpty())
        }
    }

    @Test
    fun recommendedPrefixCoversCurrentHeaderChecks() {
        assertEquals(0x150, RomPlatformClassifier.RECOMMENDED_HEADER_PREFIX_BYTES)
    }

    @Test
    fun iNesSignatureIsHighConfidence() {
        val classification = RomPlatformClassifier.classify(
            fileName = "game.rom",
            headerPrefix = byteArrayOf(0x4E, 0x45, 0x53, 0x1A),
        )

        assertCandidate(
            classification = classification,
            platform = RomPlatform.NES,
            confidence = ClassificationConfidence.HIGH,
        )
    }

    @Test
    fun binaryHeaderTakesPrecedenceOverExtension() {
        val classification = RomPlatformClassifier.classify(
            fileName = "misleading.gba",
            headerPrefix = byteArrayOf(0x4E, 0x45, 0x53, 0x1A),
        )

        assertCandidate(
            classification = classification,
            platform = RomPlatform.NES,
            confidence = ClassificationConfidence.HIGH,
        )
    }

    @Test
    fun allCommonNintendo64ByteOrdersAreHighConfidence() {
        val signatures = listOf(
            intArrayOf(0x80, 0x37, 0x12, 0x40),
            intArrayOf(0x37, 0x80, 0x40, 0x12),
            intArrayOf(0x12, 0x40, 0x80, 0x37),
            intArrayOf(0x40, 0x12, 0x37, 0x80),
        )

        signatures.forEach { signature ->
            val classification = RomPlatformClassifier.classify(
                fileName = "game.rom",
                headerPrefix = signature.map(Int::toByte).toByteArray(),
            )

            assertCandidate(
                classification = classification,
                platform = RomPlatform.NINTENDO_64,
                confidence = ClassificationConfidence.HIGH,
            )
        }
    }

    @Test
    fun partialGbaNintendoLogoIsMediumConfidence() {
        val header = ByteArray(12)
        intArrayOf(0x24, 0xFF, 0xAE, 0x51, 0x69, 0x9A, 0xA2, 0x21)
            .forEachIndexed { index, value ->
                header[4 + index] = value.toByte()
            }

        val classification = RomPlatformClassifier.classify("game.rom", header)

        assertCandidate(
            classification = classification,
            platform = RomPlatform.GAME_BOY_ADVANCE,
            confidence = ClassificationConfidence.MEDIUM,
        )
    }

    @Test
    fun partialNdsNintendoLogoIsMediumConfidence() {
        val header = ByteArray(0xC8)
        intArrayOf(0x24, 0xFF, 0xAE, 0x51, 0x69, 0x9A, 0xA2, 0x21)
            .forEachIndexed { index, value ->
                header[0xC0 + index] = value.toByte()
            }

        val classification = RomPlatformClassifier.classify("game.rom", header)

        assertCandidate(
            classification = classification,
            platform = RomPlatform.NINTENDO_DS,
            confidence = ClassificationConfidence.MEDIUM,
        )
    }

    @Test
    fun gameBoyFullLogoAndValidChecksumAreHighConfidence() {
        val classification = RomPlatformClassifier.classify(
            fileName = "game.rom",
            headerPrefix = gameBoyHeader(colorFlag = 0x00, validChecksum = true),
        )

        assertCandidate(
            classification = classification,
            platform = RomPlatform.GAME_BOY,
            confidence = ClassificationConfidence.HIGH,
        )
    }

    @Test
    fun gameBoyColorFlagDistinguishesColorWithHighConfidence() {
        val classification = RomPlatformClassifier.classify(
            fileName = "game.rom",
            headerPrefix = gameBoyHeader(colorFlag = 0x80, validChecksum = true),
        )

        assertCandidate(
            classification = classification,
            platform = RomPlatform.GAME_BOY_COLOR,
            confidence = ClassificationConfidence.HIGH,
        )
    }

    @Test
    fun invalidGameBoyChecksumDegradesToMediumConfidence() {
        val classification = RomPlatformClassifier.classify(
            fileName = "game.rom",
            headerPrefix = gameBoyHeader(colorFlag = 0x80, validChecksum = false),
        )

        assertCandidate(
            classification = classification,
            platform = RomPlatform.GAME_BOY_COLOR,
            confidence = ClassificationConfidence.MEDIUM,
        )
    }

    @Test
    fun partialGameBoyLogoWithColorFlagStaysMediumConfidence() {
        val header = ByteArray(0x144)
        GAME_BOY_NINTENDO_LOGO.take(8).forEachIndexed { index, value ->
            header[0x104 + index] = value.toByte()
        }
        header[0x143] = 0x80.toByte()

        val classification = RomPlatformClassifier.classify("game.rom", header)

        assertCandidate(
            classification = classification,
            platform = RomPlatform.GAME_BOY_COLOR,
            confidence = ClassificationConfidence.MEDIUM,
        )
    }

    @Test
    fun truncatedGameBoyHeaderWithoutColorFlagDoesNotGuess() {
        val header = ByteArray(0x143)
        GAME_BOY_NINTENDO_LOGO.take(8).forEachIndexed { index, value ->
            header[0x104 + index] = value.toByte()
        }

        val classification = RomPlatformClassifier.classify("game.rom", header)

        assertTrue(classification.candidates.isEmpty())
        assertFalse(classification.isUnambiguous)
    }

    @Test
    fun extensionMatchingTrimsTrailingWhitespace() {
        val classification = RomPlatformClassifier.classify("game.GBA   ")

        assertCandidate(
            classification = classification,
            platform = RomPlatform.GAME_BOY_ADVANCE,
            confidence = ClassificationConfidence.MEDIUM,
        )
    }

    private fun assertCandidate(
        classification: RomClassification,
        platform: RomPlatform,
        confidence: ClassificationConfidence,
    ) {
        assertEquals(1, classification.candidates.size)
        assertEquals(platform, classification.candidates.single().platform)
        assertEquals(confidence, classification.candidates.single().confidence)
        assertTrue(classification.isUnambiguous)
    }

    private fun gameBoyHeader(
        colorFlag: Int,
        validChecksum: Boolean,
    ): ByteArray {
        val header = ByteArray(RomPlatformClassifier.RECOMMENDED_HEADER_PREFIX_BYTES)

        GAME_BOY_NINTENDO_LOGO.forEachIndexed { index, value ->
            header[0x104 + index] = value.toByte()
        }
        header[0x143] = colorFlag.toByte()

        var checksum = 0
        for (offset in 0x134..0x14C) {
            checksum = (checksum - (header[offset].toInt() and 0xFF) - 1) and 0xFF
        }
        header[0x14D] = if (validChecksum) {
            checksum.toByte()
        } else {
            ((checksum + 1) and 0xFF).toByte()
        }

        return header
    }

    private companion object {
        val GAME_BOY_NINTENDO_LOGO = intArrayOf(
            0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B,
            0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D,
            0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E,
            0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99,
            0xBB, 0xBB, 0x67, 0x63, 0x6E, 0x0E, 0xEC, 0xCC,
            0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E,
        )
    }
}
