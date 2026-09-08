package com.joel.thordoctor.modules.catalog.distribution

import android.content.Context
import carepad.contracts.CarePadProtocol
import carepad.contracts.ModuleProtocolRange
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface DevelopmentQaCatalogState {
    data object NotConnected : DevelopmentQaCatalogState
    data class Available(
        val catalog: ModuleDistributionCatalog,
        val targets: List<ModuleDistributionArtifact>,
    ) : DevelopmentQaCatalogState
    data class Invalid(val detail: String) : DevelopmentQaCatalogState
}

internal object DevelopmentQaModuleCatalog {
    private const val ASSET_DIRECTORY = "carepad-development-qa"
    private const val CATALOG_FILE = "module-catalog.json"
    private const val ASSET_PATH = "$ASSET_DIRECTORY/$CATALOG_FILE"

    fun load(context: Context): DevelopmentQaCatalogState {
        val catalogPresent = runCatching {
            CATALOG_FILE in context.assets.list(ASSET_DIRECTORY).orEmpty()
        }.getOrElse { return DevelopmentQaCatalogState.NotConnected }
        if (!catalogPresent) return DevelopmentQaCatalogState.NotConnected

        val json = runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        }.getOrElse { error ->
            return DevelopmentQaCatalogState.Invalid(error.message ?: error::class.java.simpleName)
        }

        return runCatching {
            val catalog = parseCatalog(json)
            require(catalog.channel == DEVELOPMENT_QA_CHANNEL) {
                "Unexpected catalog channel"
            }
            val targets = catalog.modules.map { module ->
                when (
                    val resolution = module.resolveLatest(
                        channel = DEVELOPMENT_QA_CHANNEL,
                        hostProtocolVersion = CarePadProtocol.VERSION,
                    )
                ) {
                    is ModuleDistributionResolution.Ready -> resolution.artifact
                    is ModuleDistributionResolution.Rejected ->
                        error("${module.moduleId}: ${resolution.reason.name}")
                }
            }
            DevelopmentQaCatalogState.Available(catalog, targets)
        }.getOrElse { error ->
            DevelopmentQaCatalogState.Invalid(error.message ?: error::class.java.simpleName)
        }
    }

    internal fun parseCatalog(json: String): ModuleDistributionCatalog {
        val root = JSONObject(json)
        val modulesJson = root.getJSONArray("modules")
        val modules = buildList(modulesJson.length()) {
            for (index in 0 until modulesJson.length()) {
                add(parseModule(modulesJson.getJSONObject(index)))
            }
        }
        return ModuleDistributionCatalog(
            schemaVersion = root.getInt("schemaVersion"),
            channel = root.getString("channel"),
            modules = modules,
        )
    }

    private fun parseModule(json: JSONObject): ModuleDistributionEntry {
        val artifactsJson = json.getJSONArray("artifacts")
        val artifacts = buildList(artifactsJson.length()) {
            for (index in 0 until artifactsJson.length()) {
                add(parseArtifact(artifactsJson.getJSONObject(index)))
            }
        }
        return ModuleDistributionEntry(
            moduleId = json.getString("moduleId"),
            packageName = json.getString("packageName"),
            latestByChannel = json.getJSONObject("latestByChannel").stringMap(),
            lastKnownRecoverableByChannel =
                json.optJSONObject("lastKnownRecoverableByChannel")?.stringMap().orEmpty(),
            artifacts = artifacts,
        )
    }

    private fun parseArtifact(json: JSONObject): ModuleDistributionArtifact =
        ModuleDistributionArtifact(
            artifactId = json.getString("artifactId"),
            moduleId = json.getString("moduleId"),
            packageName = json.getString("packageName"),
            versionCode = json.getLong("versionCode"),
            versionName = json.getString("versionName"),
            protocolRange = ModuleProtocolRange(
                min = json.getInt("protocolMin"),
                max = json.getInt("protocolMax"),
            ),
            apkSha256 = json.getString("apkSha256").lowercase(),
            signingCertificateSha256 =
                json.getString("signingCertificateSha256").lowercase(),
            sizeBytes = json.getLong("sizeBytes"),
            sources = json.getJSONArray("sources").stringList(),
            releaseTag = if (json.isNull("releaseTag")) {
                null
            } else {
                json.optString("releaseTag").takeIf { it.isNotBlank() }
            },
            commitSha = json.getString("commitSha"),
            maturity = json.getString("maturity"),
            channels = json.getJSONArray("channels").stringList().toSet(),
            distributionStatus = ModuleDistributionStatus.fromWire(
                json.getString("distributionStatus")
            ) ?: error("Unknown distribution status"),
            recoveryStatus = json.getString("recoveryStatus"),
            recoverySafety = json.getString("recoverySafety"),
            availability = ModuleArtifactAvailability.fromWire(
                json.getString("availability")
            ) ?: error("Unknown availability"),
        )
}

private fun JSONObject.stringMap(): Map<String, String> = buildMap {
    keys().forEach { key -> put(key, getString(key)) }
}

private fun JSONArray.stringList(): List<String> =
    List(length()) { index -> getString(index) }
