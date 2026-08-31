package com.joel.thordoctor.ui

import android.os.Bundle
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
    private val focusTrace = mutableListOf<String>()

    private lateinit var inputModeManager: InputModeManager
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
        focusTrace.clear()
        navHome = composeRule.activity.getString(R.string.carepad_nav_home)
        navAddModules = composeRule.activity.getString(R.string.carepad_nav_add_modules)
        navSettings = composeRule.activity.getString(R.string.carepad_nav_settings)
        themeSystem = composeRule.activity.getString(R.string.theme_system)
        themeLight = composeRule.activity.getString(R.string.theme_light)
        themeDark = composeRule.activity.getString(R.string.theme_dark)
        appearance = composeRule.activity.getString(R.string.appearance)

        composeRule.setContent {
            inputModeManager = LocalInputModeManager.current
            MaterialTheme {
                CarePadShellScreen(
                    onThemeModeChange = { mode -> themeMode.value = mode },
                    focusTrace = { entry -> focusTrace += entry },
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
    fun railTouchesMakeFirstDpadEffectiveAndKeepRailConfined() {
        establishSettingsRailControllerContext()
        focusTrace.clear()
        val inputModeBeforeTouch = currentInputMode()
        touchRailAndAssertContext(navSettings)
        val inputModeAfterTouch = currentInputMode()
        val probe = pressDpadWithFocusTimingProbe(KeyEvent.KEYCODE_DPAD_DOWN)
        emitDpadProbe(inputModeBeforeTouch, inputModeAfterTouch, probe)
        controllerHint().assertExists()
        railNode(navSettings).assertIsFocused()

        establishRailControllerTarget(navHome, KeyEvent.KEYCODE_DPAD_UP)
        touchRailAndAssertContext(navHome)
        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
        controllerHint().assertExists()
        railNode(navAddModules).assertIsFocused()

        establishRailControllerTarget(navAddModules, KeyEvent.KEYCODE_DPAD_RIGHT)
        touchRailAndAssertContext(navAddModules)
        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
        controllerHint().assertExists()
        railNode(navSettings).assertIsFocused()

        pressDpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        railNode(navSettings).assertIsFocused()
    }

    @Test
    fun settingsRailTouchThenFirstL1CrossesExactlyOnce() {
        establishSettingsRailControllerContext()
        touchRailAndAssertContext(navSettings)
        textNode(appearance).assertExists()

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
        establishSettingsContentControllerContext()

        repeat(3) {
            establishContentControllerTarget(themeSystem, KeyEvent.KEYCODE_DPAD_UP)
            touchContentAndAssertContext(themeSystem)
            pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
            controllerHint().assertExists()
            textNode(themeLight).assertIsFocused()
        }
    }

    @Test
    fun systemTouchInRealTouchModeAnchorsContentAndFirstDpadMovesImmediately() {
        establishSettingsContentControllerContext()
        establishContentControllerTarget(themeDark, KeyEvent.KEYCODE_DPAD_DOWN)

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
    fun systemTouchThenFirstL1ReturnsToRememberedRail() {
        establishSettingsContentControllerContext()
        establishContentControllerTarget(themeSystem, KeyEvent.KEYCODE_DPAD_UP)
        touchContentAndAssertContext(themeSystem)

        pressL1()
        controllerHint().assertExists()
        railNode(navSettings).assertIsFocused()
        railNode(navSettings).assertIsSelected()

        pressL1()
        textNode(themeSystem).assertIsFocused()
    }

    @Test
    fun contentDpadNeverCrossesToRailAndSelectedRemainsIndependentFromFocused() {
        establishSettingsContentControllerContext()
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
        railNode(navSettings).assertIsSelected()
        pressDpad(KeyEvent.KEYCODE_DPAD_RIGHT)
        railNode(navHome).assertIsFocused()
        textNode(appearance).assertExists()
        railNode(navSettings).assertIsSelected()
    }

    @Test
    fun themeRecompositionPreservesFirstControllerContinuationAndModeHint() {
        establishSettingsContentControllerContext()
        establishContentControllerTarget(themeLight, KeyEvent.KEYCODE_DPAD_RIGHT)
        touchContentAndAssertContext(themeLight)

        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)

        controllerHint().assertExists()
        textNode(themeDark).assertIsFocused()
    }

    @Test
    fun disappearingFocusedSubtreeDoesNotChangeLogicalZone() {
        val yourModules = composeRule.activity.getString(R.string.carepad_your_modules)

        establishSettingsContentControllerContext()
        establishContentControllerTarget(themeSystem, KeyEvent.KEYCODE_DPAD_UP)
        touchContentAndAssertContext(themeSystem)
        touchHint().assertExists()
        railNode(navSettings).assertIsSelected()

        // Back from Settings is a real shell transition: goTo(HOME) removes the Settings
        // content targets and the production LaunchedEffect emits ContentTargetsChanged.
        // It does not manufacture reducer state from the test harness.
        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        textNode(yourModules).assertExists()
        touchHint().assertExists()
        railNode(navHome).assertIsSelected()

        pressL1()
        controllerHint().assertExists()
        railNode(navHome).assertIsSelected()
        railNode(navSettings).assertIsFocused()
        textNode(yourModules).assertExists()
    }

    @Test
    fun touchAndControllerHintsFollowReducerModality() {
        establishSettingsRailControllerContext()
        touchRailAndAssertContext(navSettings)
        touchHint().assertExists()

        pressL1()
        controllerHint().assertExists()
        textNode(themeSystem).assertIsFocused()

        establishContentControllerTarget(themeSystem, KeyEvent.KEYCODE_DPAD_UP)
        touchContentAndAssertContext(themeSystem)
        touchHint().assertExists()

        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
        controllerHint().assertExists()
        textNode(themeLight).assertIsFocused()
    }

    private fun establishSettingsRailControllerContext() {
        requestKeyboardInputModeForFocusSetup()
        railNode(navHome).requestFocus()
        composeRule.waitForIdle()
        railNode(navHome).assertIsFocused()

        pressL1()
        controllerHint().assertExists()
        railNode(navHome).assertIsFocused()

        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
        controllerHint().assertExists()
        railNode(navAddModules).assertIsFocused()

        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
        controllerHint().assertExists()
        railNode(navSettings).assertIsFocused()

        pressA()
        controllerHint().assertExists()
        railNode(navSettings).assertIsFocused()
        railNode(navSettings).assertIsSelected()
        textNode(appearance).assertExists()

        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
        controllerHint().assertExists()
        railNode(navSettings).assertIsFocused()
        railNode(navSettings).assertIsSelected()
    }

    private fun establishSettingsContentControllerContext() {
        establishSettingsRailControllerContext()
        pressL1()
        controllerHint().assertExists()
        textNode(themeSystem).assertIsFocused()
        railNode(navSettings).assertIsSelected()
    }

    private fun establishRailControllerTarget(label: String, confinedKeyCode: Int) {
        when (label) {
            navHome -> {
                pressDpad(KeyEvent.KEYCODE_DPAD_UP)
                railNode(navAddModules).assertIsFocused()
                pressDpad(KeyEvent.KEYCODE_DPAD_UP)
            }
            navAddModules -> Unit
            else -> error("Unsupported productive rail setup target: $label")
        }
        controllerHint().assertExists()
        railNode(label).assertIsFocused()
        pressDpad(confinedKeyCode)
        controllerHint().assertExists()
        railNode(label).assertIsFocused()
    }

    private fun establishContentControllerTarget(text: String, confinedKeyCode: Int) {
        requestKeyboardInputModeForFocusSetup()
        textNode(text).requestFocus()
        composeRule.waitForIdle()
        textNode(text).assertIsFocused()
        pressDpad(confinedKeyCode)
        controllerHint().assertExists()
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

    private fun touchRailAndAssertContext(label: String) {
        tapRail(label)
        touchHint().assertExists()
        railNode(label).assertIsSelected()
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

    private data class DpadFocusTimingProbe(
        val inputModeBeforeDown: InputMode,
        val inputModeAfterDown: InputMode,
        val inputModeAfterUp: InputMode,
        val settingsFocusedAfterDown: Boolean,
        val homeFocusedAfterDown: Boolean,
        val settingsFocusedAfterUp: Boolean,
        val homeFocusedAfterUp: Boolean,
        val traceAfterDown: List<String>,
        val traceAfterUp: List<String>,
    )

    private fun pressDpadWithFocusTimingProbe(keyCode: Int): DpadFocusTimingProbe {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val inputModeBeforeDown = currentInputMode()
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
            InputDevice.SOURCE_DPAD,
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
            InputDevice.SOURCE_DPAD,
        )

        check(instrumentation.uiAutomation.injectInputEvent(down, true))
        composeRule.waitForIdle()
        val inputModeAfterDown = currentInputMode()
        val settingsFocusedAfterDown =
            runCatching { railNode(navSettings).assertIsFocused() }.isSuccess
        val homeFocusedAfterDown = runCatching { railNode(navHome).assertIsFocused() }.isSuccess
        val traceAfterDown = focusTrace.toList()

        check(instrumentation.uiAutomation.injectInputEvent(up, true))
        composeRule.waitForIdle()
        return DpadFocusTimingProbe(
            inputModeBeforeDown = inputModeBeforeDown,
            inputModeAfterDown = inputModeAfterDown,
            inputModeAfterUp = currentInputMode(),
            settingsFocusedAfterDown = settingsFocusedAfterDown,
            homeFocusedAfterDown = homeFocusedAfterDown,
            settingsFocusedAfterUp =
                runCatching { railNode(navSettings).assertIsFocused() }.isSuccess,
            homeFocusedAfterUp = runCatching { railNode(navHome).assertIsFocused() }.isSuccess,
            traceAfterDown = traceAfterDown,
            traceAfterUp = focusTrace.toList(),
        )
    }

    private fun emitDpadProbe(
        inputModeBeforeTouch: InputMode,
        inputModeAfterTouch: InputMode,
        probe: DpadFocusTimingProbe,
    ) {
        val traceMessage = buildString {
            appendLine("CAREPAD_TOUCH_DPAD_PROBE_V1")
            appendLine(
                "INPUT_MODE beforeTouch=$inputModeBeforeTouch afterTouch=$inputModeAfterTouch " +
                    "beforeDown=${probe.inputModeBeforeDown} afterDown=${probe.inputModeAfterDown} " +
                    "afterUp=${probe.inputModeAfterUp}"
            )
            appendLine("TRACE_AFTER_DOWN")
            probe.traceAfterDown.forEach(::appendLine)
            appendLine(
                "PHYSICAL_AFTER_DOWN settings=${probe.settingsFocusedAfterDown} " +
                    "home=${probe.homeFocusedAfterDown}"
            )
            appendLine("TRACE_AFTER_UP_DELTA")
            probe.traceAfterUp.drop(probe.traceAfterDown.size).forEach(::appendLine)
            append(
                "PHYSICAL_AFTER_UP settings=${probe.settingsFocusedAfterUp} " +
                    "home=${probe.homeFocusedAfterUp}"
            )
        }
        println(traceMessage)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("stream", traceMessage) },
        )
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
