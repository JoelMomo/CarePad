package com.joel.thordoctor.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SettingsBrightness
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.joel.thordoctor.AppThemeMode
import com.joel.thordoctor.PermissionManager
import com.joel.thordoctor.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThorDoctorSettingsScreen(
    status: PermissionManager.Status,
    themeMode: AppThemeMode,
    diagnosticFolderName: String,
    gameFolderName: String?,
    gameCount: Int,
    gameScanInProgress: Boolean,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onBack: () -> Unit,
    onChangeDiagnosticFolder: () -> Unit,
    onChangeGameFolder: () -> Unit,
    onScanGames: () -> Unit,
    onUsagePermission: () -> Unit,
    onFilesPermission: () -> Unit,
    onNotificationPermission: () -> Unit
) {
    val context = LocalContext.current
    val backClick = rememberCozyClick(onBack)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                title = {
                    Text(
                        text = stringResource(R.string.settings),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                navigationIcon = {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        IconButton(onClick = backClick) {
                            Icon(
                                imageVector = Icons.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(
                start = 18.dp,
                end = 18.dp,
                top = 10.dp,
                bottom = 30.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                SettingsSectionTitle(
                    icon = Icons.Rounded.SettingsBrightness,
                    title = stringResource(R.string.appearance),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        ThemeChoiceRow(
                            icon = Icons.Rounded.SettingsBrightness,
                            title = stringResource(R.string.theme_system),
                            selected = themeMode == AppThemeMode.SYSTEM,
                            onClick = { onThemeModeChange(AppThemeMode.SYSTEM) }
                        )
                        ThemeChoiceRow(
                            icon = Icons.Rounded.LightMode,
                            title = stringResource(R.string.theme_light),
                            selected = themeMode == AppThemeMode.LIGHT,
                            onClick = { onThemeModeChange(AppThemeMode.LIGHT) }
                        )
                        ThemeChoiceRow(
                            icon = Icons.Rounded.DarkMode,
                            title = stringResource(R.string.theme_dark),
                            selected = themeMode == AppThemeMode.DARK,
                            onClick = { onThemeModeChange(AppThemeMode.DARK) }
                        )
                    }
                }
            }

            item {
                SettingsSectionTitle(
                    icon = Icons.Rounded.Folder,
                    title = stringResource(R.string.diagnostics),
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = stringResource(R.string.current_folder),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.7f)
                            )
                            Text(
                                text = diagnosticFolderName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }

                        Text(
                            text = stringResource(R.string.diagnostic_folder_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.72f)
                        )

                        CozySettingsActionButton(
                            text = stringResource(R.string.change_folder),
                            icon = Icons.Rounded.Folder,
                            tint = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            onClick = onChangeDiagnosticFolder
                        )
                    }
                }
            }

            item {
                SettingsSectionTitle(
                    icon = Icons.Rounded.SportsEsports,
                    title = stringResource(R.string.game_library),
                    tint = MaterialTheme.colorScheme.tertiary
                )
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = stringResource(R.string.game_folder),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.70f)
                            )
                            Text(
                                text = gameFolderName
                                    ?: stringResource(R.string.game_folder_not_selected),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            if (gameFolderName != null) {
                                Text(
                                    text = "${stringResource(R.string.games_found)}: $gameCount",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.72f)
                                )
                            }
                        }

                        Text(
                            text = stringResource(R.string.game_library_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.72f)
                        )

                        CozySettingsActionButton(
                            text = if (gameFolderName == null) {
                                stringResource(R.string.select_game_folder)
                            } else {
                                stringResource(R.string.change_game_folder)
                            },
                            icon = Icons.Rounded.Folder,
                            tint = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            onClick = onChangeGameFolder
                        )

                        if (gameFolderName != null) {
                            CozySettingsActionButton(
                                text = stringResource(R.string.scan_games),
                                icon = Icons.Rounded.Refresh,
                                tint = MaterialTheme.colorScheme.tertiary,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                enabled = !gameScanInProgress,
                                onClick = onScanGames
                            )
                        }
                    }
                }
            }

            item {
                SettingsSectionTitle(
                    icon = Icons.Rounded.Apps,
                    title = stringResource(R.string.permissions),
                    tint = MaterialTheme.colorScheme.secondary
                )
            }

            item {
                PermissionSettingsCard(
                    icon = Icons.Rounded.Apps,
                    title = stringResource(R.string.permission_usage_title),
                    granted = status.usageAccess,
                    onClick = onUsagePermission
                )
            }

            item {
                PermissionSettingsCard(
                    icon = Icons.Rounded.Storage,
                    title = stringResource(R.string.permission_files_title),
                    granted = status.allFilesAccess,
                    onClick = onFilesPermission
                )
            }

            item {
                PermissionSettingsCard(
                    icon = Icons.Rounded.Notifications,
                    title = stringResource(R.string.permission_notifications_title),
                    granted = status.notifications,
                    onClick = onNotificationPermission
                )
            }

            item {
                SettingsSectionTitle(
                    icon = Icons.Rounded.Info,
                    title = stringResource(R.string.information),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(13.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = stringResource(R.string.app_description),
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.66f)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                SettingsInfoRow(
                                    label = stringResource(R.string.version),
                                    value = getAppVersionName(context)
                                )
                                SettingsInfoRow(
                                    label = stringResource(R.string.device),
                                    value = getDeviceDisplayName()
                                )
                                SettingsInfoRow(
                                    label = stringResource(R.string.android),
                                    value = Build.VERSION.RELEASE
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
private fun CozySettingsActionButton(
    text: String,
    icon: ImageVector,
    tint: Color,
    contentColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val press = rememberCozyPressState()

    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .cozyPress(press.scale),
        interactionSource = press.interactionSource,
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = tint.copy(alpha = 0.28f),
            contentColor = contentColor,
            disabledContainerColor = tint.copy(alpha = 0.10f),
            disabledContentColor = contentColor.copy(alpha = 0.42f)
        )
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.size(8.dp))
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ThemeChoiceRow(
    icon: ImageVector,
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val cozyClick = rememberCozyClick(onClick)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = cozyClick),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsIcon(
                icon = icon,
                containerColor = if (selected) {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.74f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium
            )
            RadioButton(
                selected = selected,
                onClick = cozyClick
            )
        }
    }
}

