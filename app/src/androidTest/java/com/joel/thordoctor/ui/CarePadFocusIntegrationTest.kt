package com.joel.thordoctor.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.joel.thordoctor.AppThemeMode
import com.joel.thordoctor.R
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CarePadFocusIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val themeMode = mutableStateOf(AppThemeMode.SYSTEM)
    private val showSettingsSubtree = mutableStateOf(true)

    private lateinit var navHome: String
    private lateinit var navAddModules: String
    private lateinit var navSettings: String
    private lateinit var themeSystem: String
    private lateinit var themeLight: String
    private lateinit var themeDark: String
    private lateinit var appearance: String

    @Before
    fun setUp() {
        themeMode.value = AppThemeMode.SYSTEM
        showSettingsSubtree.value = true
        navHome = composeRule.activity.getString(R.string.carepad_nav_home)
        navAddModules = composeRule.activity.getString(R.string.carepad_nav_add_modules)
        navSettings = composeRule.activity.getString(R.string.carepad_nav_settings)
        themeSystem = composeRule.activity.getString(R.string.theme_system)
        themeLight = composeRule.activity.getString(R.string.theme_light)
        themeDark = composeRule.activity.getString(R.string.theme_dark)
        appearance = composeRule.activity.getString(R.string.appearance)

        composeRule.setContent {
            MaterialTheme {
                CarePadShellScreen(
                    onThemeModeChange = { mode -> themeMode.value = mode },
                    settingsContent = {
                            _,
                            onThemeFocusChanged,
                            onThemeTouched,
                            themeFocusRequesters,
                        ->
                        if (showSettingsSubtree.value) {
                            CarePadSettingsScreen(
                                themeMode = themeMode.value,
                                onThemeModeChange = { mode ->
                                    onThemeTouched(mode)
                                    themeMode.value = mode
                                },
                                onControllerThemeFocusChanged = onThemeFocusChanged,
                                controllerFocusRequesters = themeFocusRequesters,
                            )
                        } else {
                            Text("settings-subtree-removed")
                        }
                    },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun railTouchesMakeFirstDpadEffectiveAndKeepRailConfined() {
        tapRail(navSettings)
        railNode(navSettings).assertIsFocused()
        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
        controllerHint().assertExists()
        railNode(navSettings).assertIsFocused()

        tapRail(navHome)
        railNode(navHome).assertIsFocused()
        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
        controllerHint().assertExists()
        railNode(navAddModules).assertIsFocused()

        tapRail(navAddModules)
        railNode(navAddModules).assertIsFocused()
        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
        controllerHint().assertExists()
        railNode(navSettings).assertIsFocused()

        pressDpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        railNode(navSettings).assertIsFocused()
    }

    @Test
    fun settingsRailTouchThenFirstL1CrossesExactlyOnce() {
        tapRail(navSettings)
        railNode(navSettings).assertIsFocused()

        pressL1()
        controllerHint().assertExists()
        textNode(themeSystem).assertIsFocused()

        pressL1()
        railNode(navSettings).assertIsFocused()

        pressL1()
        textNode(themeSystem).assertIsFocused()
    }

    @Test
    fun systemTouchThenFirstDpadIsEffectiveRegardlessOfPriorHistory() {
        enterSettingsContent()

        repeat(3) {
            tapText(themeSystem)
            touchHint().assertExists()
            pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
            controllerHint().assertExists()
            textNode(themeLight).assertIsFocused()
        }
    }

    @Test
    fun systemTouchThenFirstL1ReturnsToRememberedRail() {
        enterSettingsContent()
        tapText(themeSystem)

        pressL1()
        controllerHint().assertExists()
        railNode(navSettings).assertIsFocused()

        pressL1()
        textNode(themeSystem).assertIsFocused()
    }

    @Test
    fun contentDpadNeverCrossesToRailAndSelectedRemainsIndependentFromFocused() {
        enterSettingsContent()
        textNode(themeSystem).assertIsFocused()

        pressDpad(KeyEvent.KEYCODE_DPAD_LEFT)
        textNode(themeSystem).assertIsFocused()
        pressDpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        textNode(themeSystem).assertIsFocused()

        pressL1()
        railNode(navSettings).assertIsFocused()
        pressDpad(KeyEvent.KEYCODE_DPAD_UP)
        railNode(navAddModules).assertIsFocused()
        pressDpad(KeyEvent.KEYCODE_DPAD_UP)
        railNode(navHome).assertIsFocused()

        textNode(appearance).assertExists()
        pressDpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        railNode(navHome).assertIsFocused()
        textNode(appearance).assertExists()
    }

    @Test
    fun themeRecompositionPreservesFirstControllerContinuationAndModeHint() {
        enterSettingsContent()
        tapText(themeLight)
        touchHint().assertExists()

        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)

        controllerHint().assertExists()
        textNode(themeDark).assertIsFocused()
    }

    @Test
    fun disappearingFocusedSubtreeDoesNotChangeLogicalZone() {
        enterSettingsContent()
        textNode(themeSystem).assertIsFocused()

        composeRule.runOnUiThread {
            showSettingsSubtree.value = false
        }
        composeRule.waitForIdle()
        textNode("settings-subtree-removed").assertExists()

        pressL1()

        controllerHint().assertExists()
        railNode(navSettings).assertIsFocused()
    }

    @Test
    fun touchAndControllerHintsFollowReducerModality() {
        tapRail(navSettings)
        railNode(navSettings).assertIsFocused()
        touchHint().assertExists()

        pressL1()
        controllerHint().assertExists()

        tapText(themeSystem)
        touchHint().assertExists()

        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
        controllerHint().assertExists()
    }

    private fun enterSettingsContent() {
        tapRail(navSettings)
        railNode(navSettings).assertIsFocused()
        pressL1()
        controllerHint().assertExists()
        textNode(themeSystem).assertIsFocused()
    }

    private fun tapRail(label: String) {
        railNode(label).performTouchInput { click() }
        composeRule.waitForIdle()
    }

    private fun tapText(text: String) {
        textNode(text).performTouchInput { click() }
        composeRule.waitForIdle()
    }

    private fun railNode(label: String) = composeRule.onNode(
        matcher = hasClickAction() and hasAnyDescendant(hasContentDescription(label)),
        useUnmergedTree = true,
    )

    private fun textNode(text: String) = composeRule.onNodeWithText(text)

    private fun touchHint() = composeRule.onNodeWithText("Toca", substring = true)

    private fun controllerHint() =
        composeRule.onNodeWithText("Navegación", substring = true)

    private fun pressDpad(keyCode: Int) {
        injectControllerKey(keyCode, InputDevice.SOURCE_DPAD)
    }

    private fun pressL1() {
        injectControllerKey(KeyEvent.KEYCODE_BUTTON_L1, InputDevice.SOURCE_GAMEPAD)
    }

    private fun injectControllerKey(keyCode: Int, source: Int) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val downTime = SystemClock.uptimeMillis()
        val down = KeyEvent(
            downTime,
            downTime,
            KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            source,
        )
        val up = KeyEvent(
            downTime,
            SystemClock.uptimeMillis(),
            KeyEvent.ACTION_UP,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            source,
        )
        check(instrumentation.uiAutomation.injectInputEvent(down, true))
        check(instrumentation.uiAutomation.injectInputEvent(up, true))
        composeRule.waitForIdle()
    }
}