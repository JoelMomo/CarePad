package com.joel.thordoctor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CarePadFocusObservedContractTest {
    @Test
    fun incidentalRailObservationInContentPreservesRailMemoryAndL1RestoresIt() {
        val settingsRail = CarePadFocusKey.Rail(CarePadDestination.SETTINGS)
        val homeRail = CarePadFocusKey.Rail(CarePadDestination.HOME)
        val initial = CarePadFocusControllerState(
            activeZone = CarePadFocusZone.CONTENT,
            selectedDestination = CarePadDestination.HOME,
            railPreferredDestination = CarePadDestination.SETTINGS,
        )

        val observed = reduceCarePadFocus(
            initial,
            CarePadFocusEvent.FocusObserved(homeRail),
        )

        assertEquals(homeRail, observed.observedFocus)
        assertEquals(CarePadDestination.SETTINGS, observed.railPreferredDestination)

        val afterL1 = reduceCarePadFocus(
            observed,
            CarePadFocusEvent.ControllerL1(),
        )

        assertEquals(CarePadFocusZone.RAIL, afterL1.activeZone)
        assertEquals(settingsRail, afterL1.pendingFocus?.target)
    }

    @Test
    fun railObservationInRailZoneUpdatesRailMemory() {
        val homeRail = CarePadFocusKey.Rail(CarePadDestination.HOME)
        val initial = CarePadFocusControllerState(
            activeZone = CarePadFocusZone.RAIL,
            selectedDestination = CarePadDestination.SETTINGS,
            railPreferredDestination = CarePadDestination.SETTINGS,
        )

        val observed = reduceCarePadFocus(
            initial,
            CarePadFocusEvent.FocusObserved(homeRail),
        )

        assertEquals(homeRail, observed.observedFocus)
        assertEquals(CarePadDestination.HOME, observed.railPreferredDestination)
    }
}
