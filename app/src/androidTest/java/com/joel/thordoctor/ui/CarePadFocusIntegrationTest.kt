package com.joel.thordoctor.ui

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
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

    private lateinit var inputModeManager: InputModeManager
    private lateinit var navHome: String
    private lateinit var navAddModules: String
    private lateinit var navSettings: String
    private lateinit var themeSystem: String
    private lateinit var themeLight: String
    private lateinit var themeDark: String
    private lateinit var appearance: String
    private lateinit var performanceModule: String
    private lateinit var yourModules: String

    @Before
    fun setUp() {
        themeMode.value = AppThemeMode.SYSTEM
        navHome = composeRule.activity.getString(R.string.carepad_nav_home)
        navAddModules = composeRule.activity.getString(R.string.carepad_nav_add_modules)
        navSettings = composeRule.activity.getString(R.string.carepad_nav_settings)
        themeSystem = composeRule.activity.getString(R.string.theme_system)
        themeLight = composeRule.activity.getString(R.string.theme_light)
        themeDark = composeRule.activity.getString(R.string.theme_dark)
        appearance = composeRule.activity.getString(R.string.appearance)
        performanceModule = composeRule.activity.getString(R.string.carepad_module_performance)
        yourModules = composeRule.activity.getString(R.string.carepad_your_modules)

        composeRule.setContent {
            inputModeManager = LocalInputModeManager.current
            MaterialTheme {
                CarePadShellScreen(
                    onThemeModeChange = { mode -> themeMode.value = mode },
                    settingsContent = {
                            _,
                            onThemeFocusChanged,
                            onThemeTouched,
                            themeFocusRequesters,
                        ->
                        CarePadSettingsScreen(
                            themeMode = themeMode.value,
                            onThemeModeChange = { mode ->
                                onThemeTouched(mode)
                                themeMode.value = mode
                            },
                            onControllerThemeFocusChanged = onThemeFocusChanged,
                            controllerFocusRequesters = themeFocusRequesters,
                        )
                    },
                )
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun settingsBoundaryUsesNaturalSpatialLeftRightWithoutL1() {
        establishSettingsRailControllerContext()

        pressDpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        controllerHint().assertExists()
        textNode(themeSystem).assertIsFocused()
        railNode(navSettings).assertIsSelected()

        pressDpad(KeyEvent.KEYCODE_DPAD_LEFT)
        controllerHint().assertExists()
        railNode(navSettings).assertIsFocused()
        railNode(navSettings).assertIsSelected()
    }

    @Test
    fun systemTouchThenFirstDpadMovesImmediately() {
        establishSettingsContentControllerContext()
        establishContentControllerTarget(themeDark)

        touchContentAndAssertContext(themeSystem)
        touchHint().assertExists()
        textNode(themeSystem).assertIsFocused()

        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)

        controllerHint().assertExists()
        textNode(themeLight).assertIsFocused()
        railNode(navSettings).assertIsSelected()
    }

    @Test
    fun systemTouchInRealTouchModeMovesOnFirstDpadAndUpdatesHintIndependently() {
        establishSettingsContentControllerContext()
        establishContentControllerTarget(themeDark)

        requestTouchInputModeForPhysicalOracle()
        touchContentAndAssertContext(themeSystem)

        check(currentInputMode() == InputMode.Touch)
        touchHint().assertExists()
        textNode(themeSystem).assertIsFocused()
        railNode(navSettings).assertIsSelected()

        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)

        check(currentInputMode() == InputMode.Keyboard)
        controllerHint().assertExists()
        textNode(themeLight).assertIsFocused()
        railNode(navSettings).assertIsSelected()
    }

    @Test
    fun l1RemainsAnOptionalShortcutAcrossTheSameSpatialBoundary() {
        establishSettingsRailControllerContext()

        pressL1()
        controllerHint().assertExists()
        textNode(themeSystem).assertIsFocused()
        railNode(navSettings).assertIsSelected()

        pressL1()
        controllerHint().assertExists()
        railNode(navSettings).assertIsFocused()
        railNode(navSettings).assertIsSelected()
    }

    @Test
    fun themeRecompositionKeepsSpatialControllerNavigation() {
        establishSettingsContentControllerContext()
        establishContentControllerTarget(themeLight)
        touchContentAndAssertContext(themeLight)

        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)

        controllerHint().assertExists()
        textNode(themeDark).assertIsFocused()
        railNode(navSettings).assertIsSelected()
    }

    @Test
    fun touchAndControllerHintsDoNotChooseFocusTargets() {
        establishSettingsContentControllerContext()
        touchContentAndAssertContext(themeSystem)
        touchHint().assertExists()
        textNode(themeSystem).assertIsFocused()

        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
        controllerHint().assertExists()
        textNode(themeLight).assertIsFocused()

        tapRail(navSettings)
        touchHint().assertExists()
        railNode(navSettings).assertIsFocused()
        railNode(navSettings).assertIsSelected()

        pressDpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        controllerHint().assertExists()
        textNode(themeSystem).assertIsFocused()
    }

    @Test
    fun homeDynamicModuleUsesNaturalHorizontalCrossingWithoutVerticalRailJump() {
        requestKeyboardInputModeForFocusSetup()
        textNode(performanceModule).assertExists()
        railNode(navHome).requestFocus()
        composeRule.waitForIdle()
        railNode(navHome).assertIsFocused()
        railNode(navHome).assertIsSelected()

        pressDpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        controllerHint().assertExists()
        textNode(performanceModule).assertIsFocused()
        railNode(navHome).assertIsSelected()

        pressDpad(KeyEvent.KEYCODE_DPAD_LEFT)
        railNode(navHome).assertIsFocused()
        railNode(navHome).assertIsSelected()

        pressDpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        textNode(performanceModule).assertIsFocused()

        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
        textNode(performanceModule).assertIsFocused()
        railNode(navAddModules).assertIsNotFocusedCompat()
        railNode(navSettings).assertIsNotFocusedCompat()
    }

    @Test
    fun backStillReturnsSettingsToHomeWithoutFocusPolicyState() {
        establishSettingsContentControllerContext()
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()

        textNode(yourModules).assertExists()
        railNode(navHome).assertIsSelected()
    }

    private fun establishSettingsRailControllerContext() {
        requestKeyboardInputModeForFocusSetup()
        railNode(navHome).requestFocus()
        composeRule.waitForIdle()
        railNode(navHome).assertIsFocused()

        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
        railNode(navAddModules).assertIsFocused()
        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
        railNode(navSettings).assertIsFocused()

        pressA()
        controllerHint().assertExists()
        railNode(navSettings).assertIsFocused()
        railNode(navSettings).assertIsSelected()
        textNode(appearance).assertExists()
    }

    private fun establishSettingsContentControllerContext() {
        establishSettingsRailControllerContext()
        pressDpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        controllerHint().assertExists()
        textNode(themeSystem).assertIsFocused()
        railNode(navSettings).assertIsSelected()
    }

    private fun establishContentControllerTarget(text: String) {
        requestKeyboardInputModeForFocusSetup()
        textNode(text).requestFocus()
        composeRule.waitForIdle()
        textNode(text).assertIsFocused()
    }

    private fun requestKeyboardInputModeForFocusSetup() {
        composeRule.runOnIdle {
            check(inputModeManager.requestInputMode(InputMode.Keyboard))
        }
    }

    private fun requestTouchInputModeForPhysicalOracle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            "android.permission.MODIFY_TOUCH_MODE_STATE"
        )
        try {
            instrumentation.setInTouchMode(true)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
        composeRule.waitForIdle()
        check(currentInputMode() == InputMode.Touch)
    }

    private fun touchContentAndAssertContext(text: String) {
        tapText(text)
        touchHint().assertExists()
        textNode(appearance).assertExists()
        railNode(navSettings).assertIsSelected()
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

    private fun currentInputMode(): InputMode {
        var mode = InputMode.Touch
        composeRule.runOnIdle {
            mode = inputModeManager.inputMode
        }
        return mode
    }

    private fun pressDpad(keyCode: Int) {
        injectControllerKey(keyCode, InputDevice.SOURCE_DPAD)
    }

    private fun pressL1() {
        injectControllerKey(KeyEvent.KEYCODE_BUTTON_L1, InputDevice.SOURCE_GAMEPAD)
    }

    private fun pressA() {
        injectControllerKey(KeyEvent.KEYCODE_BUTTON_A, InputDevice.SOURCE_GAMEPAD)
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

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertIsNotFocusedCompat() {
        check(runCatching { assertIsFocused() }.isFailure)
    }
}
