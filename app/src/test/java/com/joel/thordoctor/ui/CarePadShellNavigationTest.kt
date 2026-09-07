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
                CarePadDestination.ADD_MODULES,
                CarePadDestination.SETTINGS,
            ),
            railItems().map { item -> item.destination },
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
            observedFocus = CarePadFocusKey.Rail(CarePadDestination.ADD_MODULES),
        )

        assertEquals(
            CarePadFocusKey.Rail(CarePadDestination.ADD_MODULES),
            carePadControllerActionTarget(state, visiblePackages),
        )
    }

    @Test
    fun modulePrimaryActionRequiresHomeAndVisiblePackage() {
        val module = CarePadFocusKey.Module(modulePackage)
        val state = CarePadFocusControllerState(
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
    fun fallbackFocusIsNeverAnActionTarget() {
        val state = CarePadFocusControllerState(
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.ADD_MODULES,
            observedFocus = CarePadFocusKey.ContentFallback(CarePadDestination.ADD_MODULES),
        )

        assertNull(carePadControllerActionTarget(state, visiblePackages))
    }

    @Test
    fun detailsActionTracksObservedModuleOrUninstallPackage() {
        val moduleState = CarePadFocusControllerState(
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
                moduleState.copy(
                    observedFocus = CarePadFocusKey.Rail(CarePadDestination.HOME)
                ),
                visiblePackages,
            )
        )
    }
}
