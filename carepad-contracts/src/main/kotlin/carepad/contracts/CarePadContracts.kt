package carepad.contracts

/** Shared protocol version between the CarePad host and product modules. */
object CarePadProtocol {
    const val VERSION = 1
}

/** Stable technical IDs. They are intentionally independent from UI labels. */
object CarePadModuleIds {
    const val PERFORMANCE = "performance"
    const val COMPATIBILITY_SETTINGS = "compatibility_settings"
    const val CONTROLS = "controls"
    const val GAMES_BIOS = "games_bios"
    const val SAVED_GAMES = "saved_games"
    const val UPDATES = "updates"
}

/**
 * Prototype Android actions shared by the host and module APKs.
 *
 * These remain part of the technical prototype until the downloadable-module
 * architecture is validated on real hardware.
 */
object CarePadModuleActions {
    const val MODULE = "dev.carepad.action.MODULE"
    const val OPEN_MODULE = "dev.carepad.action.OPEN_MODULE"
    const val OPEN_MODULE_SETTINGS = "dev.carepad.action.OPEN_MODULE_SETTINGS"
    const val BIND_MODULE = "dev.carepad.action.BIND_MODULE"
}

/** Known module capability tokens exchanged via manifest metadata. */
object CarePadModuleCapabilities {
    const val SETTINGS_INLINE = "settings_inline"
    const val SETTINGS_DELEGATED = "settings_delegated"
}

/** Manifest metadata keys used by the host to inspect module APKs. */
object CarePadModuleMetadataKeys {
    const val MODULE_ID = "dev.carepad.meta.MODULE_ID"
    const val PROTOCOL_MIN = "dev.carepad.meta.PROTOCOL_MIN"
    const val PROTOCOL_MAX = "dev.carepad.meta.PROTOCOL_MAX"
    const val CAPABILITIES = "dev.carepad.meta.CAPABILITIES"
}

data class ModuleProtocolRange(
    val min: Int,
    val max: Int
) {
    init {
        require(min > 0) { "Minimum protocol version must be positive" }
        require(max >= min) { "Maximum protocol version must be >= minimum" }
    }

    fun supports(protocolVersion: Int): Boolean =
        protocolVersion in min..max
}

/**
 * Android-independent metadata exchanged by CarePad and a product module.
 * Package discovery, signatures and IPC transport stay outside this library.
 */
data class CarePadModuleMetadata(
    val moduleId: String,
    val moduleVersion: String,
    val protocol: ModuleProtocolRange,
    val capabilities: Set<String> = emptySet()
) {
    init {
        require(moduleId.isNotBlank()) { "Module ID must not be blank" }
        require(moduleVersion.isNotBlank()) { "Module version must not be blank" }
    }
}
