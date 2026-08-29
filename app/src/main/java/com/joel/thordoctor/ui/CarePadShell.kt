package com.joel.thordoctor.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.InputDevice
import android.view.KeyEvent as AndroidKeyEvent
import android.view.MotionEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.joel.thordoctor.AppPreferences
import com.joel.thordoctor.AppThemeMode
import com.joel.thordoctor.ControlGlyphProfile
import com.joel.thordoctor.R
import com.joel.thordoctor.modules.host.DiscoveredCarePadModule
import com.joel.thordoctor.modules.host.ModuleManager

internal enum class CarePadDestination {
    HOME,
    ADD_MODULES,
    SETTINGS,
}

internal enum class CarePadInputMethod {
    TOUCH,
    CONTROLLER,
}

internal enum class CarePadFocusZone {
    RAIL,
    CONTENT,
}

internal enum class CarePadRailVisualState {
    EXPANDED,
    COMPACT,
}

internal data class CarePadFocusState(
    val zone: CarePadFocusZone = CarePadFocusZone.CONTENT,
    val lastRailDestination: CarePadDestination? = null,
) {
    fun onRailFocused(destination: CarePadDestination): CarePadFocusState = copy(
        zone = CarePadFocusZone.RAIL,
        lastRailDestination = destination,
    )

    fun onContentFocused(): CarePadFocusState = copy(zone = CarePadFocusZone.CONTENT)
}

internal fun carePadRailVisualState(
    focusState: CarePadFocusState,
): CarePadRailVisualState = when (focusState.zone) {
    CarePadFocusZone.RAIL -> CarePadRailVisualState.EXPANDED
    CarePadFocusZone.CONTENT -> CarePadRailVisualState.COMPACT
}

internal fun carePadRailItemSelected(
    selected: CarePadDestination,
    candidate: CarePadDestination,
): Boolean = selected == candidate

internal fun carePadRailRestoreDestination(
    focusState: CarePadFocusState,
    selectedDestination: CarePadDestination,
): CarePadDestination = focusState.lastRailDestination ?: selectedDestination

private val CarePadRailCompactWidth = 80.dp
private val CarePadRailExpandedWidth = 176.dp
private const val CarePadRailTransitionMillis = 180

internal sealed interface CarePadPrimaryControllerTarget {
    data class Rail(val destination: CarePadDestination) : CarePadPrimaryControllerTarget
    data class Module(val packageName: String) : CarePadPrimaryControllerTarget
    data class Uninstall(val packageName: String) : CarePadPrimaryControllerTarget
    data class Theme(val mode: AppThemeMode) : CarePadPrimaryControllerTarget
    data object None : CarePadPrimaryControllerTarget
}

internal data class CarePadInteractionSnapshot(
    val inputMethod: CarePadInputMethod,
    val destination: CarePadDestination,
    val focusState: CarePadFocusState,
    val lastContentTarget: CarePadPrimaryControllerTarget,
)

internal fun carePadContentTouchTransition(
    snapshot: CarePadInteractionSnapshot,
    touchedTarget: CarePadPrimaryControllerTarget? = null,
): CarePadInteractionSnapshot = snapshot.copy(
    inputMethod = CarePadInputMethod.TOUCH,
    focusState = snapshot.focusState.onContentFocused(),
    lastContentTarget = touchedTarget ?: snapshot.lastContentTarget,
)

internal fun carePadControllerInputTransition(
    snapshot: CarePadInteractionSnapshot,
): CarePadInteractionSnapshot = snapshot.copy(
    inputMethod = CarePadInputMethod.CONTROLLER,
)

