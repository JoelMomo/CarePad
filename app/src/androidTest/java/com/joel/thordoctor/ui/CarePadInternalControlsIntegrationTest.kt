package com.joel.thordoctor.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertExists
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
}
