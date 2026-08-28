package com.joel.thordoctor.modules.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import carepad.contracts.CarePadModuleActions

/** Host-side entry point for module discovery, launch and Android-owned lifecycle prompts. */
object ModuleManager {
    fun discover(context: Context): ModuleDiscoveryResult =
        AndroidModuleDiscovery.discover(context)

    fun open(context: Context, module: DiscoveredCarePadModule) {
        val intent = Intent(CarePadModuleActions.OPEN_MODULE)
            .setComponent(module.entryActivity)
            .apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        context.startActivity(intent)
    }

    fun requestUninstall(context: Context, module: DiscoveredCarePadModule) {
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE)
            .setData(Uri.fromParts("package", module.packageName, null))
            .apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
        context.startActivity(intent)
    }
}
