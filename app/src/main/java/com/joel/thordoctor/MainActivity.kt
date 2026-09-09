package com.joel.thordoctor

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.joel.thordoctor.ui.CarePadSettingsScreen
import com.joel.thordoctor.ui.CarePadShellScreen
import com.joel.thordoctor.ui.theme.ThorDoctorTheme

class MainActivity : ComponentActivity() {
    private var rawKeyHandler: ((KeyEvent) -> Boolean)? = null
    private var rawMotionHandler: ((MotionEvent) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ThorDoctorRoot(
                onRawInputHandlersChanged = { keyHandler, motionHandler ->
                    rawKeyHandler = keyHandler
                    rawMotionHandler = motionHandler
                }
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        if (rawKeyHandler?.invoke(event) == true) true else super.dispatchKeyEvent(event)

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean =
        if (rawMotionHandler?.invoke(event) == true) true else super.dispatchGenericMotionEvent(event)

    override fun onDestroy() {
        rawKeyHandler = null
        rawMotionHandler = null
        super.onDestroy()
    }
}

@Composable
private fun ThorDoctorRoot(
    onRawInputHandlersChanged: (
        ((KeyEvent) -> Boolean)?,
        ((MotionEvent) -> Boolean)?,
    ) -> Unit,
) {
    val context = LocalContext.current

    var themeMode by remember {
        mutableStateOf(AppPreferences.getThemeMode(context))
    }

    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

    ThorDoctorTheme(darkTheme = darkTheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            ThorDoctorApp(
                themeMode = themeMode,
                onThemeModeChange = { mode ->
                    AppPreferences.setThemeMode(context, mode)
                    themeMode = mode
                },
                onRawInputHandlersChanged = onRawInputHandlersChanged,
            )
        }
    }
}

@Composable
private fun ThorDoctorApp(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onRawInputHandlersChanged: (
        ((KeyEvent) -> Boolean)?,
        ((MotionEvent) -> Boolean)?,
    ) -> Unit,
) {
    CarePadShellScreen(
        onThemeModeChange = onThemeModeChange,
        onRawInputHandlersChanged = onRawInputHandlersChanged,
        settingsContent = {
                _,
                onThemeFocusChanged,
                onThemeTouched,
                themeFocusRequesters,
            ->
            CarePadSettingsScreen(
                themeMode = themeMode,
                onThemeModeChange = { mode ->
                    onThemeTouched(mode)
                    onThemeModeChange(mode)
                },
                onControllerThemeFocusChanged = onThemeFocusChanged,
                controllerFocusRequesters = themeFocusRequesters,
            )
        }
    )
}
