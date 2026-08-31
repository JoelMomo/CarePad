package com.joel.thordoctor

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.joel.thordoctor.core.diagnostics.CoreDiagnosticStorage
import com.joel.thordoctor.ui.CarePadSettingsScreen
import com.joel.thordoctor.ui.CarePadShellScreen
import com.joel.thordoctor.ui.GameLibrarySetupScreen
import com.joel.thordoctor.ui.PermissionSetupScreen
import com.joel.thordoctor.ui.StorageSetupScreen
import com.joel.thordoctor.ui.theme.ThorDoctorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CUE_BIN_QA_LOG_TAG = "CarePadCueBin"

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
    val scope = rememberCoroutineScope()

    var onboardingComplete by remember {
        mutableStateOf(AppPreferences.isOnboardingComplete(context))
    }
    var storageOnboardingComplete by remember {
        mutableStateOf(AppPreferences.isStorageOnboardingComplete(context))
    }
    var gameLibraryOnboardingComplete by remember {
        mutableStateOf(AppPreferences.isGameLibraryOnboardingComplete(context))
    }
    var permissionStatus by remember {
        mutableStateOf(PermissionManager.status(context))
    }
    var diagnosticFolderName by remember {
        mutableStateOf(CoreDiagnosticStorage.folderDisplayName(context))
    }
    var hasValidDiagnosticFolder by remember {
        mutableStateOf(CoreDiagnosticStorage.hasValidCustomFolder(context))
    }
    var gameFolderName by remember {
        mutableStateOf(GameLibraryStorage.folderDisplayName(context))
    }
    var hasValidGameFolder by remember {
        mutableStateOf(GameLibraryStorage.hasValidRootFolder(context))
    }
    var gameCount by remember {
        mutableStateOf(GameLibraryStorage.cachedGameCount(context))
    }
    var gameScanInProgress by remember {
        mutableStateOf(false)
    }

    fun scanGames() {
        if (gameScanInProgress) {
            return
        }

        val scanRootUri =
            GameLibraryStorage.rootFolderUri(context)

        val validFolder =
            scanRootUri != null &&
                GameLibraryStorage.hasValidRootFolder(context)

        hasValidGameFolder = validFolder

        if (!validFolder || scanRootUri == null) {
            gameFolderName = null
            gameCount = 0
            return
        }

        gameScanInProgress = true

        scope.launch {
            try {
                val (result, cueBinDiagnostics) =
                    withContext(Dispatchers.IO) {
                        val scanResult =
                            GameLibraryStorage.scan(context)

                        val diagnosticResult =
                            runCatching {
                                GameLibraryCueBinDiagnostics.evaluate(
                                    context = context,
                                    rootUri = scanRootUri,
                                )
                            }
                                .getOrNull()

                        scanResult to diagnosticResult
                    }

                if (
                    GameLibraryStorage.rootFolderUri(context) ==
                    scanRootUri
                ) {
                    gameCount = result.gameCount
                    gameFolderName =
                        GameLibraryStorage.folderDisplayName(context)
                    hasValidGameFolder =
                        GameLibraryStorage.hasValidRootFolder(context)

                    if (cueBinDiagnostics != null) {
                        Log.i(
                            CUE_BIN_QA_LOG_TAG,
                            GameLibraryCueBinDiagnostics.qaLogMessage(
                                cueBinDiagnostics
                            )
                        )
                    } else {
                        Log.w(
                            CUE_BIN_QA_LOG_TAG,
                            "evaluation_failed"
                        )
                    }
                }

            } finally {
                if (
                    GameLibraryStorage.rootFolderUri(context) ==
                    scanRootUri
                ) {
                    gameScanInProgress = false
                }
            }
        }
    }

    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {
            permissionStatus = PermissionManager.status(context)
        }

    val diagnosticFolderLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                val success =
                    CoreDiagnosticStorage.setCustomFolder(
                        context,
                        uri
                    )

                if (success) {
                    diagnosticFolderName =
                        CoreDiagnosticStorage.folderDisplayName(context)
                    hasValidDiagnosticFolder = true
                }
            }
        }

    val gameFolderLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                val success =
                    GameLibraryStorage.setRootFolder(
                        context,
                        uri
                    )

                if (success) {
                    gameFolderName =
                        GameLibraryStorage.folderDisplayName(context)
                    hasValidGameFolder = true
                    gameCount = 0
                    gameScanInProgress = false
                }
            }
        }

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

        while (true) {
            permissionStatus = PermissionManager.status(context)
            hasValidDiagnosticFolder =
                CoreDiagnosticStorage.hasValidCustomFolder(context)
            hasValidGameFolder =
                GameLibraryStorage.hasValidRootFolder(context)
            gameCount =
                GameLibraryStorage.cachedGameCount(context)

            if (hasValidDiagnosticFolder) {
                diagnosticFolderName =
                    CoreDiagnosticStorage.folderDisplayName(context)
            }

            gameFolderName =
                if (hasValidGameFolder) {
                    GameLibraryStorage.folderDisplayName(context)
                } else {
                    null
                }

            delay(750)
        }
    }

    val showPermissionSetup =
        !onboardingComplete ||
            !permissionStatus.requiredGranted

    if (showPermissionSetup) {
        PermissionSetupScreen(
            status = permissionStatus,
            onUsagePermission = {
                PermissionManager.openUsageAccessSettings(context)
            },
            onFilesPermission = {
                PermissionManager.openAllFilesAccessSettings(context)
            },
            onNotificationPermission = {
                if (
                    Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.TIRAMISU
                ) {
                    notificationPermissionLauncher.launch(
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                }
            },
            onContinue = {
                AppPreferences.setOnboardingComplete(
                    context,
                    true
                )
                onboardingComplete = true
            }
        )
        return
    }

    val showStorageSetup =
        !storageOnboardingComplete ||
            !hasValidDiagnosticFolder

    if (showStorageSetup) {
        StorageSetupScreen(
            folderSelected = hasValidDiagnosticFolder,
            folderName =
                if (hasValidDiagnosticFolder) {
                    diagnosticFolderName
                } else {
                    null
                },
            onSelectFolder = {
                diagnosticFolderLauncher.launch(null)
            },
            onContinue = {
                if (hasValidDiagnosticFolder) {
                    AppPreferences.setStorageOnboardingComplete(
                        context,
                        true
                    )
                    storageOnboardingComplete = true
                }
            }
        )
        return
    }

    if (!gameLibraryOnboardingComplete) {
        GameLibrarySetupScreen(
            folderSelected = hasValidGameFolder,
            folderName = gameFolderName,
            gameCount = gameCount,
            scanning = gameScanInProgress,
            onSelectFolder = {
                gameFolderLauncher.launch(null)
            },
            onScan = {
                scanGames()
            },
            onContinue = {
                AppPreferences.setGameLibraryOnboardingComplete(
                    context,
                    true
                )
                gameLibraryOnboardingComplete = true
            },
            onSkip = {
                AppPreferences.setGameLibraryOnboardingComplete(
                    context,
                    true
                )
                gameLibraryOnboardingComplete = true
            }
        )
        return
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
