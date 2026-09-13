package com.joel.thordoctor.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
import dev.carepad.module.controls.internalui.R as ControlsR

/** Only the device catalogue is controlled; the Activity, shell, Controls UI and targets are real. */
class CarePadControlsTouchModeTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var composeView: View
    private lateinit var composeInputModeManager: InputModeManager
    private var focusColor = Color.Unspecified

    @Test
    fun firstHatKeyGestureAfterAndroidTouchFocusesVisibleUsableControlsAction() {
        installRealControls()
        tap(action(composeRule.activity.getString(R.string.carepad_module_controls)))
        assertAndroidTouch()

        // HAT is delivered before KEY, as allowed by the recorded Thor gesture contract.
        // Dispatching through the Activity preserves its raw bridge and Compose fallback.
        hat(1f)
        key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_DOWN)
        key(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.ACTION_UP)
        hat(0f)
        composeRule.waitForIdle()

        val guided = action(composeRule.activity.getString(ControlsR.string.guided_test))
        guided.assertIsEnabled().assertIsDisplayed().assertIsFocused()
        assertVisibleBorder(guided)
        composeRule.onNodeWithText(composeRule.activity.getString(R.string.carepad_nav_home))
            .assertIsNotFocused()

        // Focus must activate the real module action, not just set an observed-mode flag.
        press(KeyEvent.KEYCODE_BUTTON_A)
        composeRule.onNodeWithText(composeRule.activity.getString(ControlsR.string.prepare_test))
            .assertIsDisplayed()
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
    fun hatKeyReleaseOrdersDoNotLeaveDrainForTheNextPress() {
        installRealControls()
        tap(action(composeRule.activity.getString(R.string.carepad_module_controls)))
        val orders = listOf(
            listOf("hat", "down", "up", "neutral"),
            listOf("down", "hat", "up", "neutral"),
            listOf("hat", "down", "neutral", "up"),
        )
        for (order in orders) {
            tap(composeRule.onNodeWithText(composeRule.activity.getString(ControlsR.string.app_name)))
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
    fun replacedScreenTargetsCanAcquireFocusAfterTheFirstDpad() {
        installRealControls()
        tap(action(composeRule.activity.getString(R.string.carepad_module_controls)))
        tap(action(composeRule.activity.getString(ControlsR.string.guided_test)))
        assertAndroidTouch()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertAnyInternalActionFocused(ControlsR.string.back, ControlsR.string.start_test)

        // Touch replaces the preparation actions with the real digital-test actions.
        tap(action(composeRule.activity.getString(ControlsR.string.start_test)))
        assertAndroidTouch()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        assertFocusedAction(ControlsR.string.try_this_control)
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

    private fun installRealControls() {
        val sources = Sources(gamepad = true, joystick = true, dpad = true)
        val device = DeviceInfo(
            DEVICE_ID, "t1-test-device", 1, 1, "T1 test controller", 1,
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
                    SideEffect {
                        composeInputModeManager = inputModeManager
                        focusColor = primary
                    }
                    CarePadShellScreen(
                        onThemeModeChange = {},
                        onRawInputHandlersChanged = composeRule.activity::updateRawInputHandlers,
                        settingsContent = { _, _, _, _ -> },
                        controlsControllerFactory = { context ->
                            ControlsInternalController(context, { listOf(device) }, { id -> device.takeIf { id == DEVICE_ID } })
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun action(text: String) = composeRule.onNode(
        hasClickAction() and hasAnyDescendant(hasText(text)), useUnmergedTree = true,
    )

    private fun tap(node: SemanticsNodeInteraction) {
        val center = node.fetchSemanticsNode().boundsInRoot.center
        val location = IntArray(2)
        composeRule.runOnUiThread { composeView.getLocationOnScreen(location) }
        // An Android touch event, not a semantics performClick or a direct onClick invocation.
        instrumentation.setInTouchMode(true)
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action,
                center.x + location[0], center.y + location[1], 0,
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

    private fun hat(y: Float) {
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply { setAxisValue(MotionEvent.AXIS_HAT_Y, y) })
        val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1, properties, coordinates, 0, 0, 1f, 1f, DEVICE_ID, 0, InputDevice.SOURCE_JOYSTICK, 0)
        composeRule.runOnUiThread { composeRule.activity.dispatchGenericMotionEvent(event) }
        event.recycle()
    }

    private fun key(code: Int, action: Int) {
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, action, code, 0, 0, DEVICE_ID, 0, 0, InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_DPAD)
        composeRule.runOnUiThread { composeRule.activity.dispatchKeyEvent(event) }
    }

    private fun press(code: Int) {
        key(code, KeyEvent.ACTION_DOWN)
        key(code, KeyEvent.ACTION_UP)
        composeRule.waitForIdle()
    }

    private fun assertVisibleBorder(node: SemanticsNodeInteraction) {
        val pixels = node.captureToImage().toPixelMap()
        val top = pixels[pixels.width / 2, 1]
        assertTrue("Focused action must draw its primary border: $top vs $focusColor",
            kotlin.math.abs(top.red - focusColor.red) < 0.06f &&
                kotlin.math.abs(top.green - focusColor.green) < 0.06f &&
                kotlin.math.abs(top.blue - focusColor.blue) < 0.06f)
    }

    private companion object { const val DEVICE_ID = 77 }
}
