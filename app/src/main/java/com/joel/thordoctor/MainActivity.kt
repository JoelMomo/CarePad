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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import carepad.contracts.CarePadHostNavigation
import com.joel.thordoctor.ui.CarePadSettingsScreen
import com.joel.thordoctor.ui.CarePadShellScreen
import com.joel.thordoctor.ui.theme.ThorDoctorTheme

class MainActivity : ComponentActivity() {
    private val requestedDestination = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        readHostNavigationIntent(intent)

        setContent {
            ThorDoctorRoot(requestedDestination)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readHostNavigationIntent(intent)
    }

    private fun readHostNavigationIntent(intent: Intent?) {
        if (intent?.action != CarePadHostNavigation.ACTION_OPEN_DESTINATION) return
        val destination = intent.getStringExtra(CarePadHostNavigation.EXTRA_DESTINATION)
        if (CarePadHostNavigation.isKnownDestination(destination)) {
            requestedDestination.value = destination
        }
    }
}

@Composable
private fun ThorDoctorRoot(requestedDestination: MutableState<String?>) {
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
                requestedDestination = requestedDestination.value,
                onDestinationRequestConsumed = { requestedDestination.value = null },
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
    requestedDestination: String?,
    onDestinationRequestConsumed: () -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
) {
    CarePadShellScreen(
        onThemeModeChange = onThemeModeChange,
        requestedDestination = requestedDestination,
        onDestinationRequestConsumed = onDestinationRequestConsumed,
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
