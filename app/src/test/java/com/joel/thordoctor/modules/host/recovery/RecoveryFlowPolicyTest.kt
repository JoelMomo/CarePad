package com.joel.thordoctor.modules.host.recovery

import com.joel.thordoctor.modules.catalog.recovery.RecoveryProtocolRange
import com.joel.thordoctor.modules.catalog.recovery.RecoverySafety
import com.joel.thordoctor.modules.catalog.recovery.RecoveryTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryFlowPolicyTest {

    @Test
    fun regenerableTargetUsesSafeRouteWithoutDestructiveConsent() {
        val result = RecoveryAuthorizationPolicy.authorize(
            target(RecoverySafety.REGENERABLE),
            RecoveryAuthorization()
        )

        assertEquals(
            RecoveryExecutionRoute.SAFE_RECOVERY,
            (result as RecoveryAuthorizationResult.Allowed).route
        )
    }

    @Test
    fun backupRestoreTargetRequiresVerifiedBackupBeforeSafeRoute() {
        val blocked = RecoveryAuthorizationPolicy.authorize(
            target(RecoverySafety.BACKUP_RESTORE_REQUIRED),
            RecoveryAuthorization()
        )
        assertEquals(
            RecoveryAuthorizationFailure.BACKUP_REQUIRED,
            (blocked as RecoveryAuthorizationResult.Blocked).reason
        )

        val allowed = RecoveryAuthorizationPolicy.authorize(
            target(RecoverySafety.BACKUP_RESTORE_REQUIRED),
            RecoveryAuthorization(backupVerified = true)
        )
        assertEquals(
            RecoveryExecutionRoute.SAFE_RECOVERY,
            (allowed as RecoveryAuthorizationResult.Allowed).route
        )
    }

    @Test
    fun possibleDataLossRequiresProductPermissionAndExplicitAcknowledgement() {
        val policyBlocked = RecoveryAuthorizationPolicy.authorize(
            target(RecoverySafety.DATA_LOSS_POSSIBLE),
            RecoveryAuthorization(dataLossAcknowledged = true)
        )
        assertEquals(
            RecoveryAuthorizationFailure.RISKY_REINSTALL_NOT_PERMITTED,
            (policyBlocked as RecoveryAuthorizationResult.Blocked).reason
        )

        val consentBlocked = RecoveryAuthorizationPolicy.authorize(
            target(RecoverySafety.DATA_LOSS_POSSIBLE),
            RecoveryAuthorization(riskyReinstallPermitted = true)
        )
        assertEquals(
            RecoveryAuthorizationFailure.DATA_LOSS_ACKNOWLEDGEMENT_REQUIRED,
            (consentBlocked as RecoveryAuthorizationResult.Blocked).reason
        )

        val allowed = RecoveryAuthorizationPolicy.authorize(
            target(RecoverySafety.DATA_LOSS_POSSIBLE),
            RecoveryAuthorization(
                riskyReinstallPermitted = true,
                dataLossAcknowledged = true
            )
        )
        assertEquals(
            RecoveryExecutionRoute.REINSTALL_WITH_POSSIBLE_LOSS,
            (allowed as RecoveryAuthorizationResult.Allowed).route
        )
    }

    @Test
    fun installedPackageAfterSystemRemovalDialogDoesNotStartInstall() {
        assertEquals(
            RecoveryPhase.CANCELLED,
            RecoveryFlowPolicy.afterUninstallObservation(packageStillInstalled = true)
        )
        assertEquals(
            RecoveryPhase.READY_TO_INSTALL,
            RecoveryFlowPolicy.afterUninstallObservation(packageStillInstalled = false)
        )
    }

    @Test
    fun successRequiresVerificationPhaseBeforeVerified() {
        assertEquals(
            RecoveryPhase.VERIFYING,
            RecoveryFlowPolicy.afterInstallStatus(success = true)
        )
        assertEquals(
            RecoveryPhase.VERIFIED,
            RecoveryFlowPolicy.afterVerification(valid = true)
        )
        assertTrue(RecoveryPhase.VERIFIED.isTerminal)
    }

    private fun target(safety: RecoverySafety) = RecoveryTarget(
        artifactId = "performance@1",
        moduleId = "performance",
        packageName = "dev.carepad.module.performance",
        versionCode = 1,
        versionName = "1.0",
        apkSha256 = "a".repeat(64),
        signingCertificateSha256 = "b".repeat(64),
        protocol = RecoveryProtocolRange(1, 1),
        sources = listOf("https://official.invalid/performance.apk"),
        recoverySafety = safety
    )
}
