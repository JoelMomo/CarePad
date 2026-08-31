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
    fun l1IsTheOnlyControllerOrFocusEventThatChangesZone() {
        val initial = settingsState()
        assertEquals(
            CarePadFocusZone.CONTENT,
            reduceCarePadFocus(initial, CarePadFocusEvent.ControllerActivity).activeZone,
        )
        assertEquals(
            CarePadFocusZone.CONTENT,
            reduceCarePadFocus(
                initial,
                CarePadFocusEvent.FocusObserved(
                    CarePadFocusKey.Rail(CarePadDestination.HOME)
                ),
            ).activeZone,
        )
        assertEquals(
            CarePadFocusZone.RAIL,
            reduceCarePadFocus(initial, CarePadFocusEvent.ControllerL1()).activeZone,
        )
    }

    @Test
    fun touchRailUpdatesLogicalContextWithoutSchedulingPhysicalFocus() {
        val after = reduceCarePadFocus(
            settingsState(),
            CarePadFocusEvent.TouchRail(CarePadDestination.SETTINGS),
        )
        assertEquals(CarePadInputMethod.TOUCH, after.modality)
        assertEquals(CarePadFocusZone.RAIL, after.activeZone)
        assertEquals(CarePadDestination.SETTINGS, after.railPreferredDestination)
        assertNull(after.observedFocus)
        assertNull(after.pendingFocus)
        assertEquals(system, after.contentPreferredTargets[CarePadDestination.SETTINGS])
    }

    @Test
    fun touchContentRecordsSemanticTargetWithoutSchedulingPhysicalFocus() {
        val initial = settingsState().copy(
            activeZone = CarePadFocusZone.RAIL,
            observedFocus = CarePadFocusKey.Rail(CarePadDestination.SETTINGS),
        )
        val after = reduceCarePadFocus(initial, CarePadFocusEvent.TouchContent(light))
        assertEquals(CarePadInputMethod.TOUCH, after.modality)
        assertEquals(CarePadFocusZone.CONTENT, after.activeZone)
        assertEquals(light, after.contentPreferredTargets[CarePadDestination.SETTINGS])
        assertNull(after.observedFocus)
        assertNull(after.pendingFocus)
    }

    @Test
    fun l1AfterRailTouchCrossesOnceAndRequestsRememberedContentTarget() {
        val touched = reduceCarePadFocus(
            settingsState().copy(
                contentPreferredTargets = mapOf(CarePadDestination.SETTINGS to light),
            ),
            CarePadFocusEvent.TouchRail(CarePadDestination.SETTINGS),
        )
        val after = reduceCarePadFocus(touched, CarePadFocusEvent.ControllerL1())
        assertEquals(CarePadInputMethod.CONTROLLER, after.modality)
        assertEquals(CarePadFocusZone.CONTENT, after.activeZone)
        assertEquals(light, after.pendingFocus?.target)
    }

    @Test
    fun l1FromContentRequestsRememberedRailDestination() {
        val after = reduceCarePadFocus(
            settingsState().copy(railPreferredDestination = CarePadDestination.ADD_MODULES),
            CarePadFocusEvent.ControllerL1(),
        )
        assertEquals(CarePadFocusZone.RAIL, after.activeZone)
        assertEquals(
            CarePadFocusKey.Rail(CarePadDestination.ADD_MODULES),
            after.pendingFocus?.target,
        )
    }

    @Test
    fun invalidContentMemoryIsRemovedAndL1UsesFallback() {
        val removed = CarePadFocusKey.Module("dev.carepad.removed")
        val initial = CarePadFocusControllerState(
            activeZone = CarePadFocusZone.RAIL,
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.HOME,
            contentPreferredTargets = mapOf(CarePadDestination.HOME to removed),
            contentFallbackTarget = removed,
            observedFocus = CarePadFocusKey.Rail(CarePadDestination.HOME),
        )
        val changed = reduceCarePadFocus(
            initial,
            CarePadFocusEvent.ContentTargetsChanged(
                destination = CarePadDestination.HOME,
                validTargets = emptySet(),
                fallbackTarget = CarePadFocusKey.ContentFallback(CarePadDestination.HOME),
            ),
        )
        assertNull(changed.contentPreferredTargets[CarePadDestination.HOME])
        assertEquals(
            CarePadFocusKey.ContentFallback(CarePadDestination.HOME),
            changed.contentFallbackTarget,
        )

        val afterL1 = reduceCarePadFocus(changed, CarePadFocusEvent.ControllerL1())
        assertEquals(CarePadFocusZone.CONTENT, afterL1.activeZone)
        assertEquals(
            CarePadFocusKey.ContentFallback(CarePadDestination.HOME),
            afterL1.pendingFocus?.target,
        )
    }

    @Test
    fun selectedAndFocusedRemainIndependent() {
        val initial = CarePadFocusControllerState(
            activeZone = CarePadFocusZone.RAIL,
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.SETTINGS,
            observedFocus = CarePadFocusKey.Rail(CarePadDestination.HOME),
        )
        val observed = reduceCarePadFocus(
            initial,
            CarePadFocusEvent.FocusObserved(
                CarePadFocusKey.Rail(CarePadDestination.ADD_MODULES)
            ),
        )
        assertEquals(CarePadDestination.SETTINGS, observed.selectedDestination)
        assertEquals(
            CarePadFocusKey.Rail(CarePadDestination.ADD_MODULES),
            observed.observedFocus,
        )
    }

    @Test
    fun l1RepeatDoesNotCreateRequestOrToggleZone() {
        val initial = settingsState()
        val repeated = reduceCarePadFocus(
            initial,
            CarePadFocusEvent.ControllerL1(repeat = true),
        )
        assertNull(repeated.pendingFocus)
        assertEquals(CarePadFocusZone.CONTENT, repeated.activeZone)
        assertEquals(CarePadInputMethod.CONTROLLER, repeated.modality)
    }

    @Test
    fun focusRequestCompletionIsTokenScopedAndPhysicalObservationIsIndependent() {
        val requested = reduceCarePadFocus(settingsState(), CarePadFocusEvent.ControllerL1())
        val request = requested.pendingFocus!!

        val stale = reduceCarePadFocus(
            requested,
            CarePadFocusEvent.FocusRequestExecuted(request.token + 1),
        )
        assertEquals(request, stale.pendingFocus)

        val observed = reduceCarePadFocus(
            stale,
            CarePadFocusEvent.FocusObserved(request.target),
        )
        assertEquals(request.target, observed.observedFocus)
        assertNull(observed.pendingFocus)

        assertEquals(
            observed,
            reduceCarePadFocus(
                observed,
                CarePadFocusEvent.FocusRequestExecuted(request.token),
            ),
        )
    }

    @Test
    fun zoneFocusLossOnlyClearsFocusFromThatZone() {
        val rail = settingsState().copy(
            activeZone = CarePadFocusZone.RAIL,
            observedFocus = CarePadFocusKey.Rail(CarePadDestination.SETTINGS),
        )
        assertNull(
            reduceCarePadFocus(
                rail,
                CarePadFocusEvent.ZoneFocusLost(CarePadFocusZone.RAIL),
            ).observedFocus,
        )
        val content = rail.copy(observedFocus = light)
        assertEquals(
            light,
            reduceCarePadFocus(
                content,
                CarePadFocusEvent.ZoneFocusLost(CarePadFocusZone.RAIL),
            ).observedFocus,
        )
    }

    @Test
    fun actionValidationUsesObservedPhysicalFocusWithoutSecondMutableTarget() {
        val visible = listOf("dev.carepad.module.performance")
        val module = CarePadFocusKey.Module(visible.single())
        val uninstall = CarePadFocusKey.Uninstall(visible.single())
        val moduleState = CarePadFocusControllerState(
            activeZone = CarePadFocusZone.CONTENT,
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
            activeZone = CarePadFocusZone.CONTENT,
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.SETTINGS,
            railPreferredDestination = CarePadDestination.SETTINGS,
            contentPreferredTargets = mapOf(CarePadDestination.SETTINGS to system),
            contentFallbackTarget = system,
            observedFocus = system,
        )
}
