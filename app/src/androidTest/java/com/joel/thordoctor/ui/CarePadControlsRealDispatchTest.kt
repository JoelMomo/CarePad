package com.joel.thordoctor.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.joel.thordoctor.AppThemeMode
import com.joel.thordoctor.MainActivity
import com.joel.thordoctor.R
import org.junit.Rule
import org.junit.Test

class CarePadControlsRealDispatchTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun firstAndSecondDpadAfterTouchStayInsideControlsThroughRealDispatch() {
        val themeMode = mutableStateOf(AppThemeMode.SYSTEM)
        val controls = composeRule.activity.getString(R.string.carepad_module_controls)
        val home = composeRule.activity.getString(R.string.carepad_nav_home)
        val firstAction = "Controls focus fixture first"
        val secondAction = "Controls focus fixture second"

        composeRule.runOnUiThread {
            composeRule.activity.setContent {
                MaterialTheme {
                    CarePadShellScreen(
                        onThemeModeChange = { mode -> themeMode.value = mode },
                        onRawInputHandlersChanged = composeRule.activity::updateRawInputHandlers,
                        settingsContent = { _, _, _, _ -> },
                        controlsContent = { _, modifier ->
                            Column(
                                modifier = modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Button(onClick = {}) { Text(firstAction) }
                                Button(onClick = {}) { Text(secondAction) }
                            }
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNode(
            matcher = hasClickAction() and hasAnyDescendant(hasText(controls)),
            useUnmergedTree = true,
        ).performClick()
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            check(composeRule.activity.dispatchKeyEvent(controllerKeyEvent(KeyEvent.ACTION_DOWN)))
            check(composeRule.activity.dispatchKeyEvent(controllerKeyEvent(KeyEvent.ACTION_UP)))
        }
        composeRule.waitForIdle()

        actionNode(firstAction).assertIsFocused()
        composeRule.onNodeWithText(home).assertIsNotFocused()

        composeRule.runOnUiThread {
            composeRule.activity.dispatchKeyEvent(controllerKeyEvent(KeyEvent.ACTION_DOWN))
            composeRule.activity.dispatchKeyEvent(controllerKeyEvent(KeyEvent.ACTION_UP))
        }
        composeRule.waitForIdle()

        actionNode(secondAction).assertIsFocused()
        composeRule.onNodeWithText(home).assertIsNotFocused()
    }

    private fun actionNode(text: String) = composeRule.onNode(
        matcher = hasClickAction() and hasAnyDescendant(hasText(text)),
        useUnmergedTree = true,
    )

    private fun controllerKeyEvent(action: Int): KeyEvent {
        val now = SystemClock.uptimeMillis()
        return KeyEvent(
            now,
            now,
            action,
            KeyEvent.KEYCODE_DPAD_DOWN,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_DPAD,
        )
    }
}
