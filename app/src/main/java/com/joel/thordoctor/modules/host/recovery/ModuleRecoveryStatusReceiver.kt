package com.joel.thordoctor.modules.host.recovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Receives PackageInstaller status for the persisted single-module recovery operation. */
class ModuleRecoveryStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidModuleRecovery.ACTION_RECOVERY_STATUS) return

        val operation = RecoveryOperationStore.current(context) ?: return
        val artifactId = intent.getStringExtra(AndroidModuleRecovery.EXTRA_ARTIFACT_ID) ?: return
        if (artifactId != operation.target.artifactId) return

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            AndroidModuleRecovery.markWaitingForInstallConfirmation(context)
            val confirmationIntent = pendingUserActionIntent(intent)
            if (confirmationIntent == null) {
                AndroidModuleRecovery.failAndroidConfirmation(
                    context,
                    "Android requested install confirmation but supplied no confirmation Intent."
                )
                return
            }
            confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(confirmationIntent) }
                .onFailure { error ->
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
}
