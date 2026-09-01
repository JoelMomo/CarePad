package com.joel.thordoctor.core.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class CarePadSettingsSecurityTest {

    @Test
    fun permissionConstantMatchesSpecification() {
        assertEquals(
            "dev.carepad.permission.MODULE_SETTINGS",
            CarePadSettingsSecurity.PERMISSION_MODULE_SETTINGS
        )
    }
}
