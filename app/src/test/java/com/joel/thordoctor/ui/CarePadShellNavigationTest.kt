package com.joel.thordoctor.ui

import com.joel.thordoctor.AppThemeMode
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
            focusedTarget = CarePadPrimaryControllerTarget.Rail(
                CarePadDestination.ADD_MODULES
            ),
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
            focusedTarget = CarePadPrimaryControllerTarget.Module(modulePackage),
            visiblePackages = visiblePackages,
        )

        assertEquals(CarePadPrimaryControllerTarget.Module(modulePackage), target)
    }

    @Test
    fun uninstallFocusInOpenDetailsTargetsUninstallNotModule() {
        val target = carePadPrimaryControllerTarget(
            focusState = CarePadFocusState(),
            destination = CarePadDestination.HOME,
            focusedTarget = CarePadPrimaryControllerTarget.Uninstall(modulePackage),
            visiblePackages = visiblePackages,
            expandedPackage = modulePackage,
        )

        assertEquals(CarePadPrimaryControllerTarget.Uninstall(modulePackage), target)
        assertFalse(target is CarePadPrimaryControllerTarget.Module)
    }

    @Test
    fun themeFocusTargetsExactlyFocusedThemeOption() {
        listOf(
            AppThemeMode.SYSTEM,
            AppThemeMode.LIGHT,
            AppThemeMode.DARK,
        ).forEach { mode ->
            val target = carePadPrimaryControllerTarget(
                focusState = CarePadFocusState(),
                destination = CarePadDestination.SETTINGS,
                focusedTarget = CarePadPrimaryControllerTarget.Theme(mode),
                visiblePackages = visiblePackages,
            )

            assertEquals(CarePadPrimaryControllerTarget.Theme(mode), target)
        }
    }

    @Test
    fun staleDetailsChildTargetCannotOpenModuleAfterDetailsClose() {
        val target = carePadPrimaryControllerTarget(
            focusState = CarePadFocusState(),
            destination = CarePadDestination.HOME,
            focusedTarget = CarePadPrimaryControllerTarget.Uninstall(modulePackage),
            visiblePackages = visiblePackages,
            expandedPackage = null,
        )

        assertEquals(CarePadPrimaryControllerTarget.None, target)
        assertFalse(target is CarePadPrimaryControllerTarget.Module)
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
