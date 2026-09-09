package dev.carepad.module.controls.internalui

import android.content.Context
import android.graphics.Paint
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.carepad.module.controls.runtime.AndroidDeviceCatalog
import dev.carepad.module.controls.runtime.AndroidEventMapper
import dev.carepad.module.controls.runtime.Axes
import dev.carepad.module.controls.runtime.Button as ControlButton
import dev.carepad.module.controls.runtime.ControlsSession
import dev.carepad.module.controls.runtime.DeviceInfo
import dev.carepad.module.controls.runtime.Direction
import dev.carepad.module.controls.runtime.KeyAction
import dev.carepad.module.controls.runtime.KeySample
import dev.carepad.module.controls.runtime.Resolution
import dev.carepad.module.controls.runtime.SessionState
import java.util.Locale

internal enum class Screen { MAIN, GUIDED, DETECTED }
internal enum class GuidedStage { PREPARE, DIGITAL, LEFT_REST, LEFT_MOVE, RIGHT_REST, RIGHT_MOVE, SUMMARY }
internal enum class Outcome { OBSERVED, NOT_DETECTED, INCONCLUSIVE }
private enum class ControllerFamily { PLAYSTATION, XBOX, NINTENDO, GENERIC }
private enum class DiagramControl {
    FACE_BOTTOM, FACE_RIGHT, FACE_LEFT, FACE_TOP,
    DPAD_UP, DPAD_RIGHT, DPAD_DOWN, DPAD_LEFT,
    LEFT_STICK, RIGHT_STICK,
}

private data class DigitalTarget(
    val button: ControlButton,
    @StringRes val nameRes: Int,
    val diagramControl: DiagramControl,
)

private val digitalTargets = listOf(
    DigitalTarget(ControlButton.A, R.string.button_a, DiagramControl.FACE_BOTTOM),
    DigitalTarget(ControlButton.B, R.string.button_b, DiagramControl.FACE_RIGHT),
    DigitalTarget(ControlButton.X, R.string.button_x, DiagramControl.FACE_LEFT),
    DigitalTarget(ControlButton.Y, R.string.button_y, DiagramControl.FACE_TOP),
    DigitalTarget(ControlButton.DPAD_UP, R.string.dpad_up, DiagramControl.DPAD_UP),
    DigitalTarget(ControlButton.DPAD_RIGHT, R.string.dpad_right, DiagramControl.DPAD_RIGHT),
    DigitalTarget(ControlButton.DPAD_DOWN, R.string.dpad_down, DiagramControl.DPAD_DOWN),
    DigitalTarget(ControlButton.DPAD_LEFT, R.string.dpad_left, DiagramControl.DPAD_LEFT),
)

/** Raw Android input bridge used only while the internal Controls surface is visible. */
class ControlsInternalController(context: Context) : InputManager.InputDeviceListener {
    private val appContext = context.applicationContext
    private val inputManager = appContext.getSystemService(InputManager::class.java)
    private val deviceCatalog = AndroidDeviceCatalog(inputManager)
    private val handler = Handler(Looper.getMainLooper())

    private var started = false
    private var session: ControlsSession? = null
    private var pendingExit: (() -> Unit)? = null
    private var attemptReadyAt = 0L
    private var attemptBaselineTrajectoryCount = 0
    private var attemptGeneration = 0L
    private var activityGeneration = 0L
    private var detectedRefreshGeneration = 0L

    internal var screen by mutableStateOf(Screen.MAIN)
        private set
    internal var guidedStage by mutableStateOf(GuidedStage.PREPARE)
        private set
    internal var candidates by mutableStateOf<List<DeviceInfo>>(emptyList())
        private set
    internal var selectedDeviceId by mutableStateOf<Int?>(null)
        private set
    internal var digitalTargetIndex by mutableIntStateOf(0)
        private set
    internal var attemptArmed by mutableStateOf(false)
        private set
    internal var attemptCanFail by mutableStateOf(false)
        private set
    internal var showLeaveDialog by mutableStateOf(false)
        private set
    internal var activityDeviceId by mutableStateOf<Int?>(null)
        private set
    internal var revision by mutableIntStateOf(0)
        private set

    private val digitalOutcomes = mutableStateMapOf<ControlButton, Outcome>()
    private val stickOutcomes = mutableStateMapOf<DiagramControl, Outcome>()

    fun start() {
        if (started) return
        started = true
        inputManager.registerInputDeviceListener(this, null)
        syncSelection()
        if (screen != Screen.MAIN && session?.state == SessionState.INVALIDATED) {
            returnToMainAfterDeviceLoss(R.string.controller_session_invalidated)
        } else {
            revision++
        }
    }

