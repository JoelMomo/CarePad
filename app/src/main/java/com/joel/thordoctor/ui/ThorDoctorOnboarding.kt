package com.joel.thordoctor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.joel.thordoctor.PermissionManager
import com.joel.thordoctor.R

@Composable
fun PermissionSetupScreen(
    status: PermissionManager.Status,
    onUsagePermission: () -> Unit,
    onFilesPermission: () -> Unit,
    onNotificationPermission: () -> Unit,
    onContinue: () -> Unit
) {
    val continueClick = rememberCozyClick(onContinue)

    SetupScreen(
        step = 1,
        title = stringResource(R.string.initial_setup),
        description = stringResource(R.string.permission_intro),
        accentColor = MaterialTheme.colorScheme.primary,
        accentContainer = MaterialTheme.colorScheme.primaryContainer
    ) {
        PermissionSetupCard(
            icon = Icons.Rounded.Apps,
            title = stringResource(R.string.permission_usage_title),
            description = stringResource(R.string.permission_usage_description),
            granted = status.usageAccess,
            required = false,
            buttonText = stringResource(R.string.grant_access),
            onClick = onUsagePermission
        )

        PermissionSetupCard(
            icon = Icons.Rounded.Storage,
            title = stringResource(R.string.permission_files_title),
            description = stringResource(R.string.permission_files_description),
            granted = status.allFilesAccess,
            required = false,
            buttonText = stringResource(R.string.grant_access),
            onClick = onFilesPermission
        )

        PermissionSetupCard(
            icon = Icons.Rounded.Notifications,
            title = stringResource(R.string.permission_notifications_title),
            description = stringResource(R.string.permission_notifications_description),
            granted = status.notifications,
            required = false,
            buttonText = stringResource(R.string.allow_notifications),
            onClick = onNotificationPermission
        )

        Button(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(20.dp),
            onClick = continueClick
        ) {
            Text(
                text = if (status.notifications) {
                    stringResource(R.string.continue_button)
                } else {
                    stringResource(R.string.continue_without_notifications)
                },
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun StorageSetupScreen(
    folderSelected: Boolean,
    folderName: String?,
    onSelectFolder: () -> Unit,
    onContinue: () -> Unit
) {
    val selectFolderClick = rememberCozyClick(onSelectFolder)
    val continueClick = rememberCozyClick(onContinue)

    SetupScreen(
        step = 2,
        title = stringResource(R.string.storage_setup_title),
        description = stringResource(R.string.storage_setup_intro),
        accentColor = MaterialTheme.colorScheme.secondary,
        accentContainer = MaterialTheme.colorScheme.secondaryContainer
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                        modifier = Modifier.size(50.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (folderSelected) {
                                    Icons.Rounded.CheckCircle
                                } else {
                                    Icons.Rounded.Folder
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (folderSelected) {
                                stringResource(R.string.storage_folder_selected)
                            } else {
                                stringResource(R.string.storage_folder_required)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )

                        if (folderSelected && folderName != null) {
                            Text(
                                text = folderName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f)
                            )
                        }
                    }
                }

                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    onClick = selectFolderClick
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.select_diagnostic_folder))
                }
            }
        }

        if (folderSelected) {
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(20.dp),
                onClick = continueClick
            ) {
                Text(
                    text = stringResource(R.string.continue_button),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun GameLibrarySetupScreen(
    folderSelected: Boolean,
    folderName: String?,
    gameCount: Int,
    scanning: Boolean,
    onSelectFolder: () -> Unit,
    onScan: () -> Unit,
    onContinue: () -> Unit,
    onSkip: () -> Unit
) {
    val selectFolderClick = rememberCozyClick(onSelectFolder)
    val scanClick = rememberCozyClick(onScan)
    val continueClick = rememberCozyClick(onContinue)
    val skipClick = rememberCozyClick(onSkip)

    SetupScreen(
        step = 3,
        title = stringResource(R.string.game_library_setup_title),
        description = stringResource(R.string.game_library_setup_intro),
        accentColor = MaterialTheme.colorScheme.tertiary,
        accentContainer = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(30.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                        modifier = Modifier.size(50.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (folderSelected) {
                                    Icons.Rounded.CheckCircle
                                } else {
                                    Icons.Rounded.SportsEsports
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = folderName ?: stringResource(R.string.game_folder_not_selected),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )

                        if (folderSelected) {
                            Text(
                                text = "${stringResource(R.string.games_found)}: $gameCount",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.72f)
                            )
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.game_library_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.74f)
                )

                FilledTonalButton(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    onClick = selectFolderClick
                ) {
                    Icon(Icons.Rounded.Folder, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (folderSelected) {
                            stringResource(R.string.change_game_folder)
                        } else {
                            stringResource(R.string.select_game_folder)
                        }
                    )
                }

                if (folderSelected) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(18.dp),
                        enabled = !scanning,
                        onClick = scanClick
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.scan_games))
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.game_library_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (folderSelected) {
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(20.dp),
                onClick = continueClick
            ) {
                Text(
                    text = stringResource(R.string.continue_button),
                    fontWeight = FontWeight.Bold
                )
            }
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            onClick = skipClick
        ) {
            Text(stringResource(R.string.skip_for_now))
        }
    }
}

@Composable
private fun SetupScreen(
    step: Int,
    title: String,
    description: String,
    accentColor: Color,
    accentContainer: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(32.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = accentContainer),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(58.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)
                ) {}

                Column(
                    modifier = Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = stringResource(R.string.app_subtitle),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.76f)
                        ) {
                            Text(
                                text = "$step / 3",
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                color = accentColor,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    Spacer(Modifier.size(4.dp))

                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold
                    )

                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        content()
    }
}

@Composable
private fun PermissionSetupCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    required: Boolean,
    buttonText: String,
    onClick: () -> Unit
) {
    val cozyClick = rememberCozyClick(onClick)
    val containerColor = if (granted) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
                    modifier = Modifier.size(46.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (granted) Icons.Rounded.CheckCircle else icon,
                            contentDescription = null,
                            tint = if (granted) {
                                MaterialTheme.colorScheme.secondary
                            } else {
                                MaterialTheme.colorScheme.primary
                            }
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = if (granted) {
                            stringResource(R.string.permission_granted)
                        } else if (required) {
                            stringResource(R.string.permission_required)
                        } else {
                            stringResource(R.string.permission_optional)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (granted) {
                            MaterialTheme.colorScheme.secondary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            Text(
                text = description,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!granted) {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    onClick = cozyClick
                ) {
                    Text(buttonText)
                }
            }
        }
    }
}
