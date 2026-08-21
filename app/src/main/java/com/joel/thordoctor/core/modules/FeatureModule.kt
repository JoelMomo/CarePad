package com.joel.thordoctor.core.modules

import android.content.Context

/**
 * Runtime contract for a CarePad feature module.
 *
 * User-facing names and descriptions intentionally stay outside the Core so
 * each host app can localize and present modules independently.
 */
interface FeatureModule {
    val descriptor: ModuleDescriptor

    /** Whether this module can currently operate on the device. */
    fun isAvailable(context: Context): Boolean
}
