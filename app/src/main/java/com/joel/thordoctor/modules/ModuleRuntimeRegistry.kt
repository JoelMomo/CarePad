package com.joel.thordoctor.modules

import com.joel.thordoctor.core.modules.FeatureModule
import com.joel.thordoctor.core.modules.ModuleId
import com.joel.thordoctor.modules.performance.PerformanceModule

/** Host-level registry of module implementations currently bundled in legacy DocThor. */
object ModuleRuntimeRegistry {

    val internalModules: List<FeatureModule> =
        listOf(PerformanceModule)

    fun implementationFor(id: ModuleId): FeatureModule? =
        internalModules.firstOrNull { module ->
            module.descriptor.id == id
        }
}
