package com.joel.thordoctor.ui

import com.joel.thordoctor.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarePadFocusControllerTest {
    private val settingsSystem = CarePadFocusKey.Theme(AppThemeMode.SYSTEM)
    private val settingsLight = CarePadFocusKey.Theme(AppThemeMode.LIGHT)
    private val settingsDark = CarePadFocusKey.Theme(AppThemeMode.DARK)

    @Test
    fun onlyControllerL1CrossesZones() {
        val initial = settingsControllerState()

        val afterDpad = reduceCarePadFocus(
            initial,
            CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
        )
        val afterObserved = reduceCarePadFocus(
            initial,
            CarePadFocusEvent.FocusObserved(CarePadFocusKey.Rail(CarePadDestination.HOME)),
        )
        val afterActivity = reduceCarePadFocus(initial, CarePadFocusEvent.ControllerActivity)
        val afterL1 = reduceCarePadFocus(initial, CarePadFocusEvent.ControllerL1())

        assertEquals(CarePadFocusZone.CONTENT, afterDpad.activeZone)
        assertEquals(CarePadFocusZone.CONTENT, afterObserved.activeZone)
        assertEquals(CarePadFocusZone.CONTENT, afterActivity.activeZone)
        assertEquals(CarePadFocusZone.RAIL, afterL1.activeZone)
    }

    @Test
    fun dpadNeverChangesZoneAndSchedulesOneMoveWhenFocusIsObserved() {
        val initial = settingsControllerState()

        val after = reduceCarePadFocus(
            initial,
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
    fun focusObservedNeverChangesZoneOrSelectedDestination() {
        val initial = settingsControllerState().copy(
            activeZone = CarePadFocusZone.CONTENT,
            selectedDestination = CarePadDestination.SETTINGS,
        )

        val after = reduceCarePadFocus(
            initial,
            CarePadFocusEvent.FocusObserved(CarePadFocusKey.Rail(CarePadDestination.HOME)),
        )

        assertEquals(CarePadFocusZone.CONTENT, after.activeZone)
        assertEquals(CarePadDestination.SETTINGS, after.selectedDestination)
        assertEquals(CarePadDestination.HOME, after.railPreferredDestination)
    }

    @Test
    fun touchRailDeclaresTouchContextAndInvalidatesResidualFocus() {
        val initial = settingsControllerState()

        val after = reduceCarePadFocus(
            initial,
            CarePadFocusEvent.TouchRail(CarePadDestination.SETTINGS),
        )

        assertEquals(CarePadInputMethod.TOUCH, after.modality)
        assertEquals(CarePadFocusZone.RAIL, after.activeZone)
        assertEquals(CarePadDestination.SETTINGS, after.railPreferredDestination)
        assertNull(after.observedFocus)
        assertNull(after.pendingFocus)
        assertEquals(settingsSystem, after.contentPreferredTargets[CarePadDestination.SETTINGS])
    }

    @Test
    fun touchContentKeepsRailMemoryAndRecordsSemanticContentTarget() {
        val initial = settingsControllerState().copy(
            activeZone = CarePadFocusZone.RAIL,
            railPreferredDestination = CarePadDestination.SETTINGS,
            observedFocus = CarePadFocusKey.Rail(CarePadDestination.SETTINGS),
        )

        val after = reduceCarePadFocus(
            initial,
            CarePadFocusEvent.TouchContent(settingsLight),
        )

        assertEquals(CarePadInputMethod.TOUCH, after.modality)
        assertEquals(CarePadFocusZone.CONTENT, after.activeZone)
        assertEquals(CarePadDestination.SETTINGS, after.railPreferredDestination)
        assertEquals(settingsLight, after.contentPreferredTargets[CarePadDestination.SETTINGS])
        assertNull(after.observedFocus)
    }

    @Test
    fun firstDpadAfterRailTouchRestoresTouchedItemThenContinuesSameInput() {
        val touched = reduceCarePadFocus(
            settingsControllerState(),
            CarePadFocusEvent.TouchRail(CarePadDestination.SETTINGS),
        )

        val firstInput = reduceCarePadFocus(
            touched,
            CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
        )
        val request = firstInput.pendingFocus

        assertEquals(CarePadInputMethod.CONTROLLER, firstInput.modality)
        assertEquals(CarePadFocusZone.RAIL, firstInput.activeZone)
        assertEquals(
            CarePadFocusIntent.RequestTarget(
                CarePadFocusKey.Rail(CarePadDestination.SETTINGS)
            ),
            request?.intent,
        )
        assertEquals(CarePadDirection.DOWN, request?.moveAfterConfirmation)

        val confirmed = reduceCarePadFocus(
            firstInput,
            CarePadFocusEvent.FocusObserved(CarePadFocusKey.Rail(CarePadDestination.SETTINGS)),
        )

        assertEquals(
            CarePadFocusIntent.MoveWithinZone(CarePadDirection.DOWN),
            confirmed.pendingFocus?.intent,
        )
        assertTrue((confirmed.pendingFocus?.token ?: 0L) > (request?.token ?: 0L))
    }

    @Test
    fun firstDpadAfterContentTouchRestoresPreferredContentBeforeMoving() {
        val touched = reduceCarePadFocus(
            settingsControllerState().copy(
                contentPreferredTargets = mapOf(CarePadDestination.SETTINGS to settingsLight),
            ),
            CarePadFocusEvent.TouchContent(settingsLight),
        )

        val firstInput = reduceCarePadFocus(
            touched,
            CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
        )

        assertEquals(
            CarePadFocusIntent.RequestTarget(settingsLight),
            firstInput.pendingFocus?.intent,
        )
        assertEquals(CarePadDirection.DOWN, firstInput.pendingFocus?.moveAfterConfirmation)
    }

    @Test
    fun l1AfterRailTouchCrossesOnceAndRequestsContentTarget() {
        val touched = reduceCarePadFocus(
            settingsControllerState().copy(
                contentPreferredTargets = mapOf(CarePadDestination.SETTINGS to settingsLight),
            ),
            CarePadFocusEvent.TouchRail(CarePadDestination.SETTINGS),
        )

        val after = reduceCarePadFocus(touched, CarePadFocusEvent.ControllerL1())

        assertEquals(CarePadInputMethod.CONTROLLER, after.modality)
        assertEquals(CarePadFocusZone.CONTENT, after.activeZone)
        assertEquals(
            CarePadFocusIntent.RequestTarget(settingsLight),
            after.pendingFocus?.intent,
        )
        assertNull(after.pendingFocus?.moveAfterConfirmation)
    }

    @Test
    fun invalidContentTargetIsRemovedAndFallbackStaysInContent() {
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
        assertEquals(
            CarePadFocusIntent.RequestTarget(
                CarePadFocusKey.ContentFallback(CarePadDestination.HOME)
            ),
            afterDpad.pendingFocus?.intent,
        )
        assertEquals(CarePadFocusZone.CONTENT, afterDpad.activeZone)
    }

    @Test
    fun settingsFallbackIsSystemAndSelectedDoesNotImplyFocused() {
        var state = CarePadFocusControllerState(
            activeZone = CarePadFocusZone.RAIL,
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.HOME,
            observedFocus = CarePadFocusKey.Rail(CarePadDestination.HOME),
        )
        state = reduceCarePadFocus(
            state,
            CarePadFocusEvent.DestinationSelected(CarePadDestination.SETTINGS),
        )

        assertEquals(CarePadDestination.SETTINGS, state.selectedDestination)
        assertEquals(settingsSystem, state.contentFallbackTarget)
        assertEquals(CarePadFocusKey.Rail(CarePadDestination.HOME), state.observedFocus)
    }

    @Test
    fun repeatsDoNotCreatePendingFocusOrToggleZone() {
        val initial = settingsControllerState()

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
    fun pendingTokenExecutesOnceAndLateResultCannotCompleteNewIntent() {
        val touched = reduceCarePadFocus(
            settingsControllerState(),
            CarePadFocusEvent.TouchContent(settingsSystem),
        )
        val requested = reduceCarePadFocus(
            touched,
            CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
        )
        val requestToken = requested.pendingFocus!!.token

        val confirmed = reduceCarePadFocus(
            requested,
            CarePadFocusEvent.FocusObserved(settingsSystem),
        )
        val moveToken = confirmed.pendingFocus!!.token
        assertTrue(moveToken > requestToken)

        val afterLateRequestResult = reduceCarePadFocus(
            confirmed,
            CarePadFocusEvent.FocusExecutionResult(requestToken, accepted = true),
        )
        assertEquals(moveToken, afterLateRequestResult.pendingFocus?.token)

        val completed = reduceCarePadFocus(
            afterLateRequestResult,
            CarePadFocusEvent.FocusExecutionResult(moveToken, accepted = true),
        )
        assertNull(completed.pendingFocus)

        val duplicate = reduceCarePadFocus(
            completed,
            CarePadFocusEvent.FocusExecutionResult(moveToken, accepted = true),
        )
        assertEquals(completed, duplicate)
    }

    @Test
    fun pendingRequestFailureDoesNotRunContinuation() {
        val touched = reduceCarePadFocus(
            settingsControllerState(),
            CarePadFocusEvent.TouchContent(settingsSystem),
        )
        val requested = reduceCarePadFocus(
            touched,
            CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
        )
        val token = requested.pendingFocus!!.token

        val failed = reduceCarePadFocus(
            requested,
            CarePadFocusEvent.FocusExecutionResult(token, accepted = false),
        )

        assertNull(failed.pendingFocus)
        assertEquals(CarePadFocusZone.CONTENT, failed.activeZone)
    }

    @Test
    fun contentTargetHelpersKeepFallbackAndActionValidationInsideCurrentDestination() {
        val visible = listOf("dev.carepad.module.performance")
        val homeTargets = carePadContentTargets(
            destination = CarePadDestination.HOME,
            visiblePackages = visible,
            expandedPackage = visible.single(),
        )

        assertTrue(CarePadFocusKey.Module(visible.single()) in homeTargets)
        assertTrue(CarePadFocusKey.Uninstall(visible.single()) in homeTargets)
        assertEquals(
            CarePadFocusKey.Module(visible.single()),
            carePadContentFallback(CarePadDestination.HOME, visible),
        )
        assertEquals(
            settingsSystem,
            carePadContentFallback(CarePadDestination.SETTINGS, emptyList()),
        )

        val state = CarePadFocusControllerState(
            activeZone = CarePadFocusZone.CONTENT,
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.HOME,
            observedFocus = CarePadFocusKey.Module(visible.single()),
        )
        assertEquals(
            CarePadFocusKey.Module(visible.single()),
            carePadControllerActionTarget(state, visible),
        )
        assertTrue(carePadDetailsControllerActionAllowed(state, visible))
        assertFalse(
            carePadDetailsControllerActionAllowed(
                state.copy(activeZone = CarePadFocusZone.RAIL),
                visible,
            )
        )
    }

    @Test
    fun longMultimodalSequenceKeepsInvariantsAndNeedsNoWakeUpEvent() {
        var state = settingsControllerState().copy(
            railPreferredDestination = CarePadDestination.SETTINGS,
            contentPreferredTargets = mapOf(CarePadDestination.SETTINGS to settingsSystem),
        )

        state = completeMove(
            reduceCarePadFocus(
                state,
                CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
            ),
            settingsLight,
        )
        assertEquals(settingsLight, state.observedFocus)

        state = reduceCarePadFocus(state, CarePadFocusEvent.ControllerL1())
        state = reduceCarePadFocus(
            state,
            CarePadFocusEvent.FocusObserved(CarePadFocusKey.Rail(CarePadDestination.SETTINGS)),
        )
        assertEquals(CarePadFocusZone.RAIL, state.activeZone)

        state = completeMove(
            reduceCarePadFocus(
                state,
                CarePadFocusEvent.ControllerDpad(CarePadDirection.UP),
            ),
            CarePadFocusKey.Rail(CarePadDestination.ADD_MODULES),
        )
        assertEquals(CarePadDestination.ADD_MODULES, state.railPreferredDestination)

        state = reduceCarePadFocus(state, CarePadFocusEvent.ControllerL1())
        state = reduceCarePadFocus(
            state,
            CarePadFocusEvent.FocusObserved(settingsLight),
        )
        assertEquals(CarePadFocusZone.CONTENT, state.activeZone)
        assertEquals(settingsLight, state.observedFocus)

        state = reduceCarePadFocus(state, CarePadFocusEvent.TouchContent(settingsLight))
        state = reduceCarePadFocus(
            state,
            CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
        )
        val restoreToken = state.pendingFocus!!.token
        state = reduceCarePadFocus(state, CarePadFocusEvent.FocusObserved(settingsLight))
        assertEquals(
            CarePadFocusIntent.MoveWithinZone(CarePadDirection.DOWN),
            state.pendingFocus?.intent,
        )
        state = reduceCarePadFocus(state, CarePadFocusEvent.FocusObserved(settingsDark))
        state = reduceCarePadFocus(
            state,
            CarePadFocusEvent.FocusExecutionResult(
                token = state.pendingFocus!!.token,
                accepted = true,
            ),
        )
        state = reduceCarePadFocus(
            state,
            CarePadFocusEvent.FocusExecutionResult(restoreToken, accepted = true),
        )
        assertEquals(settingsDark, state.observedFocus)
        assertEquals(CarePadFocusZone.CONTENT, state.activeZone)

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
            CarePadFocusEvent.FocusObserved(CarePadFocusKey.Rail(CarePadDestination.SETTINGS)),
        )
        val edgeMove = state.pendingFocus!!.token
        state = reduceCarePadFocus(
            state,
            CarePadFocusEvent.FocusExecutionResult(edgeMove, accepted = false),
        )

        assertEquals(CarePadInputMethod.CONTROLLER, state.modality)
        assertEquals(CarePadFocusZone.RAIL, state.activeZone)
        assertEquals(CarePadDestination.SETTINGS, state.selectedDestination)
        assertEquals(CarePadDestination.SETTINGS, state.railPreferredDestination)
        assertNull(state.pendingFocus)
    }

    private fun settingsControllerState(): CarePadFocusControllerState =
        CarePadFocusControllerState(
            activeZone = CarePadFocusZone.CONTENT,
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.SETTINGS,
            railPreferredDestination = CarePadDestination.SETTINGS,
            contentPreferredTargets = mapOf(CarePadDestination.SETTINGS to settingsSystem),
            contentFallbackTarget = settingsSystem,
            observedFocus = settingsSystem,
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
