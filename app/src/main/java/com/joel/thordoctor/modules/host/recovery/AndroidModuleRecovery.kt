package com.joel.thordoctor.modules.host.recovery

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import carepad.contracts.CarePadModuleMetadataKeys
import carepad.contracts.CarePadProtocol
import com.joel.thordoctor.modules.catalog.recovery.RecoverySafety
import com.joel.thordoctor.modules.catalog.recovery.RecoveryTarget
import java.io.File
import java.security.MessageDigest

/**
 * Host-side recovery mechanism for an exact catalog-selected RecoveryTarget.
 *
 * It never selects a version, never requests privileged downgrade, and never escalates from a safe
 * route to a destructive route. Android is allowed to require user confirmation for both removal
 * and installation. Success is persisted only after the installed package is verified.
 */
object AndroidModuleRecovery {
    internal const val ACTION_RECOVERY_STATUS = "com.joel.thordoctor.carepad.RECOVERY_STATUS"
    internal const val EXTRA_ARTIFACT_ID = "artifact_id"
    internal const val EXTRA_STEP = "step"
    internal const val STEP_UNINSTALL = "uninstall"
    internal const val STEP_INSTALL = "install"

    fun currentState(context: Context): RecoveryOperationSnapshot? =
        RecoveryOperationStore.current(context)?.snapshot()

    fun prepare(
        context: Context,
        target: RecoveryTarget,
        sourceApk: File,
        authorization: RecoveryAuthorization
    ): RecoveryActionResult {
        val current = RecoveryOperationStore.current(context)
        if (current != null && !current.phase.isTerminal) {
            return RecoveryActionResult.Rejected(
                RecoveryErrorCode.OPERATION_ALREADY_ACTIVE,
                "Another module recovery is already in progress.",
                current.snapshot()
            )
        }
        if (current != null) {
            deletePreparedApk(current)
            RecoveryOperationStore.clear(context)
        }

        val route = when (val authorizationResult =
            RecoveryAuthorizationPolicy.authorize(target, authorization)) {
            is RecoveryAuthorizationResult.Allowed -> authorizationResult.route
            is RecoveryAuthorizationResult.Blocked -> {
                val code = when (authorizationResult.reason) {
                    RecoveryAuthorizationFailure.BACKUP_REQUIRED -> RecoveryErrorCode.BACKUP_REQUIRED
                    RecoveryAuthorizationFailure.DATA_LOSS_ACKNOWLEDGEMENT_REQUIRED ->
                        RecoveryErrorCode.DATA_LOSS_ACKNOWLEDGEMENT_REQUIRED
                }
                return RecoveryActionResult.Rejected(
                    code,
                    "Recovery data requirements have not been satisfied."
                )
            }
        }

        validateTargetAndInstalledPackage(context, target, sourceApk)?.let { invalid ->
            return RecoveryActionResult.Rejected(invalid.code, invalid.detail)
        }

        val preparedFile = runCatching { copyToRecoveryStorage(context, target, sourceApk) }
            .getOrElse { error ->
                return RecoveryActionResult.Rejected(
                    RecoveryErrorCode.APK_MISSING,
                    "Unable to retain the verified recovery APK: ${error.message ?: error::class.java.simpleName}"
                )
            }

        if (sha256(preparedFile) != target.normalizedApkSha256) {
            preparedFile.delete()
            return RecoveryActionResult.Rejected(
                RecoveryErrorCode.APK_HASH_MISMATCH,
                "The retained recovery APK no longer matches the catalog SHA-256."
            )
        }

        val stored = RecoveryOperationStore.savePrepared(
            context = context,
            target = target,
            apkPath = preparedFile.absolutePath,
            route = route
        )
        return RecoveryActionResult.Accepted(stored.snapshot())
    }

