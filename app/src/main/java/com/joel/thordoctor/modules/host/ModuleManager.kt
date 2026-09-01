package com.joel.thordoctor.modules.host

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import carepad.contracts.CarePadModuleActions
import carepad.contracts.CarePadModuleCapabilities

/** Host-side entry point for module discovery, launch and Android-owned lifecycle prompts. */
object ModuleManager {
    private const val MODULE_SETTINGS_PERMISSION = "dev.carepad.permission.MODULE_SETTINGS"

    fun discover(context: Context): ModuleDiscoveryResult = AndroidModuleDiscovery.discover(context)

    fun open(context: Context, module: DiscoveredCarePadModule) {
        val intent = Intent(CarePadModuleActions.OPEN_MODULE)
            .setComponent(module.entryActivity)
            .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }

    fun canOpenSettings(context: Context, module: DiscoveredCarePadModule): Boolean =
        resolveSettingsActivity(context, module) != null

    fun openSettings(context: Context, module: DiscoveredCarePadModule): Boolean {
        val component = resolveSettingsActivity(context, module) ?: return false
        val intent = Intent(CarePadModuleActions.OPEN_MODULE_SETTINGS)
            .setComponent(component)
            .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun requestUninstall(context: Context, module: DiscoveredCarePadModule) {
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE)
            .setData(Uri.fromParts("package", module.packageName, null))
            .apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        context.startActivity(intent)
    }

    private fun resolveSettingsActivity(
        context: Context,
        module: DiscoveredCarePadModule,
    ): ComponentName? {
        if (CarePadModuleCapabilities.SETTINGS_DELEGATED !in module.metadata.capabilities) return null
        val queryIntent = Intent(CarePadModuleActions.OPEN_MODULE_SETTINGS).setPackage(module.packageName)
        val candidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                queryIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(queryIntent, PackageManager.MATCH_DEFAULT_ONLY)
        }.filter { info ->
            val activityInfo = info.activityInfo
            activityInfo != null &&
                activityInfo.packageName == module.packageName &&
                activityInfo.exported &&
                activityInfo.permission == MODULE_SETTINGS_PERMISSION
        }
        if (candidates.size != 1) return null
        val activityInfo = candidates.single().activityInfo ?: return null
        return ComponentName(module.packageName, activityInfo.name)
    }
}
