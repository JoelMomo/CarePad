package com.joel.thordoctor

import android.content.Context
import com.joel.thordoctor.core.permissions.CorePermissionManager

/**
 * Legacy DocThor compatibility facade.
 *
 * Permission implementation now lives in CarePad Core. Keeping this facade
 * avoids coupling the current UI to the refactor while modules are extracted
 * incrementally.
 */
object PermissionManager {

    data class Status(
        val usageAccess: Boolean,
        val allFilesAccess: Boolean,
        val notifications: Boolean
    )

    fun status(context: Context): Status {
        val status = CorePermissionManager.status(context)
        return Status(
            usageAccess = status.usageAccess,
            allFilesAccess = status.allFilesAccess,
            notifications = status.notifications
        )
    }

    fun openUsageAccessSettings(context: Context) {
        CorePermissionManager.openUsageAccessSettings(context)
    }

    fun openAllFilesAccessSettings(context: Context) {
        CorePermissionManager.openAllFilesAccessSettings(context)
    }

    fun openNotificationSettings(context: Context) {
        CorePermissionManager.openNotificationSettings(context)
    }
}
