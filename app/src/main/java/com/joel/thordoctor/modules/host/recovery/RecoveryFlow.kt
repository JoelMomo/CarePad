package com.joel.thordoctor.modules.host.recovery

import com.joel.thordoctor.modules.catalog.recovery.RecoverySafety
import com.joel.thordoctor.modules.catalog.recovery.RecoveryTarget

enum class RecoveryExecutionRoute {
    SAFE_RECOVERY,
    REINSTALL_WITH_POSSIBLE_LOSS
}

data class RecoveryAuthorization(
    val backupVerified: Boolean = false,
    val riskyReinstallPermitted: Boolean = false,
    val dataLossAcknowledged: Boolean = false
)

enum class RecoveryAuthorizationFailure {
    BACKUP_REQUIRED,
    RISKY_REINSTALL_NOT_PERMITTED,
    DATA_LOSS_ACKNOWLEDGEMENT_REQUIRED
}

sealed class RecoveryAuthorizationResult {
    data class Allowed(val route: RecoveryExecutionRoute) : RecoveryAuthorizationResult()
    data class Blocked(val reason: RecoveryAuthorizationFailure) : RecoveryAuthorizationResult()
}

object RecoveryAuthorizationPolicy {
    fun authorize(
        target: RecoveryTarget,
        authorization: RecoveryAuthorization
    ): RecoveryAuthorizationResult = when (target.recoverySafety) {
        RecoverySafety.REGENERABLE ->
            RecoveryAuthorizationResult.Allowed(RecoveryExecutionRoute.SAFE_RECOVERY)

        RecoverySafety.BACKUP_RESTORE_REQUIRED ->
            if (authorization.backupVerified) {
                RecoveryAuthorizationResult.Allowed(RecoveryExecutionRoute.SAFE_RECOVERY)
            } else {
                RecoveryAuthorizationResult.Blocked(RecoveryAuthorizationFailure.BACKUP_REQUIRED)
            }

        RecoverySafety.DATA_LOSS_POSSIBLE -> when {
            !authorization.riskyReinstallPermitted ->
                RecoveryAuthorizationResult.Blocked(
                    RecoveryAuthorizationFailure.RISKY_REINSTALL_NOT_PERMITTED
                )

            !authorization.dataLossAcknowledged ->
                RecoveryAuthorizationResult.Blocked(
                    RecoveryAuthorizationFailure.DATA_LOSS_ACKNOWLEDGEMENT_REQUIRED
                )

            else ->
                RecoveryAuthorizationResult.Allowed(
                    RecoveryExecutionRoute.REINSTALL_WITH_POSSIBLE_LOSS
                )
        }
    }
}

enum class RecoveryPhase {
    PREPARED,
    WAITING_FOR_UNINSTALL_CONFIRMATION,
    READY_TO_INSTALL,
    WAITING_FOR_INSTALL_PERMISSION,
    INSTALLING,
    WAITING_FOR_INSTALL_CONFIRMATION,
    WAITING_FOR_DATA_RESTORE,
    VERIFYING,
    VERIFIED,
    CANCELLED,
    FAILED;

    val isTerminal: Boolean
        get() = this == VERIFIED || this == CANCELLED || this == FAILED
}

enum class RecoveryErrorCode {
    OPERATION_ALREADY_ACTIVE,
    BACKUP_REQUIRED,
    RISKY_REINSTALL_NOT_PERMITTED,
    DATA_LOSS_ACKNOWLEDGEMENT_REQUIRED,
    APK_MISSING,
    APK_HASH_MISMATCH,
    APK_METADATA_UNREADABLE,
    APK_PACKAGE_MISMATCH,
    APK_VERSION_MISMATCH,
    APK_SIGNING_MISMATCH,
    APK_MODULE_MISMATCH,
    APK_PROTOCOL_MISMATCH,
    INSTALLED_MODULE_MISSING,
    INSTALLED_MODULE_MISMATCH,
    INSTALLED_VERSION_NOT_NEWER,
    UNINSTALL_REQUEST_FAILED,
    UNINSTALL_NOT_COMPLETED,
    APK_CHANGED_AFTER_PREPARATION,
    INSTALL_PERMISSION_REQUEST_FAILED,
    INSTALL_SESSION_FAILED,
    ANDROID_CONFIRMATION_MISSING,
    INSTALL_FAILED,
    DATA_RESTORE_FAILED,
    VERIFICATION_PACKAGE_MISSING,
    VERIFICATION_VERSION_MISMATCH,
    VERIFICATION_SIGNING_MISMATCH,
    VERIFICATION_MODULE_MISMATCH,
    VERIFICATION_PROTOCOL_MISMATCH,
    OPERATION_STATE_INVALID
}

data class RecoveryOperationSnapshot(
    val artifactId: String,
    val moduleId: String,
    val packageName: String,
    val targetVersionCode: Long,
    val targetVersionName: String,
    val route: RecoveryExecutionRoute,
    val phase: RecoveryPhase,
    val errorCode: RecoveryErrorCode? = null,
    val detail: String? = null
)

sealed class RecoveryActionResult {
    data class Accepted(val state: RecoveryOperationSnapshot) : RecoveryActionResult()
    data class Rejected(
        val errorCode: RecoveryErrorCode,
        val detail: String,
        val current: RecoveryOperationSnapshot? = null
    ) : RecoveryActionResult()
}

/** Pure transition rules used by the Android mechanism and JVM tests. */
object RecoveryFlowPolicy {
    fun afterUninstallObservation(packageStillInstalled: Boolean): RecoveryPhase =
        if (packageStillInstalled) RecoveryPhase.CANCELLED else RecoveryPhase.READY_TO_INSTALL

    fun afterInstallStatus(success: Boolean): RecoveryPhase =
        if (success) RecoveryPhase.VERIFYING else RecoveryPhase.FAILED

    fun afterVerification(valid: Boolean): RecoveryPhase =
        if (valid) RecoveryPhase.VERIFIED else RecoveryPhase.FAILED
}
