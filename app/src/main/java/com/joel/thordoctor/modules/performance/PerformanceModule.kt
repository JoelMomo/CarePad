package com.joel.thordoctor.modules.performance

import android.content.Context
import com.joel.thordoctor.core.modules.FeatureModule
import com.joel.thordoctor.core.modules.ModuleDescriptor
import com.joel.thordoctor.core.modules.ModuleId
import com.joel.thordoctor.core.modules.ModuleType

/**
 * First concrete CarePad internal module.
 *
 * The performance module owns metric collection, session statistics and
 * session recovery. The existing DocThor foreground service still hosts the
 * monitoring loop and Android notification lifecycle; those responsibilities
 * are the next extraction step.
 */
object PerformanceModule : FeatureModule {

    override val descriptor =
        ModuleDescriptor(
            id = ModuleId.PERFORMANCE,
            type = ModuleType.INTERNAL
        )

    override fun isAvailable(context: Context): Boolean = true
}
