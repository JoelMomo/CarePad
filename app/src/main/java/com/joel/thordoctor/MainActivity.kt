package com.joel.thordoctor

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.joel.thordoctor.ui.CarePadSettingsScreen
import com.joel.thordoctor.ui.CarePadShellScreen
import com.joel.thordoctor.ui.theme.ThorDoctorTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ThorDoctorRoot()
        }
    }
}

@Composable
private fun ThorDoctorRoot() {
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
                }
            )
        }
    }
}

@Composable
private fun ThorDoctorApp(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (
            !SessionMonitorService.isRunning &&
            SessionMonitorService.hasRecoverableSession(context)
        ) {
            val resumeIntent =
                Intent(
                    context,
                    SessionMonitorService::class.java
                ).apply {
                    action = SessionMonitorService.ACTION_RESUME
                }

            ContextCompat.startForegroundService(
                context,
                resumeIntent
            )
        }
    }

    CarePadShellScreen(
        onThemeModeChange = onThemeModeChange,
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
