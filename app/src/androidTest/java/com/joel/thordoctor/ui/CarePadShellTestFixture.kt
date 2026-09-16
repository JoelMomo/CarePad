package com.joel.thordoctor.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import com.joel.thordoctor.AppThemeMode

/** Focus-test fixture that does not exercise raw internal-module input forwarding. */
@Composable
internal fun CarePadShellScreen(
    onThemeModeChange: (AppThemeMode) -> Unit,
    settingsContent: @Composable (
        onBack: () -> Unit,
        onThemeFocusChanged: (AppThemeMode, Boolean) -> Unit,
        onThemeTouched: (AppThemeMode) -> Unit,
        themeFocusRequesters: Map<AppThemeMode, FocusRequester>,
    ) -> Unit,
) {
    CarePadShellScreen(
        onThemeModeChange = onThemeModeChange,
        onRawInputHandlersChanged = { _, _ -> },
        settingsContent = settingsContent,
    )
}
