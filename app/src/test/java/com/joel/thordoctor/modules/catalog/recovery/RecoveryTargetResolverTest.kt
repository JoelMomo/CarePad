package com.joel.thordoctor.modules.catalog.recovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTargetResolverTest {

    @Test
    fun resolvesOnlyExplicitLvrkPointerWithoutSortingVersions() {
        val pointed = target("performance@1", versionCode = 1)
        val newerRecoverable = target("performance@2", versionCode = 2)
        val catalog = catalog(
            pointer = pointed.artifactId,
            artifacts = listOf(
                record(pointed),
                record(newerRecoverable)
            )
        )

        val resolution = RecoveryTargetResolver.resolve(
            catalog = catalog,
            channel = "public",
            installed = installed(versionCode = 3),
            hostProtocolVersion = 1
        )

        assertEquals(
            pointed,
            (resolution as RecoveryTargetResolution.Available).target
        )
    }

    @Test
    fun candidateAndBlockedPointersAreNeverRecoveryTargets() {
        listOf(RecoveryStatus.CANDIDATE, RecoveryStatus.BLOCKED).forEach { status ->
            val candidate = target("performance@1", versionCode = 1)
            val resolution = RecoveryTargetResolver.resolve(
                catalog = catalog(
                    pointer = candidate.artifactId,
                    artifacts = listOf(record(candidate, status = status))
                ),
                channel = "public",
                installed = installed(versionCode = 2),
                hostProtocolVersion = 1
            )

            assertEquals(
                RecoveryTargetUnavailableReason.NOT_RECOVERABLE,
                (resolution as RecoveryTargetResolution.Unavailable).reason
            )
        }
    }

    @Test
    fun unavailableArtifactRequiresVerifiedLocalCopy() {
        val target = target("performance@1", versionCode = 1, sources = emptyList())
        val catalog = catalog(
            pointer = target.artifactId,
            artifacts = listOf(
                record(target, availability = RecoveryAvailability.UNAVAILABLE)
            )
        )

        val unavailable = RecoveryTargetResolver.resolve(
            catalog = catalog,
            channel = "public",
            installed = installed(versionCode = 2),
            hostProtocolVersion = 1
        )
        assertEquals(
            RecoveryTargetUnavailableReason.ARTIFACT_UNAVAILABLE,
            (unavailable as RecoveryTargetResolution.Unavailable).reason
        )

        val cached = RecoveryTargetResolver.resolve(
            catalog = catalog,
            channel = "public",
            installed = installed(versionCode = 2),
            hostProtocolVersion = 1,
            verifiedLocalArtifactIds = setOf(target.artifactId)
        )
        assertEquals(target, (cached as RecoveryTargetResolution.Available).target)
    }

    @Test
    fun rejectsTargetThatIsNotOlderThanInstalledVersion() {
        val target = target("performance@3", versionCode = 3)
        val resolution = RecoveryTargetResolver.resolve(
            catalog = catalog(target.artifactId, listOf(record(target))),
            channel = "public",
            installed = installed(versionCode = 3),
            hostProtocolVersion = 1
        )

        assertEquals(
            RecoveryTargetUnavailableReason.TARGET_NOT_OLDER,
            (resolution as RecoveryTargetResolution.Unavailable).reason
        )
    }

    @Test
    fun rejectsProtocolIncompatibleTarget() {
        val target = target(
            artifactId = "performance@1",
            versionCode = 1,
            protocol = RecoveryProtocolRange(2, 3)
        )
        val resolution = RecoveryTargetResolver.resolve(
            catalog = catalog(target.artifactId, listOf(record(target))),
            channel = "public",
            installed = installed(versionCode = 2),
            hostProtocolVersion = 1
        )

        assertEquals(
            RecoveryTargetUnavailableReason.PROTOCOL_INCOMPATIBLE,
            (resolution as RecoveryTargetResolution.Unavailable).reason
        )
    }

    @Test
    fun sourceFallbackNeverChangesArtifactId() {
        val target = target(
            artifactId = "performance@1",
            versionCode = 1,
            sources = listOf("https://one.invalid/module.apk", "https://two.invalid/module.apk")
        )

        val second = RecoveryArtifactSourceSelector.select(
            target,
            failedOfficialSources = setOf("https://one.invalid/module.apk")
        )
        assertEquals(
            RecoveryArtifactSource.OfficialSource("https://two.invalid/module.apk"),
            second
        )

        val local = RecoveryArtifactSourceSelector.select(
            target,
            failedOfficialSources = target.sources.toSet(),
            hasVerifiedLocalCopy = true
        )
        assertTrue(local === RecoveryArtifactSource.VerifiedLocalCache)
    }

    private fun catalog(
        pointer: String,
        artifacts: List<RecoveryArtifactRecord>
    ) = ModuleRecoveryCatalog(
        moduleId = MODULE_ID,
        packageName = PACKAGE_NAME,
        lastKnownRecoverableByChannel = mapOf("public" to pointer),
        artifacts = artifacts
    )

    private fun installed(versionCode: Long) = InstalledModuleRecoveryIdentity(
        moduleId = MODULE_ID,
        packageName = PACKAGE_NAME,
        versionCode = versionCode
    )

    private fun record(
        target: RecoveryTarget,
        status: RecoveryStatus = RecoveryStatus.RECOVERABLE,
        availability: RecoveryAvailability = RecoveryAvailability.AVAILABLE
    ) = RecoveryArtifactRecord(target, status, availability)

    private fun target(
        artifactId: String,
        versionCode: Long,
        protocol: RecoveryProtocolRange = RecoveryProtocolRange(1, 1),
        sources: List<String> = listOf("https://official.invalid/$artifactId.apk")
    ) = RecoveryTarget(
        artifactId = artifactId,
        moduleId = MODULE_ID,
        packageName = PACKAGE_NAME,
        versionCode = versionCode,
        versionName = "1.$versionCode",
        apkSha256 = "a".repeat(64),
        signingCertificateSha256 = "b".repeat(64),
        protocol = protocol,
        sources = sources,
        recoverySafety = RecoverySafety.REGENERABLE
    )

    private companion object {
        const val MODULE_ID = "performance"
        const val PACKAGE_NAME = "dev.carepad.module.performance"
    }
}
