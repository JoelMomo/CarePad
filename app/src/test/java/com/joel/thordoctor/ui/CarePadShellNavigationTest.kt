package com.joel.thordoctor.ui

import androidx.compose.ui.unit.dp
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
    fun responsiveNavigationUsesRailOnlyForWideHorizontalSpace() {
        assertEquals(
            CarePadNavigationLayout.RAIL,
            carePadNavigationLayout(width = 1280.dp, height = 720.dp),
        )
        assertEquals(
            CarePadNavigationLayout.RAIL,
            carePadNavigationLayout(width = 600.dp, height = 360.dp),
        )
        assertEquals(
            CarePadNavigationLayout.BOTTOM_BAR,
            carePadNavigationLayout(width = 599.dp, height = 360.dp),
        )
        assertEquals(
            CarePadNavigationLayout.BOTTOM_BAR,
            carePadNavigationLayout(width = 720.dp, height = 1280.dp),
        )
    }

    @Test
    fun responsiveNavigationKeepsDestinationOrderStable() {
        assertEquals(
            listOf(
                CarePadDestination.HOME,
                CarePadDestination.MODULES,
                CarePadDestination.SETTINGS,
            ),
            railItems().map { item -> item.destination },
        )
    }

    @Test
    fun legacyAddModulesSavedDestinationRestoresAsModules() {
        assertEquals(
            CarePadDestination.MODULES,
            carePadDestinationFromSavedName("ADD_MODULES"),
        )
        assertEquals(
            CarePadDestination.HOME,
            carePadDestinationFromSavedName("UNKNOWN"),
        )
    }

    @Test
    fun railVisualStateFollowsObservedRailFocusOnly() {
        assertEquals(
            CarePadRailVisualState.EXPANDED,
            carePadRailVisualState(CarePadFocusKey.Rail(CarePadDestination.HOME)),
        )
        assertEquals(
            CarePadRailVisualState.COMPACT,
            carePadRailVisualState(CarePadFocusKey.Theme(AppThemeMode.SYSTEM)),
        )
        assertEquals(CarePadRailVisualState.COMPACT, carePadRailVisualState(null))
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
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.HOME,
            observedFocus = CarePadFocusKey.Rail(CarePadDestination.MODULES),
        )

        assertEquals(
            CarePadFocusKey.Rail(CarePadDestination.MODULES),
            carePadControllerActionTarget(state, visiblePackages),
        )
    }

    @Test
    fun modulePrimaryActionRequiresModulesAndVisiblePackage() {
        val module = CarePadFocusKey.Module(modulePackage)
        val state = CarePadFocusControllerState(
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.MODULES,
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
    fun themePrimaryActionRequiresSettings() {
        val theme = CarePadFocusKey.Theme(AppThemeMode.LIGHT)
        val state = CarePadFocusControllerState(
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
    fun homeShortcutFallbackIsAControllerActionTarget() {
        val homeShortcut = CarePadFocusKey.ContentFallback(CarePadDestination.HOME)
        val state = CarePadFocusControllerState(
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.HOME,
            observedFocus = homeShortcut,
        )

        assertEquals(homeShortcut, carePadControllerActionTarget(state, visiblePackages))
    }

    @Test
    fun fallbackFocusIsNeverAnActionTarget() {
        val state = CarePadFocusControllerState(
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.MODULES,
            observedFocus = CarePadFocusKey.ContentFallback(CarePadDestination.MODULES),
        )

        assertNull(carePadControllerActionTarget(state, visiblePackages))
    }

    @Test
    fun detailsActionTracksObservedModulePackage() {
        val moduleState = CarePadFocusControllerState(
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.MODULES,
            observedFocus = CarePadFocusKey.Module(modulePackage),
        )

        assertTrue(carePadDetailsControllerActionAllowed(moduleState, visiblePackages))
        assertFalse(
            carePadDetailsControllerActionAllowed(
                moduleState.copy(
                    observedFocus = CarePadFocusKey.Rail(CarePadDestination.HOME)
                ),
                visiblePackages,
            )
        )
    }
}
