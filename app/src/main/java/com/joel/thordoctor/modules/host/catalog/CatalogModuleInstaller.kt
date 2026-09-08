package com.joel.thordoctor.modules.host.catalog

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import carepad.contracts.CarePadModuleMetadataKeys
import com.joel.thordoctor.modules.catalog.distribution.ModuleDistributionArtifact
import java.io.File
import java.security.MessageDigest

internal sealed interface CatalogInstallRequestResult {
    data class Submitted(val sessionId: Int) : CatalogInstallRequestResult
    data class Rejected(val reason: String) : CatalogInstallRequestResult
}

internal object CatalogModuleInstaller {
    const val ACTION_INSTALL_STATUS =
        "com.joel.thordoctor.action.CAREPAD_CATALOG_INSTALL_STATUS"
    const val EXTRA_MODULE_ID = "module_id"
    const val EXTRA_PACKAGE_NAME = "package_name"
    const val EXTRA_ARTIFACT_ID = "artifact_id"

    private const val DEVELOPMENT_QA_ASSET_PREFIX =
        "asset://carepad-development-qa/"

    fun requestInstall(
        context: Context,
        target: ModuleDistributionArtifact,
    ): CatalogInstallRequestResult {
        CatalogModuleInstallState.markPreparing(context, target.packageName)
        val prepared = runCatching { prepareExactApk(context, target) }
            .getOrElse { error ->
                val detail = error.message ?: error::class.java.simpleName
                CatalogModuleInstallState.markFailed(context, target.packageName, detail)
                return CatalogInstallRequestResult.Rejected(detail)
            }

        return runCatching {
            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            ).apply {
                setAppPackageName(target.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
            val sessionId = packageInstaller.createSession(params)
            packageInstaller.openSession(sessionId).use { session ->
                prepared.inputStream().use { input ->
                    session.openWrite("base.apk", 0, prepared.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callbackIntent = Intent(context, CatalogModuleInstallStatusReceiver::class.java)
                    .setAction(ACTION_INSTALL_STATUS)
                    .putExtra(EXTRA_MODULE_ID, target.moduleId)
                    .putExtra(EXTRA_PACKAGE_NAME, target.packageName)
                    .putExtra(EXTRA_ARTIFACT_ID, target.artifactId)
                val callbackFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                val callback = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callbackIntent,
                    callbackFlags,
                )
                session.commit(callback.intentSender)
            }
            CatalogModuleInstallState.markSubmitted(context, target.packageName)
            prepared.delete()
            CatalogInstallRequestResult.Submitted(sessionId)
        }.getOrElse { error ->
            prepared.delete()
            val detail = error.message ?: error::class.java.simpleName
            CatalogModuleInstallState.markFailed(context, target.packageName, detail)
            CatalogInstallRequestResult.Rejected(detail)
        }
    }

    private fun prepareExactApk(
        context: Context,
        target: ModuleDistributionArtifact,
    ): File {
        require(!isPackageInstalled(context.packageManager, target.packageName)) {
            "El módulo ya está instalado."
        }
        val source = target.sources.firstOrNull { it.startsWith(DEVELOPMENT_QA_ASSET_PREFIX) }
            ?: error("El fixture no contiene una fuente APK autorizada.")
        val assetPath = source.removePrefix("asset://")
        require(!assetPath.contains("..")) { "Ruta de fixture no válida." }

        val cacheDir = File(context.cacheDir, "carepad-catalog-install").apply { mkdirs() }
        val apk = File(cacheDir, target.packageName.replace('.', '_') + ".apk")
        context.assets.open(assetPath).use { input ->
            apk.outputStream().use { output -> input.copyTo(output) }
        }

        try {
            require(apk.length() == target.sizeBytes) {
                "El tamaño del APK no coincide con el catálogo."
            }
            require(apk.sha256() == target.apkSha256) {
                "El hash del APK no coincide con el catálogo."
            }

            val archive = archivePackageInfo(context.packageManager, apk)
                ?: error("Android no reconoce el APK del catálogo.")
            require(archive.packageName == target.packageName) {
                "El package del APK no coincide con el catálogo."
            }
            require(archive.longVersionCodeCompat() == target.versionCode) {
                "El versionCode del APK no coincide con el catálogo."
            }
            require(archive.versionName == target.versionName) {
                "El versionName del APK no coincide con el catálogo."
            }

            val moduleActivity = archive.activities.orEmpty().firstOrNull { activity ->
                activity.metaData?.getString(CarePadModuleMetadataKeys.MODULE_ID) == target.moduleId
            } ?: error("El APK no declara el moduleId esperado.")

            val protocolMin = moduleActivity.metaData?.getInt(
                CarePadModuleMetadataKeys.PROTOCOL_MIN,
                -1,
            ) ?: -1
            val protocolMax = moduleActivity.metaData?.getInt(
                CarePadModuleMetadataKeys.PROTOCOL_MAX,
                -1,
            ) ?: -1
            require(protocolMin == target.protocolRange.min && protocolMax == target.protocolRange.max) {
                "El protocolo del APK no coincide con el catálogo."
            }

            val archiveSigners = currentSignerSha256(archive)
            require(archiveSigners.size == 1) {
                "El APK debe tener un único firmante actual."
            }
            require(target.signingCertificateSha256 in archiveSigners) {
                "La firma del APK no coincide con el catálogo."
            }

            val host = installedPackageInfo(context.packageManager, context.packageName)
                ?: error("No se pudo comprobar la firma de CarePad.")
            require(currentSignerSha256(host) == archiveSigners) {
                "El módulo y CarePad no están firmados por el mismo signer QA/lab."
            }
            return apk
        } catch (error: Throwable) {
            apk.delete()
            throw error
        }
    }

    private fun archivePackageInfo(packageManager: PackageManager, apk: File): PackageInfo? {
        val signingFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val flags = PackageManager.GET_ACTIVITIES or
            PackageManager.GET_META_DATA or
            signingFlag
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(flags.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        }
    }

    private fun installedPackageInfo(
        packageManager: PackageManager,
        packageName: String,
    ): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(
                    PackageManager.GET_SIGNING_CERTIFICATES.toLong()
                ),
            )
        } else {
            val signingFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_SIGNATURES
            }
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, signingFlag)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun isPackageInstalled(
        packageManager: PackageManager,
        packageName: String,
    ): Boolean = installedPackageInfo(packageManager, packageName) != null

    private fun currentSignerSha256(packageInfo: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures.orEmpty()
        }
        return signatures.mapTo(linkedSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }

    private fun PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }

    private fun File.sha256(): String = inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
