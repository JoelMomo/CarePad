package com.joel.thordoctor.modules.host.recovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log

/** Receives PackageInstaller status for the persisted single-module recovery operation. */
class ModuleRecoveryStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val receivedArtifactId = intent.getStringExtra(AndroidModuleRecovery.EXTRA_ARTIFACT_ID)
        val receivedStatus = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val receivedDetail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(
            TAG,
            "RECOVERY_INSTALL_STATUS_RECEIVED action=${intent.action} " +
                "artifactId=$receivedArtifactId status=$receivedStatus statusMessage=$receivedDetail"
        )

        if (intent.action != AndroidModuleRecovery.ACTION_RECOVERY_STATUS) return

        val operation = RecoveryOperationStore.current(context) ?: return
        val artifactId = receivedArtifactId ?: return
        if (artifactId != operation.target.artifactId) return

        val status = receivedStatus
        val detail = receivedDetail

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Log.i(TAG, "RECOVERY_INSTALL_STATUS_PENDING_BEFORE_MARK_WAITING artifactId=$artifactId")
            AndroidModuleRecovery.markWaitingForInstallConfirmation(context)
            val confirmationIntent = pendingUserActionIntent(intent)
            Log.i(
                TAG,
                "RECOVERY_INSTALL_CONFIRMATION_INTENT present=${confirmationIntent != null} " +
                    "action=${confirmationIntent?.action} " +
                    "component=${confirmationIntent?.component?.flattenToShortString()}"
            )
            if (confirmationIntent == null) {
                AndroidModuleRecovery.failAndroidConfirmation(
                    context,
                    "Android requested install confirmation but supplied no confirmation Intent."
                )
                return
            }
            confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            Log.i(TAG, "RECOVERY_INSTALL_CONFIRMATION_BEFORE_START artifactId=$artifactId")
            runCatching { context.startActivity(confirmationIntent) }
                .onSuccess {
                    Log.i(TAG, "START_CONFIRMATION_SUCCESS artifactId=$artifactId")
                }
                .onFailure { error ->
                    Log.e(
                        TAG,
                        "START_CONFIRMATION_FAILURE artifactId=$artifactId " +
                            "exception=${error::class.java.name}: ${error.message}",
                        error
                    )
                    AndroidModuleRecovery.failAndroidConfirmation(
                        context,
                        "Unable to open Android install confirmation: ${error.message ?: error::class.java.simpleName}"
                    )
                }
            return
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            AndroidModuleRecovery.handleInstallSuccess(context)
        } else {
            AndroidModuleRecovery.handleInstallFailure(context, status, detail)
        }
    }

    private fun pendingUserActionIntent(source: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            source.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private companion object {
        const val TAG = "CarePadRecoveryStatus"
    }
}
