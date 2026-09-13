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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import carepad.contracts.CarePadModuleIds
import com.joel.thordoctor.AppPreferences
import com.joel.thordoctor.AppThemeMode
import com.joel.thordoctor.ControlGlyphProfile
import com.joel.thordoctor.R
import com.joel.thordoctor.modules.host.DiscoveredCarePadModule
import com.joel.thordoctor.modules.host.ModuleManager
import dev.carepad.module.controls.internalui.ControlsInternalController
import dev.carepad.module.controls.internalui.ControlsInternalScreen
import dev.carepad.module.controls.internalui.ControlsFocusTrace
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalView
import kotlin.math.abs

internal enum class CarePadDestination {
    HOME,
    ADD_MODULES,
    SETTINGS,
}

internal enum class CarePadInputMethod {
    TOUCH,
    CONTROLLER,
}

internal enum class CarePadRailVisualState {
    EXPANDED,
    COMPACT,
}

internal fun carePadRailVisualState(
    observedFocus: CarePadFocusKey?,
): CarePadRailVisualState = if (observedFocus is CarePadFocusKey.Rail) {
    CarePadRailVisualState.EXPANDED
} else {
    CarePadRailVisualState.COMPACT
}

internal fun carePadRailItemSelected(
    selected: CarePadDestination,
    candidate: CarePadDestination,
): Boolean = selected == candidate

private val CarePadRailCompactWidth = 80.dp
private val CarePadRailExpandedWidth = 176.dp
private const val CarePadRailTransitionMillis = 180
private const val InternalControlsKey = "carepad-internal:controls"
private val ControllerMotionAxes = intArrayOf(
    MotionEvent.AXIS_X,
    MotionEvent.AXIS_Y,
    MotionEvent.AXIS_Z,
    MotionEvent.AXIS_RZ,
    MotionEvent.AXIS_RX,
    MotionEvent.AXIS_RY,
    MotionEvent.AXIS_HAT_X,
    MotionEvent.AXIS_HAT_Y,
)

private data class VisibleModule(
    val key: String,
    val module: DiscoveredCarePadModule?,
    val presentation: CarePadModulePresentation,
) {
    val isInternalControls: Boolean
        get() = key == InternalControlsKey
}

private data class ControllerGlyphs(
    val primary: String,
    val details: String,
    val back: String,
    val navigation: String,
    val detailsKeyCode: Int,
)

