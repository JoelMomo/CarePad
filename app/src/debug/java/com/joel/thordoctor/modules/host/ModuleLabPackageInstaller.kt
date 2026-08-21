package com.joel.thordoctor.modules.host

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import carepad.contracts.CarePadModuleMetadataKeys
import carepad.contracts.CarePadProtocol
import carepad.contracts.ModuleProtocolRange
import java.io.File
import java.security.MessageDigest

enum class ModuleLabPackageOperation {
    INSTALL,
    UPDATE
}

data class PreparedModuleApk(
    val file: File,
    val moduleId: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val protocol: ModuleProtocolRange,
    val operation: ModuleLabPackageOperation,
    val previousVersionName: String? = null,
    val previousVersionCode: Long? = null
)

object ModuleLabInstallState {
    private const val PREFS = "carepad_module_lab_install"
    private const val KEY_STATUS = "status"
    private const val KEY_PENDING_PATH = "pending_path"

    fun status(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_STATUS, null)

    fun setStatus(context: Context, status: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, status)
            .apply()
    }

    fun pendingPath(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_PATH, null)

    fun setPendingPath(context: Context, path: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (path == null) remove(KEY_PENDING_PATH) else putString(KEY_PENDING_PATH, path)
            }
            .apply()
    }
}

/** Debug-only PackageInstaller prototype for validating CarePad-managed module install/update. */
object ModuleLabPackageInstaller {
    const val ACTION_INSTALL_STATUS = "com.joel.thordoctor.carepadlab.INSTALL_STATUS"
    const val EXTRA_MODULE_ID = "module_id"
    const val EXTRA_PACKAGE_NAME = "package_name"
    const val EXTRA_OPERATION = "operation"

