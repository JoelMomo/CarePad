package com.joel.thordoctor.modules.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.Signature
import android.os.Build
import carepad.contracts.CarePadModuleActions
import carepad.contracts.CarePadModuleMetadata
import carepad.contracts.CarePadModuleMetadataKeys
import carepad.contracts.CarePadProtocol
import carepad.contracts.ModuleProtocolRange
import java.security.MessageDigest

data class DiscoveredCarePadModule(
    val metadata: CarePadModuleMetadata,
    val packageName: String,
    val entryActivity: ComponentName
)

data class RejectedCarePadModule(
    val packageName: String,
    val reason: String
)

data class ModuleDiscoveryResult(
    val modules: List<DiscoveredCarePadModule>,
    val rejected: List<RejectedCarePadModule>
)

/** Android host-side discovery for independently installed CarePad module APKs. */
object AndroidModuleDiscovery {
    fun discover(context: Context): ModuleDiscoveryResult {
        val packageManager = context.packageManager
        val hostSigners = signingDigests(packageManager, context.packageName)
        val rejected = mutableListOf<RejectedCarePadModule>()
        val candidates = queryModuleActivities(packageManager)
            .mapNotNull { resolveInfo ->
                parseCandidate(packageManager, resolveInfo, hostSigners, rejected)
            }

        val duplicateIds = candidates
            .groupBy { it.metadata.moduleId }
            .filterValues { it.size > 1 }
            .keys

        val accepted = candidates
            .filterNot { it.metadata.moduleId in duplicateIds }
            .sortedBy { it.metadata.moduleId }

        candidates
            .filter { it.metadata.moduleId in duplicateIds }
            .forEach { module ->
                rejected += RejectedCarePadModule(
                    packageName = module.packageName,
                    reason = "Duplicate moduleId: ${module.metadata.moduleId}"
                )
            }

        return ModuleDiscoveryResult(
            modules = accepted,
            rejected = rejected.sortedBy { it.packageName }
        )
    }

    private fun parseCandidate(
        packageManager: PackageManager,
        resolveInfo: ResolveInfo,
        hostSigners: Set<String>,
        rejected: MutableList<RejectedCarePadModule>
    ): DiscoveredCarePadModule? {
        val activityInfo = resolveInfo.activityInfo ?: return null
        val packageName = activityInfo.packageName
        val metadata = activityInfo.metaData

        val moduleId = metadata?.getString(CarePadModuleMetadataKeys.MODULE_ID)
        val protocolMin = metadata?.getInt(CarePadModuleMetadataKeys.PROTOCOL_MIN, 0) ?: 0
        val protocolMax = metadata?.getInt(CarePadModuleMetadataKeys.PROTOCOL_MAX, 0) ?: 0

        if (moduleId.isNullOrBlank() || protocolMin <= 0 || protocolMax < protocolMin) {
            rejected += RejectedCarePadModule(packageName, "Missing or invalid module metadata")
            return null
        }

        val moduleSigners = signingDigests(packageManager, packageName)
        if (hostSigners.isEmpty() || moduleSigners.isEmpty() || hostSigners != moduleSigners) {
            rejected += RejectedCarePadModule(packageName, "Signing certificate does not match host")
            return null
        }

        val protocolRange = ModuleProtocolRange(protocolMin, protocolMax)
        if (!protocolRange.supports(CarePadProtocol.VERSION)) {
            rejected += RejectedCarePadModule(
                packageName,
                "Protocol $protocolMin..$protocolMax does not include host ${CarePadProtocol.VERSION}"
            )
            return null
        }

        val packageInfo = packageInfo(packageManager, packageName, 0)
        val capabilities = metadata
            ?.getString(CarePadModuleMetadataKeys.CAPABILITIES)
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

        return DiscoveredCarePadModule(
            metadata = CarePadModuleMetadata(
                moduleId = moduleId,
                moduleVersion = packageInfo.versionName ?: "unknown",
                protocol = protocolRange,
                capabilities = capabilities
            ),
            packageName = packageName,
            entryActivity = ComponentName(packageName, activityInfo.name)
        )
    }

    private fun queryModuleActivities(packageManager: PackageManager): List<ResolveInfo> {
        val intent = Intent(CarePadModuleActions.MODULE)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(intent, PackageManager.GET_META_DATA)
        }
    }

    private fun signingDigests(
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