@Composable
private fun PermissionSettingsCard(
    icon: ImageVector,
    title: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    val cozyClick = rememberCozyClick(onClick)
    val containerColor = if (granted) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val contentColor = if (granted) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    val actionTint = if (granted) {
        MaterialTheme.colorScheme.secondary
    } else {
        MaterialTheme.colorScheme.tertiary
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsIcon(
                icon = if (granted) Icons.Rounded.CheckCircle else icon,
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
                contentColor = actionTint
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.ExtraBold,
                    color = contentColor
                )
                Text(
                    text = if (granted) {
                        stringResource(R.string.permission_granted)
                    } else {
                        stringResource(R.string.permission_pending)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (granted) {
                        MaterialTheme.colorScheme.secondary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }

            FilledTonalButton(
                onClick = cozyClick,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = actionTint.copy(alpha = 0.24f),
                    contentColor = contentColor
                )
            ) {
                Text(
                    text = stringResource(R.string.manage_permission),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(
    icon: ImageVector,
    title: String,
    tint: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier.padding(start = 4.dp, top = 6.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = tint.copy(alpha = 0.16f),
            modifier = Modifier.size(34.dp)
        ) {
            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(19.dp)
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun SettingsIcon(
    icon: ImageVector,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        shape = CircleShape,
        color = containerColor,
        modifier = Modifier.size(42.dp)
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(21.dp)
            )
        }
    }
}

@Composable
private fun SettingsInfoRow(
    label: String,
    value: String
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            fontWeight = FontWeight.ExtraBold
        )
    }
}