    fun copyAndValidate(context: Context, uri: Uri): Result<PreparedModuleApk> = runCatching {
        val target = File(
            context.cacheDir,
            "carepad-module-${System.currentTimeMillis()}.apk"
        )
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Unable to open the selected APK")
            input.use { source ->
                target.outputStream().use { destination ->
                    source.copyTo(destination)
                }
            }
            validateCachedFile(context, target)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun validateCachedFile(context: Context, file: File): PreparedModuleApk {
        require(file.isFile && file.length() > 0L) { "Selected APK is empty or unavailable" }

        val packageManager = context.packageManager
        val archiveInfo = archivePackageInfo(packageManager, file)
            ?: error("Android could not read package metadata from this APK")
        val packageName = archiveInfo.packageName
        require(packageName.isNotBlank()) { "APK package name is missing" }

        val moduleActivity = archiveInfo.activities
            ?.firstOrNull { activity ->
                !activity.metaData?.getString(CarePadModuleMetadataKeys.MODULE_ID).isNullOrBlank()
            }
            ?: error("APK does not declare CarePad module metadata")

        val metadata = requireNotNull(moduleActivity.metaData) {
            "APK does not contain readable CarePad module metadata"
        }
        val moduleId = metadata.getString(CarePadModuleMetadataKeys.MODULE_ID)
            ?.takeIf { it.isNotBlank() }
            ?: error("CarePad moduleId is missing")
        val protocolMin = metadata.getInt(CarePadModuleMetadataKeys.PROTOCOL_MIN, 0)
        val protocolMax = metadata.getInt(CarePadModuleMetadataKeys.PROTOCOL_MAX, 0)
        require(protocolMin > 0 && protocolMax >= protocolMin) {
            "CarePad protocol metadata is invalid"
        }

        val protocol = ModuleProtocolRange(protocolMin, protocolMax)
        require(protocol.supports(CarePadProtocol.VERSION)) {
            "Module protocol $protocolMin..$protocolMax does not include host ${CarePadProtocol.VERSION}"
        }

        val hostSigners = installedSigningDigests(packageManager, context.packageName)
        val archiveSigners = signingDigests(archiveInfo)
        require(hostSigners.isNotEmpty() && archiveSigners.isNotEmpty()) {
            "Unable to verify APK signing certificate"
        }
        require(hostSigners == archiveSigners) {
            "APK signing certificate does not match the CarePad host"
        }

        val archiveVersionCode = versionCode(archiveInfo)
        val archiveVersionName = archiveInfo.versionName ?: "unknown"
        val installedInfo = installedModulePackageInfo(packageManager, packageName)

        if (installedInfo == null) {
            return PreparedModuleApk(
                file = file,
                moduleId = moduleId,
                packageName = packageName,
                versionName = archiveVersionName,
                versionCode = archiveVersionCode,
                protocol = protocol,
                operation = ModuleLabPackageOperation.INSTALL
            )
        }

        val installedModuleId = installedInfo.activities
            ?.firstNotNullOfOrNull { activity ->
                activity.metaData?.getString(CarePadModuleMetadataKeys.MODULE_ID)
                    ?.takeIf { it.isNotBlank() }
            }
            ?: error("Installed package $packageName is not a readable CarePad module")
        require(installedModuleId == moduleId) {
            "Installed moduleId $installedModuleId does not match APK moduleId $moduleId"
        }

        val installedSigners = signingDigests(installedInfo)
        require(installedSigners.isNotEmpty() && installedSigners == hostSigners) {
            "Installed module signing certificate does not match the CarePad host"
        }
        require(installedSigners == archiveSigners) {
            "Update APK signing certificate does not match the installed module"
        }

        val installedVersionCode = versionCode(installedInfo)
        require(archiveVersionCode > installedVersionCode) {
            "Update versionCode $archiveVersionCode must be greater than installed $installedVersionCode"
        }

        return PreparedModuleApk(
            file = file,
            moduleId = moduleId,
            packageName = packageName,
            versionName = archiveVersionName,
            versionCode = archiveVersionCode,
            protocol = protocol,
            operation = ModuleLabPackageOperation.UPDATE,
            previousVersionName = installedInfo.versionName ?: "unknown",
            previousVersionCode = installedVersionCode
        )
    }

    fun install(context: Context, prepared: PreparedModuleApk): Int {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(prepared.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                setInstallReason(PackageManager.INSTALL_REASON_USER)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }

        val sessionId = installer.createSession(params)
        try {
            val session = installer.openSession(sessionId)
            try {
                prepared.file.inputStream().use { input ->
                    session.openWrite("base.apk", 0L, prepared.file.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }

                val statusIntent = Intent(context, ModuleInstallStatusReceiver::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                    .putExtra(EXTRA_MODULE_ID, prepared.moduleId)
                    .putExtra(EXTRA_PACKAGE_NAME, prepared.packageName)
                    .putExtra(EXTRA_OPERATION, prepared.operation.name)
                val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                val statusReceiver = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    statusIntent,
                    pendingIntentFlags
                )

                val operation = prepared.operation.name.lowercase()
                ModuleLabInstallState.setStatus(
                    context,
                    "$operation session $sessionId committed for ${prepared.moduleId}. Waiting for Android."
                )
                session.commit(statusReceiver.intentSender)
            } finally {
                session.close()
            }
            return sessionId
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        } finally {
            prepared.file.delete()
        }
    }

    private fun archivePackageInfo(
        packageManager: PackageManager,
        file: File
    ): PackageInfo? {
        val flags = packageInfoFlags()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }
    }

    private fun installedModulePackageInfo(
        packageManager: PackageManager,
        packageName: String
    ): PackageInfo? = runCatching {
        installedPackageInfo(packageManager, packageName, packageInfoFlags())
    }.getOrNull()

    private fun packageInfoFlags(): Int =
        PackageManager.GET_ACTIVITIES or
            PackageManager.GET_META_DATA or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }

    private fun installedSigningDigests(
        packageManager: PackageManager,
        packageName: String
    ): Set<String> = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        signingDigests(installedPackageInfo(packageManager, packageName, flags))
    }.getOrDefault(emptySet())

    private fun signingDigests(packageInfo: PackageInfo): Set<String> {
        val signatures: Array<out Signature> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures.orEmpty()
        }
        return signatures.map { signature -> sha256(signature.toByteArray()) }.toSet()
    }

    private fun installedPackageInfo(
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

    private fun versionCode(packageInfo: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
