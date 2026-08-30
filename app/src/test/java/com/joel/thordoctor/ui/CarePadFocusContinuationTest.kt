package com.joel.thordoctor.ui

import com.joel.thordoctor.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarePadFocusContinuationTest {
    private val system = CarePadFocusKey.Theme(AppThemeMode.SYSTEM)
    private val light = CarePadFocusKey.Theme(AppThemeMode.LIGHT)

    @Test
    fun acceptedRequestDoesNotReleaseMoveBeforeExpectedFocusIsObserved() {
        val requested = firstDpadAfterTouch()
        val request = requested.pendingFocus!!

        val accepted = reduceCarePadFocus(
            requested,
            CarePadFocusEvent.FocusExecutionResult(request.token, accepted = true),
        )

        assertEquals(CarePadInputMethod.CONTROLLER, accepted.modality)
        assertEquals(CarePadFocusZone.CONTENT, accepted.activeZone)
        assertEquals(request, accepted.pendingFocus)
        assertEquals(
            CarePadFocusIntent.RequestTarget(system),
            accepted.pendingFocus?.intent,
        )
        assertEquals(CarePadDirection.DOWN, accepted.pendingFocus?.moveAfterConfirmation)
    }

    @Test
    fun onlyExpectedFocusObservationReleasesPendingDirection() {
        val requested = firstDpadAfterTouch()
        val request = requested.pendingFocus!!
        val accepted = reduceCarePadFocus(
            requested,
            CarePadFocusEvent.FocusExecutionResult(request.token, accepted = true),
        )

        val wrongObserved = reduceCarePadFocus(
            accepted,
            CarePadFocusEvent.FocusObserved(light),
        )
        assertEquals(request.token, wrongObserved.pendingFocus?.token)
        assertEquals(CarePadFocusIntent.RequestTarget(system), wrongObserved.pendingFocus?.intent)

        val confirmed = reduceCarePadFocus(
            wrongObserved,
            CarePadFocusEvent.FocusObserved(system),
        )
        assertEquals(
            CarePadFocusIntent.MoveWithinZone(CarePadDirection.DOWN),
            confirmed.pendingFocus?.intent,
        )
        assertTrue(confirmed.pendingFocus!!.token > request.token)
        assertNull(confirmed.pendingFocus?.moveAfterConfirmation)
    }

    @Test
    fun expectedFocusObservationReleasesDirectionExactlyOnce() {
        val requested = firstDpadAfterTouch()
        val request = requested.pendingFocus!!
        val accepted = reduceCarePadFocus(
            requested,
            CarePadFocusEvent.FocusExecutionResult(request.token, accepted = true),
        )
        val confirmed = reduceCarePadFocus(
            accepted,
            CarePadFocusEvent.FocusObserved(system),
        )
        val move = confirmed.pendingFocus!!

        val duplicateObservation = reduceCarePadFocus(
            confirmed,
            CarePadFocusEvent.FocusObserved(system),
        )
        assertEquals(move, duplicateObservation.pendingFocus)

        val lateRequestResult = reduceCarePadFocus(
            duplicateObservation,
            CarePadFocusEvent.FocusExecutionResult(request.token, accepted = true),
        )
        assertEquals(move, lateRequestResult.pendingFocus)

        val completed = reduceCarePadFocus(
            lateRequestResult,
            CarePadFocusEvent.FocusExecutionResult(move.token, accepted = true),
        )
        assertNull(completed.pendingFocus)
    }

    private fun firstDpadAfterTouch(): CarePadFocusControllerState {
        val initial = CarePadFocusControllerState(
            activeZone = CarePadFocusZone.CONTENT,
            modality = CarePadInputMethod.CONTROLLER,
            selectedDestination = CarePadDestination.SETTINGS,
            railPreferredDestination = CarePadDestination.SETTINGS,
            contentPreferredTargets = mapOf(CarePadDestination.SETTINGS to system),
            contentFallbackTarget = system,
        )
        val touched = reduceCarePadFocus(
            initial,
            CarePadFocusEvent.TouchContent(system),
        )
        return reduceCarePadFocus(
            touched,
            CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
        )
    }
}
