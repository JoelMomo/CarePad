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
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
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

internal fun carePadRailVisualState(
    zone: CarePadFocusZone,
): CarePadRailVisualState = when (zone) {
    CarePadFocusZone.RAIL -> CarePadRailVisualState.EXPANDED
    CarePadFocusZone.CONTENT -> CarePadRailVisualState.COMPACT
}

internal fun carePadRailItemSelected(
    selected: CarePadDestination,
    candidate: CarePadDestination,
): Boolean = selected == candidate

private val CarePadRailCompactWidth = 80.dp
private val CarePadRailExpandedWidth = 176.dp
private const val CarePadRailTransitionMillis = 180

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
    focusTrace: ((String) -> Unit)? = null,
    settingsContent: @Composable (
        onBack: () -> Unit,
        onThemeFocusChanged: (AppThemeMode, Boolean) -> Unit,
        onThemeTouched: (AppThemeMode) -> Unit,
        themeFocusRequesters: Map<AppThemeMode, FocusRequester>,
    ) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val inputModeManager = LocalInputModeManager.current
    var discovery by remember { mutableStateOf(ModuleManager.discover(context)) }
    var focusControllerState by remember { mutableStateOf(CarePadFocusControllerState()) }
    var expandedPackage by remember { mutableStateOf<String?>(null) }
    var pendingUninstall by remember { mutableStateOf<VisibleModule?>(null) }
    val focusTraceSequence = remember { longArrayOf(0L) }

    fun traceFocus(message: String) {
        val trace = focusTrace ?: return
        focusTraceSequence[0] += 1
        trace("${focusTraceSequence[0]} $message")
    }

    fun focusStateTrace(state: CarePadFocusControllerState): String =
        "zone=${state.activeZone} modality=${state.modality} " +
            "selected=${state.selectedDestination} railPreferred=${state.railPreferredDestination} " +
            "observed=${state.observedFocus} pending=${state.pendingFocus} " +
            "nextToken=${state.nextFocusToken}"

    val destination = focusControllerState.selectedDestination
    val inputMethod = focusControllerState.modality
    val focusedModulePackage = when (val focus = focusControllerState.observedFocus) {
        is CarePadFocusKey.Module -> focus.packageName
        is CarePadFocusKey.Uninstall -> focus.packageName
        else -> null
    }

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

    fun dispatchFocus(event: CarePadFocusEvent) {
        val before = focusControllerState
        val after = reduceCarePadFocus(before, event)
        focusControllerState = after
        traceFocus(
            "EVENT $event | before=${focusStateTrace(before)} | after=${focusStateTrace(after)}"
        )
    }

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

    LaunchedEffect(destination, visiblePackages, expandedPackage) {
        if (expandedPackage?.let { it !in visiblePackages } == true) {
            expandedPackage = null
        }
        if (
            pendingUninstall?.module?.packageName?.let { it !in visiblePackages } == true
        ) {
            pendingUninstall = null
        }
        dispatchFocus(
            CarePadFocusEvent.ContentTargetsChanged(
                destination = destination,
                validTargets = carePadContentTargets(
                    destination = destination,
                    visiblePackages = visiblePackages,
                    expandedPackage = expandedPackage,
                ),
                fallbackTarget = carePadContentFallback(
                    destination = destination,
                    visiblePackages = visiblePackages,
                ),
            )
        )
    }

    val pendingFocus = focusControllerState.pendingFocus
    LaunchedEffect(pendingFocus?.token) {
        val pending = pendingFocus ?: return@LaunchedEffect
        traceFocus("EXECUTE token=${pending.token} intent=${pending.intent}")
        val accepted = when (val intent = pending.intent) {
            is CarePadFocusIntent.RequestTarget -> {
                val requester = when (val target = intent.target) {
                    is CarePadFocusKey.Rail -> railFocusRequesters[target.destination]
                    is CarePadFocusKey.Module -> moduleFocusRequesters[target.packageName]
                    is CarePadFocusKey.Uninstall ->
                        uninstallFocusRequesters[target.packageName]
                    is CarePadFocusKey.Theme -> themeFocusRequesters[target.mode]
                    is CarePadFocusKey.ContentFallback -> {
                        if (target.destination == destination) {
                            contentFallbackRequester
                        } else {
                            null
                        }
                    }
                }
                traceFocus(
                    "REQUESTER token=${pending.token} target=${intent.target} " +
                        "resolved=${requester != null}"
                )
                if (requester == null) {
                    traceFocus(
                        "REQUEST_FOCUS token=${pending.token} target=${intent.target} skipped=null"
                    )
                    false
                } else {
                    traceFocus("REQUEST_FOCUS token=${pending.token} target=${intent.target} call")
                    val requestAccepted = requester.requestFocus()
                    traceFocus(
                        "REQUEST_FOCUS token=${pending.token} target=${intent.target} " +
                            "return=$requestAccepted"
                    )
                    requestAccepted
                }
            }

            is CarePadFocusIntent.MoveWithinZone ->
                focusManager.moveFocus(intent.direction.toComposeFocusDirection())
        }
        dispatchFocus(
            CarePadFocusEvent.FocusExecutionResult(
                token = pending.token,
                accepted = accepted,
            )
        )
    }

    fun goTo(next: CarePadDestination) {
        expandedPackage = null
        dispatchFocus(CarePadFocusEvent.DestinationSelected(next))
    }

    fun handleBack(): Boolean {
        if (expandedPackage != null) {
            expandedPackage = null
            return true
        }
        if (destination != CarePadDestination.HOME) {
            goTo(CarePadDestination.HOME)
            return true
        }
        return false
    }
    fun enterTouchRail(touchedDestination: CarePadDestination) {
        dispatchFocus(CarePadFocusEvent.TouchRail(touchedDestination))
    }

    fun enterTouchContent(touchedTarget: CarePadFocusKey? = null) {
        dispatchFocus(CarePadFocusEvent.TouchContent(touchedTarget))
    }

    fun focusedPackageName(): String? = when (val focused = focusControllerState.observedFocus) {
        is CarePadFocusKey.Module -> focused.packageName
        is CarePadFocusKey.Uninstall -> focused.packageName
        else -> null
    }

    fun toggleFocusedDetails() {
        if (!carePadDetailsControllerActionAllowed(focusControllerState, visiblePackages)) {
            return
        }
        val packageName = focusedPackageName() ?: return
        expandedPackage = if (expandedPackage == packageName) null else packageName
    }

    fun markControllerActivity(wasTouch: Boolean) {
        dispatchFocus(CarePadFocusEvent.ControllerActivity)
        if (wasTouch) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
        }
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
                    dispatchFocus(CarePadFocusEvent.TouchContext)
                }
                false
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (
                    event.type != KeyEventType.KeyDown ||
                    !isControllerSource(native.source)
                ) {
                    return@onPreviewKeyEvent false
                }

                val direction = controllerDirection(native.keyCode)
                if (native.repeatCount != 0) {
                    return@onPreviewKeyEvent (
                        native.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_L1 ||
                            direction != null
                    )
                }

                val wasTouch = focusControllerState.modality == CarePadInputMethod.TOUCH
                when {
                    native.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_L1 -> {
                        traceFocus(
                            "KEYDOWN L1 start state=${focusStateTrace(focusControllerState)} " +
                                "wasTouch=$wasTouch"
                        )
                        dispatchFocus(CarePadFocusEvent.ControllerL1())
                        if (wasTouch) {
                            traceFocus("INPUT_MODE Keyboard call reason=L1")
                            val modeAccepted =
                                inputModeManager.requestInputMode(InputMode.Keyboard)
                            traceFocus("INPUT_MODE Keyboard return=$modeAccepted reason=L1")
                        }
                        traceFocus(
                            "KEYDOWN L1 handler-return state=${focusStateTrace(focusControllerState)}"
                        )
                        true
                    }

                    direction != null -> {
                        dispatchFocus(CarePadFocusEvent.ControllerDpad(direction))
                        if (wasTouch) {
                            inputModeManager.requestInputMode(InputMode.Keyboard)
                        }
                        true
                    }

                    native.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_B -> {
                        markControllerActivity(wasTouch)
                        handleBack()
                    }

                    native.keyCode == glyphs.detailsKeyCode -> {
                        markControllerActivity(wasTouch)
                        val allowed = carePadDetailsControllerActionAllowed(
                            focusControllerState,
                            visiblePackages,
                        )
                        if (allowed) {
                            toggleFocusedDetails()
                        }
                        allowed
                    }

                    native.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_A -> {
                        markControllerActivity(wasTouch)
                        when (
                            val target = carePadControllerActionTarget(
                                state = focusControllerState,
                                visiblePackages = visiblePackages,
                                expandedPackage = expandedPackage,
                            )
                        ) {
                            is CarePadFocusKey.Rail -> {
                                goTo(target.destination)
                                true
                            }

                            is CarePadFocusKey.Module -> {
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

                            is CarePadFocusKey.Uninstall -> {
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

                            is CarePadFocusKey.Theme -> {
                                onThemeModeChange(target.mode)
                                true
                            }

                            is CarePadFocusKey.ContentFallback,
                            null -> false
                        }
                    }

                    else -> {
                        markControllerActivity(wasTouch)
                        false
                    }
                }
            },
    ) {
        CarePadNavigationRail(
            selected = destination,
            visualState = carePadRailVisualState(focusControllerState.activeZone),
            focusRequesters = railFocusRequesters,
            onFocused = { focusedDestination ->
                dispatchFocus(
                    CarePadFocusEvent.FocusObserved(
                        CarePadFocusKey.Rail(focusedDestination)
                    )
                )
            },
            onZoneFocusLost = {
                dispatchFocus(CarePadFocusEvent.ZoneFocusLost(CarePadFocusZone.RAIL))
            },
            onSelected = { selectedDestination ->
                goTo(selectedDestination)
                if (focusControllerState.modality == CarePadInputMethod.TOUCH) {
                    enterTouchRail(selectedDestination)
                }
            },
        )

        Surface(
            modifier = Modifier
                .onFocusChanged { state ->
                    if (!state.hasFocus) {
                        dispatchFocus(
                            CarePadFocusEvent.ZoneFocusLost(CarePadFocusZone.CONTENT)
                        )
                    }
                }
                .focusProperties {
                    onExit = {
                        when (requestedFocusDirection) {
                            FocusDirection.Up,
                            FocusDirection.Down,
                            FocusDirection.Left,
                            FocusDirection.Right -> cancelFocusChange()
                            else -> Unit
                        }
                    }
                }
                .focusGroup()
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
                                dispatchFocus(
                                    CarePadFocusEvent.FocusObserved(
                                        CarePadFocusKey.ContentFallback(destination)
                                    )
                                )
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
                                if (focused) {
                                    dispatchFocus(
                                        CarePadFocusEvent.FocusObserved(
                                            CarePadFocusKey.Module(packageName)
                                        )
                                    )
                                }
                            },
                            onOpen = { item ->
                                enterTouchContent(
                                    CarePadFocusKey.Module(item.module.packageName)
                                )
                                expandedPackage = null
                                ModuleManager.open(context, item.module)
                            },
                            onToggleDetails = { item ->
                                val packageName = item.module.packageName
                                enterTouchContent(CarePadFocusKey.Module(packageName))
                                expandedPackage =
                                    if (expandedPackage == packageName) null else packageName
                            },
                            onUninstallFocusChanged = { item, focused ->
                                if (focused) {
                                    dispatchFocus(
                                        CarePadFocusEvent.FocusObserved(
                                            CarePadFocusKey.Uninstall(
                                                item.module.packageName
                                            )
                                        )
                                    )
                                }
                            },
                            onUninstall = { item ->
                                enterTouchContent(
                                    CarePadFocusKey.Uninstall(item.module.packageName)
                                )
                                pendingUninstall = item
                            },
                        )

                        CarePadDestination.ADD_MODULES -> CarePadAddModules()

                        CarePadDestination.SETTINGS -> settingsContent(
                            { goTo(CarePadDestination.HOME) },
                            { mode, focused ->
                                if (focused) {
                                    dispatchFocus(
                                        CarePadFocusEvent.FocusObserved(
                                            CarePadFocusKey.Theme(mode)
                                        )
                                    )
                                }
                            },
                            { mode ->
                                enterTouchContent(CarePadFocusKey.Theme(mode))
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
    onZoneFocusLost: () -> Unit,
    onSelected: (CarePadDestination) -> Unit,
) {
    val expanded = visualState == CarePadRailVisualState.EXPANDED
    val animatedWidth by animateDpAsState(
        targetValue = if (expanded) CarePadRailExpandedWidth else CarePadRailCompactWidth,
        animationSpec = tween(durationMillis = CarePadRailTransitionMillis),
    )

    NavigationRail(
        modifier = Modifier
            .onFocusChanged { state ->
                if (!state.hasFocus) {
                    onZoneFocusLost()
                }
            }
            .focusProperties {
                onExit = {
                    when (requestedFocusDirection) {
                        FocusDirection.Up,
                        FocusDirection.Down,
                        FocusDirection.Left,
                        FocusDirection.Right -> cancelFocusChange()
                        else -> Unit
                    }
                }
            }
            .focusGroup()
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

private fun controllerDirection(keyCode: Int): CarePadDirection? = when (keyCode) {
    AndroidKeyEvent.KEYCODE_DPAD_UP -> CarePadDirection.UP
    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> CarePadDirection.DOWN
    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> CarePadDirection.LEFT
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> CarePadDirection.RIGHT
    else -> null
}

private fun CarePadDirection.toComposeFocusDirection(): FocusDirection = when (this) {
    CarePadDirection.UP -> FocusDirection.Up
    CarePadDirection.DOWN -> FocusDirection.Down
    CarePadDirection.LEFT -> FocusDirection.Left
    CarePadDirection.RIGHT -> FocusDirection.Right
}

private fun isControllerSource(source: Int): Boolean =
    (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD ||
        (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK