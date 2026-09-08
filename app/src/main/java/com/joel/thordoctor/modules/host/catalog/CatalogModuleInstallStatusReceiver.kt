package com.joel.thordoctor.modules.host.catalog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

class CatalogModuleInstallStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CatalogModuleInstaller.ACTION_INSTALL_STATUS) return
        val packageName = intent.getStringExtra(CatalogModuleInstaller.EXTRA_PACKAGE_NAME)
            ?: return
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                CatalogModuleInstallState.markAwaitingConfirmation(context, packageName)
                val confirmation = pendingUserActionIntent(intent)
                if (confirmation == null) {
                    CatalogModuleInstallState.markFailed(
                        context,
                        packageName,
                        "Android no proporcionó la confirmación de instalación.",
                    )
                    return
                }
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirmation) }
                    .onFailure { error ->
                        CatalogModuleInstallState.markFailed(
                            context,
                            packageName,
                            error.message ?: "No se pudo abrir la confirmación de Android.",
                        )
                    }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                CatalogModuleInstallState.clear(context, packageName)
            }

            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf(String::isNotBlank)
                    ?: "Android rechazó la instalación (estado $status)."
                CatalogModuleInstallState.markFailed(context, packageName, detail)
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
