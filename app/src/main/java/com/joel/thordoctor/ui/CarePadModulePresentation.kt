package com.joel.thordoctor.ui

import carepad.contracts.CarePadModuleIds
import com.joel.thordoctor.R

internal data class CarePadModulePresentation(
    val moduleId: String,
    val nameRes: Int,
    val descriptionRes: Int,
    val order: Int,
)

internal object CarePadModulePresentations {
    fun forModuleId(moduleId: String): CarePadModulePresentation? = when (moduleId) {
        CarePadModuleIds.PERFORMANCE -> CarePadModulePresentation(
            moduleId = moduleId,
            nameRes = R.string.carepad_module_performance,
            descriptionRes = R.string.carepad_module_performance_description,
            order = 0,
        )

        CarePadModuleIds.GAMES_BIOS -> CarePadModulePresentation(
            moduleId = moduleId,
            nameRes = R.string.carepad_module_games_bios,
            descriptionRes = R.string.carepad_module_games_bios_description,
            order = 1,
        )

        else -> null
    }

    fun installedVersionOrNull(value: String): String? =
        value.trim().takeIf { version ->
            version.isNotEmpty() && !version.equals("unknown", ignoreCase = true)
        }
}
