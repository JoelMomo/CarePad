package com.joel.thordoctor.modules.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Receives PackageInstaller status for the debug CarePad module install/update prototype. */
class ModuleInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ModuleLabPackageInstaller.ACTION_INSTALL_STATUS) return

        val moduleId = intent.getStringExtra(ModuleLabPackageInstaller.EXTRA_MODULE_ID)
            ?: "unknown"
        val packageName = intent.getStringExtra(ModuleLabPackageInstaller.EXTRA_PACKAGE_NAME)
            ?: "unknown"
        val operation = runCatching {
            ModuleLabPackageOperation.valueOf(
                intent.getStringExtra(ModuleLabPackageInstaller.EXTRA_OPERATION)
                    ?: ModuleLabPackageOperation.INSTALL.name
            )
        }.getOrDefault(ModuleLabPackageOperation.INSTALL)
        val verb = if (operation == ModuleLabPackageOperation.UPDATE) "update" else "install"
        val completedVerb = if (operation == ModuleLabPackageOperation.UPDATE) "Updated" else "Installed"
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                ModuleLabInstallState.setStatus(
                    context,
                    "Android requires confirmation to $verb $moduleId."
                )
                val confirmationIntent = pendingUserActionIntent(intent)
                if (confirmationIntent == null) {
                    ModuleLabInstallState.setStatus(
                        context,
                        "Android requested user action but supplied no confirmation Intent."
                    )
                    return
                }
                confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching {
                    context.startActivity(confirmationIntent)
                }.onFailure { error ->
                    ModuleLabInstallState.setStatus(
                        context,
                        "Unable to open Android $verb confirmation: ${error.message ?: error::class.java.simpleName}"
                    )
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                ModuleLabInstallState.setStatus(
                    context,
                    "$completedVerb $moduleId successfully as $packageName."
                )
            }

            else -> {
                val suffix = detail?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                ModuleLabInstallState.setStatus(
                    context,
                    "${verb.replaceFirstChar { it.uppercase() }} failed for $moduleId (status $status)$suffix"
                )
            }
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
