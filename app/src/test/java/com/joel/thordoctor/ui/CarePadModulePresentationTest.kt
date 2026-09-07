package com.joel.thordoctor.ui

import carepad.contracts.CarePadModuleIds
import com.joel.thordoctor.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CarePadModulePresentationTest {
    @Test
    fun consolidationSlicePresentsPerformanceGamesBiosAndControls() {
        val performance = CarePadModulePresentations.forModuleId(CarePadModuleIds.PERFORMANCE)
        val gamesBios = CarePadModulePresentations.forModuleId(CarePadModuleIds.GAMES_BIOS)
        val controls = CarePadModulePresentations.forModuleId(CarePadModuleIds.CONTROLS)

        assertNotNull(performance)
        assertNotNull(gamesBios)
        assertNotNull(controls)
        assertEquals(R.string.carepad_module_performance, performance?.nameRes)
        assertEquals(R.string.carepad_module_games_bios, gamesBios?.nameRes)
        assertEquals(R.string.carepad_module_controls, controls?.nameRes)
        assertNull(CarePadModulePresentations.forModuleId(CarePadModuleIds.UPDATES))
    }

    @Test
    fun unknownOrBlankInstalledVersionIsNotPresentedAsRealData() {
        assertNull(CarePadModulePresentations.installedVersionOrNull(""))
        assertNull(CarePadModulePresentations.installedVersionOrNull("   "))
        assertNull(CarePadModulePresentations.installedVersionOrNull("unknown"))
        assertNull(CarePadModulePresentations.installedVersionOrNull("UNKNOWN"))
        assertEquals("1.2.3", CarePadModulePresentations.installedVersionOrNull(" 1.2.3 "))
    }
}