internal fun carePadPrimaryControllerTarget(
    focusState: CarePadFocusState,
    destination: CarePadDestination,
    focusedTarget: CarePadPrimaryControllerTarget,
    visiblePackages: Collection<String>,
    expandedPackage: String? = null,
): CarePadPrimaryControllerTarget {
    if (focusState.zone == CarePadFocusZone.RAIL) {
        return if (focusedTarget is CarePadPrimaryControllerTarget.Rail) {
            focusedTarget
        } else {
            CarePadPrimaryControllerTarget.None
        }
    }

    if (focusState.zone != CarePadFocusZone.CONTENT) {
        return CarePadPrimaryControllerTarget.None
    }

    return when (focusedTarget) {
        is CarePadPrimaryControllerTarget.Module -> {
            if (
                destination == CarePadDestination.HOME &&
                focusedTarget.packageName in visiblePackages
            ) {
                focusedTarget
            } else {
                CarePadPrimaryControllerTarget.None
            }
        }

        is CarePadPrimaryControllerTarget.Uninstall -> {
            if (
                destination == CarePadDestination.HOME &&
                focusedTarget.packageName in visiblePackages &&
                expandedPackage == focusedTarget.packageName
            ) {
                focusedTarget
            } else {
                CarePadPrimaryControllerTarget.None
            }
        }

        is CarePadPrimaryControllerTarget.Theme -> {
            if (destination == CarePadDestination.SETTINGS) {
                focusedTarget
            } else {
                CarePadPrimaryControllerTarget.None
            }
        }

        is CarePadPrimaryControllerTarget.Rail,
        CarePadPrimaryControllerTarget.None -> CarePadPrimaryControllerTarget.None
    }
}

internal fun carePadRestoredContentTarget(
    destination: CarePadDestination,
    lastContentTarget: CarePadPrimaryControllerTarget,
    focusedModulePackage: String?,
    visiblePackages: Collection<String>,
    expandedPackage: String? = null,
): CarePadPrimaryControllerTarget = when (destination) {
    CarePadDestination.HOME -> {
        when (lastContentTarget) {
            is CarePadPrimaryControllerTarget.Uninstall -> {
                if (
                    lastContentTarget.packageName in visiblePackages &&
                    expandedPackage == lastContentTarget.packageName
                ) {
                    lastContentTarget
                } else {
                    null
                }
            }

            is CarePadPrimaryControllerTarget.Module -> {
                if (lastContentTarget.packageName in visiblePackages) {
                    lastContentTarget
                } else {
                    null
                }
            }

            else -> null
        } ?: focusedModulePackage
            ?.takeIf { it in visiblePackages }
            ?.let(CarePadPrimaryControllerTarget::Module)
            ?: visiblePackages.firstOrNull()?.let(CarePadPrimaryControllerTarget::Module)
            ?: CarePadPrimaryControllerTarget.None
    }

    CarePadDestination.SETTINGS ->
        (lastContentTarget as? CarePadPrimaryControllerTarget.Theme)
            ?: CarePadPrimaryControllerTarget.Theme(AppThemeMode.SYSTEM)

    CarePadDestination.ADD_MODULES -> CarePadPrimaryControllerTarget.None
}

internal fun carePadDetailsControllerActionAllowed(
    focusState: CarePadFocusState,
    destination: CarePadDestination,
    focusedModulePackage: String?,
    visiblePackages: Collection<String>,
): Boolean =
    focusState.zone == CarePadFocusZone.CONTENT &&
        destination == CarePadDestination.HOME &&
        focusedModulePackage?.let { it in visiblePackages } == true

private data class VisibleModule(
    val module: DiscoveredCarePadModule,
    val presentation: CarePadModulePresentation,
)