    fun stop() {
        if (!started) return
        started = false
        cancelAttempt()
        session?.interrupt()
        session = null
        runCatching { inputManager.unregisterInputDeviceListener(this) }
    }

    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && isControllerSource(event.source)) {
            noteControllerActivity(event.deviceId)
        }

        val activeSession = session
        if (screen == Screen.GUIDED && attemptArmed && activeSession != null) {
            val sample = AndroidEventMapper.key(event)
            if (sample != null) {
                val result = activeSession.acceptKey(sample)
                if (result.changed) {
                    revision++
                    if (event.eventTime >= attemptReadyAt) observeGuidedKey(sample)
                }
                if (result.consumeInTestMode) return true
            }
        } else if (screen == Screen.DETECTED && activeSession != null) {
            AndroidEventMapper.key(event)?.let { sample ->
                if (activeSession.acceptKey(sample).changed) scheduleDetectedRefresh()
            }
        }
        return false
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val activeSession = session
        if (
            screen == Screen.GUIDED &&
            attemptArmed &&
            activeSession != null &&
            event.deviceId == activeSession.device.deviceId
        ) {
            val frames = AndroidEventMapper.motion(event, AndroidEventMapper.axes(activeSession.mapping))
            var consumed = false
            var changed = false
            frames.forEach { frame ->
                val result = activeSession.acceptMotion(frame)
                consumed = consumed || result.consumeInTestMode
                changed = changed || result.changed
            }
            if (changed) {
                revision++
                if (event.eventTime >= attemptReadyAt) observeGuidedMotion()
            }
            if (consumed) return true
        } else if (
            screen == Screen.DETECTED &&
            activeSession != null &&
            event.deviceId == activeSession.device.deviceId
        ) {
            val frames = AndroidEventMapper.motion(event, AndroidEventMapper.axes(activeSession.mapping))
            var changed = false
            frames.forEach { frame -> changed = activeSession.acceptMotion(frame).changed || changed }
            if (changed) scheduleDetectedRefresh()
        }
        return false
    }

    /** Returns true when Controls consumed Back locally; false means the shell should leave Controles. */
    fun handleBack(): Boolean {
        return when (screen) {
            Screen.MAIN -> false
            Screen.DETECTED -> {
                exitSecondarySurface()
                true
            }
            Screen.GUIDED -> {
                handleGuidedBack()
                true
            }
        }
    }

    /** Defers a global-shell exit when an unfinished guided test needs confirmation. */
    fun requestExit(onConfirmed: () -> Unit) {
        if (screen == Screen.GUIDED && guidedStage != GuidedStage.SUMMARY) {
            pendingExit = onConfirmed
            showLeaveDialog = true
        } else {
            onConfirmed()
        }
    }

    internal fun cancelLeaveDialog() {
        pendingExit = null
        showLeaveDialog = false
    }

    internal fun confirmLeaveDialog() {
        val exit = pendingExit
        pendingExit = null
        showLeaveDialog = false
        cancelAttempt()
        session?.interrupt()
        session = null
        screen = Screen.MAIN
        guidedStage = GuidedStage.PREPARE
        if (exit != null) exit() else revision++
    }

    internal fun selectedDevice(): DeviceInfo? =
        selectedDeviceId?.let { id -> candidates.firstOrNull { it.deviceId == id } }

    internal fun selectController(deviceId: Int) {
        session?.interrupt()
        session = null
        selectedDeviceId = deviceId
        revision++
    }

    internal fun startGuidedTest() {
        if (freshSelectedDevice() == null) return
        session?.interrupt()
        session = null
        digitalOutcomes.clear()
        stickOutcomes.clear()
        digitalTargetIndex = 0
        guidedStage = GuidedStage.PREPARE
        screen = Screen.GUIDED
        revision++
    }

    internal fun startDetectedInputs() {
        val device = freshSelectedDevice() ?: return
        session?.interrupt()
        session = ControlsSession(device)
        screen = Screen.DETECTED
        revision++
    }

    internal fun beginGuidedSequence() {
        val device = freshSelectedDevice() ?: return
        session?.interrupt()
        session = ControlsSession(device)
        guidedStage = GuidedStage.DIGITAL
        digitalTargetIndex = 0
        revision++
    }

    internal fun continueDigital() {
        if (digitalTargetIndex < digitalTargets.lastIndex) {
            digitalTargetIndex++
        } else {
            guidedStage = GuidedStage.LEFT_REST
        }
        revision++
    }

    internal fun continueFromStickRest(left: Boolean) {
        val activeSession = session ?: return
        val control = if (left) DiagramControl.LEFT_STICK else DiagramControl.RIGHT_STICK
        val resolution = if (left) activeSession.mapping.left.state else activeSession.mapping.right.state
        if (resolution != Resolution.STANDARD && stickOutcomes[control] == null) {
            stickOutcomes[control] = Outcome.INCONCLUSIVE
        }
        guidedStage = if (left) GuidedStage.LEFT_MOVE else GuidedStage.RIGHT_MOVE
        revision++
    }

    internal fun continueFromStickMove(left: Boolean) {
        guidedStage = if (left) GuidedStage.RIGHT_REST else GuidedStage.SUMMARY
        revision++
    }

    internal fun startAttempt() {
        val activeSession = session ?: return
        if (activeSession.state == SessionState.INVALIDATED) return
        cancelAttempt()
        attemptArmed = true
        attemptCanFail = false
        attemptReadyAt = SystemClock.uptimeMillis() + ATTEMPT_ARM_DELAY_MS
        attemptBaselineTrajectoryCount = currentTrajectoryCount(activeSession)
        val generation = ++attemptGeneration
        revision++
        handler.postDelayed({
            if (screen == Screen.GUIDED && attemptArmed && generation == attemptGeneration) {
                attemptArmed = false
                attemptCanFail = true
                revision++
            }
        }, ATTEMPT_WINDOW_MS)
    }

    internal fun markCurrentNotDetected() {
        if (!attemptCanFail) return
        when (guidedStage) {
            GuidedStage.DIGITAL -> digitalOutcomes[digitalTargets[digitalTargetIndex].button] = Outcome.NOT_DETECTED
            GuidedStage.LEFT_MOVE -> stickOutcomes[DiagramControl.LEFT_STICK] = Outcome.NOT_DETECTED
            GuidedStage.RIGHT_MOVE -> stickOutcomes[DiagramControl.RIGHT_STICK] = Outcome.NOT_DETECTED
            else -> return
        }
        cancelAttempt()
        revision++
    }

    internal fun digitalOutcome(): Outcome? = digitalOutcomes[digitalTargets[digitalTargetIndex].button]
    internal fun stickOutcome(left: Boolean): Outcome? =
        stickOutcomes[if (left) DiagramControl.LEFT_STICK else DiagramControl.RIGHT_STICK]

    internal fun stickResolution(left: Boolean): Resolution? = session?.let {
        if (left) it.mapping.left.state else it.mapping.right.state
    }

    internal fun observedPath(left: Boolean): List<Pair<Float, Float>> {
        val activeSession = session ?: return emptyList()
        val trajectory = if (left) activeSession.leftMetrics().trajectory else activeSession.rightMetrics().trajectory
        return trajectory.mapNotNull { sample ->
            val x = sample.normalizedX
            val y = sample.normalizedY
            if (x == null || y == null) null else x to y
        }.takeLast(MAX_GUIDED_TRAJECTORY_POINTS)
    }

    internal fun aggregateDigitalOutcome(): Outcome {
        val values = digitalTargets.map { digitalOutcomes[it.button] }
        return when {
            values.all { it == Outcome.OBSERVED } -> Outcome.OBSERVED
            values.any { it == Outcome.NOT_DETECTED } -> Outcome.NOT_DETECTED
            else -> Outcome.INCONCLUSIVE
        }
    }

    internal fun summaryStickOutcome(left: Boolean): Outcome =
        stickOutcome(left) ?: Outcome.INCONCLUSIVE

    internal fun activeSession(): ControlsSession? = session

    override fun onInputDeviceAdded(deviceId: Int) {
        syncSelection()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val wasSelected = selectedDeviceId == deviceId
        session?.onRemoved(deviceId)
        syncSelection()
        if (wasSelected && screen != Screen.MAIN) {
            returnToMainAfterDeviceLoss(R.string.controller_disconnected)
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        val wasSelected = selectedDeviceId == deviceId
        session?.onChanged(deviceId)
        syncSelection()
        if (wasSelected && screen != Screen.MAIN) {
            returnToMainAfterDeviceLoss(R.string.controller_changed)
        }
    }

    private fun syncSelection() {
        val updated = deviceCatalog.candidates()
        val current = selectedDeviceId
        selectedDeviceId = when {
            current != null && updated.any { it.deviceId == current } -> current
            updated.size == 1 -> updated.single().deviceId
            else -> null
        }
        candidates = updated
        revision++
    }

    private fun freshSelectedDevice(): DeviceInfo? = selectedDeviceId?.let(deviceCatalog::byId)

    private fun noteControllerActivity(deviceId: Int) {
        if (screen != Screen.MAIN || candidates.none { it.deviceId == deviceId }) return
        activityDeviceId = deviceId
        val generation = ++activityGeneration
        handler.postDelayed({
            if (generation == activityGeneration && activityDeviceId == deviceId) {
                activityDeviceId = null
            }
        }, ACTIVITY_HINT_MS)
    }

    private fun observeGuidedKey(sample: KeySample) {
        if (!attemptArmed || sample.action != KeyAction.DOWN || sample.repeatCount != 0) return
        if (guidedStage != GuidedStage.DIGITAL) return
        if (sample.button == digitalTargets[digitalTargetIndex].button) finishAttemptObserved()
    }

    private fun observeGuidedMotion() {
        if (!attemptArmed) return
        val activeSession = session ?: return
        when (guidedStage) {
            GuidedStage.DIGITAL -> {
                val direction = when (digitalTargets[digitalTargetIndex].button) {
                    ControlButton.DPAD_UP -> Direction.UP
                    ControlButton.DPAD_RIGHT -> Direction.RIGHT
                    ControlButton.DPAD_DOWN -> Direction.DOWN
                    ControlButton.DPAD_LEFT -> Direction.LEFT
                    else -> null
                }
                if (direction != null && direction in activeSession.dpadPath.lastOrNull()?.directions.orEmpty()) {
                    finishAttemptObserved()
                }
            }
            GuidedStage.LEFT_MOVE, GuidedStage.RIGHT_MOVE -> {
                if (currentTrajectoryCount(activeSession) > attemptBaselineTrajectoryCount) {
                    finishAttemptObserved()
                }
            }
            else -> Unit
        }
    }

    private fun finishAttemptObserved() {
        when (guidedStage) {
            GuidedStage.DIGITAL -> digitalOutcomes[digitalTargets[digitalTargetIndex].button] = Outcome.OBSERVED
            GuidedStage.LEFT_MOVE -> stickOutcomes[DiagramControl.LEFT_STICK] = Outcome.OBSERVED
            GuidedStage.RIGHT_MOVE -> stickOutcomes[DiagramControl.RIGHT_STICK] = Outcome.OBSERVED
            else -> return
        }
        cancelAttempt()
        revision++
    }

    private fun currentTrajectoryCount(activeSession: ControlsSession): Int = when (guidedStage) {
        GuidedStage.LEFT_MOVE -> activeSession.leftMetrics().trajectory.size
        GuidedStage.RIGHT_MOVE -> activeSession.rightMetrics().trajectory.size
        else -> 0
    }

    private fun cancelAttempt() {
        attemptArmed = false
        attemptCanFail = false
        attemptReadyAt = 0L
        attemptBaselineTrajectoryCount = 0
        attemptGeneration++
    }

    private fun handleGuidedBack() {
        cancelAttempt()
        when (guidedStage) {
            GuidedStage.PREPARE -> showLeaveDialog = true
            GuidedStage.DIGITAL -> {
                if (digitalTargetIndex > 0) digitalTargetIndex-- else guidedStage = GuidedStage.PREPARE
                revision++
            }
            GuidedStage.LEFT_REST -> {
                guidedStage = GuidedStage.DIGITAL
                digitalTargetIndex = digitalTargets.lastIndex
                revision++
            }
            GuidedStage.LEFT_MOVE -> { guidedStage = GuidedStage.LEFT_REST; revision++ }
            GuidedStage.RIGHT_REST -> { guidedStage = GuidedStage.LEFT_MOVE; revision++ }
            GuidedStage.RIGHT_MOVE -> { guidedStage = GuidedStage.RIGHT_REST; revision++ }
            GuidedStage.SUMMARY -> { guidedStage = GuidedStage.RIGHT_MOVE; revision++ }
        }
    }

    internal fun exitSecondarySurface() {
        cancelAttempt()
        session?.interrupt()
        session = null
        screen = Screen.MAIN
        guidedStage = GuidedStage.PREPARE
        syncSelection()
    }

    private fun returnToMainAfterDeviceLoss(@StringRes messageRes: Int) {
        cancelAttempt()
        session = null
        screen = Screen.MAIN
        guidedStage = GuidedStage.PREPARE
        syncSelection()
        Toast.makeText(appContext, messageRes, Toast.LENGTH_LONG).show()
    }

    private fun scheduleDetectedRefresh() {
        revision++
        val generation = ++detectedRefreshGeneration
        handler.postDelayed({
            if (screen == Screen.DETECTED && generation == detectedRefreshGeneration) revision++
        }, LIVE_ACTIVITY_WINDOW_MS + 40L)
    }

    private companion object {
        const val ATTEMPT_ARM_DELAY_MS = 250L
        const val ATTEMPT_WINDOW_MS = 1800L
        const val LIVE_ACTIVITY_WINDOW_MS = 520L
        const val ACTIVITY_HINT_MS = 900L
        const val MAX_GUIDED_TRAJECTORY_POINTS = 96
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ControlsInternalScreen(
    controller: ControlsInternalController,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(controller) {
        controller.start()
        onDispose { controller.stop() }
    }
    controller.revision
    val view = LocalView.current
    val feedback = {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        view.playSoundEffect(SoundEffectConstants.CLICK)
    }

    if (controller.showLeaveDialog) {
        AlertDialog(
            onDismissRequest = controller::cancelLeaveDialog,
            title = { Text(stringResource(R.string.leave_test_title)) },
            text = { Text(stringResource(R.string.leave_test_message)) },
            dismissButton = {
                TextButton(onClick = { feedback(); controller.cancelLeaveDialog() }) {
                    Text(stringResource(R.string.keep_testing))
                }
            },
            confirmButton = {
                TextButton(onClick = { feedback(); controller.confirmLeaveDialog() }) {
                    Text(stringResource(R.string.leave_test))
                }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            val wide = maxWidth >= 600.dp && maxWidth >= maxHeight
            when (controller.screen) {
                Screen.MAIN -> ControlsMain(controller, wide, feedback)
                Screen.GUIDED -> GuidedContent(controller, wide, feedback)
                Screen.DETECTED -> DetectedInputs(controller, wide, feedback)
            }
        }
    }
}

@Composable
private fun ControlsMain(
    controller: ControlsInternalController,
    wide: Boolean,
    feedback: () -> Unit,
) {
    val selected = controller.selectedDevice()
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.controls_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ControllerCard(controller, Modifier.weight(1f), feedback)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ActionCard(
                        title = stringResource(R.string.guided_test),
                        description = actionDescription(controller, selected, R.string.guided_test_description, R.string.connect_controller_to_start, R.string.choose_controller_to_start),
                        enabled = selected != null,
                        primary = true,
                        feedback = feedback,
                        action = controller::startGuidedTest,
                    )
                    ActionCard(
                        title = stringResource(R.string.detected_inputs),
                        description = actionDescription(controller, selected, R.string.detected_inputs_description, R.string.connect_controller_to_view_inputs, R.string.choose_controller_to_view_inputs),
                        enabled = selected != null,
                        primary = false,
                        feedback = feedback,
                        action = controller::startDetectedInputs,
                    )
                }
            }
        } else {
            ControllerCard(controller, Modifier.fillMaxWidth(), feedback)
            ActionCard(
                title = stringResource(R.string.guided_test),
                description = actionDescription(controller, selected, R.string.guided_test_description, R.string.connect_controller_to_start, R.string.choose_controller_to_start),
                enabled = selected != null,
                primary = true,
                feedback = feedback,
                action = controller::startGuidedTest,
            )
            ActionCard(
                title = stringResource(R.string.detected_inputs),
                description = actionDescription(controller, selected, R.string.detected_inputs_description, R.string.connect_controller_to_view_inputs, R.string.choose_controller_to_view_inputs),
                enabled = selected != null,
                primary = false,
                feedback = feedback,
                action = controller::startDetectedInputs,
            )
        }
    }
}

