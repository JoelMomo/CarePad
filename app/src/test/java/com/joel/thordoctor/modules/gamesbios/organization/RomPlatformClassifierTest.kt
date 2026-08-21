package com.joel.thordoctor.modules.gamesbios.organization

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
    fun ambiguousContainerClassificationIsNotUnambiguous() {
        val classification = RomPlatformClassifier.classify("disc.iso")

        assertFalse(classification.isUnambiguous)
    }
}
