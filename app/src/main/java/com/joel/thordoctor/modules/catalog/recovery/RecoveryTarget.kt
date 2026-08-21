package com.joel.thordoctor.modules.catalog.recovery

enum class RecoverySafety {
    REGENERABLE,
    BACKUP_RESTORE_REQUIRED,
    DATA_LOSS_POSSIBLE
}

enum class RecoveryStatus {
    CANDIDATE,
    RECOVERABLE,
    BLOCKED
}

enum class RecoveryAvailability {
    AVAILABLE,
    UNAVAILABLE
}

data class RecoveryProtocolRange(
    val min: Int,
    val max: Int
) {
    init {
        require(min > 0) { "protocol min must be positive" }
        require(max >= min) { "protocol max must be >= min" }
    }

    fun supports(version: Int): Boolean = version in min..max
}

/** Exact catalog-selected recovery artifact. Distribution metadata, not runtime module metadata. */
data class RecoveryTarget(
    val artifactId: String,
    val moduleId: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkSha256: String,
    val signingCertificateSha256: String,
    val protocol: RecoveryProtocolRange,
    val sources: List<String>,
    val recoverySafety: RecoverySafety
) {
    init {
        require(artifactId.isNotBlank()) { "artifactId must not be blank" }
        require(moduleId.isNotBlank()) { "moduleId must not be blank" }
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(versionCode > 0L) { "versionCode must be positive" }
        require(versionName.isNotBlank()) { "versionName must not be blank" }
        require(SHA256.matches(apkSha256)) { "apkSha256 must be a 64-character hex digest" }
        require(SHA256.matches(signingCertificateSha256)) {
            "signingCertificateSha256 must be a 64-character hex digest"
        }
        require(sources.none { it.isBlank() }) { "sources must not contain blank entries" }
    }

    val normalizedApkSha256: String = apkSha256.lowercase()
    val normalizedSigningCertificateSha256: String = signingCertificateSha256.lowercase()

    private companion object {
        val SHA256 = Regex("[0-9a-fA-F]{64}")
    }
}

data class RecoveryArtifactRecord(
    val target: RecoveryTarget,
    val recoveryStatus: RecoveryStatus,
    val availability: RecoveryAvailability
)

data class ModuleRecoveryCatalog(
    val moduleId: String,
    val packageName: String,
    val lastKnownRecoverableByChannel: Map<String, String>,
    val artifacts: List<RecoveryArtifactRecord>
)

data class InstalledModuleRecoveryIdentity(
    val moduleId: String,
    val packageName: String,
    val versionCode: Long
)

enum class RecoveryTargetUnavailableReason {
    NO_LVRK,
    ARTIFACT_NOT_FOUND,
    ARTIFACT_ID_NOT_UNIQUE,
    NOT_RECOVERABLE,
    ARTIFACT_UNAVAILABLE,
    MODULE_MISMATCH,
    PACKAGE_MISMATCH,
    TARGET_NOT_OLDER,
    PROTOCOL_INCOMPATIBLE
}

sealed class RecoveryTargetResolution {
    data class Available(val target: RecoveryTarget) : RecoveryTargetResolution()
    data class Unavailable(val reason: RecoveryTargetUnavailableReason) : RecoveryTargetResolution()
}

/**
 * Resolves only the catalog's explicit LVRK pointer. It never sorts versions or derives
 * "the previous version" from versionCode.
 */
object RecoveryTargetResolver {
    fun resolve(
        catalog: ModuleRecoveryCatalog,
        channel: String,
        installed: InstalledModuleRecoveryIdentity,
        hostProtocolVersion: Int,
        verifiedLocalArtifactIds: Set<String> = emptySet()
    ): RecoveryTargetResolution {
        if (catalog.moduleId != installed.moduleId) {
            return RecoveryTargetResolution.Unavailable(RecoveryTargetUnavailableReason.MODULE_MISMATCH)
        }
        if (catalog.packageName != installed.packageName) {
            return RecoveryTargetResolution.Unavailable(RecoveryTargetUnavailableReason.PACKAGE_MISMATCH)
        }

        val artifactId = catalog.lastKnownRecoverableByChannel[channel]
            ?: return RecoveryTargetResolution.Unavailable(RecoveryTargetUnavailableReason.NO_LVRK)
        val matchingRecords = catalog.artifacts.filter { it.target.artifactId == artifactId }
        if (matchingRecords.isEmpty()) {
            return RecoveryTargetResolution.Unavailable(RecoveryTargetUnavailableReason.ARTIFACT_NOT_FOUND)
        }
        if (matchingRecords.size != 1) {
            return RecoveryTargetResolution.Unavailable(
                RecoveryTargetUnavailableReason.ARTIFACT_ID_NOT_UNIQUE
            )
        }
        val record = matchingRecords.single()

        if (record.recoveryStatus != RecoveryStatus.RECOVERABLE) {
            return RecoveryTargetResolution.Unavailable(RecoveryTargetUnavailableReason.NOT_RECOVERABLE)
        }

        val target = record.target
        if (target.moduleId != installed.moduleId || target.moduleId != catalog.moduleId) {
            return RecoveryTargetResolution.Unavailable(RecoveryTargetUnavailableReason.MODULE_MISMATCH)
        }
        if (target.packageName != installed.packageName || target.packageName != catalog.packageName) {
            return RecoveryTargetResolution.Unavailable(RecoveryTargetUnavailableReason.PACKAGE_MISMATCH)
        }
        if (target.versionCode >= installed.versionCode) {
            return RecoveryTargetResolution.Unavailable(RecoveryTargetUnavailableReason.TARGET_NOT_OLDER)
        }
        if (!target.protocol.supports(hostProtocolVersion)) {
            return RecoveryTargetResolution.Unavailable(
                RecoveryTargetUnavailableReason.PROTOCOL_INCOMPATIBLE
            )
        }

        val verifiedLocalCopy = target.artifactId in verifiedLocalArtifactIds
        val officialBytesAvailable = record.availability == RecoveryAvailability.AVAILABLE &&
            target.sources.isNotEmpty()
        if (!verifiedLocalCopy && !officialBytesAvailable) {
            return RecoveryTargetResolution.Unavailable(
                RecoveryTargetUnavailableReason.ARTIFACT_UNAVAILABLE
            )
        }

        return RecoveryTargetResolution.Available(target)
    }
}

sealed class RecoveryArtifactSource {
    data class OfficialSource(val source: String) : RecoveryArtifactSource()
    data object VerifiedLocalCache : RecoveryArtifactSource()
}

/** Source fallback stays within the exact artifactId already selected by the catalog. */
object RecoveryArtifactSourceSelector {
    fun select(
        target: RecoveryTarget,
        failedOfficialSources: Set<String> = emptySet(),
        hasVerifiedLocalCopy: Boolean = false
    ): RecoveryArtifactSource? {
        target.sources.firstOrNull { it !in failedOfficialSources }?.let { source ->
            return RecoveryArtifactSource.OfficialSource(source)
        }
        return if (hasVerifiedLocalCopy) RecoveryArtifactSource.VerifiedLocalCache else null
    }
}