@Composable
private fun actionDescription(
    controller: ControlsInternalController,
    selected: DeviceInfo?,
    @StringRes available: Int,
    @StringRes noDevice: Int,
    @StringRes chooseDevice: Int,
): String = stringResource(
    when {
        selected != null -> available
        controller.candidates.isEmpty() -> noDevice
        else -> chooseDevice
    }
)

@Composable
private fun ControllerCard(
    controller: ControlsInternalController,
    modifier: Modifier,
    feedback: () -> Unit,
) {
    Card(modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.controller_section), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val selected = controller.selectedDevice()
            when {
                controller.candidates.isEmpty() -> Text(stringResource(R.string.no_devices_friendly))
                controller.candidates.size == 1 && selected != null -> {
                    Text(friendlyDeviceName(selected), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.controller_connected), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    Text(stringResource(if (selected == null) R.string.choose_controller_help else R.string.change_controller_help))
                    controller.candidates.forEach { device ->
                        FocusOutlinedButton(
                            text = if (controller.activityDeviceId == device.deviceId) {
                                stringResource(R.string.controller_activity_option, friendlyDeviceName(device))
                            } else friendlyDeviceName(device),
                            enabled = true,
                            feedback = feedback,
                        ) { controller.selectController(device.deviceId) }
                    }
                    Text(
                        stringResource(R.string.activity_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    description: String,
    enabled: Boolean,
    primary: Boolean,
    feedback: () -> Unit,
    action: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (primary) {
                FocusButton(title, enabled, feedback, action)
            } else {
                FocusOutlinedButton(title, enabled, feedback, action)
            }
        }
    }
}

@Composable
private fun GuidedContent(
    controller: ControlsInternalController,
    wide: Boolean,
    feedback: () -> Unit,
) {
    val device = controller.selectedDevice() ?: return
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.guided_breadcrumb), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(friendlyDeviceName(device), color = MaterialTheme.colorScheme.onSurfaceVariant)
        when (controller.guidedStage) {
            GuidedStage.PREPARE -> Preparation(controller, device, feedback)
            GuidedStage.DIGITAL -> DigitalStep(controller, device, feedback)
            GuidedStage.LEFT_REST -> StickRest(controller, device, true, feedback)
            GuidedStage.LEFT_MOVE -> StickMove(controller, device, true, feedback)
            GuidedStage.RIGHT_REST -> StickRest(controller, device, false, feedback)
            GuidedStage.RIGHT_MOVE -> StickMove(controller, device, false, feedback)
            GuidedStage.SUMMARY -> GuidedSummary(controller, device, feedback)
        }
        if (wide) Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun Preparation(controller: ControlsInternalController, device: DeviceInfo, feedback: () -> Unit) {
    SectionCard(stringResource(R.string.prepare_test)) {
        Text(stringResource(R.string.prepare_test_instruction))
        Supporting(stringResource(R.string.prepare_test_note))
        ControllerDiagram(device = device)
        GuidedButtons(
            backText = stringResource(R.string.back),
            primaryText = stringResource(R.string.start_test),
            feedback = feedback,
            back = { controller.handleBack() },
            primary = controller::beginGuidedSequence,
        )
    }
}

@Composable
private fun DigitalStep(controller: ControlsInternalController, device: DeviceInfo, feedback: () -> Unit) {
    val target = digitalTargets[controller.digitalTargetIndex]
    val outcome = controller.digitalOutcome()
    SectionCard(stringResource(R.string.buttons_and_dpad)) {
        Supporting(stringResource(R.string.control_counter, controller.digitalTargetIndex + 1, digitalTargets.size))
        Text(stringResource(R.string.digital_target_instruction, stringResource(target.nameRes)))
        ControllerDiagram(device = device, highlighted = target.diagramControl)
        Supporting(
            when {
                controller.attemptArmed -> stringResource(R.string.listening_for_attempt)
                controller.attemptCanFail -> stringResource(R.string.attempt_not_seen_yet)
                outcome == Outcome.OBSERVED -> stringResource(R.string.control_observed)
                outcome == Outcome.NOT_DETECTED -> stringResource(R.string.control_not_detected_after_attempt)
                outcome == Outcome.INCONCLUSIVE -> stringResource(R.string.inconclusive)
                else -> stringResource(R.string.ready_for_explicit_attempt)
            }
        )
        if (outcome == null) {
            FocusButton(stringResource(R.string.try_this_control), !controller.attemptArmed, feedback, controller::startAttempt)
            FocusOutlinedButton(stringResource(R.string.tried_not_detected), controller.attemptCanFail, feedback, controller::markCurrentNotDetected)
        } else {
            FocusButton(
                stringResource(if (controller.digitalTargetIndex == digitalTargets.lastIndex) R.string.continue_label else R.string.next_control),
                true,
                feedback,
                controller::continueDigital,
            )
        }
        FocusOutlinedButton(stringResource(R.string.back), true, feedback) { controller.handleBack() }
    }
}

@Composable
private fun StickRest(
    controller: ControlsInternalController,
    device: DeviceInfo,
    left: Boolean,
    feedback: () -> Unit,
) {
    SectionCard(stringResource(if (left) R.string.left_stick else R.string.right_stick)) {
        Text(stringResource(if (left) R.string.left_rest_instruction else R.string.right_rest_instruction))
        Supporting(stringResource(R.string.rest_is_observation_not_diagnosis))
        ControllerDiagram(
            device = device,
            highlighted = if (left) DiagramControl.LEFT_STICK else DiagramControl.RIGHT_STICK,
            showCenterGuide = true,
        )
        GuidedButtons(
            backText = stringResource(R.string.back),
            primaryText = stringResource(R.string.stick_is_still),
            feedback = feedback,
            back = { controller.handleBack() },
            primary = { controller.continueFromStickRest(left) },
        )
    }
}

@Composable
private fun StickMove(
    controller: ControlsInternalController,
    device: DeviceInfo,
    left: Boolean,
    feedback: () -> Unit,
) {
    val resolution = controller.stickResolution(left) ?: Resolution.INCONCLUSIVE
    val outcome = controller.stickOutcome(left)
    SectionCard(stringResource(if (left) R.string.left_stick else R.string.right_stick)) {
        Text(stringResource(if (left) R.string.left_move_instruction else R.string.right_move_instruction))
        ControllerDiagram(
            device = device,
            highlighted = if (left) DiagramControl.LEFT_STICK else DiagramControl.RIGHT_STICK,
            showMovementGuide = true,
            observedPath = controller.observedPath(left),
        )
        Supporting(
            when {
                resolution != Resolution.STANDARD -> stringResource(R.string.mapping_inconclusive)
                controller.attemptArmed -> stringResource(R.string.listening_for_attempt)
                controller.attemptCanFail -> stringResource(R.string.attempt_not_seen_yet)
                outcome == Outcome.OBSERVED -> stringResource(R.string.stick_observed)
                outcome == Outcome.NOT_DETECTED -> stringResource(R.string.control_not_detected_after_attempt)
                else -> stringResource(R.string.ready_for_explicit_attempt)
            }
        )
        if (resolution == Resolution.STANDARD && outcome == null) {
            FocusButton(stringResource(R.string.try_stick_movement), !controller.attemptArmed, feedback, controller::startAttempt)
            FocusOutlinedButton(stringResource(R.string.tried_not_detected), controller.attemptCanFail, feedback, controller::markCurrentNotDetected)
        } else {
            FocusButton(stringResource(R.string.continue_label), true, feedback) { controller.continueFromStickMove(left) }
        }
        FocusOutlinedButton(stringResource(R.string.back), true, feedback) { controller.handleBack() }
    }
}

@Composable
private fun GuidedSummary(controller: ControlsInternalController, device: DeviceInfo, feedback: () -> Unit) {
    val digital = controller.aggregateDigitalOutcome()
    val left = controller.summaryStickOutcome(true)
    val right = controller.summaryStickOutcome(false)
    val needsReview = listOf(digital, left, right).any { it != Outcome.OBSERVED }
    SectionCard(stringResource(R.string.test_finished)) {
        Text(stringResource(if (needsReview) R.string.summary_review_items else R.string.summary_nothing_unusual))
        SummaryRow(stringResource(R.string.buttons_and_dpad), outcomeLabel(digital))
        SummaryRow(stringResource(R.string.left_stick), outcomeLabel(left))
        SummaryRow(stringResource(R.string.right_stick), outcomeLabel(right))
        Supporting(stringResource(R.string.controls_scope_note))
        ControllerDiagram(device = device)
        GuidedButtons(
            backText = stringResource(R.string.back),
            primaryText = stringResource(R.string.back_to_controls),
            feedback = feedback,
            back = { controller.handleBack() },
            primary = controller::exitSecondarySurface,
        )
    }
}

@Composable
private fun DetectedInputs(
    controller: ControlsInternalController,
    wide: Boolean,
    feedback: () -> Unit,
) {
    val session = controller.activeSession() ?: return
    val rows = detectedRows(session)
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.detected_breadcrumb), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(friendlyDeviceName(session.device), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Supporting(stringResource(R.string.detected_inputs_scope_note))
        SectionCard(stringResource(R.string.detected_inputs)) {
            if (rows.isEmpty()) {
                Text(stringResource(R.string.no_inputs_to_show))
            } else if (wide) {
                DenseRow(stringResource(R.string.input_column), stringResource(R.string.android_label_column), stringResource(R.string.state_column), header = true)
                rows.forEach { row -> DenseRow(row.friendly, row.androidLabel, row.state, active = row.active) }
            } else {
                rows.forEach { row ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (row.active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(row.friendly, fontWeight = FontWeight.SemiBold)
                            Supporting(row.androidLabel)
                            Supporting(row.state)
                        }
                    }
                }
            }
        }
        FocusOutlinedButton(stringResource(R.string.back), true, feedback, controller::exitSecondarySurface)
    }
}