private data class ControllerGlyphs(
    val primary: String,
    val details: String,
    val back: String,
    val navigation: String,
    val detailsKeyCode: Int,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun CarePadShellScreen(
    onThemeModeChange: (AppThemeMode) -> Unit,
    settingsContent: @Composable (
        onBack: () -> Unit,
        onThemeFocusChanged: (AppThemeMode, Boolean) -> Unit,
        onThemeTouched: (AppThemeMode) -> Unit,
        themeFocusRequesters: Map<AppThemeMode, FocusRequester>,
    ) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var discovery by remember { mutableStateOf(ModuleManager.discover(context)) }
    var destination by remember { mutableStateOf(CarePadDestination.HOME) }
    var inputMethod by remember { mutableStateOf(CarePadInputMethod.TOUCH) }
    var focusState by remember { mutableStateOf(CarePadFocusState()) }
    var primaryControllerTarget by remember {
        mutableStateOf<CarePadPrimaryControllerTarget>(CarePadPrimaryControllerTarget.None)
    }
    var lastContentControllerTarget by remember {
        mutableStateOf<CarePadPrimaryControllerTarget>(CarePadPrimaryControllerTarget.None)
    }
    var expandedPackage by remember { mutableStateOf<String?>(null) }
    var focusedModulePackage by remember { mutableStateOf<String?>(null) }
    var pendingUninstall by remember { mutableStateOf<VisibleModule?>(null) }

    val glyphProfile = remember { AppPreferences.getControlGlyphProfile(context) }
    val glyphs = remember(glyphProfile) { controllerGlyphs(glyphProfile) }
    val homeListState = rememberLazyListState()

    val visibleModules = remember(discovery.modules) {
        discovery.modules
            .mapNotNull { module ->
                CarePadModulePresentations.forModuleId(module.metadata.moduleId)
                    ?.let { presentation -> VisibleModule(module, presentation) }
            }
            .sortedWith(
                compareBy<VisibleModule> { it.presentation.order }
                    .thenBy { it.module.packageName }
            )
    }
    val visiblePackages = visibleModules.map { it.module.packageName }
    val moduleFocusRequesters = remember(visiblePackages) {
        visiblePackages.associateWith { FocusRequester() }
    }
    val uninstallFocusRequesters = remember(visiblePackages) {
        visiblePackages.associateWith { FocusRequester() }
    }
    val themeFocusRequesters = remember {
        AppThemeMode.entries.associateWith { FocusRequester() }
    }
    val railFocusRequesters = remember {
        CarePadDestination.entries.associateWith { FocusRequester() }
    }
    val contentFallbackRequester = remember { FocusRequester() }

    fun refreshModules() {
        discovery = ModuleManager.discover(context)
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                refreshModules()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    LaunchedEffect(visiblePackages) {
        if (focusedModulePackage?.let { it in visiblePackages } != true) {
            focusedModulePackage = visiblePackages.firstOrNull()
        }
        if (expandedPackage?.let { it !in visiblePackages } == true) {
            expandedPackage = null
        }
        if (
            pendingUninstall?.module?.packageName?.let { it !in visiblePackages } == true
        ) {
            pendingUninstall = null
        }
        when (val target = primaryControllerTarget) {
            is CarePadPrimaryControllerTarget.Module -> {
                if (target.packageName !in visiblePackages) {
                    primaryControllerTarget = CarePadPrimaryControllerTarget.None
                }
            }

            is CarePadPrimaryControllerTarget.Uninstall -> {
                if (target.packageName !in visiblePackages) {
                    primaryControllerTarget = CarePadPrimaryControllerTarget.None
                }
            }

            else -> Unit
        }
        when (val target = lastContentControllerTarget) {
            is CarePadPrimaryControllerTarget.Module -> {
                if (target.packageName !in visiblePackages) {
                    lastContentControllerTarget = CarePadPrimaryControllerTarget.None
                }
            }

            is CarePadPrimaryControllerTarget.Uninstall -> {
                if (target.packageName !in visiblePackages) {
                    lastContentControllerTarget = CarePadPrimaryControllerTarget.None
                }
            }

            else -> Unit
        }
    }

    fun goTo(next: CarePadDestination) {
        expandedPackage = null
        destination = next
    }

    fun handleBack(): Boolean {
        if (expandedPackage != null) {
            expandedPackage = null
            return true
        }
        if (destination != CarePadDestination.HOME) {
            destination = CarePadDestination.HOME
            return true
        }
        return false
    }

    fun currentInteractionSnapshot(): CarePadInteractionSnapshot =
        CarePadInteractionSnapshot(
            inputMethod = inputMethod,
            destination = destination,
            focusState = focusState,
            lastContentTarget = lastContentControllerTarget,
        )

    fun applyInteractionSnapshot(snapshot: CarePadInteractionSnapshot) {
        inputMethod = snapshot.inputMethod
        destination = snapshot.destination
        focusState = snapshot.focusState
        lastContentControllerTarget = snapshot.lastContentTarget
    }

    fun enterTouchContent(touchedTarget: CarePadPrimaryControllerTarget? = null) {
        applyInteractionSnapshot(
            carePadContentTouchTransition(
                snapshot = currentInteractionSnapshot(),
                touchedTarget = touchedTarget,
            )
        )
        primaryControllerTarget = CarePadPrimaryControllerTarget.None
        contentFallbackRequester.requestFocus()
        when (touchedTarget) {
            is CarePadPrimaryControllerTarget.Module ->
                focusedModulePackage = touchedTarget.packageName

            is CarePadPrimaryControllerTarget.Uninstall ->
                focusedModulePackage = touchedTarget.packageName

            else -> Unit
        }
    }

    fun requestContentFocus(): CarePadPrimaryControllerTarget {
        val target = carePadRestoredContentTarget(
            destination = destination,
            lastContentTarget = lastContentControllerTarget,
            focusedModulePackage = focusedModulePackage,
            visiblePackages = visiblePackages,
            expandedPackage = expandedPackage,
        )
        val requester = when (target) {
            is CarePadPrimaryControllerTarget.Module ->
                moduleFocusRequesters[target.packageName]

            is CarePadPrimaryControllerTarget.Uninstall ->
                uninstallFocusRequesters[target.packageName]

            is CarePadPrimaryControllerTarget.Theme ->
                themeFocusRequesters[target.mode]

            is CarePadPrimaryControllerTarget.Rail,
            CarePadPrimaryControllerTarget.None -> null
        }
        (requester ?: contentFallbackRequester).requestFocus()
        return target
    }

    fun activateContentControllerContext(): CarePadPrimaryControllerTarget {
        focusState = focusState.onContentFocused()
        val target = requestContentFocus()
        primaryControllerTarget = target
        return target
    }

    fun toggleFocusZone() {
        if (focusState.zone == CarePadFocusZone.RAIL) {
            activateContentControllerContext()
        } else {
            val railDestination = carePadRailRestoreDestination(
                focusState = focusState,
                selectedDestination = destination,
            )
            focusState = focusState.onRailFocused(railDestination)
            primaryControllerTarget = CarePadPrimaryControllerTarget.Rail(railDestination)
            railFocusRequesters[railDestination]?.requestFocus()
        }
    }

    fun toggleFocusedDetails() {
        if (
            !carePadDetailsControllerActionAllowed(
                focusState = focusState,
                destination = destination,
                focusedModulePackage = focusedModulePackage,
                visiblePackages = visiblePackages,
            )
        ) {
            return
        }
        val packageName = focusedModulePackage ?: return
        expandedPackage = if (expandedPackage == packageName) null else packageName
    }

    BackHandler(enabled = expandedPackage != null || destination != CarePadDestination.HOME) {
        handleBack()
    }

    if (pendingUninstall != null) {
        val item = pendingUninstall ?: return
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = {
                Text(
                    stringResource(
                        R.string.carepad_uninstall_title,
                        stringResource(item.presentation.nameRes),
                    )
                )
            },
            text = { Text(stringResource(R.string.carepad_uninstall_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUninstall = null
                        expandedPackage = null
                        ModuleManager.requestUninstall(context, item.module)
                    }
                ) {
                    Text(stringResource(R.string.carepad_uninstall_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text(stringResource(R.string.carepad_cancel))
                }
            },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .pointerInteropFilter { event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    inputMethod = CarePadInputMethod.TOUCH
                }
                false
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (
                    event.type != KeyEventType.KeyDown ||
                    native.repeatCount != 0 ||
                    !isControllerSource(native.source)
                ) {
                    return@onPreviewKeyEvent false
                }

                val wasTouch = inputMethod == CarePadInputMethod.TOUCH
                val wasContent = focusState.zone == CarePadFocusZone.CONTENT
                applyInteractionSnapshot(
                    carePadControllerInputTransition(currentInteractionSnapshot())
                )

                val restoreContentForFirstInput =
                    wasTouch &&
                        wasContent &&
                        native.keyCode != AndroidKeyEvent.KEYCODE_BUTTON_L1
                if (restoreContentForFirstInput) {
                    activateContentControllerContext()
                }

                val focusDirection = controllerFocusDirection(native.keyCode)
                when {
                    native.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_L1 -> {
                        toggleFocusZone()
                        true
                    }

                    focusDirection != null -> {
                        if (restoreContentForFirstInput) {
                            focusManager.moveFocus(focusDirection)
                            true
                        } else {
                            false
                        }
                    }

                    native.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_B -> handleBack()

                    native.keyCode == glyphs.detailsKeyCode -> {
                        val allowed = carePadDetailsControllerActionAllowed(
                            focusState = focusState,
                            destination = destination,
                            focusedModulePackage = focusedModulePackage,
                            visiblePackages = visiblePackages,
                        )
                        if (allowed) {
                            toggleFocusedDetails()
                        }
                        allowed
                    }

                    native.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_A -> {
                        when (
                            val target = carePadPrimaryControllerTarget(
                                focusState = focusState,
                                destination = destination,
                                focusedTarget = primaryControllerTarget,
                                visiblePackages = visiblePackages,
                                expandedPackage = expandedPackage,
                            )
                        ) {
                            is CarePadPrimaryControllerTarget.Rail -> {
                                goTo(target.destination)
                                true
                            }

                            is CarePadPrimaryControllerTarget.Module -> {
                                val item = visibleModules.firstOrNull {
                                    it.module.packageName == target.packageName
                                }
                                if (item == null) {
                                    false
                                } else {
                                    expandedPackage = null
                                    ModuleManager.open(context, item.module)
                                    true
                                }
                            }

                            is CarePadPrimaryControllerTarget.Uninstall -> {
                                val item = visibleModules.firstOrNull {
                                    it.module.packageName == target.packageName
                                }
                                if (item == null) {
                                    false
                                } else {
                                    pendingUninstall = item
                                    true
                                }
                            }

                            is CarePadPrimaryControllerTarget.Theme -> {
                                onThemeModeChange(target.mode)
                                true
                            }

                            CarePadPrimaryControllerTarget.None -> false
                        }
                    }

                    else -> false
                }
            },
    ) {
        CarePadNavigationRail(
            selected = destination,
            visualState = carePadRailVisualState(focusState),
            focusRequesters = railFocusRequesters,
            onFocused = { focusedDestination ->
                focusState = focusState.onRailFocused(focusedDestination)
                primaryControllerTarget =
                    CarePadPrimaryControllerTarget.Rail(focusedDestination)
            },
            onSelected = ::goTo,
        )

        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .pointerInteropFilter { event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        enterTouchContent()
                    }
                    false
                },
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .focusRequester(contentFallbackRequester)
                        .onFocusChanged { state ->
                            if (state.isFocused) {
                                focusState = focusState.onContentFocused()
                                primaryControllerTarget = CarePadPrimaryControllerTarget.None
                            }
                        }
                        .focusable(),
                ) {
                    when (destination) {
                        CarePadDestination.HOME -> CarePadHome(
                            modules = visibleModules,
                            expandedPackage = expandedPackage,
                            focusedModulePackage = focusedModulePackage,
                            focusRequesters = moduleFocusRequesters,
                            uninstallFocusRequesters = uninstallFocusRequesters,
                            listState = homeListState,
                            onFocusChanged = { packageName, focused ->
                                val target = CarePadPrimaryControllerTarget.Module(packageName)
                                if (focused) {
                                    focusedModulePackage = packageName
                                    focusState = focusState.onContentFocused()
                                    primaryControllerTarget = target
                                    lastContentControllerTarget = target
                                } else if (primaryControllerTarget == target) {
                                    primaryControllerTarget = CarePadPrimaryControllerTarget.None
                                }
                            },
                            onOpen = { item ->
                                val target = CarePadPrimaryControllerTarget.Module(
                                    item.module.packageName
                                )
                                enterTouchContent(target)
                                expandedPackage = null
                                ModuleManager.open(context, item.module)
                            },
                            onToggleDetails = { item ->
                                val packageName = item.module.packageName
                                enterTouchContent(
                                    CarePadPrimaryControllerTarget.Module(packageName)
                                )
                                expandedPackage =
                                    if (expandedPackage == packageName) null else packageName
                            },
                            onUninstallFocusChanged = { item, focused ->
                                val packageName = item.module.packageName
                                val target = CarePadPrimaryControllerTarget.Uninstall(packageName)
                                if (focused) {
                                    focusedModulePackage = packageName
                                    focusState = focusState.onContentFocused()
                                    primaryControllerTarget = target
                                    lastContentControllerTarget = target
                                } else if (primaryControllerTarget == target) {
                                    primaryControllerTarget = CarePadPrimaryControllerTarget.None
                                }
                            },
                            onUninstall = { item ->
                                enterTouchContent(
                                    CarePadPrimaryControllerTarget.Uninstall(
                                        item.module.packageName
                                    )
                                )
                                pendingUninstall = item
                            },
                        )

                        CarePadDestination.ADD_MODULES -> CarePadAddModules()

                        CarePadDestination.SETTINGS -> settingsContent(
                            { destination = CarePadDestination.HOME },
                            { mode, focused ->
                                val target = CarePadPrimaryControllerTarget.Theme(mode)
                                if (focused) {
                                    focusState = focusState.onContentFocused()
                                    primaryControllerTarget = target
                                    lastContentControllerTarget = target
                                } else if (primaryControllerTarget == target) {
                                    primaryControllerTarget = CarePadPrimaryControllerTarget.None
                                }
                            },
                            { mode ->
                                enterTouchContent(
                                    CarePadPrimaryControllerTarget.Theme(mode)
                                )
                            },
                            themeFocusRequesters,
                        )
                    }
                }

                HorizontalDivider()
                CarePadControlHints(
                    destination = destination,
                    inputMethod = inputMethod,
                    glyphs = glyphs,
                    hasModules = visibleModules.isNotEmpty(),
                )
            }
        }
    }
}

