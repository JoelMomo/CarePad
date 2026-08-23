package com.joel.thordoctor.modules.host.recovery

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.joel.thordoctor.modules.catalog.recovery.RecoveryProtocolRange
import com.joel.thordoctor.modules.catalog.recovery.RecoverySafety
import com.joel.thordoctor.modules.catalog.recovery.RecoveryTarget
import java.io.File

/**
 * Lab-only command trampoline for exercising AndroidModuleRecovery from adb.
 *
 * This source directory is compiled only with -PcarepadLabHost=true. The manifest component is
 * disabled by default as a second guard, and this activity refuses to run outside the parallel
 * .carepadlabhost applicationId.
 */
class RecoveryLabHarnessActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (applicationContext.packageName != LAB_PACKAGE_NAME) {
            finish()
            return
        }

        val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
        val pid = Process.myPid()
        Log.i(TAG, "LAB_COMMAND_START command=$command pid=$pid")
        val result = runCatching { execute(command, intent) }
            .onSuccess {
                Log.i(TAG, "LAB_COMMAND_EXECUTED command=$command pid=$pid outcome=success")
            }
            .getOrElse { error ->
                Log.e(
                    TAG,
                    "LAB_COMMAND_EXECUTED command=$command pid=$pid outcome=exception",
                    error
                )
                lines(
                    "kind" to "exception",
                    "command" to command,
                    "error" to error::class.java.simpleName,
                    "detail" to (error.message ?: "unknown")
                )
            }
        persistResult(command, result, pid)
        finish()
    }

    private fun execute(command: String, source: Intent): String = when (command) {
        "state" -> renderState(AndroidModuleRecovery.currentState(this))
        "prepare" -> renderAction(
            AndroidModuleRecovery.prepare(
                context = this,
                target = recoveryTarget(source),
                sourceApk = stagedApk(source),
                authorization = RecoveryAuthorization(
                    backupVerified = source.getBooleanExtra(EXTRA_BACKUP_VERIFIED, false),
                    riskyReinstallPermitted = source.getBooleanExtra(
                        EXTRA_RISKY_REINSTALL_PERMITTED,
                        false
                    ),
                    dataLossAcknowledged = source.getBooleanExtra(
                        EXTRA_DATA_LOSS_ACKNOWLEDGED,
                        false
                    )
                )
            )
        )
        "request_uninstall" -> renderAction(AndroidModuleRecovery.requestUninstall(this))
        "continue_uninstall" ->
            renderAction(AndroidModuleRecovery.continueAfterUninstallConfirmation(this))
        "request_install_permission" ->
            renderAction(AndroidModuleRecovery.requestInstallPermission(this))
        "continue_install_permission" ->
            renderAction(AndroidModuleRecovery.continueAfterInstallPermission(this))
        "complete_data_restore" -> renderAction(
            AndroidModuleRecovery.completeDataRestore(
                context = this,
                restoredAndVerified = source.getBooleanExtra(EXTRA_RESTORED_AND_VERIFIED, false),
                detail = source.getStringExtra(EXTRA_DETAIL)
            )
        )
        "retry_install" -> renderAction(AndroidModuleRecovery.retryInstall(this))
        "cancel_prepared" -> renderAction(AndroidModuleRecovery.cancelPrepared(this))
        "clear_terminal" -> lines(
            "kind" to "boolean",
            "cleared" to AndroidModuleRecovery.clearTerminalState(this).toString()
        )
        else -> lines(
            "kind" to "exception",
            "command" to command,
            "error" to "UNKNOWN_COMMAND",
            "detail" to "Unsupported recovery lab command."
        )
    }

    private fun recoveryTarget(source: Intent): RecoveryTarget = RecoveryTarget(
        artifactId = source.requiredString(EXTRA_ARTIFACT_ID),
        moduleId = source.requiredString(EXTRA_MODULE_ID),
        packageName = source.requiredString(EXTRA_PACKAGE_NAME),
        versionCode = source.requiredLong(EXTRA_VERSION_CODE),
        versionName = source.requiredString(EXTRA_VERSION_NAME),
        apkSha256 = source.requiredString(EXTRA_APK_SHA256),
        signingCertificateSha256 = source.requiredString(EXTRA_SIGNING_SHA256),
        protocol = RecoveryProtocolRange(
            min = source.requiredInt(EXTRA_PROTOCOL_MIN),
            max = source.requiredInt(EXTRA_PROTOCOL_MAX)
        ),
        sources = source.getStringExtra(EXTRA_SOURCES_CSV)
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty),
        recoverySafety = RecoverySafety.valueOf(
            source.requiredString(EXTRA_RECOVERY_SAFETY)
        )
    )

    private fun stagedApk(source: Intent): File {
        val name = source.requiredString(EXTRA_STAGED_APK)
        require(name == File(name).name && !name.contains("..")) {
            "staged_apk must be a plain file name inside app filesDir"
        }
        return File(filesDir, name)
    }

    private fun renderState(snapshot: RecoveryOperationSnapshot?): String =
        if (snapshot == null) {
            lines("kind" to "state", "present" to "false")
        } else {
            lines("kind" to "state", "present" to "true") + "\n" + renderSnapshot(snapshot)
        }

    private fun renderAction(result: RecoveryActionResult): String = when (result) {
        is RecoveryActionResult.Accepted ->
            lines("kind" to "accepted") + "\n" + renderSnapshot(result.state)
        is RecoveryActionResult.Rejected -> buildString {
            append(lines(
                "kind" to "rejected",
                "error" to result.errorCode.name,
                "detail" to result.detail
            ))
            result.current?.let { current ->
                append('\n')
                append(renderSnapshot(current, prefix = "current_"))
            }
        }
    }

    private fun renderSnapshot(
        snapshot: RecoveryOperationSnapshot,
        prefix: String = ""
    ): String = lines(
        "${prefix}artifact_id" to snapshot.artifactId,
        "${prefix}module_id" to snapshot.moduleId,
        "${prefix}package_name" to snapshot.packageName,
        "${prefix}target_version_code" to snapshot.targetVersionCode.toString(),
        "${prefix}target_version_name" to snapshot.targetVersionName,
        "${prefix}route" to snapshot.route.name,
        "${prefix}phase" to snapshot.phase.name,
        "${prefix}error" to (snapshot.errorCode?.name ?: ""),
        "${prefix}detail" to (snapshot.detail ?: "")
    )

    private fun persistResult(command: String, result: String, pid: Int) {
        val normalized = result.trimEnd() + "\n"
        val resultFile = File(filesDir, RESULT_FILE_NAME)
        resultFile.writeText(normalized)
        Log.i(
            TAG,
            "LAB_RESULT_PERSISTED command=$command pid=$pid bytes=${resultFile.length()}"
        )
        Log.i(TAG, normalized.replace('\n', ';'))
    }

    private fun lines(vararg values: Pair<String, String>): String = values.joinToString("\n") {
        (key, value) -> "$key=${sanitize(value)}"
    }

    private fun sanitize(value: String): String = value
        .replace('\r', ' ')
        .replace('\n', ' ')

    private fun Intent.requiredString(name: String): String =
        requireNotNull(getStringExtra(name)) { "$name is required" }
            .also { require(it.isNotBlank()) { "$name must not be blank" } }

    private fun Intent.requiredLong(name: String): Long =
        getLongExtra(name, Long.MIN_VALUE)
            .also { require(it != Long.MIN_VALUE) { "$name is required" } }

    private fun Intent.requiredInt(name: String): Int =
        getIntExtra(name, Int.MIN_VALUE)
            .also { require(it != Int.MIN_VALUE) { "$name is required" } }

    private companion object {
        const val LAB_PACKAGE_NAME = "com.joel.thordoctor.carepadlabhost"
        const val TAG = "CarePadRecoveryLab"
        const val RESULT_FILE_NAME = "recovery-lab-result.txt"

        const val EXTRA_COMMAND = "command"
        const val EXTRA_STAGED_APK = "staged_apk"
        const val EXTRA_ARTIFACT_ID = "artifact_id"
        const val EXTRA_MODULE_ID = "module_id"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_VERSION_CODE = "version_code"
        const val EXTRA_VERSION_NAME = "version_name"
        const val EXTRA_APK_SHA256 = "apk_sha256"
        const val EXTRA_SIGNING_SHA256 = "signing_sha256"
        const val EXTRA_PROTOCOL_MIN = "protocol_min"
        const val EXTRA_PROTOCOL_MAX = "protocol_max"
        const val EXTRA_SOURCES_CSV = "sources_csv"
        const val EXTRA_RECOVERY_SAFETY = "recovery_safety"
        const val EXTRA_BACKUP_VERIFIED = "backup_verified"
        const val EXTRA_RISKY_REINSTALL_PERMITTED = "risky_reinstall_permitted"
        const val EXTRA_DATA_LOSS_ACKNOWLEDGED = "data_loss_acknowledged"
        const val EXTRA_RESTORED_AND_VERIFIED = "restored_and_verified"
        const val EXTRA_DETAIL = "detail"
    }
}
