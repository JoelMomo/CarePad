package com.joel.thordoctor.ui

import com.joel.thordoctor.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarePadFocusControllerTest {
    private val system = CarePadFocusKey.Theme(AppThemeMode.SYSTEM)
    private val light = CarePadFocusKey.Theme(AppThemeMode.LIGHT)
    private val dark = CarePadFocusKey.Theme(AppThemeMode.DARK)

    @Test
    fun l1IsTheOnlyControllerOrFocusEventThatChangesZone() {
        val initial = settingsState()
        assertEquals(
            CarePadFocusZone.CONTENT,
            reduceCarePadFocus(
                initial,
                CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
            ).activeZone,
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
            CarePadFocusZone.CONTENT,
            reduceCarePadFocus(initial, CarePadFocusEvent.ControllerActivity).activeZone,
        )
        assertEquals(
            CarePadFocusZone.RAIL,
            reduceCarePadFocus(initial, CarePadFocusEvent.ControllerL1()).activeZone,
        )
    }

    @Test
    fun dpadWithObservedFocusSchedulesExactlyOneMoveWithoutChangingZone() {
        val after = reduceCarePadFocus(
            settingsState(),
            CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
        )
        assertEquals(CarePadFocusZone.CONTENT, after.activeZone)
        assertEquals(CarePadInputMethod.CONTROLLER, after.modality)
        assertEquals(
            CarePadFocusIntent.MoveWithinZone(CarePadDirection.DOWN),
            after.pendingFocus?.intent,
        )
        assertNull(after.pendingFocus?.moveAfterConfirmation)
    }

    @Test
    fun focusObservedDoesNotChangeZoneOrSelectedDestination() {
        val after = reduceCarePadFocus(
            settingsState(),
            CarePadFocusEvent.FocusObserved(
                CarePadFocusKey.Rail(CarePadDestination.HOME)
            ),
        )
        assertEquals(CarePadFocusZone.CONTENT, after.activeZone)
        assertEquals(CarePadDestination.SETTINGS, after.selectedDestination)
        assertEquals(
            CarePadFocusKey.Rail(CarePadDestination.HOME),
            after.observedFocus,
        )
        assertEquals(CarePadDestination.SETTINGS, after.railPreferredDestination)
    }

    @Test
    fun touchRailDeclaresTouchContextAndRequestsTouchedRailTarget() {
        val after = reduceCarePadFocus(
            settingsState(),
            CarePadFocusEvent.TouchRail(CarePadDestination.SETTINGS),
        )
        assertEquals(CarePadInputMethod.TOUCH, after.modality)
        assertEquals(CarePadFocusZone.RAIL, after.activeZone)
        assertEquals(CarePadDestination.SETTINGS, after.railPreferredDestination)
        assertNull(after.observedFocus)
        assertEquals(
            CarePadFocusIntent.RequestTarget(
                CarePadFocusKey.Rail(CarePadDestination.SETTINGS)
            ),
            after.pendingFocus?.intent,
        )
        assertEquals(system, after.contentPreferredTargets[CarePadDestination.SETTINGS])
    }

    @Test
    fun touchContentRecordsSemanticTargetAndRequestsItThroughExecutor() {
        val initial = settingsState().copy(
            activeZone = CarePadFocusZone.RAIL,
            observedFocus = CarePadFocusKey.Rail(CarePadDestination.SETTINGS),
        )
        val after = reduceCarePadFocus(initial, CarePadFocusEvent.TouchContent(light))
        assertEquals(CarePadInputMethod.TOUCH, after.modality)
        assertEquals(CarePadFocusZone.CONTENT, after.activeZone)
        assertEquals(light, after.contentPreferredTargets[CarePadDestination.SETTINGS])
        assertNull(after.observedFocus)
        assertEquals(
            CarePadFocusIntent.RequestTarget(light),
            after.pendingFocus?.intent,
        )
    }

    @Test
    fun firstDpadAfterRailTouchReplacesTouchIntentWithRestoreAndContinuation() {
        val touched = reduceCarePadFocus(
            settingsState(),
            CarePadFocusEvent.TouchRail(CarePadDestination.SETTINGS),
        )
        val firstInput = reduceCarePadFocus(
            touched,
            CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
        )
        val request = firstInput.pendingFocus!!
        assertEquals(CarePadInputMethod.CONTROLLER, firstInput.modality)
        assertEquals(CarePadFocusZone.RAIL, firstInput.activeZone)
        assertEquals(
            CarePadFocusIntent.RequestTarget(
                CarePadFocusKey.Rail(CarePadDestination.SETTINGS)
            ),
            request.intent,
        )
        assertEquals(CarePadDirection.DOWN, request.moveAfterConfirmation)

        val confirmed = reduceCarePadFocus(
            firstInput,
            CarePadFocusEvent.FocusObserved(
                CarePadFocusKey.Rail(CarePadDestination.SETTINGS)
            ),
        )
        assertEquals(
            CarePadFocusIntent.MoveWithinZone(CarePadDirection.DOWN),
            confirmed.pendingFocus?.intent,
        )
        assertTrue(confirmed.pendingFocus!!.token > request.token)
    }

    @Test
    fun firstDpadAfterContentTouchRestoresPreferredTargetBeforeMoving() {
        val touched = reduceCarePadFocus(
            settingsState().copy(
                contentPreferredTargets = mapOf(CarePadDestination.SETTINGS to light),
            ),
            CarePadFocusEvent.TouchContent(light),
        )
        val firstInput = reduceCarePadFocus(
            touched,
            CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
        )
        assertEquals(
            CarePadFocusIntent.RequestTarget(light),
            firstInput.pendingFocus?.intent,
        )
        assertEquals(CarePadDirection.DOWN, firstInput.pendingFocus?.moveAfterConfirmation)
    }

    @Test
    fun l1AfterRailTouchCrossesOnceAndSupersedesTouchFocusIntent() {
        val touched = reduceCarePadFocus(
            settingsState().copy(
                contentPreferredTargets = mapOf(CarePadDestination.SETTINGS to light),
            ),
            CarePadFocusEvent.TouchRail(CarePadDestination.SETTINGS),
        )
        val after = reduceCarePadFocus(touched, CarePadFocusEvent.ControllerL1())
        assertEquals(CarePadInputMethod.CONTROLLER, after.modality)
        assertEquals(CarePadFocusZone.CONTENT, after.activeZone)
        assertEquals(CarePadFocusIntent.RequestTarget(light), after.pendingFocus?.intent)
        assertNull(after.pendingFocus?.moveAfterConfirmation)
    }

    @Test
    fun invalidTargetIsRemovedAndFallbackNeverLeavesContent() {
        val removed = CarePadFocusKey.Module("dev.carepad.removed")
        val initial = CarePadFocusControllerState(
            activeZone = CarePadFocusZone.CONTENT,
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.HOME,
            contentPreferredTargets = mapOf(CarePadDestination.HOME to removed),
            contentFallbackTarget = removed,
            observedFocus = removed,
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
        assertNull(changed.observedFocus)
        assertEquals(
            CarePadFocusKey.ContentFallback(CarePadDestination.HOME),
            changed.contentFallbackTarget,
        )
        val afterDpad = reduceCarePadFocus(
            changed,
            CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
        )
        assertEquals(CarePadFocusZone.CONTENT, afterDpad.activeZone)
        assertEquals(
            CarePadFocusIntent.RequestTarget(
                CarePadFocusKey.ContentFallback(CarePadDestination.HOME)
            ),
            afterDpad.pendingFocus?.intent,
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
    fun repeatsDoNotCreateNewIntentOrToggleZone() {
        val initial = settingsState()
        val repeatedDpad = reduceCarePadFocus(
            initial,
            CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN, repeat = true),
        )
        val repeatedL1 = reduceCarePadFocus(
            initial,
            CarePadFocusEvent.ControllerL1(repeat = true),
        )
        assertNull(repeatedDpad.pendingFocus)
        assertNull(repeatedL1.pendingFocus)
        assertEquals(CarePadFocusZone.CONTENT, repeatedDpad.activeZone)
        assertEquals(CarePadFocusZone.CONTENT, repeatedL1.activeZone)
    }

    @Test
    fun pendingTokenExecutesOnceAndLateResultCannotCompleteContinuation() {
        val touched = reduceCarePadFocus(
            settingsState().copy(observedFocus = null),
            CarePadFocusEvent.TouchContent(system),
        )
        val requested = reduceCarePadFocus(
            touched,
            CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
        )
        val requestToken = requested.pendingFocus!!.token
        val confirmed = reduceCarePadFocus(
            requested,
            CarePadFocusEvent.FocusObserved(system),
        )
        val moveToken = confirmed.pendingFocus!!.token
        assertTrue(moveToken > requestToken)

        val late = reduceCarePadFocus(
            confirmed,
            CarePadFocusEvent.FocusExecutionResult(requestToken, accepted = true),
        )
        assertEquals(moveToken, late.pendingFocus?.token)

        val completed = reduceCarePadFocus(
            late,
            CarePadFocusEvent.FocusExecutionResult(moveToken, accepted = true),
        )
        assertNull(completed.pendingFocus)
        assertEquals(
            completed,
            reduceCarePadFocus(
                completed,
                CarePadFocusEvent.FocusExecutionResult(moveToken, accepted = true),
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

    @Test
    fun longMultimodalSequencePreservesZoneSelectionAndMemory() {
        var state = settingsState().copy(
            railPreferredDestination = CarePadDestination.SETTINGS,
            contentPreferredTargets = mapOf(CarePadDestination.SETTINGS to system),
        )
        state = completeMove(
            reduceCarePadFocus(
                state,
                CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
            ),
            light,
        )
        state = reduceCarePadFocus(state, CarePadFocusEvent.ControllerL1())
        state = reduceCarePadFocus(
            state,
            CarePadFocusEvent.FocusObserved(
                CarePadFocusKey.Rail(CarePadDestination.SETTINGS)
            ),
        )
        state = completeMove(
            reduceCarePadFocus(
                state,
                CarePadFocusEvent.ControllerDpad(CarePadDirection.UP),
            ),
            CarePadFocusKey.Rail(CarePadDestination.ADD_MODULES),
        )
        state = reduceCarePadFocus(state, CarePadFocusEvent.ControllerL1())
        state = reduceCarePadFocus(state, CarePadFocusEvent.FocusObserved(light))
        state = reduceCarePadFocus(state, CarePadFocusEvent.TouchContent(light))
        state = reduceCarePadFocus(
            state,
            CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
        )
        state = reduceCarePadFocus(state, CarePadFocusEvent.FocusObserved(light))
        state = completeMove(state, dark)
        state = reduceCarePadFocus(
            state,
            CarePadFocusEvent.TouchRail(CarePadDestination.SETTINGS),
        )
        state = reduceCarePadFocus(
            state,
            CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
        )
        state = reduceCarePadFocus(
            state,
            CarePadFocusEvent.FocusObserved(
                CarePadFocusKey.Rail(CarePadDestination.SETTINGS)
            ),
        )
        val edgeToken = state.pendingFocus!!.token
        state = reduceCarePadFocus(
            state,
            CarePadFocusEvent.FocusExecutionResult(edgeToken, accepted = false),
        )
        assertEquals(CarePadInputMethod.CONTROLLER, state.modality)
        assertEquals(CarePadFocusZone.RAIL, state.activeZone)
        assertEquals(CarePadDestination.SETTINGS, state.selectedDestination)
        assertEquals(CarePadDestination.SETTINGS, state.railPreferredDestination)
        assertNull(state.pendingFocus)
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

    private fun completeMove(
        stateWithMove: CarePadFocusControllerState,
        observed: CarePadFocusKey,
    ): CarePadFocusControllerState {
        val token = stateWithMove.pendingFocus!!.token
        val observedState = reduceCarePadFocus(
            stateWithMove,
            CarePadFocusEvent.FocusObserved(observed),
        )
        return reduceCarePadFocus(
            observedState,
            CarePadFocusEvent.FocusExecutionResult(token, accepted = true),
        )
    }
}