@Composable
private fun CarePadNavigationRail(
    selected: CarePadDestination,
    visualState: CarePadRailVisualState,
    focusRequesters: Map<CarePadDestination, FocusRequester>,
    onFocused: (CarePadDestination) -> Unit,
    onSelected: (CarePadDestination) -> Unit,
) {
    val expanded = visualState == CarePadRailVisualState.EXPANDED
    val animatedWidth by animateDpAsState(
        targetValue = if (expanded) CarePadRailExpandedWidth else CarePadRailCompactWidth,
        animationSpec = tween(durationMillis = CarePadRailTransitionMillis),
    )

    NavigationRail(
        modifier = Modifier
            .width(animatedWidth)
            .fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Spacer(Modifier.height(18.dp))
        railItems().forEach { item ->
            NavigationRailItem(
                selected = carePadRailItemSelected(selected, item.destination),
                onClick = { onSelected(item.destination) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = stringResource(item.labelRes),
                    )
                },
                label = if (expanded) {
                    { Text(stringResource(item.labelRes)) }
                } else {
                    null
                },
                alwaysShowLabel = expanded,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequesters.getValue(item.destination))
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            onFocused(item.destination)
                        }
                    },
            )
        }
    }
}

private data class RailItem(
    val destination: CarePadDestination,
    val labelRes: Int,
    val icon: ImageVector,
)