private data class DetectedRow(val friendly: String, val androidLabel: String, val state: String, val active: Boolean)

@Composable
private fun detectedRows(session: ControlsSession): List<DetectedRow> {
    val resources = LocalContext.current.resources
    return buildList {
        session.device.keys.sortedBy { it.ordinal }.forEach { button ->
            val active = when (button) {
                ControlButton.DPAD_UP -> Direction.UP in session.dpadPath.lastOrNull()?.directions.orEmpty()
                ControlButton.DPAD_DOWN -> Direction.DOWN in session.dpadPath.lastOrNull()?.directions.orEmpty()
                ControlButton.DPAD_LEFT -> Direction.LEFT in session.dpadPath.lastOrNull()?.directions.orEmpty()
                ControlButton.DPAD_RIGHT -> Direction.RIGHT in session.dpadPath.lastOrNull()?.directions.orEmpty()
                else -> session.buttonMetrics(button).pressed
            }
            add(
                DetectedRow(
                    resources.getString(buttonFriendlyNameRes(button)),
                    buttonAndroidLabel(button),
                    resources.getString(if (active) R.string.input_active else R.string.input_idle),
                    active,
                )
            )
        }
        val latest = session.rawMotion.lastOrNull()
        fun addAxis(@StringRes friendlyRes: Int, axis: Int) {
            val value = latest?.axes?.get(axis)
            val active = latest != null && SystemClock.uptimeMillis() - latest.timeMs <= 520L
            val state = if (value == null) {
                resources.getString(R.string.no_signal_yet)
            } else {
                resources.getString(
                    if (active) R.string.input_active_value else R.string.input_value,
                    String.format(Locale.getDefault(), "%+.2f", value),
                )
            }
            add(DetectedRow(resources.getString(friendlyRes), axisAndroidLabel(axis), state, active))
        }
        session.mapping.left.pair?.let {
            addAxis(R.string.left_stick_x, it.x)
            addAxis(R.string.left_stick_y, it.y)
        }
        session.mapping.right.pair?.let {
            addAxis(R.string.right_stick_x, it.x)
            addAxis(R.string.right_stick_y, it.y)
        }
        session.mapping.hat?.let {
            addAxis(R.string.dpad_horizontal, it.x)
            addAxis(R.string.dpad_vertical, it.y)
        }
    }
}

