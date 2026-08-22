package com.joel.thordoctor.modules.catalog.recovery

enum class DistributionStatus {
    ACTIVE,
    SUPERSEDED,
    WITHDRAWN,
}

enum class RecoveryStatus {
    CANDIDATE,
    RECOVERABLE,
    BLOCKED,
}

enum class RecoverySafety {
    REGENERABLE,
    BACKUP_RESTORE_REQUIRED,
    DATA_LOSS_POSSIBLE,
}

enum class ArtifactAvailability {
    AVAILABLE,
    UNAVAILABLE,
}

data class RecoveryProtocolRange(
    val min: Int,
    val max: Int,
) {
    init {
        require(min > 0 && max >= min)
    }

    fun supports(protocolVersion: Int): Boolean = protocolVersion in min..max
}

data class RecoveryCatalogModule(
    val moduleId: String,
    val packageName: String,
    val lastKnownRecoverableByChannel: Map<String, String>,
)

data class RecoveryCatalogArtifact(
    val artifactId: String,
    val moduleId: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val protocolRange: RecoveryProtocolRange,
    val apkSha256: String,
    val signingCertificateSha256: String,
    val sizeBytes: Long,
    val sources: List<String>,
    val releaseTag: String,
    val commitSha: String,
    val maturity: String,
    val channels: Set<String>,
    val distributionStatus: DistributionStatus,
    val recoveryStatus: RecoveryStatus,
    val recoveryQualifiedAt: String?,
    val recoverySafety: RecoverySafety,
    val availability: ArtifactAvailability,
)

data class RecoveryTarget(
    val artifactId: String,
    val moduleId: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkSha256: String,
    val signingCertificateSha256: String,
    val sizeBytes: Long,
    val protocolRange: RecoveryProtocolRange,
    val sources: List<String>,
    val recoverySafety: RecoverySafety,
)

enum class RecoveryTargetUnavailableReason {
    NO_LVRK_FOR_CHANNEL,
    CATALOG_ARTIFACT_MISSING,
    MODULE_ID_MISMATCH,
    PACKAGE_NAME_MISMATCH,
    NOT_RECOVERABLE,
    WITHDRAWN,
    NOT_OLDER_THAN_INSTALLED,
    HOST_PROTOCOL_INCOMPATIBLE,
    ARTIFACT_UNAVAILABLE,
}

sealed interface RecoveryTargetResolution {
    data class Available(val target: RecoveryTarget) : RecoveryTargetResolution

    data class Unavailable(
        val reason: RecoveryTargetUnavailableReason,
        val artifactId: String? = null,
    ) : RecoveryTargetResolution
}

/**
 * Resolves only the artifact explicitly referenced by the verified catalog.
 * It deliberately never sorts versions or guesses "the previous version".
 */
object RecoveryTargetResolver {
    fun resolve(
        module: RecoveryCatalogModule,
        artifactsById: Map<String, RecoveryCatalogArtifact>,
        channel: String,
        installedVersionCode: Long,
        hostProtocolVersion: Int,
        locallyVerifiedArtifactIds: Set<String> = emptySet(),
    ): RecoveryTargetResolution {
        val artifactId = module.lastKnownRecoverableByChannel[channel]
            ?: return RecoveryTargetResolution.Unavailable(
                RecoveryTargetUnavailableReason.NO_LVRK_FOR_CHANNEL,
            )
        val artifact = artifactsById[artifactId]
            ?: return RecoveryTargetResolution.Unavailable(
                RecoveryTargetUnavailableReason.CATALOG_ARTIFACT_MISSING,
                artifactId,
            )

        if (artifact.moduleId != module.moduleId) {
            return RecoveryTargetResolution.Unavailable(
                RecoveryTargetUnavailableReason.MODULE_ID_MISMATCH,
                artifactId,
            )
        }
        if (artifact.packageName != module.packageName) {
            return RecoveryTargetResolution.Unavailable(
                RecoveryTargetUnavailableReason.PACKAGE_NAME_MISMATCH,
                artifactId,
            )
        }
        if (artifact.recoveryStatus != RecoveryStatus.RECOVERABLE) {
            return RecoveryTargetResolution.Unavailable(
                RecoveryTargetUnavailableReason.NOT_RECOVERABLE,
                artifactId,
            )
        }
        if (artifact.distributionStatus == DistributionStatus.WITHDRAWN) {
            return RecoveryTargetResolution.Unavailable(
                RecoveryTargetUnavailableReason.WITHDRAWN,
                artifactId,
            )
        }
        if (artifact.versionCode >= installedVersionCode) {
            return RecoveryTargetResolution.Unavailable(
                RecoveryTargetUnavailableReason.NOT_OLDER_THAN_INSTALLED,
                artifactId,
            )
        }
        if (!artifact.protocolRange.supports(hostProtocolVersion)) {
            return RecoveryTargetResolution.Unavailable(
                RecoveryTargetUnavailableReason.HOST_PROTOCOL_INCOMPATIBLE,
                artifactId,
            )
        }
        if (
            artifact.availability != ArtifactAvailability.AVAILABLE &&
            artifact.artifactId !in locallyVerifiedArtifactIds
        ) {
            return RecoveryTargetResolution.Unavailable(
                RecoveryTargetUnavailableReason.ARTIFACT_UNAVAILABLE,
                artifactId,
            )
        }

        return RecoveryTargetResolution.Available(
            RecoveryTarget(
                artifactId = artifact.artifactId,
                moduleId = artifact.moduleId,
                packageName = artifact.packageName,
                versionCode = artifact.versionCode,
                versionName = artifact.versionName,
                apkSha256 = artifact.apkSha256,
                signingCertificateSha256 = artifact.signingCertificateSha256,
                sizeBytes = artifact.sizeBytes,
                protocolRange = artifact.protocolRange,
                sources = artifact.sources,
                recoverySafety = artifact.recoverySafety,
            ),
        )
    }
}

enum class RecoveryAuthorizationFailure {
    BACKUP_REQUIRED,
    RISK_ACKNOWLEDGEMENT_REQUIRED,
}

sealed interface RecoveryAuthorization {
    data object Safe : RecoveryAuthorization
    data object RiskAcknowledged : RecoveryAuthorization
    data class Blocked(val reason: RecoveryAuthorizationFailure) : RecoveryAuthorization
}

/** Data-safety is evaluated independently from whether an artifact is recoverable. */
object RecoveryAuthorizationPolicy {
    fun evaluate(
        recoverySafety: RecoverySafety,
        backupVerified: Boolean,
        destructiveRiskAcknowledged: Boolean,
    ): RecoveryAuthorization = when (recoverySafety) {
        RecoverySafety.REGENERABLE -> RecoveryAuthorization.Safe
        RecoverySafety.BACKUP_RESTORE_REQUIRED -> {
            if (backupVerified) {
                RecoveryAuthorization.Safe
            } else {
                RecoveryAuthorization.Blocked(RecoveryAuthorizationFailure.BACKUP_REQUIRED)
            }
        }
        RecoverySafety.DATA_LOSS_POSSIBLE -> {
            if (destructiveRiskAcknowledged) {
                RecoveryAuthorization.RiskAcknowledged
            } else {
                RecoveryAuthorization.Blocked(
                    RecoveryAuthorizationFailure.RISK_ACKNOWLEDGEMENT_REQUIRED,
                )
            }
        }
    }
}