private data class ControlsTouchRecoveryDrain(
    val deviceId: Int,
    val waitsForHatNeutral: Boolean,
    val sawDpadKey: Boolean,
    val keyReleased: Boolean = false,
    val hatNeutral: Boolean = !waitsForHatNeutral,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun CarePadShellScreen(
    onThemeModeChange: (AppThemeMode) -> Unit,
    onRawInputHandlersChanged: (
        ((AndroidKeyEvent) -> Boolean)?,
        ((MotionEvent) -> Boolean)?,
    ) -> Unit,
    settingsContent: @Composable (
        onBack: () -> Unit,
        onThemeFocusChanged: (AppThemeMode, Boolean) -> Unit,
        onThemeTouched: (AppThemeMode) -> Unit,
        themeFocusRequesters: Map<AppThemeMode, FocusRequester>,
    ) -> Unit,
    controlsControllerFactory: (Context) -> ControlsInternalController = { ControlsInternalController(it) },
    controlsContent: @Composable (ControlsInternalController, Modifier) -> Unit = { controller, modifier ->
        ControlsInternalScreen(controller = controller, modifier = modifier)
    },
) {
    val context = LocalContext.current
    val inputModeManager = LocalInputModeManager.current
    val view = LocalView.current
    val performFeedback = rememberCozyFeedback()
    val controlsController = remember(context.applicationContext) {
        controlsControllerFactory(context.applicationContext)
    }
    var savedDestinationName by rememberSaveable {
        mutableStateOf(CarePadDestination.HOME.name)
    }
    var discovery by remember { mutableStateOf(ModuleManager.discover(context)) }
    var focusControllerState by remember {
        mutableStateOf(
            CarePadFocusControllerState(
                selectedDestination = CarePadDestination.valueOf(savedDestinationName)
            )
        )
    }
    var expandedPackage by remember { mutableStateOf<String?>(null) }
    var pendingUninstall by remember { mutableStateOf<VisibleModule?>(null) }
    var controlsOpen by rememberSaveable { mutableStateOf(false) }
    var controlsTouchRecoveryDrain by remember {
        mutableStateOf<ControlsTouchRecoveryDrain?>(null)
    }
    var controlsContentFocusObserved by remember { mutableStateOf(false) }

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
        val external = discovery.modules
            .filter { module -> module.metadata.moduleId != CarePadModuleIds.CONTROLS }
            .mapNotNull { module ->
                CarePadModulePresentations.forModuleId(module.metadata.moduleId)
                    ?.let { presentation ->
                        VisibleModule(
                            key = module.packageName,
                            module = module,
                            presentation = presentation,
                        )
                    }
            }
        val controls = requireNotNull(
            CarePadModulePresentations.forModuleId(CarePadModuleIds.CONTROLS)
        )
        (external + VisibleModule(InternalControlsKey, null, controls))
            .sortedWith(
                compareBy<VisibleModule> { it.presentation.order }
                    .thenBy { it.key }
            )
    }
    val visiblePackages = visibleModules.map { it.key }
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
    val controlsContentFocusRequester = remember { FocusRequester() }
    val contentTargets = if (controlsOpen) {
        emptyList()
    } else {
        carePadContentTargets(
            destination = destination,
            visiblePackages = visiblePackages,
            expandedPackage = expandedPackage,
        )
    }

    fun focusRequesterFor(target: CarePadFocusKey): FocusRequester? = when (target) {
        is CarePadFocusKey.Rail -> railFocusRequesters[target.destination]
        is CarePadFocusKey.Module -> moduleFocusRequesters[target.packageName]
        is CarePadFocusKey.Uninstall -> uninstallFocusRequesters[target.packageName]
        is CarePadFocusKey.Theme -> themeFocusRequesters[target.mode]
        is CarePadFocusKey.ContentFallback -> when {
            target.destination != destination -> null
            controlsOpen -> controlsContentFocusRequester
            else -> contentFallbackRequester
        }
    }

    fun requestFocusTarget(target: CarePadFocusKey) {
        if (target is CarePadFocusKey.ContentFallback && target.destination == destination && controlsOpen) {
            // Raw HAT/KEY recovery is consumed before Android/Compose's normal dispatch.
            // SystemDefined actions cannot take focus until the platform leaves touch mode.
            val accepted = inputModeManager.requestInputMode(InputMode.Keyboard)
            ControlsFocusTrace.log("input-mode-request") { "accepted=$accepted inputMode=${inputModeManager.inputMode} touch=${view.isInTouchMode}" }
            if (!accepted) return
            if (controlsController.requestMainEntryFocus()) return
        }
        focusRequesterFor(target)?.requestFocus()
    }

    fun touchRecoverySettled(drain: ControlsTouchRecoveryDrain): Boolean =
        (!drain.waitsForHatNeutral || drain.hatNeutral) &&
            (!drain.sawDpadKey || drain.keyReleased)

    fun settleControlsTouchRecoveryIfReady() {
        val drain = controlsTouchRecoveryDrain ?: return
        if (touchRecoverySettled(drain)) {
            controlsTouchRecoveryDrain = null
        }
    }

    fun controlsNeedsEntryFocus(): Boolean =
        inputModeManager.inputMode == InputMode.Touch ||
            (!controlsContentFocusObserved && focusControllerState.observedFocus !is CarePadFocusKey.Rail)

    fun requestControlsContentFocus() {
        ControlsFocusTrace.log("content-request") { "inputMode=${inputModeManager.inputMode} touch=${view.isInTouchMode} carepad=${focusControllerState.modality} observed=$controlsContentFocusObserved drain=$controlsTouchRecoveryDrain" }
        requestFocusTarget(CarePadFocusKey.ContentFallback(destination))
    }

    fun dispatchFocus(event: CarePadFocusEvent) {
        val nextState = reduceCarePadFocus(focusControllerState, event)
        focusControllerState = nextState
        savedDestinationName = nextState.selectedDestination.name
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

    DisposableEffect(controlsOpen, controlsController) {
        if (controlsOpen) {
            onRawInputHandlersChanged(
                { event ->
                    val needsEntryFocus = controlsNeedsEntryFocus()
                    var consumed = controlsController.onKeyEvent(event)
                    if (consumed) {
                        controlsTouchRecoveryDrain = null
                    } else {
                        val drain = controlsTouchRecoveryDrain
                        if (
                            drain != null &&
                            event.deviceId == drain.deviceId &&
                            isControllerSource(event.source) &&
                            controllerDirection(event.keyCode) != null
                        ) {
                            when (event.action) {
                                AndroidKeyEvent.ACTION_DOWN -> {
                                    if (drain.keyReleased && event.repeatCount == 0) {
                                        // A new press is not a retry for a failed focus request.
                                        controlsTouchRecoveryDrain = null
                                    } else {
                                        controlsTouchRecoveryDrain = drain.copy(sawDpadKey = true)
                                        consumed = true
                                    }
                                }

                                AndroidKeyEvent.ACTION_UP -> {
                                    controlsTouchRecoveryDrain = drain.copy(
                                        sawDpadKey = true,
                                        keyReleased = true,
                                    )
                                    settleControlsTouchRecoveryIfReady()
                                    consumed = true
                                }
                            }
                        }
                    }

                    if (
                        event.action == AndroidKeyEvent.ACTION_DOWN &&
                        event.repeatCount == 0 &&
                        isControllerSource(event.source)
                    ) {
                        dispatchFocus(CarePadFocusEvent.ControllerActivity)
                        if (!consumed && needsEntryFocus && controllerDirection(event.keyCode) != null) {
                            requestControlsContentFocus()
                            val waitsForHatNeutral = controllerKeyHasHatRange(event)
                            controlsTouchRecoveryDrain = ControlsTouchRecoveryDrain(
                                deviceId = event.deviceId,
                                waitsForHatNeutral = waitsForHatNeutral,
                                sawDpadKey = true,
                                hatNeutral = !waitsForHatNeutral,
                            )
                            consumed = true
                        }
                    }
                    ControlsFocusTrace.log("shell-key") { "time=${event.eventTime} device=${event.deviceId} consumed=$consumed inputMode=${inputModeManager.inputMode} touch=${view.isInTouchMode} carepad=${focusControllerState.modality} observed=$controlsContentFocusObserved drain=$controlsTouchRecoveryDrain" }
                    consumed
                },
                { event ->
                    val needsEntryFocus = controlsNeedsEntryFocus()
                    val activeHat = isActiveControllerHatMotion(event)
                    val significant = activeHat || isSignificantControllerMotion(event)
                    var consumed = controlsController.onGenericMotionEvent(event)
                    if (consumed) {
                        controlsTouchRecoveryDrain = null
                    }
                    if (significant) {
                        dispatchFocus(CarePadFocusEvent.ControllerActivity)
                    }

                    if (!consumed) {
                        val drain = controlsTouchRecoveryDrain
                        if (
                            drain != null &&
                            event.deviceId == drain.deviceId &&
                            event.actionMasked == MotionEvent.ACTION_MOVE &&
                            isControllerSource(event.source)
                        ) {
                            if (isControllerHatNeutral(event) && drain.waitsForHatNeutral) {
                                controlsTouchRecoveryDrain = drain.copy(hatNeutral = true)
                                settleControlsTouchRecoveryIfReady()
                                consumed = true
                            } else if (activeHat) {
                                // KEY may precede HAT even when its source exposes no ranges.
                                controlsTouchRecoveryDrain = drain.copy(waitsForHatNeutral = true, hatNeutral = false)
                                consumed = true
                            }
                        }
                    }

                    if (!consumed && needsEntryFocus && activeHat) {
                        requestControlsContentFocus()
                        controlsTouchRecoveryDrain = ControlsTouchRecoveryDrain(
                            deviceId = event.deviceId,
                            waitsForHatNeutral = true,
                            sawDpadKey = false,
                            hatNeutral = false,
                        )
                        consumed = true
                    }
                    ControlsFocusTrace.log("shell-motion") { "time=${event.eventTime} device=${event.deviceId} consumed=$consumed inputMode=${inputModeManager.inputMode} touch=${view.isInTouchMode} carepad=${focusControllerState.modality} observed=$controlsContentFocusObserved drain=$controlsTouchRecoveryDrain" }
                    consumed
                },
            )
        } else {
            controlsTouchRecoveryDrain = null
            controlsContentFocusObserved = false
            onRawInputHandlersChanged(null, null)
        }
        onDispose {
            controlsTouchRecoveryDrain = null
            controlsContentFocusObserved = false
            onRawInputHandlersChanged(null, null)
        }
    }

    LaunchedEffect(destination, visiblePackages, expandedPackage, controlsOpen) {
        if (expandedPackage?.let { it !in visiblePackages } == true) {
            expandedPackage = null
        }
        if (pendingUninstall?.key?.let { it !in visiblePackages } == true) {
            pendingUninstall = null
        }

        val observed = focusControllerState.observedFocus
        if (observed != null && observed !is CarePadFocusKey.Rail) {
            val fallback = if (controlsOpen) {
                CarePadFocusKey.ContentFallback(destination)
            } else {
                carePadContentFallback(destination, visiblePackages)
            }
            if (observed != fallback && observed !in contentTargets) {
                dispatchFocus(CarePadFocusEvent.FocusObserved(null))
            }
        }
    }

    LaunchedEffect(controlsOpen) {
        if (controlsOpen && focusControllerState.modality == CarePadInputMethod.CONTROLLER) {
            requestControlsContentFocus()
        }
    }

    fun completeGoTo(next: CarePadDestination) {
        controlsOpen = false
        expandedPackage = null
        dispatchFocus(CarePadFocusEvent.DestinationSelected(next))
    }

    fun goTo(next: CarePadDestination) {
        if (controlsOpen) {
            controlsController.requestExit { completeGoTo(next) }
        } else {
            completeGoTo(next)
        }
    }

    fun handleBack(): Boolean {
        if (controlsOpen) {
            if (controlsController.handleBack()) return true
            controlsOpen = false
            expandedPackage = null
            return true
        }
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

    fun enterTouchContent(touchedTarget: CarePadFocusKey? = null) {
        touchedTarget?.let(::requestFocusTarget)
    }

    fun focusedPackageName(): String? = when (val focused = focusControllerState.observedFocus) {
        is CarePadFocusKey.Module -> focused.packageName
        is CarePadFocusKey.Uninstall -> focused.packageName
        else -> null
    }

    fun toggleFocusedDetails() {
        if (controlsOpen) return
        if (!carePadDetailsControllerActionAllowed(focusControllerState, visiblePackages)) {
            return
        }
        val packageName = focusedPackageName() ?: return
        expandedPackage = if (expandedPackage == packageName) null else packageName
    }

    fun markControllerActivity() {
        dispatchFocus(CarePadFocusEvent.ControllerActivity)
    }

    BackHandler(
        enabled = controlsOpen || expandedPackage != null || destination != CarePadDestination.HOME
    ) {
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
                    onClick = rememberCozyClick {
                        val module = item.module ?: return@rememberCozyClick
                        pendingUninstall = null
                        expandedPackage = null
                        ModuleManager.requestUninstall(context, module)
                    }
                ) {
                    Text(stringResource(R.string.carepad_uninstall_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = rememberCozyClick { pendingUninstall = null }
                ) {
                    Text(stringResource(R.string.carepad_cancel))
                }
            },
        )
    }

    CarePadResponsiveNavigationScaffold(
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
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_L1) {
                        return@onPreviewKeyEvent true
                    }
                    if (direction != null) {
                        return@onPreviewKeyEvent false
                    }
                }

                when {
                    native.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_L1 -> {
                        markControllerActivity()
                        val target = if (
                            focusControllerState.observedFocus is CarePadFocusKey.Rail
                        ) {
                            if (controlsOpen) {
                                CarePadFocusKey.ContentFallback(destination)
                            } else {
                                carePadContentFallback(destination, visiblePackages)
                            }
                        } else {
                            CarePadFocusKey.Rail(destination)
                        }
                        requestFocusTarget(target)
                        true
                    }

                    direction != null -> {
                        markControllerActivity()
                        false
                    }

                    native.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_B -> {
                        markControllerActivity()
                        val handled = controlsOpen ||
                            expandedPackage != null ||
                            destination != CarePadDestination.HOME
                        if (handled) {
                            performFeedback()
                            handleBack()
                        }
                        handled
                    }

                    native.keyCode == glyphs.detailsKeyCode -> {
                        markControllerActivity()
                        val allowed = !controlsOpen && carePadDetailsControllerActionAllowed(
                            focusControllerState,
                            visiblePackages,
                        )
                        if (allowed) {
                            performFeedback()
                            toggleFocusedDetails()
                        }
                        allowed
                    }

                    native.keyCode == AndroidKeyEvent.KEYCODE_BUTTON_A -> {
                        markControllerActivity()
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
                                    it.key == target.packageName
                                }
                                if (item == null) {
                                    false
                                } else {
                                    performFeedback()
                                    expandedPackage = null
                                    if (item.isInternalControls) {
                                        controlsOpen = true
                                    } else {
                                        item.module?.let { ModuleManager.open(context, it) }
                                    }
                                    true
                                }
                            }

                            is CarePadFocusKey.Uninstall -> {
                                val item = visibleModules.firstOrNull {
                                    it.key == target.packageName
                                }
                                if (item?.module == null) {
                                    false
                                } else {
                                    performFeedback()
                                    pendingUninstall = item
                                    true
                                }
                            }

                            is CarePadFocusKey.Theme -> {
                                performFeedback()
                                onThemeModeChange(target.mode)
                                true
                            }

                            is CarePadFocusKey.ContentFallback,
                            null -> false
                        }
                    }

                    else -> {
                        markControllerActivity()
                        false
                    }
                }
            },
        selected = destination,
        railVisualState = carePadRailVisualState(focusControllerState.observedFocus),
        focusRequesters = railFocusRequesters,
        onFocusChanged = { focusedDestination, focused ->
            val railFocus = CarePadFocusKey.Rail(focusedDestination)
            if (focused) {
                dispatchFocus(CarePadFocusEvent.FocusObserved(railFocus))
            } else if (focusControllerState.observedFocus == railFocus) {
                dispatchFocus(CarePadFocusEvent.FocusObserved(null))
            }
        },
        onSelected = { selectedDestination ->
            goTo(selectedDestination)
            if (focusControllerState.modality == CarePadInputMethod.TOUCH) {
                requestFocusTarget(CarePadFocusKey.Rail(selectedDestination))
            }
        },
    ) { contentModifier ->
        Surface(
            modifier = contentModifier
                .focusGroup()
                .pointerInteropFilter { event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        dispatchFocus(CarePadFocusEvent.TouchContext)
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
                        .focusProperties { canFocus = contentTargets.isEmpty() && !controlsOpen }
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
                        CarePadDestination.HOME -> if (controlsOpen) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .focusRequester(controlsContentFocusRequester)
                                    .onFocusChanged { state ->
                                        controlsContentFocusObserved = state.hasFocus
                                        if (state.hasFocus) {
                                            dispatchFocus(CarePadFocusEvent.FocusObserved(CarePadFocusKey.ContentFallback(destination)))
                                        }
                                        ControlsFocusTrace.log("content-focus") { "isFocused=${state.isFocused} hasFocus=${state.hasFocus}" }
                                    }
                                    .focusGroup(),
                            ) {
                                controlsContent(
                                    controlsController,
                                    Modifier.fillMaxSize(),
                                )
                            }
                        } else {
                            CarePadHome(
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
                                    enterTouchContent(CarePadFocusKey.Module(item.key))
                                    expandedPackage = null
                                    if (item.isInternalControls) {
                                        controlsOpen = true
                                    } else {
                                        item.module?.let { ModuleManager.open(context, it) }
                                    }
                                },
                                onToggleDetails = { item ->
                                    val packageName = item.key
                                    enterTouchContent(CarePadFocusKey.Module(packageName))
                                    expandedPackage =
                                        if (expandedPackage == packageName) null else packageName
                                },
                                onUninstallFocusChanged = { item, focused ->
                                    if (focused && item.module != null) {
                                        dispatchFocus(
                                            CarePadFocusEvent.FocusObserved(
                                                CarePadFocusKey.Uninstall(item.key)
                                            )
                                        )
                                    }
                                },
                                onUninstall = { item ->
                                    if (item.module != null) {
                                        enterTouchContent(CarePadFocusKey.Uninstall(item.key))
                                        pendingUninstall = item
                                    }
                                },
                            )
                        }

                        CarePadDestination.ADD_MODULES -> CarePadAddModules()

                        CarePadDestination.SETTINGS -> settingsContent(
                            { goTo(CarePadDestination.HOME) },
                            { mode, focused ->
                                val themeFocus = CarePadFocusKey.Theme(mode)
                                if (focused) {
                                    dispatchFocus(CarePadFocusEvent.FocusObserved(themeFocus))
                                } else if (focusControllerState.observedFocus == themeFocus) {
                                    dispatchFocus(CarePadFocusEvent.FocusObserved(null))
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
                    controlsOpen = controlsOpen,
                )
            }
        }
    }
}

