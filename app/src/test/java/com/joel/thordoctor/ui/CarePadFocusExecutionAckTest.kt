package com.joel.thordoctor.ui

import com.joel.thordoctor.AppThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarePadFocusExecutionAckTest {
    private val system = CarePadFocusKey.Theme(AppThemeMode.SYSTEM)

    @Test
    fun acceptedRequestAckAdvancesContinuationWithoutInventingObservation() {
        val touched = reduceCarePadFocus(
            CarePadFocusControllerState(
                activeZone = CarePadFocusZone.CONTENT,
                modality = CarePadInputMethod.CONTROLLER,
                selectedDestination = CarePadDestination.SETTINGS,
                railPreferredDestination = CarePadDestination.SETTINGS,
                contentPreferredTargets = mapOf(CarePadDestination.SETTINGS to system),
                contentFallbackTarget = system,
                observedFocus = system,
            ),
            CarePadFocusEvent.TouchContent(system),
        )
        val requested = reduceCarePadFocus(
            touched,
            CarePadFocusEvent.ControllerDpad(CarePadDirection.DOWN),
        )
        val requestToken = requested.pendingFocus!!.token

        assertNull(requested.observedFocus)
        assertEquals(
            CarePadFocusIntent.RequestTarget(system),
            requested.pendingFocus?.intent,
        )
        assertEquals(CarePadDirection.DOWN, requested.pendingFocus?.moveAfterConfirmation)

        val acknowledged = reduceCarePadFocus(
            requested,
            CarePadFocusEvent.FocusExecutionResult(requestToken, accepted = true),
        )

        assertNull(acknowledged.observedFocus)
        assertEquals(
            CarePadFocusIntent.MoveWithinZone(CarePadDirection.DOWN),
            acknowledged.pendingFocus?.intent,
        )
        assertTrue(acknowledged.pendingFocus!!.token > requestToken)

        val moveToken = acknowledged.pendingFocus!!.token
        val observedLater = reduceCarePadFocus(
            acknowledged,
            CarePadFocusEvent.FocusObserved(system),
        )
        assertEquals(system, observedLater.observedFocus)
        assertEquals(moveToken, observedLater.pendingFocus?.token)

        val staleAck = reduceCarePadFocus(
            observedLater,
            CarePadFocusEvent.FocusExecutionResult(requestToken, accepted = true),
        )
        assertEquals(moveToken, staleAck.pendingFocus?.token)
    }

    @Test
    fun acceptedRequestWithoutContinuationCompletesPendingButNotObservation() {
        val requested = reduceCarePadFocus(
            CarePadFocusControllerState(
                activeZone = CarePadFocusZone.RAIL,
                modality = CarePadInputMethod.TOUCH,
                selectedDestination = CarePadDestination.SETTINGS,
            ),
            CarePadFocusEvent.TouchRail(CarePadDestination.SETTINGS),
        )
        val token = requested.pendingFocus!!.token

        val acknowledged = reduceCarePadFocus(
            requested,
            CarePadFocusEvent.FocusExecutionResult(token, accepted = true),
        )

        assertNull(acknowledged.pendingFocus)
        assertNull(acknowledged.observedFocus)
        assertEquals(CarePadFocusZone.RAIL, acknowledged.activeZone)
        assertEquals(CarePadDestination.SETTINGS, acknowledged.railPreferredDestination)
    }
}