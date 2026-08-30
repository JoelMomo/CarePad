package com.joel.thordoctor.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Bundle
import android.os.SystemClock
import android.test.mock.MockPackageManager
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import carepad.contracts.CarePadModuleActions
import carepad.contracts.CarePadModuleIds
import carepad.contracts.CarePadModuleMetadataKeys
import carepad.contracts.CarePadProtocol
import com.joel.thordoctor.AppThemeMode
import com.joel.thordoctor.R
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_PERFORMANCE_MODULE_PACKAGE = "dev.carepad.test.performance"

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

        val shellContext = SingleModuleContext(composeRule.activity)
        composeRule.setContent {
            CompositionLocalProvider(LocalContext provides shellContext) {
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
        }
        composeRule.waitForIdle()
    }

    @Test
    fun railTouchesMakeFirstDpadEffectiveAndKeepRailConfined() {
        establishSettingsRailControllerContext()
        touchRailAndAssertContext(navSettings)
        pressDpad(KeyEvent.KEYCODE_DPAD_DOWN)
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
        val performance = composeRule.activity.getString(R.string.carepad_module_performance)
        val uninstall = composeRule.activity.getString(R.string.carepad_uninstall_module)

        moduleNode(performance).assertExists()
        moduleNode(performance).performTouchInput { longClick() }
        composeRule.waitForIdle()
        touchHint().assertExists()
        uninstallNode(uninstall).assertExists()

        // Keep the reducer in TOUCH while the physically focused product target is the
        // Details-only uninstall control. Android back then collapses Details through the
        // production handleBack -> expandedPackage -> ContentTargetsChanged path.
        uninstallNode(uninstall).requestFocus()
        composeRule.waitForIdle()
        uninstallNode(uninstall).assertIsFocused()
        touchHint().assertExists()
        railNode(navHome).assertIsSelected()

        composeRule.runOnUiThread {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitForIdle()
        uninstallNode(uninstall).assertDoesNotExist()
        moduleNode(performance).assertExists()
        touchHint().assertExists()
        railNode(navHome).assertIsSelected()

        // B: this same first L1 must reach the CarePad reducer and switch modality.
        pressL1()
        controllerHint().assertExists()

        // C: after the signalled target disappearance, that first L1 must complete the
        // CONTENT -> RAIL transition and physically focus the selected Home rail target.
        railNode(navHome).assertIsFocused()
        railNode(navHome).assertIsSelected()
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
        railNode(navSettings).requestFocus()
        composeRule.waitForIdle()
        railNode(navSettings).assertIsFocused()

        // The keyboard input-mode change above is test-only platform setup. Reducer modality
        // remains untouched until this real controller event reaches the production pipeline.
        // L1 aligns the logical zone with the physically observed rail target.
        pressL1()
        controllerHint().assertExists()
        railNode(navSettings).assertIsFocused()

        // Select Settings with a real controller action, then use the lower rail edge as a
        // confined controller-history event that must not move focus or change zones.
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
        requestKeyboardInputModeForFocusSetup()
        railNode(label).requestFocus()
        composeRule.waitForIdle()
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

    private fun moduleNode(label: String) = composeRule.onNode(
        matcher = hasClickAction() and hasAnyDescendant(hasText(label)),
        useUnmergedTree = true,
    )

    private fun uninstallNode(label: String) = composeRule.onNode(
        matcher = hasClickAction() and hasAnyDescendant(hasText(label)),
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
}

private class SingleModuleContext(base: Context) : ContextWrapper(base) {
    private val packageManagerOverride = SingleModulePackageManager(
        delegate = base.packageManager,
        hostPackageName = base.packageName,
    )

    override fun getPackageManager(): PackageManager = packageManagerOverride
}

@Suppress("DEPRECATION")
private class SingleModulePackageManager(
    private val delegate: PackageManager,
    private val hostPackageName: String,
) : MockPackageManager() {
    private val moduleResolveInfo = ResolveInfo().apply {
        activityInfo = ActivityInfo().apply {
            packageName = TEST_PERFORMANCE_MODULE_PACKAGE
            name = "$TEST_PERFORMANCE_MODULE_PACKAGE.EntryActivity"
            metaData = Bundle().apply {
                putString(CarePadModuleMetadataKeys.MODULE_ID, CarePadModuleIds.PERFORMANCE)
                putInt(CarePadModuleMetadataKeys.PROTOCOL_MIN, CarePadProtocol.VERSION)
                putInt(CarePadModuleMetadataKeys.PROTOCOL_MAX, CarePadProtocol.VERSION)
                putString(CarePadModuleMetadataKeys.CAPABILITIES, "")
            }
        }
    }

    override fun queryIntentActivities(intent: Intent, flags: Int): MutableList<ResolveInfo> =
        if (intent.action == CarePadModuleActions.MODULE) {
            mutableListOf(moduleResolveInfo)
        } else {
            delegate.queryIntentActivities(intent, flags).toMutableList()
        }

    override fun getPackageInfo(packageName: String, flags: Int): PackageInfo =
        delegate.getPackageInfo(
            if (packageName == TEST_PERFORMANCE_MODULE_PACKAGE) hostPackageName else packageName,
            flags,
        )
}
