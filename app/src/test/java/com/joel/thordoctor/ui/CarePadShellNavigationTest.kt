package com.joel.thordoctor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarePadShellNavigationTest {
    private val modulePackage = "dev.carepad.module.performance"
    private val visiblePackages = setOf(modulePackage)

    @Test
    fun railPrimaryActionActivatesFocusedRailDestinationInsteadOfModule() {
        val focusState = CarePadFocusState()
            .onRailFocused(CarePadDestination.ADD_MODULES)

        val target = carePadPrimaryControllerTarget(
            focusState = focusState,
            destination = CarePadDestination.HOME,
            focusedModulePackage = modulePackage,
            visiblePackages = visiblePackages,
        )

        assertEquals(
            CarePadPrimaryControllerTarget.Rail(CarePadDestination.ADD_MODULES),
            target,
        )
        assertFalse(target is CarePadPrimaryControllerTarget.Module)
    }

    @Test
    fun contentRoundTripPreservesLastActuallyFocusedRailDestination() {
        var focusState = CarePadFocusState()
            .onRailFocused(CarePadDestination.SETTINGS)

        focusState = focusState.onContentFocused()

        assertEquals(CarePadFocusZone.CONTENT, focusState.zone)
        assertEquals(CarePadDestination.SETTINGS, focusState.lastRailDestination)

        val targetAfterReturningToRail = focusState.onRailFocused(
            focusState.lastRailDestination
        )

        assertEquals(CarePadFocusZone.RAIL, targetAfterReturningToRail.zone)
        assertEquals(
            CarePadDestination.SETTINGS,
            targetAfterReturningToRail.lastRailDestination,
        )
    }

    @Test
    fun contentHomePrimaryActionStillTargetsFocusedModule() {
        val target = carePadPrimaryControllerTarget(
            focusState = CarePadFocusState(),
            destination = CarePadDestination.HOME,
            focusedModulePackage = modulePackage,
            visiblePackages = visiblePackages,
        )

        assertEquals(CarePadPrimaryControllerTarget.Module(modulePackage), target)
    }

    @Test
    fun detailsActionIsLimitedToHomeContent() {
        val contentHome = CarePadFocusState()
        val railHome = contentHome.onRailFocused(CarePadDestination.HOME)

        assertTrue(
            carePadDetailsControllerActionAllowed(
                focusState = contentHome,
                destination = CarePadDestination.HOME,
                focusedModulePackage = modulePackage,
                visiblePackages = visiblePackages,
            )
        )
        assertFalse(
            carePadDetailsControllerActionAllowed(
                focusState = railHome,
                destination = CarePadDestination.HOME,
                focusedModulePackage = modulePackage,
                visiblePackages = visiblePackages,
            )
        )
        assertFalse(
            carePadDetailsControllerActionAllowed(
                focusState = contentHome,
                destination = CarePadDestination.SETTINGS,
                focusedModulePackage = modulePackage,
                visiblePackages = visiblePackages,
            )
        )
    }
}
