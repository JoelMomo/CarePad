package com.joel.thordoctor.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
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

        composeRule.onNodeWithText(guidedTest).assertExists()
        composeRule.onNodeWithText(detectedInputs).assertExists()
        composeRule.onNodeWithText(home).assertExists()

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(yourModules).assertExists()
        composeRule.onNodeWithText(controls).assertExists()
    }

    @Test
    fun firstDpadAfterTouchInsideControlsRestoresControllerHint() {
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
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(
            now,
            now,
            KeyEvent.ACTION_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_DPAD,
        )

        composeRule.runOnUiThread {
            check(checkNotNull(rawKeyHandler).invoke(down).not())
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText(touchHint).assertDoesNotExist()
        composeRule.onNodeWithText("Navegación", substring = true).assertExists()
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
}
