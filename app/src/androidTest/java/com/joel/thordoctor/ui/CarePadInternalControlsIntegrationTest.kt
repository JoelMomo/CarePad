package com.joel.thordoctor.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joel.thordoctor.AppThemeMode
import com.joel.thordoctor.R
import dev.carepad.module.controls.internalui.R as ControlsR
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarePadInternalControlsIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun controlsOpensInsideCarePadShellAndBackReturnsToYourModules() {
        val themeMode = mutableStateOf(AppThemeMode.SYSTEM)
        val controls = composeRule.activity.getString(R.string.carepad_module_controls)
        val guidedTest = composeRule.activity.getString(ControlsR.string.guided_test)
        val detectedInputs = composeRule.activity.getString(ControlsR.string.detected_inputs)
        val home = composeRule.activity.getString(R.string.carepad_nav_home)
        val yourModules = composeRule.activity.getString(R.string.carepad_your_modules)

        composeRule.setContent {
            MaterialTheme {
                CarePadShellScreen(
                    onThemeModeChange = { mode -> themeMode.value = mode },
                    onRawInputHandlersChanged = { _, _ -> },
                    settingsContent = { _, _, _, _ -> },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(
            matcher = hasClickAction() and hasAnyDescendant(hasText(controls)),
            useUnmergedTree = true,
        ).performClick()
        composeRule.waitForIdle()

        actionNode(guidedTest).assertExists()
        actionNode(detectedInputs).assertExists()
        composeRule.onNodeWithText(home).assertExists()

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(yourModules).assertExists()
        composeRule.onNodeWithText(controls).assertExists()
    }

    @Test
    fun firstDpadAfterTouchInsideControlsIsConsumedBeforeSpatialNavigation() {
        val themeMode = mutableStateOf(AppThemeMode.SYSTEM)
        val controls = composeRule.activity.getString(R.string.carepad_module_controls)
        val touchHint = composeRule.activity.getString(R.string.carepad_hint_touch_navigation)
        var rawKeyHandler: ((KeyEvent) -> Boolean)? = null

        composeRule.setContent {
            MaterialTheme {
                CarePadShellScreen(
                    onThemeModeChange = { mode -> themeMode.value = mode },
                    onRawInputHandlersChanged = { keyHandler, _ ->
                        rawKeyHandler = keyHandler
                    },
                    settingsContent = { _, _, _, _ -> },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(
            matcher = hasClickAction() and hasAnyDescendant(hasText(controls)),
            useUnmergedTree = true,
        ).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(touchHint).assertExists()
        val down = controllerKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN, InputDevice.SOURCE_DPAD)

        composeRule.runOnUiThread {
            check(checkNotNull(rawKeyHandler).invoke(down))
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(touchHint).assertDoesNotExist()
        composeRule.onNodeWithText("Navegación", substring = true).assertExists()
    }

    @Test
    fun dpadHatThenKeyAfterTouchIsOneConsumedRecoveryGesture() {
        val themeMode = mutableStateOf(AppThemeMode.SYSTEM)
        val controls = composeRule.activity.getString(R.string.carepad_module_controls)
        val touchHint = composeRule.activity.getString(R.string.carepad_hint_touch_navigation)
        var rawKeyHandler: ((KeyEvent) -> Boolean)? = null
        var rawMotionHandler: ((MotionEvent) -> Boolean)? = null

        composeRule.setContent {
            MaterialTheme {
                CarePadShellScreen(
                    onThemeModeChange = { mode -> themeMode.value = mode },
                    onRawInputHandlersChanged = { keyHandler, motionHandler ->
                        rawKeyHandler = keyHandler
                        rawMotionHandler = motionHandler
                    },
                    settingsContent = { _, _, _, _ -> },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(
            matcher = hasClickAction() and hasAnyDescendant(hasText(controls)),
            useUnmergedTree = true,
        ).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(touchHint).assertExists()

        val hatDown = controllerHatMotion(y = 1f)
        val keyDown = controllerKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN, InputDevice.SOURCE_DPAD)
        val keyUp = controllerKeyEvent(
            KeyEvent.KEYCODE_DPAD_DOWN,
            InputDevice.SOURCE_DPAD,
            KeyEvent.ACTION_UP,
        )
        val hatNeutral = controllerHatMotion()

        composeRule.runOnUiThread {
            check(checkNotNull(rawMotionHandler).invoke(hatDown))
            check(checkNotNull(rawKeyHandler).invoke(keyDown))
            check(checkNotNull(rawKeyHandler).invoke(keyUp))
            check(checkNotNull(rawMotionHandler).invoke(hatNeutral))
            hatDown.recycle()
            hatNeutral.recycle()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(touchHint).assertDoesNotExist()
        composeRule.onNodeWithText("Navegación", substring = true).assertExists()

        composeRule.runOnUiThread {
            check(
                checkNotNull(rawKeyHandler).invoke(
                    controllerKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, InputDevice.SOURCE_DPAD)
                ).not()
            )
        }
    }

    @Test
    fun l1StillMovesFromControlsToRailWithoutSelectedController() {
        val themeMode = mutableStateOf(AppThemeMode.SYSTEM)
        val controls = composeRule.activity.getString(R.string.carepad_module_controls)
        val home = composeRule.activity.getString(R.string.carepad_nav_home)
        var rawKeyHandler: ((KeyEvent) -> Boolean)? = null

        composeRule.setContent {
            MaterialTheme {
                CarePadShellScreen(
                    onThemeModeChange = { mode -> themeMode.value = mode },
                    onRawInputHandlersChanged = { keyHandler, _ ->
                        rawKeyHandler = keyHandler
                    },
                    settingsContent = { _, _, _, _ -> },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(
            matcher = hasClickAction() and hasAnyDescendant(hasText(controls)),
            useUnmergedTree = true,
        ).performClick()
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            check(
                checkNotNull(rawKeyHandler).invoke(
                    controllerKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN, InputDevice.SOURCE_DPAD)
                )
            )
        }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            composeRule.activity.dispatchKeyEvent(
                controllerKeyEvent(KeyEvent.KEYCODE_BUTTON_L1, InputDevice.SOURCE_GAMEPAD)
            )
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(home).assertIsFocused()
    }

    @Test
    fun neutralGenericMotionAfterTouchKeepsTouchHint() {
        val themeMode = mutableStateOf(AppThemeMode.SYSTEM)
        val controls = composeRule.activity.getString(R.string.carepad_module_controls)
        val touchHint = composeRule.activity.getString(R.string.carepad_hint_touch_navigation)
        var rawMotionHandler: ((MotionEvent) -> Boolean)? = null

        composeRule.setContent {
            MaterialTheme {
                CarePadShellScreen(
                    onThemeModeChange = { mode -> themeMode.value = mode },
                    onRawInputHandlersChanged = { _, motionHandler ->
                        rawMotionHandler = motionHandler
                    },
                    settingsContent = { _, _, _, _ -> },
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(
            matcher = hasClickAction() and hasAnyDescendant(hasText(controls)),
            useUnmergedTree = true,
        ).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(touchHint).assertExists()
        val now = SystemClock.uptimeMillis()
        val motion = MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_MOVE,
            0f,
            0f,
            0,
        ).apply {
            source = InputDevice.SOURCE_JOYSTICK
        }

        composeRule.runOnUiThread {
            check(checkNotNull(rawMotionHandler).invoke(motion).not())
            motion.recycle()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(touchHint).assertExists()
    }

    private fun actionNode(text: String) = composeRule.onNode(
        matcher = hasClickAction() and hasAnyDescendant(hasText(text)),
        useUnmergedTree = true,
    )

    private fun controllerKeyEvent(
        keyCode: Int,
        source: Int,
        action: Int = KeyEvent.ACTION_DOWN,
    ): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(
            now,
            now,
            action,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            source,
        )
    }

    private fun controllerHatMotion(x: Float = 0f, y: Float = 0f): MotionEvent {
        val now = SystemClock.uptimeMillis()
        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_UNKNOWN
            }
        )
        val coordinates = arrayOf(
            MotionEvent.PointerCoords().apply {
                setAxisValue(MotionEvent.AXIS_HAT_X, x)
                setAxisValue(MotionEvent.AXIS_HAT_Y, y)
            }
        )
        return MotionEvent.obtain(
            now,
            now,
            MotionEvent.ACTION_MOVE,
            1,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            InputDevice.SOURCE_JOYSTICK,
            0,
        )
    }
}