private fun railItems(): List<RailItem> = listOf(
    RailItem(CarePadDestination.HOME, R.string.carepad_nav_home, Icons.Rounded.Home),
    RailItem(
        CarePadDestination.ADD_MODULES,
        R.string.carepad_nav_add_modules,
        Icons.Rounded.AddCircle,
    ),
    RailItem(CarePadDestination.SETTINGS, R.string.carepad_nav_settings, Icons.Rounded.Settings),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CarePadHome(
    modules: List<VisibleModule>,
    expandedPackage: String?,
    focusedModulePackage: String?,
    focusRequesters: Map<String, FocusRequester>,
    uninstallFocusRequesters: Map<String, FocusRequester>,
    listState: LazyListState,
    onFocusChanged: (String, Boolean) -> Unit,
    onOpen: (VisibleModule) -> Unit,
    onToggleDetails: (VisibleModule) -> Unit,
    onUninstallFocusChanged: (VisibleModule, Boolean) -> Unit,
    onUninstall: (VisibleModule) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.carepad_your_modules),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        if (modules.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Text(
                        text = stringResource(R.string.carepad_no_installed_modules),
                        modifier = Modifier.padding(20.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            items(
                items = modules,
                key = { item -> item.module.packageName },
            ) { item ->
                val packageName = item.module.packageName
                val focused = focusedModulePackage == packageName
                val expanded = expandedPackage == packageName
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequesters.getValue(packageName))
                            .onFocusChanged { state ->
                                onFocusChanged(packageName, state.isFocused)
                            }
                            .combinedClickable(
                                onClick = { onOpen(item) },
                                onLongClick = { onToggleDetails(item) },
                            ),
                        shape = RoundedCornerShape(26.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = if (focused) {
                            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            null
                        },
                        tonalElevation = if (focused) 2.dp else 0.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Icon(
                                imageVector = moduleIcon(item.presentation.moduleId),
                                contentDescription = null,
                                modifier = Modifier.size(30.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(item.presentation.nameRes),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }

                    if (expanded) {
                        CarePadModuleDetails(
                            item = item,
                            uninstallFocusRequester =
                                uninstallFocusRequesters.getValue(packageName),
                            onUninstallFocusChanged = { focused ->
                                onUninstallFocusChanged(item, focused)
                            },
                            onUninstall = { onUninstall(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CarePadModuleDetails(
    item: VisibleModule,
    uninstallFocusRequester: FocusRequester,
    onUninstallFocusChanged: (Boolean) -> Unit,
    onUninstall: () -> Unit,
) {
    val installedVersion = CarePadModulePresentations.installedVersionOrNull(
        item.module.metadata.moduleVersion
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(item.presentation.descriptionRes),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (installedVersion != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.carepad_installed_version),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = installedVersion,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            OutlinedButton(
                onClick = onUninstall,
                modifier = Modifier
                    .focusRequester(uninstallFocusRequester)
                    .onFocusChanged { state ->
                        onUninstallFocusChanged(state.isFocused)
                    },
            ) {
                Text(stringResource(R.string.carepad_uninstall_module))
            }
        }
    }
}

@Composable
private fun CarePadAddModules() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.carepad_nav_add_modules),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                text = stringResource(R.string.carepad_add_modules_not_connected),
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CarePadControlHints(
    destination: CarePadDestination,
    inputMethod: CarePadInputMethod,
    glyphs: ControllerGlyphs,
    hasModules: Boolean,
) {
    val text = when (inputMethod) {
        CarePadInputMethod.TOUCH -> if (destination == CarePadDestination.HOME && hasModules) {
            stringResource(R.string.carepad_hint_touch_home)
        } else {
            stringResource(R.string.carepad_hint_touch_navigation)
        }

        CarePadInputMethod.CONTROLLER -> if (
            destination == CarePadDestination.HOME && hasModules
        ) {
            stringResource(
                R.string.carepad_hint_controller_home,
                glyphs.primary,
                glyphs.details,
                glyphs.back,
                glyphs.navigation,
            )
        } else {
            stringResource(
                R.string.carepad_hint_controller_navigation,
                glyphs.back,
                glyphs.navigation,
            )
        }
    }

    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun moduleIcon(moduleId: String): ImageVector = when (
    CarePadModulePresentations.forModuleId(moduleId)?.order
) {
    0 -> Icons.Rounded.Speed
    else -> Icons.Rounded.SportsEsports
}

private fun controllerGlyphs(profile: ControlGlyphProfile): ControllerGlyphs = when (profile) {
    ControlGlyphProfile.GENERIC -> ControllerGlyphs(
        primary = "A",
        details = "Superior",
        back = "B",
        navigation = "L1",
        detailsKeyCode = AndroidKeyEvent.KEYCODE_BUTTON_Y,
    )

    ControlGlyphProfile.ABXY_Y_TOP -> ControllerGlyphs(
        primary = "A",
        details = "Y",
        back = "B",
        navigation = "LB",
        detailsKeyCode = AndroidKeyEvent.KEYCODE_BUTTON_Y,
    )

    ControlGlyphProfile.ABXY_X_TOP -> ControllerGlyphs(
        primary = "A",
        details = "X",
        back = "B",
        navigation = "L",
        detailsKeyCode = AndroidKeyEvent.KEYCODE_BUTTON_X,
    )

    ControlGlyphProfile.SYMBOLS_TRIANGLE_TOP -> ControllerGlyphs(
        primary = "✕",
        details = "△",
        back = "○",
        navigation = "L1",
        detailsKeyCode = AndroidKeyEvent.KEYCODE_BUTTON_Y,
    )
}

private fun controllerFocusDirection(keyCode: Int): FocusDirection? = when (keyCode) {
    AndroidKeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
    else -> null
}

private fun isControllerSource(source: Int): Boolean =
    (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD ||
        (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
