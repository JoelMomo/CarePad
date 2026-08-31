package com.joel.thordoctor.ui

import com.joel.thordoctor.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarePadFocusControllerTest {
    private val system = CarePadFocusKey.Theme(AppThemeMode.SYSTEM)
    private val light = CarePadFocusKey.Theme(AppThemeMode.LIGHT)

    @Test
    fun modalityDoesNotOwnPhysicalFocus() {
        val initial = settingsState()
        val touch = reduceCarePadFocus(initial, CarePadFocusEvent.TouchContext)
        assertEquals(CarePadInputMethod.TOUCH, touch.modality)
        assertEquals(system, touch.observedFocus)

        val controller = reduceCarePadFocus(touch, CarePadFocusEvent.ControllerActivity)
        assertEquals(CarePadInputMethod.CONTROLLER, controller.modality)
        assertEquals(system, controller.observedFocus)
    }

    @Test
    fun observedRailFocusIsIndependentFromSelectedDestination() {
        val rail = CarePadFocusKey.Rail(CarePadDestination.HOME)
        val observed = reduceCarePadFocus(
            settingsState(),
            CarePadFocusEvent.FocusObserved(rail),
        )
        assertEquals(CarePadDestination.SETTINGS, observed.selectedDestination)
        assertEquals(rail, observed.observedFocus)
    }

    @Test
    fun observedContentFocusMustBelongToSelectedDestination() {
        val homeModule = CarePadFocusKey.Module("dev.carepad.module.performance")
        val state = settingsState()

        assertEquals(
            light,
            reduceCarePadFocus(
                state,
                CarePadFocusEvent.FocusObserved(light),
            ).observedFocus,
        )
        assertNull(
            reduceCarePadFocus(
                state,
                CarePadFocusEvent.FocusObserved(homeModule),
            ).observedFocus,
        )
    }

    @Test
    fun destinationChangePreservesRailFocusButDropsIncompatibleContentFocus() {
        val rail = CarePadFocusKey.Rail(CarePadDestination.SETTINGS)
        val railState = settingsState().copy(observedFocus = rail)
        assertEquals(
            rail,
            reduceCarePadFocus(
                railState,
                CarePadFocusEvent.DestinationSelected(CarePadDestination.HOME),
            ).observedFocus,
        )

        assertNull(
            reduceCarePadFocus(
                settingsState(),
                CarePadFocusEvent.DestinationSelected(CarePadDestination.HOME),
            ).observedFocus,
        )
    }

    @Test
    fun contentFallbackIsLocalToDestinationAndVisibleTargets() {
        val visible = listOf("dev.carepad.module.performance")
        assertEquals(
            CarePadFocusKey.Module(visible.single()),
            carePadContentFallback(CarePadDestination.HOME, visible),
        )
        assertEquals(
            CarePadFocusKey.ContentFallback(CarePadDestination.HOME),
            carePadContentFallback(CarePadDestination.HOME, emptyList()),
        )
        assertEquals(
            system,
            carePadContentFallback(CarePadDestination.SETTINGS, emptyList()),
        )
    }

    @Test
    fun actionValidationUsesObservedPhysicalFocusWithoutZoneState() {
        val visible = listOf("dev.carepad.module.performance")
        val module = CarePadFocusKey.Module(visible.single())
        val uninstall = CarePadFocusKey.Uninstall(visible.single())
        val moduleState = CarePadFocusControllerState(
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.HOME,
            observedFocus = module,
        )
        assertEquals(module, carePadControllerActionTarget(moduleState, visible))
        assertTrue(carePadDetailsControllerActionAllowed(moduleState, visible))
        assertTrue(
            carePadDetailsControllerActionAllowed(
                moduleState.copy(observedFocus = uninstall),
                visible,
            )
        )
        assertEquals(
            uninstall,
            carePadControllerActionTarget(
                moduleState.copy(observedFocus = uninstall),
                visible,
                expandedPackage = visible.single(),
            ),
        )
    }

    private fun settingsState(): CarePadFocusControllerState =
        CarePadFocusControllerState(
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.SETTINGS,
            observedFocus = system,
        )
}
