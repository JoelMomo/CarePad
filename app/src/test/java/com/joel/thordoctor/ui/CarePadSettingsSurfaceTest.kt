package com.joel.thordoctor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CarePadSettingsSurfaceTest {
    @Test
    fun carePadGlobalSettingsOnlyExposeRealHostAppearanceSetting() {
        assertEquals(
            listOf(CarePadGlobalSetting.THEME),
            carePadGlobalSettings(),
        )
    }

    @Test
    fun carePadGlobalSettingsDoNotExposeLegacySpecializedSections() {
        val visibleNames = carePadGlobalSettings().map { it.name }.toSet()

        assertFalse("DIAGNOSTICS" in visibleNames)
        assertFalse("GAME_LIBRARY" in visibleNames)
        assertFalse("GAME_SCAN" in visibleNames)
    }

    @Test
    fun shellGlobalDestinationsRemainHomeAddModulesAndSettings() {
        assertEquals(
            listOf(
                CarePadDestination.HOME,
                CarePadDestination.ADD_MODULES,
                CarePadDestination.SETTINGS,
            ),
            CarePadDestination.entries,
        )
    }
}
