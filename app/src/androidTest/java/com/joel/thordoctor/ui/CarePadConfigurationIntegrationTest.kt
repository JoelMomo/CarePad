package com.joel.thordoctor.ui

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.joel.thordoctor.MainActivity
import com.joel.thordoctor.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarePadConfigurationIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun selectedDestinationSurvivesActivityRecreation() {
        val settings = composeRule.activity.getString(R.string.carepad_nav_settings)
        val appearance = composeRule.activity.getString(R.string.appearance)

        navigationNode(settings).performTouchInput { click() }
        composeRule.waitForIdle()
        navigationNode(settings).assertIsSelected()
        composeRule.onNodeWithText(appearance).assertExists()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        navigationNode(settings).assertIsSelected()
        composeRule.onNodeWithText(appearance).assertExists()
    }

    private fun navigationNode(label: String) = composeRule.onNode(
        matcher = hasClickAction() and hasAnyDescendant(hasContentDescription(label)),
        useUnmergedTree = true,
    )
}