@StringRes
private fun buttonFriendlyNameRes(button: ControlButton): Int = when (button) {
    ControlButton.A -> R.string.button_a
    ControlButton.B -> R.string.button_b
    ControlButton.X -> R.string.button_x
    ControlButton.Y -> R.string.button_y
    ControlButton.L1 -> R.string.button_l1
    ControlButton.R1 -> R.string.button_r1
    ControlButton.THUMBL -> R.string.left_stick_press
    ControlButton.THUMBR -> R.string.right_stick_press
    ControlButton.START -> R.string.start_button
    ControlButton.SELECT -> R.string.select_button
    ControlButton.MODE -> R.string.center_button
    ControlButton.DPAD_UP -> R.string.dpad_up
    ControlButton.DPAD_DOWN -> R.string.dpad_down
    ControlButton.DPAD_LEFT -> R.string.dpad_left
    ControlButton.DPAD_RIGHT -> R.string.dpad_right
}

private fun buttonAndroidLabel(button: ControlButton): String = when (button) {
    ControlButton.A -> "BUTTON_A"
    ControlButton.B -> "BUTTON_B"
    ControlButton.X -> "BUTTON_X"
    ControlButton.Y -> "BUTTON_Y"
    ControlButton.L1 -> "BUTTON_L1"
    ControlButton.R1 -> "BUTTON_R1"
    ControlButton.THUMBL -> "BUTTON_THUMBL"
    ControlButton.THUMBR -> "BUTTON_THUMBR"
    ControlButton.START -> "BUTTON_START"
    ControlButton.SELECT -> "BUTTON_SELECT"
    ControlButton.MODE -> "BUTTON_MODE"
    ControlButton.DPAD_UP -> "DPAD_UP"
    ControlButton.DPAD_DOWN -> "DPAD_DOWN"
    ControlButton.DPAD_LEFT -> "DPAD_LEFT"
    ControlButton.DPAD_RIGHT -> "DPAD_RIGHT"
}

