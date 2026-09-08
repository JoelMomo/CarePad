package com.joel.thordoctor.modules.catalog.distribution

import carepad.contracts.ModuleProtocolRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ModuleDistributionCatalogTest {
    @Test
    fun latestPointerSelectsExactActiveCompatibleArtifact() {
        val artifact = artifact()
        val entry = entry(artifact)

        val result = entry.resolveLatest(
            channel = DEVELOPMENT_QA_CHANNEL,
            hostProtocolVersion = 1,
        )

        assertSame(artifact, (result as ModuleDistributionResolution.Ready).artifact)
    }

    @Test
    fun resolverDoesNotInferLatestByVersionOrdering() {
        val declared = artifact(artifactId = "declared", versionCode = 1)
        val newerButNotPointed = artifact(artifactId = "newer", versionCode = 2)
        val entry = entry(
            artifact = declared,
            artifacts = listOf(declared, newerButNotPointed),
        )

        val result = entry.resolveLatest(DEVELOPMENT_QA_CHANNEL, 1)

        assertSame(declared, (result as ModuleDistributionResolution.Ready).artifact)
    }

    @Test
    fun incompatibleOrUnavailableArtifactIsRejected() {
        val unavailable = artifact(
            availability = ModuleArtifactAvailability.UNAVAILABLE,
        )
        assertEquals(
            ModuleDistributionResolution.Reason.UNAVAILABLE,
            (entry(unavailable).resolveLatest(DEVELOPMENT_QA_CHANNEL, 1)
                as ModuleDistributionResolution.Rejected).reason,
        )

        val incompatible = artifact(protocolRange = ModuleProtocolRange(2, 2))
        assertEquals(
            ModuleDistributionResolution.Reason.PROTOCOL_INCOMPATIBLE,
            (entry(incompatible).resolveLatest(DEVELOPMENT_QA_CHANNEL, 1)
                as ModuleDistributionResolution.Rejected).reason,
        )
    }

    private fun entry(
        artifact: ModuleDistributionArtifact,
        artifacts: List<ModuleDistributionArtifact> = listOf(artifact),
    ) = ModuleDistributionEntry(
        moduleId = artifact.moduleId,
        packageName = artifact.packageName,
        latestByChannel = mapOf(DEVELOPMENT_QA_CHANNEL to artifact.artifactId),
        lastKnownRecoverableByChannel = emptyMap(),
        artifacts = artifacts,
    )

    private fun artifact(
        artifactId: String = "performance@1#sha256:${"a".repeat(64)}",
        versionCode: Long = 1,
        protocolRange: ModuleProtocolRange = ModuleProtocolRange(1, 1),
        availability: ModuleArtifactAvailability = ModuleArtifactAvailability.AVAILABLE,
    ) = ModuleDistributionArtifact(
        artifactId = artifactId,
        moduleId = "performance",
        packageName = "dev.carepad.module.performance",
        versionCode = versionCode,
        versionName = "0.1.0",
        protocolRange = protocolRange,
        apkSha256 = "a".repeat(64),
        signingCertificateSha256 = "b".repeat(64),
        sizeBytes = 1024,
        sources = listOf("asset://carepad-development-qa/modules/performance.apk"),
        releaseTag = null,
        commitSha = "6d9adda7",
        maturity = "development",
        channels = setOf(DEVELOPMENT_QA_CHANNEL),
        distributionStatus = ModuleDistributionStatus.ACTIVE,
        recoveryStatus = "candidate",
        recoverySafety = "regenerable",
        availability = availability,
    )
}
