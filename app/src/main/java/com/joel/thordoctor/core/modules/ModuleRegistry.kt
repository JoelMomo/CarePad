package com.joel.thordoctor.core.modules

import carepad.contracts.CarePadModuleIds

enum class ModuleId(
    val stableId: String
) {
    PERFORMANCE(CarePadModuleIds.PERFORMANCE),
    COMPATIBILITY_SETTINGS(CarePadModuleIds.COMPATIBILITY_SETTINGS),
    CONTROLS(CarePadModuleIds.CONTROLS),
    GAMES_BIOS(CarePadModuleIds.GAMES_BIOS),
    SAVED_GAMES(CarePadModuleIds.SAVED_GAMES),
    UPDATES(CarePadModuleIds.UPDATES)
}

enum class ModuleType {
    INTERNAL,
    COMPANION
}

data class ModuleDescriptor(
    val id: ModuleId,
    val type: ModuleType
)

object ModuleRegistry {

    val knownModules: List<ModuleDescriptor> = listOf(
        ModuleDescriptor(
            id = ModuleId.PERFORMANCE,
            type = ModuleType.INTERNAL
        ),
        ModuleDescriptor(
            id = ModuleId.COMPATIBILITY_SETTINGS,
            type = ModuleType.INTERNAL
        ),
        ModuleDescriptor(
            id = ModuleId.CONTROLS,
            type = ModuleType.INTERNAL
        ),
        ModuleDescriptor(
            id = ModuleId.GAMES_BIOS,
            type = ModuleType.INTERNAL
        ),
        ModuleDescriptor(
            id = ModuleId.SAVED_GAMES,
            type = ModuleType.COMPANION
        ),
        ModuleDescriptor(
            id = ModuleId.UPDATES,
            type = ModuleType.COMPANION
        )
    )
}