    /** Starts Android's normal uninstall flow for the single prepared module. */
    fun requestUninstall(context: Context): RecoveryActionResult {
        val operation = RecoveryOperationStore.current(context)
            ?: return rejectedState("There is no prepared module recovery.")
        if (operation.phase != RecoveryPhase.PREPARED) {
            return rejectedState("Recovery is not ready to request removal.", operation)
        }

        val waiting = RecoveryOperationStore.update(
            context,
            RecoveryPhase.WAITING_FOR_UNINSTALL_CONFIRMATION
        ) ?: return rejectedState("Recovery state could not be persisted.")

        return runCatching {
            context.packageManager.packageInstaller.uninstall(
                operation.target.packageName,
                statusIntentSender(context, operation.target, STEP_UNINSTALL)
            )
            RecoveryActionResult.Accepted(waiting.snapshot())
        }.getOrElse { error ->
            val failed = RecoveryOperationStore.update(
                context,
                RecoveryPhase.FAILED,
                RecoveryErrorCode.UNINSTALL_REQUEST_FAILED,
                error.message ?: error::class.java.simpleName
            )
            RecoveryActionResult.Accepted(requireNotNull(failed).snapshot())
        }
    }

    /** Opens Android's install-source settings only when the current recovery is waiting for it. */
    fun requestInstallPermission(context: Context): RecoveryActionResult {
        val operation = RecoveryOperationStore.current(context)
            ?: return rejectedState("There is no module recovery waiting for install permission.")
        if (operation.phase != RecoveryPhase.WAITING_FOR_INSTALL_PERMISSION) {
            return rejectedState("Recovery is not waiting for install permission.", operation)
        }

        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return runCatching {
            context.startActivity(intent)
            RecoveryActionResult.Accepted(operation.snapshot())
        }.getOrElse { error ->
            val failed = RecoveryOperationStore.update(
                context,
                RecoveryPhase.FAILED,
                RecoveryErrorCode.INSTALL_PERMISSION_REQUEST_FAILED,
                error.message ?: error::class.java.simpleName
            )
            RecoveryActionResult.Accepted(requireNotNull(failed).snapshot())
        }
    }

    /** Re-attempts installation after Android install-source permission has been granted. */
    fun continueAfterInstallPermission(context: Context): RecoveryActionResult =
        submitInstall(context, allowWaitingForPermission = true)

    /**
     * Marks backup restoration as verified before final package verification. No backup or restore
     * policy is invented here; a module-specific data adapter must perform that work separately.
     */
    fun completeDataRestore(
        context: Context,
        restoredAndVerified: Boolean,
        detail: String? = null
    ): RecoveryActionResult {
        val operation = RecoveryOperationStore.current(context)
            ?: return rejectedState("There is no recovery waiting for data restoration.")
        if (operation.phase != RecoveryPhase.WAITING_FOR_DATA_RESTORE) {
            return rejectedState("Recovery is not waiting for data restoration.", operation)
        }
        if (!restoredAndVerified) {
            val failed = RecoveryOperationStore.update(
                context,
                RecoveryPhase.FAILED,
                RecoveryErrorCode.DATA_RESTORE_FAILED,
                detail ?: "Required module data restoration was not verified."
            )
            return RecoveryActionResult.Accepted(requireNotNull(failed).snapshot())
        }
        return verifyAndFinish(context)
    }

    /** Retry is limited to an incomplete install after the old package has already been removed. */
    fun retryInstall(context: Context): RecoveryActionResult {
        val operation = RecoveryOperationStore.current(context)
            ?: return rejectedState("There is no failed recovery to retry.")
        if (operation.phase != RecoveryPhase.FAILED ||
            operation.errorCode !in setOf(
                RecoveryErrorCode.INSTALL_SESSION_FAILED,
                RecoveryErrorCode.INSTALL_FAILED,
                RecoveryErrorCode.ANDROID_CONFIRMATION_MISSING,
                RecoveryErrorCode.INSTALL_PERMISSION_REQUEST_FAILED
            )
        ) {
            return rejectedState("This recovery failure cannot be retried as an install-only step.", operation)
        }
        if (installedPackageInfo(context.packageManager, operation.target.packageName) != null) {
            return rejectedState(
                "The module package is installed; CarePad will not silently remove it during retry.",
                operation
            )
        }
        RecoveryOperationStore.update(context, RecoveryPhase.READY_TO_INSTALL)
        return submitInstall(context)
    }

