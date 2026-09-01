package com.joel.thordoctor.core.settings

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Binder
import android.os.Build
import android.os.Process
import java.security.MessageDigest

/**
 * Defensive security utilities for validating CarePad settings IPC endpoints and callers.
 */
object CarePadSettingsSecurity {

    const val PERMISSION_MODULE_SETTINGS = "dev.carepad.permission.MODULE_SETTINGS"

    /**
     * Host-side check: ensures the ContentProvider resolved for [authority] is strictly
     * owned by the [expectedPackageName] that was previously accepted during discovery.
     */
    fun isProviderOwnedByPackage(
        context: Context,
        authority: String,
        expectedPackageName: String
    ): Boolean {
        if (authority.isBlank() || expectedPackageName.isBlank()) return false
        val providerInfo = context.packageManager.resolveContentProvider(authority, 0) ?: return false
        return providerInfo.packageName == expectedPackageName
    }

    /**
     * Module-side check: verifies that the caller of the ContentProvider (via Binder.getCallingUid())
     * is signed by the exact same certificate as this module/host.
     */
    fun isCallingPackageTrusted(
        context: Context,
        callingUid: Int = Binder.getCallingUid()
    ): Boolean {
        if (callingUid == Process.myUid()) return true

        val packageManager = context.packageManager
        val callingPackages = packageManager.getPackagesForUid(callingUid) ?: return false
        if (callingPackages.isEmpty()) return false

        val ownSigners = signingDigests(packageManager, context.packageName)
        if (ownSigners.isEmpty()) return false

        for (callerPackage in callingPackages) {
            val callerSigners = signingDigests(packageManager, callerPackage)
            if (callerSigners.isNotEmpty() && callerSigners == ownSigners) {
                return true
            }
        }
        return false
    }

    /** Returns the set of SHA-256 signing certificate digests for a given package. */
    fun signingDigests(
        packageManager: PackageManager,
        packageName: String
    ): Set<String> = runCatching {
        val signatures: Array<out Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo(
                packageManager,
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            ).signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageInfo(packageManager, packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        }
        signatures.map { signature -> sha256(signature.toByteArray()) }.toSet()
    }.getOrDefault(emptySet())

    private fun packageInfo(
        packageManager: PackageManager,
        packageName: String,
        flags: Int
    ): PackageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(flags.toLong())
        )
    } else {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, flags)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
