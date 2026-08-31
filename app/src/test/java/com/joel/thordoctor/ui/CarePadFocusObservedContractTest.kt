package com.joel.thordoctor.ui

import com.joel.thordoctor.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarePadFocusObservedContractTest {
    @Test
    fun railObservationDoesNotChangeSelectionOrCreateEntryMemory() {
        val homeRail = CarePadFocusKey.Rail(CarePadDestination.HOME)
        val initial = CarePadFocusControllerState(
            selectedDestination = CarePadDestination.SETTINGS,
            observedFocus = CarePadFocusKey.Theme(AppThemeMode.SYSTEM),
        )

        val observed = reduceCarePadFocus(
            initial,
            CarePadFocusEvent.FocusObserved(homeRail),
        )

        assertEquals(CarePadDestination.SETTINGS, observed.selectedDestination)
        assertEquals(homeRail, observed.observedFocus)
    }

    @Test
    fun incompatibleContentObservationIsRejectedWithoutZoneState() {
        val initial = CarePadFocusControllerState(
            selectedDestination = CarePadDestination.SETTINGS,
        )
        val homeModule = CarePadFocusKey.Module("dev.carepad.module.performance")

        assertNull(
            reduceCarePadFocus(
                initial,
                CarePadFocusEvent.FocusObserved(homeModule),
            ).observedFocus,
        )
        assertEquals(
            CarePadFocusKey.Theme(AppThemeMode.LIGHT),
            reduceCarePadFocus(
                initial,
                CarePadFocusEvent.FocusObserved(
                    CarePadFocusKey.Theme(AppThemeMode.LIGHT)
                ),
            ).observedFocus,
        )
    }
}