    fun clearTerminalState(context: Context): Boolean {
        val operation = RecoveryOperationStore.current(context) ?: return false
        if (!operation.phase.isTerminal) return false
        deletePreparedApk(operation)
        RecoveryOperationStore.clear(context)
        return true
    }

    internal fun handleUninstallStatus(
        context: Context,
        status: Int,
        detail: String?
    ): RecoveryActionResult {
        val operation = RecoveryOperationStore.current(context)
            ?: return rejectedState("Recovery status arrived without an active operation.")
        if (operation.phase != RecoveryPhase.WAITING_FOR_UNINSTALL_CONFIRMATION) {
            return rejectedState("Unexpected uninstall status for current recovery phase.", operation)
        }

        return when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                RecoveryOperationStore.update(context, RecoveryPhase.READY_TO_INSTALL)
                submitInstall(context)
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                val cancelled = RecoveryOperationStore.update(
                    context,
                    RecoveryPhase.CANCELLED,
                    detail = detail ?: "Android removal was cancelled before the installation changed."
                )
                deletePreparedApk(operation)
                RecoveryActionResult.Accepted(requireNotNull(cancelled).snapshot())
            }

            else -> {
                val failed = RecoveryOperationStore.update(
                    context,
                    RecoveryPhase.FAILED,
                    RecoveryErrorCode.UNINSTALL_FAILED,
                    detail ?: "Android could not remove the defective module (status $status)."
                )
                RecoveryActionResult.Accepted(requireNotNull(failed).snapshot())
            }
        }
    }

    internal fun handleInstallSuccess(context: Context): RecoveryActionResult {
        val operation = RecoveryOperationStore.current(context)
            ?: return rejectedState("Install success arrived without an active recovery.")
        if (operation.phase !in setOf(
                RecoveryPhase.INSTALLING,
                RecoveryPhase.WAITING_FOR_INSTALL_CONFIRMATION
            )
        ) {
            return rejectedState("Unexpected install success for current recovery phase.", operation)
        }

        return if (operation.target.recoverySafety == RecoverySafety.BACKUP_RESTORE_REQUIRED) {
            val waiting = RecoveryOperationStore.update(
                context,
                RecoveryPhase.WAITING_FOR_DATA_RESTORE
            )
            RecoveryActionResult.Accepted(requireNotNull(waiting).snapshot())
        } else {
            verifyAndFinish(context)
        }
    }

    internal fun handleInstallFailure(
        context: Context,
        status: Int,
        detail: String?
    ): RecoveryActionResult {
        val failed = RecoveryOperationStore.update(
            context,
            RecoveryPhase.FAILED,
            RecoveryErrorCode.INSTALL_FAILED,
            detail ?: "Android could not install the recovery target (status $status)."
        ) ?: return rejectedState("Install failure arrived without an active recovery.")
        return RecoveryActionResult.Accepted(failed.snapshot())
    }

    internal fun markWaitingForInstallConfirmation(context: Context): RecoveryActionResult {
        val operation = RecoveryOperationStore.current(context)
            ?: return rejectedState("Install confirmation arrived without an active recovery.")
        if (operation.phase != RecoveryPhase.INSTALLING) {
            return rejectedState("Unexpected Android install confirmation for current phase.", operation)
        }
        val waiting = RecoveryOperationStore.update(
            context,
            RecoveryPhase.WAITING_FOR_INSTALL_CONFIRMATION
        )
        return RecoveryActionResult.Accepted(requireNotNull(waiting).snapshot())
    }

    internal fun failMissingAndroidConfirmation(context: Context): RecoveryActionResult {
        val failed = RecoveryOperationStore.update(
            context,
            RecoveryPhase.FAILED,
            RecoveryErrorCode.ANDROID_CONFIRMATION_MISSING,
            "Android requested user action but supplied no confirmation Intent."
        ) ?: return rejectedState("Recovery state is missing.")
        return RecoveryActionResult.Accepted(failed.snapshot())
    }

    private fun submitInstall(
        context: Context,
        allowWaitingForPermission: Boolean = false
    ): RecoveryActionResult {
        val operation = RecoveryOperationStore.current(context)
            ?: return rejectedState("There is no recovery target ready to install.")
        val allowedPhases = if (allowWaitingForPermission) {
            setOf(RecoveryPhase.READY_TO_INSTALL, RecoveryPhase.WAITING_FOR_INSTALL_PERMISSION)
        } else {
            setOf(RecoveryPhase.READY_TO_INSTALL)
        }
        if (operation.phase !in allowedPhases) {
            return rejectedState("Recovery is not ready to install the target APK.", operation)
        }

        val apkFile = File(operation.apkPath)
        if (!apkFile.isFile || sha256(apkFile) != operation.target.normalizedApkSha256) {
            val failed = RecoveryOperationStore.update(
                context,
                RecoveryPhase.FAILED,
                RecoveryErrorCode.APK_CHANGED_AFTER_PREPARATION,
                "The prepared recovery APK is missing or no longer matches its catalog hash."
            )
            return RecoveryActionResult.Accepted(requireNotNull(failed).snapshot())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val waiting = RecoveryOperationStore.update(
                context,
                RecoveryPhase.WAITING_FOR_INSTALL_PERMISSION
            )
            return RecoveryActionResult.Accepted(requireNotNull(waiting).snapshot())
        }

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(operation.target.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInstallReason(PackageManager.INSTALL_REASON_USER)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }

        val sessionId = runCatching { installer.createSession(params) }.getOrElse { error ->
            val failed = RecoveryOperationStore.update(
                context,
                RecoveryPhase.FAILED,
                RecoveryErrorCode.INSTALL_SESSION_FAILED,
                error.message ?: error::class.java.simpleName
            )
            return RecoveryActionResult.Accepted(requireNotNull(failed).snapshot())
        }

        return try {
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("base.apk", 0L, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val installing = RecoveryOperationStore.update(context, RecoveryPhase.INSTALLING)
                    ?: return rejectedState("Recovery state disappeared before install commit.")
                session.commit(statusIntentSender(context, operation.target, STEP_INSTALL))
                RecoveryActionResult.Accepted(installing.snapshot())
            }
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            val failed = RecoveryOperationStore.update(
                context,
                RecoveryPhase.FAILED,
                RecoveryErrorCode.INSTALL_SESSION_FAILED,
                error.message ?: error::class.java.simpleName
            )
            RecoveryActionResult.Accepted(requireNotNull(failed).snapshot())
        }
    }

    private fun verifyAndFinish(context: Context): RecoveryActionResult {
        val operation = RecoveryOperationStore.current(context)
            ?: return rejectedState("There is no recovery installation to verify.")
        RecoveryOperationStore.update(context, RecoveryPhase.VERIFYING)

        verifyInstalledTarget(context, operation.target)?.let { invalid ->
            val failed = RecoveryOperationStore.update(
                context,
                RecoveryPhase.FAILED,
                invalid.code,
                invalid.detail
            )
            return RecoveryActionResult.Accepted(requireNotNull(failed).snapshot())
        }

        val verified = RecoveryOperationStore.update(context, RecoveryPhase.VERIFIED)
            ?: return rejectedState("Recovery verification state could not be persisted.")
        deletePreparedApk(operation)
        return RecoveryActionResult.Accepted(verified.snapshot())
    }

    private fun validateTargetAndInstalledPackage(
        context: Context,
        target: RecoveryTarget,
        apkFile: File
    ): InvalidRecoveryArtifact? {
        if (!apkFile.isFile || apkFile.length() <= 0L) {
            return InvalidRecoveryArtifact(RecoveryErrorCode.APK_MISSING, "Recovery APK is missing or empty.")
        }
        if (sha256(apkFile) != target.normalizedApkSha256) {
            return InvalidRecoveryArtifact(
                RecoveryErrorCode.APK_HASH_MISMATCH,
                "Recovery APK SHA-256 does not match the catalog target."
            )
        }

        val archive = archivePackageInfo(context.packageManager, apkFile)
            ?: return InvalidRecoveryArtifact(
                RecoveryErrorCode.APK_METADATA_UNREADABLE,
                "Android could not read package metadata from the recovery APK."
            )
        validatePackageIdentity(archive, target, verification = false)?.let { return it }

        val installed = installedPackageInfo(context.packageManager, target.packageName)
            ?: return InvalidRecoveryArtifact(
                RecoveryErrorCode.INSTALLED_MODULE_MISSING,
                "The defective module is no longer installed."
            )
        val installedMetadata = moduleMetadata(installed)
            ?: return InvalidRecoveryArtifact(
                RecoveryErrorCode.INSTALLED_MODULE_MISMATCH,
                "The installed package is not a readable CarePad module."
            )
        val installedSigners = signingDigests(installed)
        if (installedMetadata.moduleId != target.moduleId ||
            target.normalizedSigningCertificateSha256 !in installedSigners
        ) {
            return InvalidRecoveryArtifact(
                RecoveryErrorCode.INSTALLED_MODULE_MISMATCH,
                "The installed module identity does not match the recovery target."
            )
        }
        if (versionCode(installed) <= target.versionCode) {
            return InvalidRecoveryArtifact(
                RecoveryErrorCode.INSTALLED_VERSION_NOT_NEWER,
                "Recovery target is not older than the installed defective version."
            )
        }
        return null
    }

    private fun verifyInstalledTarget(
        context: Context,
        target: RecoveryTarget
    ): InvalidRecoveryArtifact? {
        val installed = installedPackageInfo(context.packageManager, target.packageName)
            ?: return InvalidRecoveryArtifact(
                RecoveryErrorCode.VERIFICATION_PACKAGE_MISSING,
                "The target package is not installed after Android reported success."
            )
        return validatePackageIdentity(installed, target, verification = true)
    }

    private fun validatePackageIdentity(
        packageInfo: PackageInfo,
        target: RecoveryTarget,
        verification: Boolean
    ): InvalidRecoveryArtifact? {
        val prefix = if (verification) "Installed package" else "Recovery APK"
        if (packageInfo.packageName != target.packageName) {
            return InvalidRecoveryArtifact(
                if (verification) RecoveryErrorCode.VERIFICATION_MODULE_MISMATCH
                else RecoveryErrorCode.APK_PACKAGE_MISMATCH,
                "$prefix packageName does not match RecoveryTarget."
            )
        }
        if (versionCode(packageInfo) != target.versionCode) {
            return InvalidRecoveryArtifact(
                if (verification) RecoveryErrorCode.VERIFICATION_VERSION_MISMATCH
                else RecoveryErrorCode.APK_VERSION_MISMATCH,
                "$prefix versionCode does not match RecoveryTarget."
            )
        }

        val signers = signingDigests(packageInfo)
        if (target.normalizedSigningCertificateSha256 !in signers) {
            return InvalidRecoveryArtifact(
                if (verification) RecoveryErrorCode.VERIFICATION_SIGNING_MISMATCH
                else RecoveryErrorCode.APK_SIGNING_MISMATCH,
                "$prefix signing certificate does not match RecoveryTarget."
            )
        }

        val metadata = moduleMetadata(packageInfo)
            ?: return InvalidRecoveryArtifact(
                if (verification) RecoveryErrorCode.VERIFICATION_MODULE_MISMATCH
                else RecoveryErrorCode.APK_MODULE_MISMATCH,
                "$prefix does not contain readable CarePad module metadata."
            )
        if (metadata.moduleId != target.moduleId) {
            return InvalidRecoveryArtifact(
                if (verification) RecoveryErrorCode.VERIFICATION_MODULE_MISMATCH
                else RecoveryErrorCode.APK_MODULE_MISMATCH,
                "$prefix moduleId does not match RecoveryTarget."
            )
        }
        if (metadata.protocolMin != target.protocol.min ||
            metadata.protocolMax != target.protocol.max ||
            !target.protocol.supports(CarePadProtocol.VERSION)
        ) {
            return InvalidRecoveryArtifact(
                if (verification) RecoveryErrorCode.VERIFICATION_PROTOCOL_MISMATCH
                else RecoveryErrorCode.APK_PROTOCOL_MISMATCH,
                "$prefix protocol range does not match the compatible catalog target."
            )
        }
        return null
    }

    private fun copyToRecoveryStorage(
        context: Context,
        target: RecoveryTarget,
        source: File
    ): File {
        val directory = File(context.noBackupFilesDir, "module-recovery").apply { mkdirs() }
        require(directory.isDirectory) { "Unable to create module recovery storage" }
        val destination = File(directory, "${target.normalizedApkSha256}.apk")
        if (source.canonicalPath != destination.canonicalPath) {
            source.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return destination
    }

    private fun statusIntentSender(
        context: Context,
        target: RecoveryTarget,
        step: String
    ): android.content.IntentSender {
        val statusIntent = Intent(context, ModuleRecoveryStatusReceiver::class.java)
            .setAction(ACTION_RECOVERY_STATUS)
            .putExtra(EXTRA_ARTIFACT_ID, target.artifactId)
            .putExtra(EXTRA_STEP, step)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            (target.artifactId + step).hashCode(),
            statusIntent,
            flags
        ).intentSender
    }

    private fun archivePackageInfo(packageManager: PackageManager, file: File): PackageInfo? {
        val flags = packageInfoFlags()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }
    }

    private fun installedPackageInfo(
        packageManager: PackageManager,
        packageName: String
    ): PackageInfo? = runCatching {
        val flags = packageInfoFlags()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, flags)
        }
    }.getOrNull()

    private fun packageInfoFlags(): Int =
        PackageManager.GET_ACTIVITIES or
            PackageManager.GET_META_DATA or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }

    private fun moduleMetadata(packageInfo: PackageInfo): ModuleIdentityMetadata? {
        val activity = packageInfo.activities
            ?.firstOrNull { activityInfo ->
                !activityInfo.metaData?.getString(CarePadModuleMetadataKeys.MODULE_ID).isNullOrBlank()
            }
            ?: return null
        val metadata = activity.metaData ?: return null
        val moduleId = metadata.getString(CarePadModuleMetadataKeys.MODULE_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val protocolMin = metadata.getInt(CarePadModuleMetadataKeys.PROTOCOL_MIN, 0)
        val protocolMax = metadata.getInt(CarePadModuleMetadataKeys.PROTOCOL_MAX, 0)
        if (protocolMin <= 0 || protocolMax < protocolMin) return null
        return ModuleIdentityMetadata(moduleId, protocolMin, protocolMax)
    }

    private fun signingDigests(packageInfo: PackageInfo): Set<String> {
        val signatures: Array<out Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures.orEmpty()
        }
        return signatures.map { signature -> sha256(signature.toByteArray()) }.toSet()
    }

    private fun versionCode(packageInfo: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private fun rejectedState(
        detail: String,
        operation: StoredRecoveryOperation? = RecoveryOperationStore.currentOrNullFallback()
    ): RecoveryActionResult.Rejected = RecoveryActionResult.Rejected(
        RecoveryErrorCode.OPERATION_STATE_INVALID,
        detail,
        operation?.snapshot()
    )

    private fun deletePreparedApk(operation: StoredRecoveryOperation) {
        runCatching { File(operation.apkPath).delete() }
    }

    private data class InvalidRecoveryArtifact(
        val code: RecoveryErrorCode,
        val detail: String
    )

    private data class ModuleIdentityMetadata(
        val moduleId: String,
        val protocolMin: Int,
        val protocolMax: Int
    )
}

private fun RecoveryOperationStore.currentOrNullFallback(): StoredRecoveryOperation? = null
