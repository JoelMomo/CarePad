package com.joel.thordoctor.ui

import com.joel.thordoctor.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarePadShellNavigationTest {
    private val modulePackage = "dev.carepad.module.performance"
    private val visiblePackages = setOf(modulePackage)

    @Test
    fun railVisualStateDependsOnlyOnSemanticZone() {
        assertEquals(
            CarePadRailVisualState.EXPANDED,
            carePadRailVisualState(CarePadFocusZone.RAIL),
        )
        assertEquals(
            CarePadRailVisualState.COMPACT,
            carePadRailVisualState(CarePadFocusZone.CONTENT),
        )
    }

    @Test
    fun selectedRailDestinationIsIndependentFromObservedFocus() {
        assertTrue(
            carePadRailItemSelected(
                selected = CarePadDestination.SETTINGS,
                candidate = CarePadDestination.SETTINGS,
            )
        )
        assertFalse(
            carePadRailItemSelected(
                selected = CarePadDestination.SETTINGS,
                candidate = CarePadDestination.HOME,
            )
        )
    }

    @Test
    fun railPrimaryActionUsesObservedRailFocusNotSelectedDestination() {
        val state = CarePadFocusControllerState(
            activeZone = CarePadFocusZone.RAIL,
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.HOME,
            observedFocus = CarePadFocusKey.Rail(CarePadDestination.ADD_MODULES),
        )

        assertEquals(
            CarePadFocusKey.Rail(CarePadDestination.ADD_MODULES),
            carePadControllerActionTarget(state, visiblePackages),
        )
    }

    @Test
    fun modulePrimaryActionRequiresHomeContentAndVisiblePackage() {
        val module = CarePadFocusKey.Module(modulePackage)
        val state = CarePadFocusControllerState(
            activeZone = CarePadFocusZone.CONTENT,
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.HOME,
            observedFocus = module,
        )

        assertEquals(module, carePadControllerActionTarget(state, visiblePackages))
        assertNull(
            carePadControllerActionTarget(
                state.copy(selectedDestination = CarePadDestination.SETTINGS),
                visiblePackages,
            )
        )
    }

    @Test
    fun uninstallPrimaryActionRequiresOpenDetails() {
        val uninstall = CarePadFocusKey.Uninstall(modulePackage)
        val state = CarePadFocusControllerState(
            activeZone = CarePadFocusZone.CONTENT,
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.HOME,
            observedFocus = uninstall,
        )

        assertNull(carePadControllerActionTarget(state, visiblePackages))
        assertEquals(
            uninstall,
            carePadControllerActionTarget(
                state,
                visiblePackages,
                expandedPackage = modulePackage,
            ),
        )
    }

    @Test
    fun themePrimaryActionRequiresSettingsContent() {
        val theme = CarePadFocusKey.Theme(AppThemeMode.LIGHT)
        val state = CarePadFocusControllerState(
            activeZone = CarePadFocusZone.CONTENT,
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.SETTINGS,
            observedFocus = theme,
        )

        assertEquals(theme, carePadControllerActionTarget(state, emptySet()))
        assertNull(
            carePadControllerActionTarget(
                state.copy(selectedDestination = CarePadDestination.HOME),
                emptySet(),
            )
        )
    }

    @Test
    fun fallbackFocusIsNeverAnActionTarget() {
        val state = CarePadFocusControllerState(
            activeZone = CarePadFocusZone.CONTENT,
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.ADD_MODULES,
            observedFocus = CarePadFocusKey.ContentFallback(CarePadDestination.ADD_MODULES),
        )

        assertNull(carePadControllerActionTarget(state, visiblePackages))
    }

    @Test
    fun detailsActionTracksObservedModuleOrUninstallPackage() {
        val moduleState = CarePadFocusControllerState(
            activeZone = CarePadFocusZone.CONTENT,
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.HOME,
            observedFocus = CarePadFocusKey.Module(modulePackage),
        )

        assertTrue(carePadDetailsControllerActionAllowed(moduleState, visiblePackages))
        assertTrue(
            carePadDetailsControllerActionAllowed(
                moduleState.copy(
                    observedFocus = CarePadFocusKey.Uninstall(modulePackage)
                ),
                visiblePackages,
            )
        )
        assertFalse(
            carePadDetailsControllerActionAllowed(
                moduleState.copy(activeZone = CarePadFocusZone.RAIL),
                visiblePackages,
            )
        )
    }
}
