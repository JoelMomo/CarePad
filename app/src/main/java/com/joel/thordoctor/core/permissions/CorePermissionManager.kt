package com.joel.thordoctor.core.permissions

import android.Manifest
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

data class PermissionStatus(
    val usageAccess: Boolean,
    val allFilesAccess: Boolean,
    val notifications: Boolean
) {
    val requiredGranted: Boolean
        get() = usageAccess && allFilesAccess
}

object CorePermissionManager {

    fun status(context: Context): PermissionStatus =
        PermissionStatus(
            usageAccess = hasUsageAccess(context),
            allFilesAccess = hasAllFilesAccess(),
            notifications = hasNotificationPermission(context)
        )

    fun openUsageAccessSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        )
    }

    fun openAllFilesAccessSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            )
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            )
        }
    }

    fun openNotificationSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
            )
        } catch (_: ActivityNotFoundException) {
            openAppDetailsSettings(context)
        }
    }

    private fun hasUsageAccess(context: Context): Boolean {
        val appOps =
            context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

        val mode =
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )

        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

    private fun hasNotificationPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true

        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun openAppDetailsSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        )
    }
}
