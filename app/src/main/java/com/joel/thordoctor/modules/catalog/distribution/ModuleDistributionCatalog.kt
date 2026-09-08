package com.joel.thordoctor.modules.catalog.distribution

import carepad.contracts.ModuleProtocolRange

internal const val DEVELOPMENT_QA_CHANNEL = "development/QA"

internal enum class ModuleDistributionStatus(val wireValue: String) {
    ACTIVE("active"),
    SUPERSEDED("superseded"),
    WITHDRAWN("withdrawn");

    companion object {
        fun fromWire(value: String): ModuleDistributionStatus? =
            entries.firstOrNull { it.wireValue == value }
    }
}

internal enum class ModuleArtifactAvailability(val wireValue: String) {
    AVAILABLE("available"),
    UNAVAILABLE("unavailable");

    companion object {
        fun fromWire(value: String): ModuleArtifactAvailability? =
            entries.firstOrNull { it.wireValue == value }
    }
}

internal data class ModuleDistributionArtifact(
    val artifactId: String,
    val moduleId: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val protocolRange: ModuleProtocolRange,
    val apkSha256: String,
    val signingCertificateSha256: String,
    val sizeBytes: Long,
    val sources: List<String>,
    val releaseTag: String?,
    val commitSha: String,
    val maturity: String,
    val channels: Set<String>,
    val distributionStatus: ModuleDistributionStatus,
    val recoveryStatus: String,
    val recoverySafety: String,
    val availability: ModuleArtifactAvailability,
) {
    init {
        require(artifactId.isNotBlank())
        require(moduleId.isNotBlank())
        require(packageName.isNotBlank())
        require(versionCode > 0)
        require(versionName.isNotBlank())
        require(apkSha256.matches(SHA_256_REGEX))
        require(signingCertificateSha256.matches(SHA_256_REGEX))
        require(sizeBytes > 0)
        require(sources.isNotEmpty() && sources.none(String::isBlank))
        require(commitSha.isNotBlank())
        require(maturity.isNotBlank())
        require(channels.isNotEmpty())
        require(recoveryStatus.isNotBlank())
        require(recoverySafety.isNotBlank())
    }
}

internal data class ModuleDistributionEntry(
    val moduleId: String,
    val packageName: String,
    val latestByChannel: Map<String, String>,
    val lastKnownRecoverableByChannel: Map<String, String>,
    val artifacts: List<ModuleDistributionArtifact>,
) {
    init {
        require(moduleId.isNotBlank())
        require(packageName.isNotBlank())
        require(artifacts.isNotEmpty())
    }
}

internal data class ModuleDistributionCatalog(
    val schemaVersion: Int,
    val channel: String,
    val modules: List<ModuleDistributionEntry>,
) {
    init {
        require(schemaVersion == 1)
        require(channel.isNotBlank())
        require(modules.map { it.moduleId }.distinct().size == modules.size)
        require(modules.map { it.packageName }.distinct().size == modules.size)
        val artifactIds = modules.flatMap { entry -> entry.artifacts.map { it.artifactId } }
        require(artifactIds.distinct().size == artifactIds.size)
    }
}

internal sealed interface ModuleDistributionResolution {
    data class Ready(val artifact: ModuleDistributionArtifact) : ModuleDistributionResolution
    data class Rejected(val reason: Reason) : ModuleDistributionResolution

    enum class Reason {
        NO_LATEST_FOR_CHANNEL,
        ARTIFACT_NOT_FOUND,
        MODULE_ID_MISMATCH,
        PACKAGE_MISMATCH,
        CHANNEL_MISMATCH,
        NOT_ACTIVE,
        UNAVAILABLE,
        PROTOCOL_INCOMPATIBLE,
    }
}

internal fun ModuleDistributionEntry.resolveLatest(
    channel: String,
    hostProtocolVersion: Int,
): ModuleDistributionResolution {
    val artifactId = latestByChannel[channel]
        ?: return ModuleDistributionResolution.Rejected(
            ModuleDistributionResolution.Reason.NO_LATEST_FOR_CHANNEL
        )
    val artifact = artifacts.singleOrNull { it.artifactId == artifactId }
        ?: return ModuleDistributionResolution.Rejected(
            ModuleDistributionResolution.Reason.ARTIFACT_NOT_FOUND
        )
    if (artifact.moduleId != moduleId) {
        return ModuleDistributionResolution.Rejected(
            ModuleDistributionResolution.Reason.MODULE_ID_MISMATCH
        )
    }
    if (artifact.packageName != packageName) {
        return ModuleDistributionResolution.Rejected(
            ModuleDistributionResolution.Reason.PACKAGE_MISMATCH
        )
    }
    if (channel !in artifact.channels) {
        return ModuleDistributionResolution.Rejected(
            ModuleDistributionResolution.Reason.CHANNEL_MISMATCH
        )
    }
    if (artifact.distributionStatus != ModuleDistributionStatus.ACTIVE) {
        return ModuleDistributionResolution.Rejected(
            ModuleDistributionResolution.Reason.NOT_ACTIVE
        )
    }
    if (artifact.availability != ModuleArtifactAvailability.AVAILABLE) {
        return ModuleDistributionResolution.Rejected(
            ModuleDistributionResolution.Reason.UNAVAILABLE
        )
    }
    if (!artifact.protocolRange.supports(hostProtocolVersion)) {
        return ModuleDistributionResolution.Rejected(
            ModuleDistributionResolution.Reason.PROTOCOL_INCOMPATIBLE
        )
    }
    return ModuleDistributionResolution.Ready(artifact)
}

private val SHA_256_REGEX = Regex("^[0-9a-f]{64}$")