@Composable
internal fun CarePadNavigationRail(
    selected: CarePadDestination,
    visualState: CarePadRailVisualState,
    focusRequesters: Map<CarePadDestination, FocusRequester>,
    onFocusChanged: (CarePadDestination, Boolean) -> Unit,
    onSelected: (CarePadDestination) -> Unit,
) {
    val expanded = visualState == CarePadRailVisualState.EXPANDED
    val animatedWidth by animateDpAsState(
        targetValue = if (expanded) CarePadRailExpandedWidth else CarePadRailCompactWidth,
        animationSpec = tween(durationMillis = CarePadRailTransitionMillis),
    )

    NavigationRail(
        modifier = Modifier
            .focusRestorer()
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
                    .focusProperties { canFocus = true }
                    .focusRequester(focusRequesters.getValue(item.destination))
                    .onFocusChanged { state ->
                        onFocusChanged(item.destination, state.isFocused)
                    },
            )
        }
    }
}

internal data class RailItem(
    val destination: CarePadDestination,
    val labelRes: Int,
    val icon: ImageVector,
)

internal fun railItems(): List<RailItem> = listOf(
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
                key = { item -> item.key },
            ) { item ->
                val packageName = item.key
                val focused = focusedModulePackage == packageName
                val expanded = expandedPackage == packageName
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusProperties { canFocus = true }
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
                            uninstallFocusRequester = uninstallFocusRequesters.getValue(packageName),
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
    val installedVersion = item.module?.let { module ->
        CarePadModulePresentations.installedVersionOrNull(module.metadata.moduleVersion)
    }
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
            if (item.module != null) {
                OutlinedButton(
                    onClick = rememberCozyClick(onUninstall),
                    modifier = Modifier
                        .focusProperties { canFocus = true }
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
    controlsOpen: Boolean,
) {
    val showHomeActions = !controlsOpen && destination == CarePadDestination.HOME && hasModules
    val text = when (inputMethod) {
        CarePadInputMethod.TOUCH -> if (showHomeActions) {
            stringResource(R.string.carepad_hint_touch_home)
        } else {
            stringResource(R.string.carepad_hint_touch_navigation)
        }

        CarePadInputMethod.CONTROLLER -> if (showHomeActions) {
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

@Composable
private fun Modifier.combinedClickable(
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier = cozyCombinedClickable(
    enabled = enabled,
    onClick = onClick,
    onLongClick = onLongClick,
)

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

private fun controllerDirection(keyCode: Int): FocusDirection? = when (keyCode) {
    AndroidKeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
    else -> null
}

private fun isActiveControllerHatMotion(event: MotionEvent): Boolean {
    if (event.actionMasked != MotionEvent.ACTION_MOVE || !isControllerSource(event.source)) {
        return false
    }
    val x = event.getAxisValue(MotionEvent.AXIS_HAT_X)
    val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
    return (x.isFinite() && x != 0f) || (y.isFinite() && y != 0f)
}

private fun isControllerHatNeutral(event: MotionEvent): Boolean {
    val x = event.getAxisValue(MotionEvent.AXIS_HAT_X)
    val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
    return x.isFinite() && y.isFinite() && x == 0f && y == 0f
}

private fun controllerKeyHasHatRange(event: AndroidKeyEvent): Boolean =
    event.device?.motionRanges?.any { range ->
        (range.axis == MotionEvent.AXIS_HAT_X || range.axis == MotionEvent.AXIS_HAT_Y) &&
            (range.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    } == true

private fun isSignificantControllerMotion(event: MotionEvent): Boolean {
    if (event.actionMasked != MotionEvent.ACTION_MOVE || !isControllerSource(event.source)) {
        return false
    }
    if (isActiveControllerHatMotion(event)) return true
    val device = event.device ?: return false
    return ControllerMotionAxes.any { axis ->
        val ranges = device.motionRanges.filter { range ->
            range.axis == axis &&
                (range.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        }
        val range = ranges.singleOrNull() ?: return@any false
        if (
            !range.min.isFinite() ||
            !range.max.isFinite() ||
            range.min >= range.max
        ) {
            return@any false
        }

        fun significant(value: Float): Boolean {
            if (!value.isFinite()) return false
            if (axis == MotionEvent.AXIS_HAT_X || axis == MotionEvent.AXIS_HAT_Y) {
                return value != 0f
            }
            val tolerance = maxOf(range.flat, range.fuzz)
            if (tolerance <= 0f) return false
            val center = (range.min + range.max) / 2f
            return abs(value - center) > tolerance
        }

        (0 until event.historySize).any { historyIndex ->
            significant(event.getHistoricalAxisValue(axis, historyIndex))
        } || significant(event.getAxisValue(axis))
    }
}

private fun isControllerSource(source: Int): Boolean =
    (source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
        (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD ||
        (source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