private fun axisAndroidLabel(axis: Int): String = when (axis) {
    Axes.X -> "AXIS_X"
    Axes.Y -> "AXIS_Y"
    Axes.Z -> "AXIS_Z"
    Axes.RX -> "AXIS_RX"
    Axes.RY -> "AXIS_RY"
    Axes.RZ -> "AXIS_RZ"
    Axes.HAT_X -> "AXIS_HAT_X"
    Axes.HAT_Y -> "AXIS_HAT_Y"
    else -> "AXIS_$axis"
}

@Composable
private fun outcomeLabel(outcome: Outcome): String = stringResource(when (outcome) {
    Outcome.OBSERVED -> R.string.observed
    Outcome.NOT_DETECTED -> R.string.not_detected
    Outcome.INCONCLUSIVE -> R.string.inconclusive
})

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun Supporting(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun GuidedButtons(
    backText: String,
    primaryText: String,
    feedback: () -> Unit,
    back: () -> Unit,
    primary: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints(Modifier.weight(1f)) {
            FocusOutlinedButton(backText, true, feedback, back)
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            FocusButton(primaryText, true, feedback, primary)
        }
    }
}

@Composable
private fun FocusButton(text: String, enabled: Boolean, feedback: () -> Unit, action: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    Button(
        enabled = enabled,
        onClick = { feedback(); action() },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
        shape = shape,
    ) { Text(text) }
}

