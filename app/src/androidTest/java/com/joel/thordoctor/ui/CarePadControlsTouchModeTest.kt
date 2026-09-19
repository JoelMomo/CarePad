package com.joel.thordoctor.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.joel.thordoctor.MainActivity
import com.joel.thordoctor.R
import dev.carepad.module.controls.internalui.ControlsInternalController
import dev.carepad.module.controls.runtime.Axes
import dev.carepad.module.controls.runtime.Button
import dev.carepad.module.controls.runtime.DeviceInfo
import dev.carepad.module.controls.runtime.RangeInfo
import dev.carepad.module.controls.runtime.Sources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import java.io.File
import dev.carepad.module.controls.internalui.R as ControlsR

/** Only the device catalogue is controlled; the Activity, shell, Controls UI and targets are real. */
class CarePadControlsTouchModeTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()
    @get:Rule val testName = TestName()
    private var imageIndex = 0
    private val keyDownTimes = mutableMapOf<Int, Long>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var composeView: View
    private lateinit var controlsController: ControlsInternalController
    private var controllerAvailable = true
    private lateinit var composeInputModeManager: InputModeManager
    private var focusColor = Color.Unspecified
    private var focusOverlayColor = Color.Unspecified


    @Test
    fun firstHatKeyGestureAfterAndroidTouchFocusesVisibleUsableControlsAction() {
        installRealControls()
        tap(action(composeRule.activity.getString(R.string.carepad_module_controls)))
        assertAndroidTouch()

        hat(1f)
        key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN)
        key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP)
        hat(0f)
        composeRule.waitForIdle()

        val guided = action(composeRule.activity.getString(ControlsR.string.guided_test))
        guided.assertIsEnabled().assertIsDisplayed().assertIsFocused()
        assertVisibleBorder(guided)
        navigation(composeRule.activity.getString(R.string.carepad_nav_modules)).assertIsNotFocused()

        key(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_DOWN)
        key(KeyEvent.KEYCODE_BUTTON_A, KeyEvent.ACTION_UP, flags = KeyEvent.FLAG_CANCELED)
        assertFocusedAction(ControlsR.string.guided_test)
        press(KeyEvent.KEYCODE_BUTTON_A)

        waitForText(composeRule.activity.getString(ControlsR.string.prepare_test))
        assertCountdownVisible()
        assertNoIntermediateActions()

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        composeRule.onNodeWithText(composeRule.activity.getString(ControlsR.string.prepare_test)).assertIsDisplayed()
        navigation(composeRule.activity.getString(R.string.carepad_nav_modules)).assertIsNotFocused()
    }

    @Test
    fun firstKeyAfterAndroidTouchHasFocusAndSecondPressNavigatesImmediately() {
        installRealControls()
        tap(action(composeRule.activity.getString(R.string.carepad_module_controls)))
        assertAndroidTouch()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocusedAction(ControlsR.string.guided_test)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocusedAction(ControlsR.string.detected_inputs)
    }

    @Test
    fun controllerRemovalBeforePendingFocusEffectDoesNotUseAnUnmountedRequester() {
        installRealControls()
        tap(action(composeRule.activity.getString(R.string.carepad_module_controls)))
        assertAndroidTouch()
        composeRule.runOnUiThread {
            val now = SystemClock.uptimeMillis()
            composeRule.activity.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_DOWN, 0, 0, DEVICE_ID, 0, 0, InputDevice.SOURCE_DPAD))
            // Invalidate selection before the next composition/effect can grant its request.
            controllerAvailable = false
            controlsController.onInputDeviceRemoved(DEVICE_ID)
        }
        composeRule.waitForIdle()
        key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP)
        action(composeRule.activity.getString(ControlsR.string.guided_test)).assertIsNotEnabled()
    }

    @Test
    fun hatKeyReleaseOrdersDoNotLeaveDrainForTheNextPress() {
        installRealControls()
        tap(action(composeRule.activity.getString(R.string.carepad_module_controls)))
        val orders = listOf(
            listOf("hat", "down", "up", "neutral"),
            listOf("down", "hat", "up", "neutral"),
            listOf("hat", "down", "neutral", "up"),
        )
        for (order in orders) {
            // Touch passive content near the actions, including after the previous scroll.
            tap(composeRule.onNodeWithText(composeRule.activity.getString(ControlsR.string.guided_test_description)))
            assertAndroidTouch()
            for (event in order) when (event) {
                "hat" -> hat(1f)
                "neutral" -> hat(0f)
                "down" -> key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN)
                "up" -> key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP)
            }
            composeRule.waitForIdle()
            assertFocusedAction(ControlsR.string.guided_test)
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            assertFocusedAction(ControlsR.string.detected_inputs)
        }
    }


    @Test
    fun genericControllerDoesNotInventFaceButtonPositions() {
        installRealControls(deviceName = "Generic USB Controller")
        tap(action(composeRule.activity.getString(R.string.carepad_module_controls)))
        tap(action(composeRule.activity.getString(ControlsR.string.guided_test)))

        waitForDigitalTarget(ControlsR.string.dpad_up, 11_000)
        composeRule.onNodeWithText(
            composeRule.activity.getString(ControlsR.string.generic_face_mapping_inconclusive),
        ).assertIsDisplayed()
        assertTextAbsent(
            composeRule.activity.getString(
                ControlsR.string.digital_target_instruction,
                composeRule.activity.getString(ControlsR.string.button_a),
            ),
        )
    }

    @Test
    fun nintendoFamilyMapsBottomPhysicalButtonToAndroidButtonB() {
        installRealControls(deviceName = "Nintendo Switch Pro Controller")
        tap(action(composeRule.activity.getString(R.string.carepad_module_controls)))
        tap(action(composeRule.activity.getString(ControlsR.string.guided_test)))

        waitForDigitalTarget(ControlsR.string.button_a, 11_000)
        waitForCaptureArm()
        press(KeyEvent.KEYCODE_BUTTON_B)
        waitForDigitalTarget(ControlsR.string.button_b)
    }

    @Test
    fun guidedStartIsSingleActionAndBackCancelsDirectly() {
        installRealControls()
        tap(action(composeRule.activity.getString(R.string.carepad_module_controls)))
        tap(action(composeRule.activity.getString(ControlsR.string.guided_test)))

        waitForText(composeRule.activity.getString(ControlsR.string.prepare_test))
        assertCountdownVisible()
        assertNoIntermediateActions()

        press(KeyEvent.KEYCODE_BACK)
        action(composeRule.activity.getString(ControlsR.string.guided_test)).assertIsDisplayed().assertIsEnabled()
        assertTextAbsent(composeRule.activity.getString(ControlsR.string.prepare_test))
        assertTextAbsent(composeRule.activity.getString(ControlsR.string.leave_test_title))
    }

    @Test
    fun railReturnAndDetectedInputsKeepARealActionAnchor() {
        installRealControls()
        tap(action(composeRule.activity.getString(R.string.carepad_module_controls)))
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocusedAction(ControlsR.string.guided_test)
        press(KeyEvent.KEYCODE_BUTTON_L1)
        navigation(composeRule.activity.getString(R.string.carepad_nav_modules)).assertIsFocused()
        press(KeyEvent.KEYCODE_BUTTON_L1)
        assertFocusedAction(ControlsR.string.guided_test)

        tap(action(composeRule.activity.getString(ControlsR.string.detected_inputs)))
        assertAndroidTouch()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocusedAction(ControlsR.string.back)
        press(KeyEvent.KEYCODE_BUTTON_A)
        assertFocusedAction(ControlsR.string.guided_test)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocusedAction(ControlsR.string.detected_inputs)
    }

    @Test
    fun landscapeTouchRecoveryKeepsNaturalSpatialRailCrossing() {
        composeRule.runOnUiThread {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitUntil(10_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        installRealControls()
        tap(action(composeRule.activity.getString(R.string.carepad_module_controls)))
        assertAndroidTouch()
        hat(1f)
        key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN)
        key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP)
        hat(0f)
        composeRule.waitForIdle()
        assertFocusedAction(ControlsR.string.guided_test)
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        navigation(composeRule.activity.getString(R.string.carepad_nav_modules)).assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertFocusedAction(ControlsR.string.guided_test)
    }


    @Test
    fun automaticDigitalCaptureAutoAdvancesWithoutGivingFocusToRail() {
        installRealControls()
        tap(action(composeRule.activity.getString(R.string.carepad_module_controls)))
        tap(action(composeRule.activity.getString(ControlsR.string.guided_test)))
        waitForDigitalTarget(ControlsR.string.button_a, 11_000)
        assertNoIntermediateActions()

        assertTrue("CI must enable the existing focus trace", Log.isLoggable("CarePadT1Focus", Log.DEBUG))
        val marker = "pg8-digital-${SystemClock.uptimeMillis()}"
        Log.d("CarePadT1Focus", "$marker-start")

        waitForCaptureArm()
        press(KeyEvent.KEYCODE_BUTTON_B)
        assertDigitalTarget(ControlsR.string.button_a)
        navigation(composeRule.activity.getString(R.string.carepad_nav_modules)).assertIsNotFocused()

        val faceTargets = listOf(
            KeyEvent.KEYCODE_BUTTON_A to ControlsR.string.button_b,
            KeyEvent.KEYCODE_BUTTON_B to ControlsR.string.button_x,
            KeyEvent.KEYCODE_BUTTON_X to ControlsR.string.button_y,
            KeyEvent.KEYCODE_BUTTON_Y to ControlsR.string.dpad_up,
        )
        for ((code, nextTarget) in faceTargets) {
            waitForCaptureArm()
            press(code)
            waitForDigitalTarget(nextTarget)
            assertNoIntermediateActions()
        }

        waitForCaptureArm()
        pressGuidedDigital(KeyEvent.KEYCODE_DPAD_UP)
        waitForDigitalTarget(ControlsR.string.dpad_right)
        navigation(composeRule.activity.getString(R.string.carepad_nav_modules)).assertIsNotFocused()
        Log.d("CarePadT1Focus", "$marker-end")

        val logs = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "logcat -d -v brief -s CarePadT1Focus:D '*:S'",
        )).bufferedReader().use { it.readText() }
        val captureTrace = logs.substringAfter("$marker-start").substringBefore("$marker-end")
        assertFalse("Rail must never receive focus during continuous capture:\n$captureTrace",
            captureTrace.lineSequence().any { "stage=rail-focus" in it && "isFocused=true" in it })
        assertEquals("Captured inputs must not activate UI actions", 0,
            Regex("stage=action-activate").findAll(captureTrace).count())
    }



    @Test
    fun stickWindowsRunForFullCountdownAndSummaryOpensAutomatically() {
        composeRule.runOnUiThread {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        composeRule.waitUntil(10_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        installRealControls()
        tap(action(composeRule.activity.getString(R.string.carepad_module_controls)))
        tap(action(composeRule.activity.getString(ControlsR.string.guided_test)))

        val digitalTargets = listOf(
            KeyEvent.KEYCODE_BUTTON_A to ControlsR.string.button_a,
            KeyEvent.KEYCODE_BUTTON_B to ControlsR.string.button_b,
            KeyEvent.KEYCODE_BUTTON_X to ControlsR.string.button_x,
            KeyEvent.KEYCODE_BUTTON_Y to ControlsR.string.button_y,
            KeyEvent.KEYCODE_DPAD_UP to ControlsR.string.dpad_up,
            KeyEvent.KEYCODE_DPAD_RIGHT to ControlsR.string.dpad_right,
            KeyEvent.KEYCODE_DPAD_DOWN to ControlsR.string.dpad_down,
            KeyEvent.KEYCODE_DPAD_LEFT to ControlsR.string.dpad_left,
        )
        for ((index, target) in digitalTargets.withIndex()) {
            waitForDigitalTarget(target.second, if (index == 0) 11_000 else 2_500)
            waitForCaptureArm()
            pressGuidedDigital(target.first)
        }

        waitForText(composeRule.activity.getString(ControlsR.string.left_rest_instruction), 2_500)
        assertCountdownVisible()
        assertNoIntermediateActions()
        waitForText(composeRule.activity.getString(ControlsR.string.left_move_instruction), 11_000)
        assertCountdownVisible()
        val leftMoveStarted = SystemClock.uptimeMillis()
        waitForCaptureArm()
        stick(true, 1f, 0f)
        val leftObservedAt = SystemClock.uptimeMillis()
        composeRule.waitUntil(2_000) { SystemClock.uptimeMillis() - leftObservedAt >= 500 }
        composeRule.onNodeWithText(composeRule.activity.getString(ControlsR.string.left_move_instruction)).assertIsDisplayed()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        navigation(composeRule.activity.getString(R.string.carepad_nav_modules)).assertIsNotFocused()
        stick(true, 0f, 0f)
        waitForText(composeRule.activity.getString(ControlsR.string.right_rest_instruction), 11_000)
        assertTrue("Left movement window must not complete early", SystemClock.uptimeMillis() - leftMoveStarted >= 7_500)

        assertCountdownVisible()
        waitForText(composeRule.activity.getString(ControlsR.string.right_move_instruction), 11_000)
        assertCountdownVisible()
        val rightMoveStarted = SystemClock.uptimeMillis()
        waitForCaptureArm()
        stick(false, 1f, 0f)
        val rightObservedAt = SystemClock.uptimeMillis()
        composeRule.waitUntil(2_000) { SystemClock.uptimeMillis() - rightObservedAt >= 500 }
        composeRule.onNodeWithText(composeRule.activity.getString(ControlsR.string.right_move_instruction)).assertIsDisplayed()
        stick(false, 0f, 0f)
        waitForText(composeRule.activity.getString(ControlsR.string.test_finished), 11_000)
        assertTrue("Right movement window must not complete early", SystemClock.uptimeMillis() - rightMoveStarted >= 7_500)
        assertNoIntermediateActions()
    }

    private fun waitForText(text: String, timeoutMillis: Long = 2_500L) {
        composeRule.waitUntil(timeoutMillis) {
            runCatching { composeRule.onNodeWithText(text).fetchSemanticsNode() }.isSuccess
        }
    }

    private fun waitForDigitalTarget(buttonNameRes: Int, timeoutMillis: Long = 2_500L) {
        val buttonName = composeRule.activity.getString(buttonNameRes)
        waitForText(composeRule.activity.getString(ControlsR.string.digital_target_instruction, buttonName), timeoutMillis)
    }

    private fun assertDigitalTarget(buttonNameRes: Int) {
        val buttonName = composeRule.activity.getString(buttonNameRes)
        composeRule.onNodeWithText(
            composeRule.activity.getString(ControlsR.string.digital_target_instruction, buttonName),
        ).assertIsDisplayed()
    }

    private fun waitForCaptureArm() {
        val startedAt = SystemClock.uptimeMillis()
        composeRule.waitUntil(2_000) { SystemClock.uptimeMillis() - startedAt >= 300 }
    }

    private fun assertCountdownVisible() {
        composeRule.waitUntil(2_000) {
            (8 downTo 0).any { seconds ->
                runCatching {
                    composeRule.onNodeWithText(
                        composeRule.activity.getString(ControlsR.string.guided_countdown, seconds),
                    ).assertIsDisplayed()
                }.isSuccess
            }
        }
    }

    private fun assertNoIntermediateActions() {
        listOf(
            ControlsR.string.start_test,
            ControlsR.string.try_this_control,
            ControlsR.string.next_control,
            ControlsR.string.continue_label,
            ControlsR.string.tried_not_detected,
            ControlsR.string.stick_is_still,
            ControlsR.string.try_stick_movement,
        ).forEach { assertActionAbsent(it) }
    }

    private fun assertActionAbsent(textRes: Int) {
        val text = composeRule.activity.getString(textRes)
        assertTrue("Unexpected action is present: $text", runCatching { action(text).fetchSemanticsNode() }.isFailure)
    }

    private fun assertTextAbsent(text: String) {
        assertTrue("Unexpected text is present: $text", runCatching {
            composeRule.onNodeWithText(text).fetchSemanticsNode()
        }.isFailure)
    }

    private fun pressGuidedDigital(code: Int) {
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> dpadGesture(code, 0f, -1f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> dpadGesture(code, 1f, 0f)
            KeyEvent.KEYCODE_DPAD_DOWN -> dpadGesture(code, 0f, 1f)
            KeyEvent.KEYCODE_DPAD_LEFT -> dpadGesture(code, -1f, 0f)
            else -> press(code)
        }
    }

    private fun dpadGesture(code: Int, x: Float, y: Float) {
        hat(x, y)
        key(code, KeyEvent.ACTION_DOWN)
        key(code, KeyEvent.ACTION_UP)
        hat(0f, 0f)
        composeRule.waitForIdle()
    }
    private fun assertFocusedAction(textRes: Int) {
        val node = action(composeRule.activity.getString(textRes))
        node.assertIsDisplayed().assertIsEnabled().assertIsFocused()
        assertVisibleBorder(node)
        composeRule.runOnUiThread { assertFalse("Android touch mode must have ended", composeView.isInTouchMode) }
        assertEquals(InputMode.Keyboard, composeInputModeManager.inputMode)
    }

    private fun assertAnyInternalActionFocused(vararg textRes: Int) {
        val focused = textRes.map { action(composeRule.activity.getString(it)) }.filter {
            it.fetchSemanticsNode().config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Focused) { false }
        }
        assertEquals("Exactly one real internal action must be focused", 1, focused.size)
        focused.single().assertIsDisplayed().assertIsEnabled()
        assertVisibleBorder(focused.single())
    }

    private fun installRealControls(deviceName: String = "Xbox test controller") {
        val sources = Sources(gamepad = true, joystick = true, dpad = true)
        val device = DeviceInfo(
            DEVICE_ID, "t1-test-device", 1, 1, deviceName, 1,
            false, true, sources, Button.entries.toSet(),
            listOf(Axes.X, Axes.Y, Axes.Z, Axes.RZ, Axes.HAT_X, Axes.HAT_Y).map {
                RangeInfo(it, sources, -1f, 1f, if (it in listOf(Axes.HAT_X, Axes.HAT_Y)) 0f else 0.1f)
            },
        )
        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                MaterialTheme {
                    val inputModeManager = LocalInputModeManager.current
                    composeView = LocalView.current
                    val primary = MaterialTheme.colorScheme.primary
                    val onPrimary = MaterialTheme.colorScheme.onPrimary
                    SideEffect {
                        composeInputModeManager = inputModeManager
                        focusColor = primary
                        focusOverlayColor = lerp(primary, onPrimary, 0.1f)
                    }
                    CarePadShellScreen(
                        onThemeModeChange = {},
                        onRawInputHandlersChanged = composeRule.activity::updateRawInputHandlers,
                        settingsContent = { _, _, _, _ -> },
                        controlsControllerFactory = { context ->
                            ControlsInternalController(context,
                                { if (controllerAvailable) listOf(device) else emptyList() },
                                { id -> device.takeIf { id == DEVICE_ID && controllerAvailable } },
                            ).also { controlsController = it }
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        tap(navigation(composeRule.activity.getString(R.string.carepad_nav_modules)), scroll = false)
    }

    private fun navigation(label: String) = composeRule.onNode(
        hasClickAction() and hasAnyDescendant(hasContentDescription(label)), useUnmergedTree = true,
    )

    private fun action(text: String) = composeRule.onNode(
        hasClickAction() and hasAnyDescendant(hasText(text)), useUnmergedTree = true,
    )

    private fun tap(node: SemanticsNodeInteraction, scroll: Boolean = true) {
        // Clear the old controller anchor before scrolling; its bring-into-view work must
        // not move the target after screen coordinates have been measured.
        instrumentation.setInTouchMode(true)
        composeRule.waitForIdle()
        // Make the real target visible before injecting screen coordinates (also in landscape).
        if (scroll) node.performScrollTo()
        node.assertIsDisplayed()
        val target = node.fetchSemanticsNode()
        val visibleBounds = target.boundsInRoot
        assertTrue("Touch target must have nonempty clipped bounds: $visibleBounds", !visibleBounds.isEmpty)
        val centerOnScreen = target.positionOnScreen - target.positionInRoot + visibleBounds.center
        val window = android.graphics.Rect()
        composeRule.runOnUiThread { composeView.getWindowVisibleDisplayFrame(window) }
        assertTrue("Touch point $centerOnScreen must be inside the app window $window",
            window.contains(centerOnScreen.x.toInt(), centerOnScreen.y.toInt()))
        // An Android touch event, not a semantics performClick or a direct onClick invocation.
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action,
                centerOnScreen.x, centerOnScreen.y, 0,
            ).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            instrumentation.sendPointerSync(event)
            event.recycle()
        }
        composeRule.waitForIdle()
    }

    private fun assertAndroidTouch() {
        composeRule.runOnUiThread { assertTrue("Android must really be in touch mode", composeView.isInTouchMode) }
        composeRule.runOnUiThread {
            assertEquals("Compose must see Android touch mode", InputMode.Touch, composeInputModeManager.inputMode)
        }
    }

    private fun hat(y: Float) = hat(0f, y)

    private fun hat(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_HAT_X, x)
            setAxisValue(MotionEvent.AXIS_HAT_Y, y)
        })
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, properties, coordinates, 0, 0, 1f, 1f, DEVICE_ID, 0, InputDevice.SOURCE_JOYSTICK, 0)
        composeRule.runOnUiThread { composeRule.activity.dispatchGenericMotionEvent(event) }
        event.recycle()
    }

    private fun stick(left: Boolean, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(if (left) MotionEvent.AXIS_X else MotionEvent.AXIS_Z, x)
            setAxisValue(if (left) MotionEvent.AXIS_Y else MotionEvent.AXIS_RZ, y)
        })
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, properties, coordinates,
            0, 0, 1f, 1f, DEVICE_ID, 0, InputDevice.SOURCE_JOYSTICK, 0)
        composeRule.runOnUiThread { composeRule.activity.dispatchGenericMotionEvent(event) }
        event.recycle()
    }

    private fun key(code: Int, action: Int, repeat: Int = 0, flags: Int = 0) {
        val now = SystemClock.uptimeMillis()
        if (action == KeyEvent.ACTION_DOWN && repeat == 0) keyDownTimes[code] = now
        val event = KeyEvent(keyDownTimes[code] ?: now, now, action, code, repeat, 0, DEVICE_ID, 0, flags, InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD)
        composeRule.runOnUiThread { composeRule.activity.dispatchKeyEvent(event) }
        if (action == KeyEvent.ACTION_UP) keyDownTimes.remove(code)
    }

    private fun press(code: Int) {
        key(code, KeyEvent.ACTION_DOWN)
        key(code, KeyEvent.ACTION_UP)
        composeRule.waitForIdle()
    }

    private fun assertVisibleBorder(node: SemanticsNodeInteraction) {
        val capture = node.captureToImage()
        File(instrumentation.targetContext.cacheDir, "t1-focus-${testName.methodName}-${imageIndex++}.png").outputStream().use {
            capture.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        val pixels = capture.toPixelMap()
        fun Color.matches(expected: Color) =
            kotlin.math.abs(red - expected.red) < 0.025f &&
                kotlin.math.abs(green - expected.green) < 0.025f &&
                kotlin.math.abs(blue - expected.blue) < 0.025f
        // Material's visual semantics crop can omit the top of a border drawn around its
        // minimum touch target. Inspect the lateral edge, visible in the archived PNGs.
        val edgePixels = (0 until minOf(12, pixels.width / 4)).count { x ->
            val color = pixels[x, pixels.height / 2]
            color.matches(focusColor) || color.matches(focusOverlayColor)
        }
        assertTrue("Focused action must draw a primary focus edge within its touch bounds", edgePixels >= 2)
    }

    private companion object { const val DEVICE_ID = 77 }
}
