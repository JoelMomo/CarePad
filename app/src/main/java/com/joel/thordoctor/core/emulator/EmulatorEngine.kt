package com.joel.thordoctor.core.emulator

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object EmulatorEngine {

    fun installedEmulators(context: Context): List<InstalledEmulator> =
        EmulatorRegistry.definitions.mapNotNull { definition ->
            findInstalled(context, definition)
        }

    fun findInstalled(
        context: Context,
        definition: EmulatorDefinition
    ): InstalledEmulator? {
        definition.packageNames.forEach { packageName ->
            try {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(0)
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(packageName, 0)
                }

                return InstalledEmulator(
                    definition = definition,
                    packageName = packageName,
                    versionName = info.versionName ?: "unknown"
                )
            } catch (_: PackageManager.NameNotFoundException) {
                // Try the next package alias for this emulator definition.
            }
        }

        return null
    }

    fun definitionForPackage(packageName: String): EmulatorDefinition? =
        EmulatorRegistry.findByPackage(packageName)
}
