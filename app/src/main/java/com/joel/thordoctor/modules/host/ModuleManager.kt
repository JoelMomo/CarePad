package com.joel.thordoctor.modules.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import carepad.contracts.CarePadModuleActions

/**
 * Host-side entry point for module discovery and launch.
 *
 * Package lifecycle operations stay outside this manager until their product behavior is defined.
 */
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
}
