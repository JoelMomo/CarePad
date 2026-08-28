package com.joel.thordoctor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.joel.thordoctor.AppThemeMode
import com.joel.thordoctor.R

internal enum class CarePadGlobalSetting {
    THEME,
}

internal fun carePadGlobalSettings(): List<CarePadGlobalSetting> =
    listOf(CarePadGlobalSetting.THEME)

@Composable
fun CarePadSettingsScreen(
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onControllerThemeFocusChanged: (AppThemeMode, Boolean) -> Unit,
    controllerFocusRequesters: Map<AppThemeMode, FocusRequester>,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        carePadGlobalSettings().forEach { setting ->
            when (setting) {
                CarePadGlobalSetting.THEME -> {
                    item {
                        Text(
                            text = stringResource(R.string.appearance),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(24.dp),
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                CarePadThemeChoice(
                                    icon = Icons.Rounded.SettingsBrightness,
                                    title = stringResource(R.string.theme_system),
                                    selected = themeMode == AppThemeMode.SYSTEM,
                                    focusRequester =
                                        controllerFocusRequesters.getValue(AppThemeMode.SYSTEM),
                                    onFocusChanged = { focused ->
                                        onControllerThemeFocusChanged(
                                            AppThemeMode.SYSTEM,
                                            focused,
                                        )
                                    },
                                    onClick = { onThemeModeChange(AppThemeMode.SYSTEM) },
                                )
                                CarePadThemeChoice(
                                    icon = Icons.Rounded.LightMode,
                                    title = stringResource(R.string.theme_light),
                                    selected = themeMode == AppThemeMode.LIGHT,
                                    focusRequester =
                                        controllerFocusRequesters.getValue(AppThemeMode.LIGHT),
                                    onFocusChanged = { focused ->
                                        onControllerThemeFocusChanged(
                                            AppThemeMode.LIGHT,
                                            focused,
                                        )
                                    },
                                    onClick = { onThemeModeChange(AppThemeMode.LIGHT) },
                                )
                                CarePadThemeChoice(
                                    icon = Icons.Rounded.DarkMode,
                                    title = stringResource(R.string.theme_dark),
                                    selected = themeMode == AppThemeMode.DARK,
                                    focusRequester =
                                        controllerFocusRequesters.getValue(AppThemeMode.DARK),
                                    onFocusChanged = { focused ->
                                        onControllerThemeFocusChanged(
                                            AppThemeMode.DARK,
                                            focused,
                                        )
                                    },
                                    onClick = { onThemeModeChange(AppThemeMode.DARK) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CarePadThemeChoice(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { state -> onFocusChanged(state.isFocused) }
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            )
            RadioButton(
                selected = selected,
                onClick = null,
            )
        }
    }
}