@Composable
private fun FocusOutlinedButton(text: String, enabled: Boolean, feedback: () -> Unit, action: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    OutlinedButton(
        enabled = enabled,
        onClick = { feedback(); action() },
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .then(if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier),
        shape = shape,
    ) { Text(text) }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DenseRow(first: String, second: String, third: String, header: Boolean = false, active: Boolean = false) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = when {
            active -> MaterialTheme.colorScheme.secondaryContainer
            header -> MaterialTheme.colorScheme.surfaceVariant
            else -> MaterialTheme.colorScheme.surface
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(first, Modifier.weight(1f), fontWeight = if (header) FontWeight.Bold else FontWeight.Normal)
            Text(second, Modifier.weight(1f), fontWeight = if (header) FontWeight.Bold else FontWeight.Normal)
            Text(third, Modifier.weight(1f), fontWeight = if (header) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

@Composable
private fun ControllerDiagram(
    device: DeviceInfo,
    highlighted: DiagramControl? = null,
    showCenterGuide: Boolean = false,
    showMovementGuide: Boolean = false,
    observedPath: List<Pair<Float, Float>> = emptyList(),
) {
    val family = familyFor(device)
    val body = MaterialTheme.colorScheme.surfaceVariant
    val control = MaterialTheme.colorScheme.onSurfaceVariant
    val highlight = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurface.toArgbCompat()
    val pulse by rememberInfiniteTransition(label = "controls-pulse").animateFloat(
        initialValue = 0.68f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(300), RepeatMode.Reverse),
        label = "controls-pulse-alpha",
    )
    Canvas(Modifier.fillMaxWidth().height(230.dp)) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            body,
            topLeft = Offset(w * .10f, h * .17f),
            size = Size(w * .80f, h * .65f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(h * .18f),
        )
        val geometry = familyGeometry(family, w, h)
        fun colorFor(target: DiagramControl): Color =
            if (highlighted == target) highlight.copy(alpha = pulse) else control
        val arm = 26.dp.toPx()
        val half = 7.dp.toPx()
        drawRect(colorFor(DiagramControl.DPAD_UP), Offset(geometry.dpadX - half, geometry.dpadY - arm), Size(half * 2, arm))
        drawRect(colorFor(DiagramControl.DPAD_RIGHT), Offset(geometry.dpadX, geometry.dpadY - half), Size(arm, half * 2))
        drawRect(colorFor(DiagramControl.DPAD_DOWN), Offset(geometry.dpadX - half, geometry.dpadY), Size(half * 2, arm))
        drawRect(colorFor(DiagramControl.DPAD_LEFT), Offset(geometry.dpadX - arm, geometry.dpadY - half), Size(arm, half * 2))
        val r = 9.dp.toPx()
        val o = 22.dp.toPx()
        drawCircle(colorFor(DiagramControl.FACE_BOTTOM), r, Offset(geometry.faceX, geometry.faceY + o))
        drawCircle(colorFor(DiagramControl.FACE_RIGHT), r, Offset(geometry.faceX + o, geometry.faceY))
        drawCircle(colorFor(DiagramControl.FACE_LEFT), r, Offset(geometry.faceX - o, geometry.faceY))
        drawCircle(colorFor(DiagramControl.FACE_TOP), r, Offset(geometry.faceX, geometry.faceY - o))

        fun stick(x: Float, y: Float, target: DiagramControl, path: List<Pair<Float, Float>>) {
            val radius = 23.dp.toPx()
            drawCircle(colorFor(target), radius, Offset(x, y))
            if (highlighted == target && (showCenterGuide || showMovementGuide)) {
                drawCircle(highlight, radius + 13.dp.toPx(), Offset(x, y), style = Stroke(2.dp.toPx()))
                drawLine(highlight, Offset(x - 7.dp.toPx(), y), Offset(x + 7.dp.toPx(), y), 2.dp.toPx())
                drawLine(highlight, Offset(x, y - 7.dp.toPx()), Offset(x, y + 7.dp.toPx()), 2.dp.toPx())
            }
            if (highlighted == target && showMovementGuide && path.isNotEmpty()) {
                var previous: Offset? = null
                path.forEach { point ->
                    val current = Offset(
                        x + point.first.coerceIn(-1f, 1f) * (radius + 10.dp.toPx()),
                        y + point.second.coerceIn(-1f, 1f) * (radius + 10.dp.toPx()),
                    )
                    previous?.let { drawLine(highlight, it, current, 2.dp.toPx()) }
                    previous = current
                }
                previous?.let { drawCircle(highlight, 5.dp.toPx(), it) }
            }
        }

        stick(
            geometry.leftStickX,
            geometry.leftStickY,
            DiagramControl.LEFT_STICK,
            if (highlighted == DiagramControl.LEFT_STICK) observedPath else emptyList(),
        )
        stick(
            geometry.rightStickX,
            geometry.rightStickY,
            DiagramControl.RIGHT_STICK,
            if (highlighted == DiagramControl.RIGHT_STICK) observedPath else emptyList(),
        )

        val labels = when (family) {
            ControllerFamily.PLAYSTATION -> listOf("×", "○", "□", "△")
            ControllerFamily.XBOX -> listOf("A", "B", "X", "Y")
            ControllerFamily.NINTENDO -> listOf("B", "A", "Y", "X")
            ControllerFamily.GENERIC -> listOf("1", "2", "3", "4")
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = labelColor
            textAlign = Paint.Align.CENTER
            textSize = 11.dp.toPx()
        }
        drawContext.canvas.nativeCanvas.apply {
            drawText(labels[0], geometry.faceX, geometry.faceY + o + 4.dp.toPx(), paint)
            drawText(labels[1], geometry.faceX + o, geometry.faceY + 4.dp.toPx(), paint)
            drawText(labels[2], geometry.faceX - o, geometry.faceY + 4.dp.toPx(), paint)
            drawText(labels[3], geometry.faceX, geometry.faceY - o + 4.dp.toPx(), paint)
        }
    }
    Text(
        stringResource(familyLabelRes(family)),
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private data class Geometry(
    val dpadX: Float,
    val dpadY: Float,
    val faceX: Float,
    val faceY: Float,
    val leftStickX: Float,
    val leftStickY: Float,
    val rightStickX: Float,
    val rightStickY: Float,
)

private fun familyGeometry(family: ControllerFamily, w: Float, h: Float): Geometry = when (family) {
    ControllerFamily.PLAYSTATION -> Geometry(w * .27f, h * .43f, w * .73f, h * .43f, w * .42f, h * .65f, w * .58f, h * .65f)
    ControllerFamily.XBOX -> Geometry(w * .40f, h * .66f, w * .73f, h * .42f, w * .32f, h * .42f, w * .62f, h * .66f)
    ControllerFamily.NINTENDO -> Geometry(w * .31f, h * .66f, w * .73f, h * .40f, w * .31f, h * .39f, w * .69f, h * .66f)
    ControllerFamily.GENERIC -> Geometry(w * .28f, h * .43f, w * .72f, h * .43f, w * .40f, h * .66f, w * .60f, h * .66f)
}

private fun familyFor(device: DeviceInfo): ControllerFamily {
    val name = device.name.lowercase(Locale.ROOT)
    return when {
        "xbox" in name -> ControllerFamily.XBOX
        "dualshock" in name || "dualsense" in name || "playstation" in name -> ControllerFamily.PLAYSTATION
        "joy-con" in name || "nintendo" in name || "switch" in name -> ControllerFamily.NINTENDO
        else -> ControllerFamily.GENERIC
    }
}

@StringRes
private fun familyLabelRes(family: ControllerFamily): Int = when (family) {
    ControllerFamily.PLAYSTATION -> R.string.family_playstation
    ControllerFamily.XBOX -> R.string.family_xbox
    ControllerFamily.NINTENDO -> R.string.family_nintendo
    ControllerFamily.GENERIC -> R.string.family_generic
}

@Composable
private fun friendlyDeviceName(device: DeviceInfo): String =
    device.name.ifBlank { stringResource(R.string.unnamed_controller) }

private fun isControllerSource(source: Int): Boolean =
    source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
        source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
        source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

private fun Color.toArgbCompat(): Int =
    (alpha * 255).toInt().shl(24) or
        (red * 255).toInt().shl(16) or
        (green * 255).toInt().shl(8) or
        (blue * 255).toInt()