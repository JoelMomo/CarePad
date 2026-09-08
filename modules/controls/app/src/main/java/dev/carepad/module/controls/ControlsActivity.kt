package dev.carepad.module.controls

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.widget.Button as UiButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import carepad.contracts.CarePadHostNavigation
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
import kotlin.math.sin

class ControlsActivity : Activity(), InputManager.InputDeviceListener {
    private enum class Screen { MAIN, GUIDED, DETECTED }
    private enum class GuidedStage { PREPARE, DIGITAL, LEFT_REST, LEFT_MOVE, RIGHT_REST, RIGHT_MOVE, SUMMARY }
    private enum class Outcome { OBSERVED, NOT_DETECTED, INCONCLUSIVE }
    private enum class ControllerFamily { PLAYSTATION, XBOX, NINTENDO, GENERIC }
    private enum class DiagramControl {
        FACE_BOTTOM, FACE_RIGHT, FACE_LEFT, FACE_TOP,
        DPAD_UP, DPAD_RIGHT, DPAD_DOWN, DPAD_LEFT,
        LEFT_STICK, RIGHT_STICK,
    }

    private data class DigitalTarget(
        val button: ControlButton,
        val nameRes: Int,
        val diagramControl: DiagramControl,
    )

    private data class InputRow(
        val friendlyName: String,
        val androidLabel: String,
        val state: String,
        val active: Boolean,
    )

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

    private lateinit var inputManager: InputManager
    private lateinit var deviceCatalog: AndroidDeviceCatalog
    private val handler = Handler(Looper.getMainLooper())
    private val controllerOptionButtons = mutableMapOf<Int, UiButton>()
    private val digitalOutcomes = linkedMapOf<ControlButton, Outcome>()
    private val stickOutcomes = linkedMapOf<DiagramControl, Outcome>()

    private var screen = Screen.MAIN
    private var selectedDeviceId: Int? = null
    private var launchHostPackage: String? = null
    private var session: ControlsSession? = null
    private var guidedStage = GuidedStage.PREPARE
    private var digitalTargetIndex = 0
    private var attemptArmed = false
    private var attemptCanFail = false
    private var attemptReadyAt = 0L
    private var attemptBaselineTrajectoryCount = 0
    private var attemptGeneration = 0L

    private lateinit var guidedObservationText: TextView
    private var markNotDetectedButton: UiButton? = null
    private lateinit var detectedRowsContainer: LinearLayout

    private val digitalTargets by lazy {
        listOf(
            DigitalTarget(ControlButton.A, R.string.button_a, DiagramControl.FACE_BOTTOM),
            DigitalTarget(ControlButton.B, R.string.button_b, DiagramControl.FACE_RIGHT),
            DigitalTarget(ControlButton.X, R.string.button_x, DiagramControl.FACE_LEFT),
            DigitalTarget(ControlButton.Y, R.string.button_y, DiagramControl.FACE_TOP),
            DigitalTarget(ControlButton.DPAD_UP, R.string.dpad_up, DiagramControl.DPAD_UP),
            DigitalTarget(ControlButton.DPAD_RIGHT, R.string.dpad_right, DiagramControl.DPAD_RIGHT),
            DigitalTarget(ControlButton.DPAD_DOWN, R.string.dpad_down, DiagramControl.DPAD_DOWN),
            DigitalTarget(ControlButton.DPAD_LEFT, R.string.dpad_left, DiagramControl.DPAD_LEFT),
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inputManager = getSystemService(InputManager::class.java)
        deviceCatalog = AndroidDeviceCatalog(inputManager)
        launchHostPackage = intent.getStringExtra(CarePadHostNavigation.EXTRA_HOST_PACKAGE)
        syncSelection()
        renderMain()
    }

    override fun onStart() {
        super.onStart()
        inputManager.registerInputDeviceListener(this, null)
        syncSelection()
        if (screen != Screen.MAIN && session?.state == SessionState.INVALIDATED) {
            returnToMainAfterDeviceLoss(R.string.controller_session_invalidated)
        } else {
            renderCurrent()
        }
    }

    override fun onStop() {
        cancelAttempt()
        session?.interrupt()
        inputManager.unregisterInputDeviceListener(this)
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            noteControllerActivity(event.deviceId)
        }

        val activeSession = session
        if (screen == Screen.GUIDED && attemptArmed && activeSession != null) {
            val sample = AndroidEventMapper.key(event)
            if (sample != null) {
                val result = activeSession.acceptKey(sample)
                if (result.changed && event.eventTime >= attemptReadyAt) {
                    observeGuidedKey(sample)
                    updateGuidedLiveMessage()
                }
                if (result.consumeInTestMode) return true
            }
        } else if (screen == Screen.DETECTED && activeSession != null) {
            AndroidEventMapper.key(event)?.let { sample ->
                val result = activeSession.acceptKey(sample)
                if (result.changed) updateDetectedLiveSurface()
            }
        }

        if (
            screen != Screen.MAIN &&
            !attemptArmed &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            event.keyCode == KeyEvent.KEYCODE_BUTTON_B
        ) {
            performFeedback(window.decorView)
            handleBack()
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val activeSession = session
        if (screen == Screen.GUIDED && attemptArmed && activeSession != null && event.deviceId == activeSession.device.deviceId) {
            val frames = AndroidEventMapper.motion(event, AndroidEventMapper.axes(activeSession.mapping))
            var consumed = false
            var changed = false
            frames.forEach { frame ->
                val result = activeSession.acceptMotion(frame)
                consumed = consumed || result.consumeInTestMode
                changed = changed || result.changed
            }
            if (changed && event.eventTime >= attemptReadyAt) {
                observeGuidedMotion()
                updateGuidedLiveMessage()
            }
            if (consumed) return true
        } else if (screen == Screen.DETECTED && activeSession != null && event.deviceId == activeSession.device.deviceId) {
            val frames = AndroidEventMapper.motion(event, AndroidEventMapper.axes(activeSession.mapping))
            var changed = false
            frames.forEach { frame -> changed = activeSession.acceptMotion(frame).changed || changed }
            if (changed) updateDetectedLiveSurface()
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        syncSelection()
        if (screen == Screen.MAIN) renderMain()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        val wasSelected = selectedDeviceId == deviceId
        session?.onRemoved(deviceId)
        syncSelection()
        if (wasSelected && screen != Screen.MAIN) {
            returnToMainAfterDeviceLoss(R.string.controller_disconnected)
        } else if (screen == Screen.MAIN) {
            renderMain()
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        val wasSelected = selectedDeviceId == deviceId
        session?.onChanged(deviceId)
        syncSelection()
        if (wasSelected && screen != Screen.MAIN) {
            returnToMainAfterDeviceLoss(R.string.controller_changed)
        } else if (screen == Screen.MAIN) {
            renderMain()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleBack()

    private fun renderCurrent() {
        when (screen) {
            Screen.MAIN -> renderMain()
            Screen.GUIDED -> renderGuided()
            Screen.DETECTED -> renderDetectedInputs()
        }
    }

    private fun renderMain() {
        screen = Screen.MAIN
        cancelAttempt()
        controllerOptionButtons.clear()
        val candidates = syncSelection()
        val selected = selectedDevice(candidates)
        val root = pageRoot()
        addHeader(root, getString(R.string.app_name), getString(R.string.controls_intro))

        val content = LinearLayout(this).apply {
            orientation = if (isWide()) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        addBlock(root, content, top = 18)

        val controllerCard = sectionCard(getString(R.string.controller_section))
        renderControllerSection(controllerCard, candidates, selected)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addBlock(
            actions,
            actionCard(
                getString(R.string.guided_test),
                actionDescription(selected, candidates, R.string.guided_test_description, R.string.connect_controller_to_start, R.string.choose_controller_to_start),
                selected != null,
                true,
            ) { startGuidedTest() },
            top = 0,
        )
        addBlock(
            actions,
            actionCard(
                getString(R.string.detected_inputs),
                actionDescription(selected, candidates, R.string.detected_inputs_description, R.string.connect_controller_to_view_inputs, R.string.choose_controller_to_view_inputs),
                selected != null,
                false,
            ) { startDetectedInputs() },
            top = 12,
        )

        if (isWide()) {
            content.addView(controllerCard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(12) })
            content.addView(actions, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(12) })
        } else {
            addBlock(content, controllerCard, top = 0)
            addBlock(content, actions, top = 12)
        }
        setModuleContent(root)
    }

    private fun renderControllerSection(card: LinearLayout, candidates: List<DeviceInfo>, selected: DeviceInfo?) {
        when {
            candidates.isEmpty() -> addBodyText(card, getString(R.string.no_devices_friendly))
            candidates.size == 1 && selected != null -> {
                addTitleText(card, friendlyDeviceName(selected), 20f)
                addSupportingText(card, getString(R.string.controller_connected))
            }
            else -> {
                addBodyText(card, if (selected == null) getString(R.string.choose_controller_help) else getString(R.string.change_controller_help))
                candidates.forEach { device ->
                    val option = secondaryButton(controllerButtonLabel(device), true) { selectController(device.deviceId) }
                    controllerOptionButtons[device.deviceId] = option
                    addBlock(card, option, top = 10)
                }
                addSupportingText(card, getString(R.string.activity_help), top = 10)
            }
        }
    }

    private fun actionDescription(selected: DeviceInfo?, candidates: List<DeviceInfo>, availableRes: Int, noDeviceRes: Int, chooseDeviceRes: Int): String = when {
        selected != null -> getString(availableRes)
        candidates.isEmpty() -> getString(noDeviceRes)
        else -> getString(chooseDeviceRes)
    }

    private fun startGuidedTest() {
        if (freshSelectedDevice() == null) return
        session?.interrupt()
        session = null
        digitalOutcomes.clear()
        stickOutcomes.clear()
        digitalTargetIndex = 0
        guidedStage = GuidedStage.PREPARE
        screen = Screen.GUIDED
        renderGuided()
    }

    private fun startDetectedInputs() {
        val device = freshSelectedDevice() ?: return
        session?.interrupt()
        session = ControlsSession(device)
        screen = Screen.DETECTED
        renderDetectedInputs()
    }

    private fun renderGuided() {
        cancelAttempt(keepUiState = true)
        val device = freshSelectedDevice() ?: run {
            returnToMainAfterDeviceLoss(R.string.controller_disconnected)
            return
        }
        if (guidedStage != GuidedStage.PREPARE && session == null) {
            session = ControlsSession(device)
        }

        val root = pageRoot()
        addBreadcrumbHeader(root, getString(R.string.guided_breadcrumb))
        addSupportingText(root, friendlyDeviceName(device), top = 8)

        when (guidedStage) {
            GuidedStage.PREPARE -> renderPreparation(root, device)
            GuidedStage.DIGITAL -> renderDigitalTarget(root, device)
            GuidedStage.LEFT_REST -> renderStickRest(root, device, DiagramControl.LEFT_STICK)
            GuidedStage.LEFT_MOVE -> renderStickMove(root, device, DiagramControl.LEFT_STICK)
            GuidedStage.RIGHT_REST -> renderStickRest(root, device, DiagramControl.RIGHT_STICK)
            GuidedStage.RIGHT_MOVE -> renderStickMove(root, device, DiagramControl.RIGHT_STICK)
            GuidedStage.SUMMARY -> renderGuidedSummary(root, device)
        }
        setModuleContent(root)
    }

    private fun renderPreparation(root: LinearLayout, device: DeviceInfo) {
        val card = sectionCard(getString(R.string.prepare_test))
        addBodyText(card, getString(R.string.prepare_test_instruction))
        addSupportingText(card, getString(R.string.prepare_test_note), top = 8)
        addBlock(card, ControllerDiagramView().apply {
            family = familyFor(device)
            stage = guidedStage
            contentDescription = getString(R.string.prepare_test_instruction)
        }, top = 16)
        addBlock(root, card, top = 18)
        addGuidedNavigation(
            root,
            backAction = { confirmAbandonGuidedTest() },
            primaryText = getString(R.string.start_test),
        ) {
            session?.interrupt()
            session = ControlsSession(device)
            guidedStage = GuidedStage.DIGITAL
            digitalTargetIndex = 0
            renderGuided()
        }
    }

    private fun renderDigitalTarget(root: LinearLayout, device: DeviceInfo) {
        val target = digitalTargets[digitalTargetIndex]
        val outcome = digitalOutcomes[target.button]
        val card = sectionCard(getString(R.string.buttons_and_dpad))
        addSupportingText(card, getString(R.string.control_counter, digitalTargetIndex + 1, digitalTargets.size), top = 0)
        addBodyText(card, getString(R.string.digital_target_instruction, getString(target.nameRes)), top = 10)
        addBlock(card, ControllerDiagramView().apply {
            family = familyFor(device)
            stage = guidedStage
            highlightedControl = target.diagramControl
            contentDescription = getString(R.string.digital_target_instruction, getString(target.nameRes))
        }, top = 16)
        guidedObservationText = liveTextView(digitalOutcomeMessage(outcome))
        addBlock(card, guidedObservationText, top = 12)

        if (outcome == null) {
            addBlock(card, primaryButton(getString(R.string.try_this_control), true) { armAttempt() }, top = 14)
            markNotDetectedButton = secondaryButton(getString(R.string.tried_not_detected), false) { markCurrentNotDetected() }
            addBlock(card, markNotDetectedButton!!, top = 8)
        } else {
            addBlock(
                card,
                primaryButton(
                    getString(if (digitalTargetIndex == digitalTargets.lastIndex) R.string.continue_label else R.string.next_control),
                    true,
                ) {
                    if (digitalTargetIndex < digitalTargets.lastIndex) digitalTargetIndex++ else guidedStage = GuidedStage.LEFT_REST
                    renderGuided()
                },
                top = 14,
            )
        }
        addBlock(root, card, top = 18)
        addBlock(root, secondaryButton(getString(R.string.back), true) { handleGuidedBack() }, top = 14)
    }

    private fun renderStickRest(root: LinearLayout, device: DeviceInfo, control: DiagramControl) {
        val left = control == DiagramControl.LEFT_STICK
        val card = sectionCard(getString(if (left) R.string.left_stick else R.string.right_stick))
        addBodyText(card, getString(if (left) R.string.left_rest_instruction else R.string.right_rest_instruction))
        addSupportingText(card, getString(R.string.rest_is_observation_not_diagnosis), top = 8)
        addBlock(card, ControllerDiagramView().apply {
            family = familyFor(device)
            stage = guidedStage
            highlightedControl = control
            showCenterGuide = true
            contentDescription = getString(if (left) R.string.left_rest_instruction else R.string.right_rest_instruction)
        }, top = 16)
        addBlock(root, card, top = 18)
        addGuidedNavigation(root, { handleGuidedBack() }, getString(R.string.stick_is_still)) {
            guidedStage = if (left) GuidedStage.LEFT_MOVE else GuidedStage.RIGHT_MOVE
            renderGuided()
        }
    }

    private fun renderStickMove(root: LinearLayout, device: DeviceInfo, control: DiagramControl) {
        val activeSession = session ?: return
        val left = control == DiagramControl.LEFT_STICK
        val resolution = if (left) activeSession.mapping.left.state else activeSession.mapping.right.state
        if (resolution != Resolution.STANDARD && stickOutcomes[control] == null) {
            stickOutcomes[control] = Outcome.INCONCLUSIVE
        }
        val outcome = stickOutcomes[control]
        val trajectory = if (left) activeSession.leftMetrics().trajectory else activeSession.rightMetrics().trajectory
        val observedPath = trajectory.mapNotNull { sample ->
            val x = sample.normalizedX
            val y = sample.normalizedY
            if (x == null || y == null) null else x to y
        }.takeLast(MAX_GUIDED_TRAJECTORY_POINTS)
        val card = sectionCard(getString(if (left) R.string.left_stick else R.string.right_stick))
        addBodyText(card, getString(if (left) R.string.left_move_instruction else R.string.right_move_instruction))
        addBlock(card, ControllerDiagramView().apply {
            family = familyFor(device)
            stage = guidedStage
            highlightedControl = control
            showMovementGuide = true
            this.observedPath = observedPath
            contentDescription = getString(if (left) R.string.left_move_instruction else R.string.right_move_instruction)
        }, top = 16)
        guidedObservationText = liveTextView(stickOutcomeMessage(outcome, resolution))
        addBlock(card, guidedObservationText, top = 12)

        if (resolution == Resolution.STANDARD && outcome == null) {
            addBlock(card, primaryButton(getString(R.string.try_stick_movement), true) { armAttempt() }, top = 14)
            markNotDetectedButton = secondaryButton(getString(R.string.tried_not_detected), false) { markCurrentNotDetected() }
            addBlock(card, markNotDetectedButton!!, top = 8)
        } else {
            addBlock(card, primaryButton(getString(R.string.continue_label), true) {
                guidedStage = if (left) GuidedStage.RIGHT_REST else GuidedStage.SUMMARY
                renderGuided()
            }, top = 14)
        }
        addBlock(root, card, top = 18)
        addBlock(root, secondaryButton(getString(R.string.back), true) { handleGuidedBack() }, top = 14)
    }

    private fun renderGuidedSummary(root: LinearLayout, device: DeviceInfo) {
        val card = sectionCard(getString(R.string.test_finished))
        val digitalOutcome = aggregateDigitalOutcome()
        val left = stickOutcomes[DiagramControl.LEFT_STICK] ?: Outcome.INCONCLUSIVE
        val right = stickOutcomes[DiagramControl.RIGHT_STICK] ?: Outcome.INCONCLUSIVE
        val needsReview = listOf(digitalOutcome, left, right).any { it != Outcome.OBSERVED }
        addBodyText(card, getString(if (needsReview) R.string.summary_review_items else R.string.summary_nothing_unusual))
        addBlock(card, infoRow(getString(R.string.buttons_and_dpad), outcomeLabel(digitalOutcome)), top = 12)
        addBlock(card, infoRow(getString(R.string.left_stick), outcomeLabel(left)), top = 8)
        addBlock(card, infoRow(getString(R.string.right_stick), outcomeLabel(right)), top = 8)
        addSupportingText(card, getString(R.string.controls_scope_note), top = 14)
        addBlock(card, ControllerDiagramView().apply {
            family = familyFor(device)
            stage = guidedStage
            contentDescription = getString(R.string.test_finished)
        }, top = 12)
        addBlock(root, card, top = 18)
        addGuidedNavigation(root, { handleGuidedBack() }, getString(R.string.back_to_controls)) { exitSecondarySurface() }
    }

    private fun addGuidedNavigation(root: LinearLayout, backAction: () -> Unit, primaryText: String, primaryAction: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(secondaryButton(getString(R.string.back), true, backAction), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        row.addView(primaryButton(primaryText, true, primaryAction), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        addBlock(root, row, top = 16)
    }

    private fun armAttempt() {
        val activeSession = session ?: return
        if (activeSession.state == SessionState.INVALIDATED) return
        cancelAttempt(keepUiState = true)
        attemptArmed = true
        attemptCanFail = false
        attemptReadyAt = SystemClock.uptimeMillis() + ATTEMPT_ARM_DELAY_MS
        attemptBaselineTrajectoryCount = currentTrajectoryCount(activeSession)
        val generation = ++attemptGeneration
        guidedObservationText.text = getString(R.string.listening_for_attempt)
        markNotDetectedButton?.isEnabled = false
        handler.postDelayed({
            if (screen == Screen.GUIDED && attemptArmed && generation == attemptGeneration) {
                attemptArmed = false
                attemptCanFail = true
                markNotDetectedButton?.isEnabled = true
                guidedObservationText.text = getString(R.string.attempt_not_seen_yet)
            }
        }, ATTEMPT_WINDOW_MS)
    }

    private fun observeGuidedKey(sample: KeySample) {
        if (!attemptArmed || sample.action != KeyAction.DOWN || sample.repeatCount != 0) return
        if (guidedStage != GuidedStage.DIGITAL) return
        val target = digitalTargets[digitalTargetIndex]
        if (sample.button == target.button) finishAttemptObserved()
    }

    private fun observeGuidedMotion() {
        if (!attemptArmed) return
        val activeSession = session ?: return
        when (guidedStage) {
            GuidedStage.DIGITAL -> {
                val target = digitalTargets[digitalTargetIndex]
                val direction = when (target.button) {
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
                if (currentTrajectoryCount(activeSession) > attemptBaselineTrajectoryCount) finishAttemptObserved()
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
        cancelAttempt(keepUiState = true)
        renderGuided()
    }

    private fun markCurrentNotDetected() {
        if (!attemptCanFail) return
        when (guidedStage) {
            GuidedStage.DIGITAL -> digitalOutcomes[digitalTargets[digitalTargetIndex].button] = Outcome.NOT_DETECTED
            GuidedStage.LEFT_MOVE -> stickOutcomes[DiagramControl.LEFT_STICK] = Outcome.NOT_DETECTED
            GuidedStage.RIGHT_MOVE -> stickOutcomes[DiagramControl.RIGHT_STICK] = Outcome.NOT_DETECTED
            else -> return
        }
        cancelAttempt(keepUiState = true)
        renderGuided()
    }

    private fun currentTrajectoryCount(activeSession: ControlsSession): Int = when (guidedStage) {
        GuidedStage.LEFT_MOVE -> activeSession.leftMetrics().trajectory.size
        GuidedStage.RIGHT_MOVE -> activeSession.rightMetrics().trajectory.size
        else -> 0
    }

    private fun updateGuidedLiveMessage() {
        if (!::guidedObservationText.isInitialized || !attemptArmed) return
        guidedObservationText.text = getString(R.string.input_seen_checking_target)
    }

    private fun cancelAttempt(keepUiState: Boolean = false) {
        attemptArmed = false
        attemptCanFail = false
        attemptReadyAt = 0L
        attemptBaselineTrajectoryCount = 0
        attemptGeneration++
        markNotDetectedButton = null
        if (!keepUiState) handler.removeCallbacksAndMessages(null)
    }

    private fun handleBack() {
        when (screen) {
            Screen.MAIN -> super.onBackPressed()
            Screen.DETECTED -> exitSecondarySurface()
            Screen.GUIDED -> handleGuidedBack()
        }
    }

    private fun handleGuidedBack() {
        cancelAttempt()
        when (guidedStage) {
            GuidedStage.PREPARE -> confirmAbandonGuidedTest()
            GuidedStage.DIGITAL -> {
                if (digitalTargetIndex > 0) digitalTargetIndex-- else guidedStage = GuidedStage.PREPARE
                renderGuided()
            }
            GuidedStage.LEFT_REST -> {
                guidedStage = GuidedStage.DIGITAL
                digitalTargetIndex = digitalTargets.lastIndex
                renderGuided()
            }
            GuidedStage.LEFT_MOVE -> { guidedStage = GuidedStage.LEFT_REST; renderGuided() }
            GuidedStage.RIGHT_REST -> { guidedStage = GuidedStage.LEFT_MOVE; renderGuided() }
            GuidedStage.RIGHT_MOVE -> { guidedStage = GuidedStage.RIGHT_REST; renderGuided() }
            GuidedStage.SUMMARY -> { guidedStage = GuidedStage.RIGHT_MOVE; renderGuided() }
        }
    }

    private fun aggregateDigitalOutcome(): Outcome {
        val values = digitalTargets.map { digitalOutcomes[it.button] }
        return when {
            values.all { it == Outcome.OBSERVED } -> Outcome.OBSERVED
            values.any { it == Outcome.NOT_DETECTED } -> Outcome.NOT_DETECTED
            else -> Outcome.INCONCLUSIVE
        }
    }

    private fun digitalOutcomeMessage(outcome: Outcome?): String = when (outcome) {
        Outcome.OBSERVED -> getString(R.string.control_observed)
        Outcome.NOT_DETECTED -> getString(R.string.control_not_detected_after_attempt)
        Outcome.INCONCLUSIVE -> getString(R.string.inconclusive)
        null -> getString(R.string.ready_for_explicit_attempt)
    }

    private fun stickOutcomeMessage(outcome: Outcome?, resolution: Resolution): String = when {
        resolution != Resolution.STANDARD -> getString(R.string.mapping_inconclusive)
        outcome == Outcome.OBSERVED -> getString(R.string.stick_observed)
        outcome == Outcome.NOT_DETECTED -> getString(R.string.control_not_detected_after_attempt)
        else -> getString(R.string.ready_for_explicit_attempt)
    }

    private fun outcomeLabel(outcome: Outcome): String = getString(when (outcome) {
        Outcome.OBSERVED -> R.string.observed
        Outcome.NOT_DETECTED -> R.string.not_detected
        Outcome.INCONCLUSIVE -> R.string.inconclusive
    })

    private fun renderDetectedInputs() {
        val activeSession = session ?: run { exitSecondarySurface(); return }
        val root = pageRoot()
        addBreadcrumbHeader(root, getString(R.string.detected_breadcrumb))
        addSupportingText(root, friendlyDeviceName(activeSession.device), top = 8)
        addSupportingText(root, getString(R.string.detected_inputs_scope_note), top = 6)
        val card = sectionCard(getString(R.string.detected_inputs))
        detectedRowsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addBlock(card, detectedRowsContainer, top = 10)
        renderDetectedRows()
        addBlock(root, card, top = 18)
        addBlock(root, secondaryButton(getString(R.string.back), true) { exitSecondarySurface() }, top = 16)
        setModuleContent(root)
    }

    private fun renderDetectedRows() {
        if (!::detectedRowsContainer.isInitialized) return
        val activeSession = session ?: return
        val rows = detectedInputRows(activeSession)
        detectedRowsContainer.removeAllViews()
        if (rows.isEmpty()) {
            addBodyText(detectedRowsContainer, getString(R.string.no_inputs_to_show), top = 0)
            return
        }
        if (isWide()) {
            detectedRowsContainer.addView(denseTableRow(getString(R.string.input_column), getString(R.string.android_label_column), getString(R.string.state_column), true, false))
            rows.forEach { row -> addBlock(detectedRowsContainer, denseTableRow(row.friendlyName, row.androidLabel, row.state, false, row.active), top = 6) }
        } else {
            rows.forEach { row ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = roundedBackground(if (row.active) activeSurfaceColor() else surfaceVariantColor())
                    isFocusable = false
                }
                addTitleText(item, row.friendlyName, 16f)
                addSupportingText(item, row.androidLabel, top = 3)
                addSupportingText(item, row.state, top = 5)
                addBlock(detectedRowsContainer, item, top = 6)
            }
        }
    }

    private fun detectedInputRows(activeSession: ControlsSession): List<InputRow> = buildList {
        activeSession.device.keys.sortedBy { it.ordinal }.forEach { button ->
            val active = isButtonActive(activeSession, button)
            add(InputRow(buttonFriendlyName(button), buttonAndroidLabel(button), getString(if (active) R.string.input_active else R.string.input_idle), active))
        }
        val latestFrame = activeSession.rawMotion.lastOrNull()
        activeSession.mapping.left.pair?.let { pair ->
            addAxisRow(getString(R.string.left_stick_x), pair.x, latestFrame?.axes?.get(pair.x), latestFrame?.timeMs)
            addAxisRow(getString(R.string.left_stick_y), pair.y, latestFrame?.axes?.get(pair.y), latestFrame?.timeMs)
        }
        activeSession.mapping.right.pair?.let { pair ->
            addAxisRow(getString(R.string.right_stick_x), pair.x, latestFrame?.axes?.get(pair.x), latestFrame?.timeMs)
            addAxisRow(getString(R.string.right_stick_y), pair.y, latestFrame?.axes?.get(pair.y), latestFrame?.timeMs)
        }
        activeSession.mapping.hat?.let { pair ->
            addAxisRow(getString(R.string.dpad_horizontal), pair.x, latestFrame?.axes?.get(pair.x), latestFrame?.timeMs)
            addAxisRow(getString(R.string.dpad_vertical), pair.y, latestFrame?.axes?.get(pair.y), latestFrame?.timeMs)
        }
    }

    private fun MutableList<InputRow>.addAxisRow(friendlyName: String, axis: Int, value: Float?, eventTimeMs: Long?) {
        val active = eventTimeMs != null && SystemClock.uptimeMillis() - eventTimeMs <= LIVE_ACTIVITY_WINDOW_MS
        val state = if (value == null) {
            getString(R.string.no_signal_yet)
        } else {
            getString(if (active) R.string.input_active_value else R.string.input_value, String.format(Locale.getDefault(), "%+.2f", value))
        }
        add(InputRow(friendlyName, axisAndroidLabel(axis), state, active))
    }

    private fun updateDetectedLiveSurface() {
        renderDetectedRows()
        val generation = ++detectedRefreshGeneration
        handler.postDelayed({
            if (screen == Screen.DETECTED && generation == detectedRefreshGeneration) renderDetectedRows()
        }, LIVE_ACTIVITY_WINDOW_MS + 40L)
    }

    private var detectedRefreshGeneration = 0L

    private fun confirmAbandonGuidedTest(onConfirmed: () -> Unit = { exitSecondarySurface() }) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.leave_test_title)
            .setMessage(R.string.leave_test_message)
            .setNegativeButton(R.string.keep_testing, null)
            .setPositiveButton(R.string.leave_test, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { view ->
                performFeedback(view)
                dialog.dismiss()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { view ->
                performFeedback(view)
                dialog.dismiss()
                onConfirmed()
            }
        }
        dialog.show()
    }

    private fun exitSecondarySurface() {
        cancelAttempt()
        session?.interrupt()
        session = null
        screen = Screen.MAIN
        guidedStage = GuidedStage.PREPARE
        syncSelection()
        renderMain()
    }

    private fun returnToMainAfterDeviceLoss(messageRes: Int) {
        cancelAttempt()
        session = null
        screen = Screen.MAIN
        guidedStage = GuidedStage.PREPARE
        renderMain()
        Toast.makeText(this, messageRes, Toast.LENGTH_LONG).show()
    }

    private fun syncSelection(): List<DeviceInfo> {
        val candidates = deviceCatalog.candidates()
        val current = selectedDeviceId
        selectedDeviceId = when {
            current != null && candidates.any { it.deviceId == current } -> current
            candidates.size == 1 -> candidates.single().deviceId
            else -> null
        }
        return candidates
    }

    private fun selectedDevice(candidates: List<DeviceInfo>): DeviceInfo? = selectedDeviceId?.let { id -> candidates.firstOrNull { it.deviceId == id } }
    private fun freshSelectedDevice(): DeviceInfo? = selectedDeviceId?.let(deviceCatalog::byId)

    private fun selectController(deviceId: Int) {
        session?.interrupt()
        session = null
        selectedDeviceId = deviceId
        renderMain()
    }

    private fun noteControllerActivity(deviceId: Int) {
        if (screen != Screen.MAIN) return
        val button = controllerOptionButtons[deviceId] ?: return
        val device = deviceCatalog.byId(deviceId) ?: return
        button.text = getString(R.string.controller_activity_option, friendlyDeviceName(device))
        handler.postDelayed({
            if (screen == Screen.MAIN && controllerOptionButtons[deviceId] === button) button.text = controllerButtonLabel(device)
        }, 900L)
    }

    private fun friendlyDeviceName(device: DeviceInfo): String = device.name.ifBlank { getString(R.string.unnamed_controller) }
    private fun controllerButtonLabel(device: DeviceInfo): String = friendlyDeviceName(device)

    private fun requestHostDestination(destination: String) {
        val action: () -> Unit = {
            val hostPackage = launchHostPackage
            if (hostPackage.isNullOrBlank()) {
                if (destination == CarePadHostNavigation.HOME) finish() else Toast.makeText(this, R.string.host_navigation_unavailable, Toast.LENGTH_SHORT).show()
            } else {
                val intent = Intent(CarePadHostNavigation.ACTION_OPEN_DESTINATION)
                    .setPackage(hostPackage)
                    .putExtra(CarePadHostNavigation.EXTRA_DESTINATION, destination)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                runCatching { startActivity(intent) }
                    .onSuccess { finish() }
                    .onFailure { Toast.makeText(this, R.string.host_navigation_unavailable, Toast.LENGTH_SHORT).show() }
            }
        }
        if (screen == Screen.GUIDED && guidedStage != GuidedStage.SUMMARY) confirmAbandonGuidedTest(action) else action()
    }

    private fun setModuleContent(root: LinearLayout) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(pageColor())
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val shell = if (isWide()) {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(pageColor())
                addView(navigationRail(), LinearLayout.LayoutParams(dp(176), ViewGroup.LayoutParams.MATCH_PARENT))
                addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
        } else {
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(pageColor())
                addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(bottomNavigation(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        setContentView(shell)
    }

    private fun navigationRail(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.TOP
        setPadding(dp(8), dp(18), dp(8), dp(12))
        setBackgroundColor(surfaceColor())
        addView(navButton(getString(R.string.nav_home), CarePadHostNavigation.HOME, true))
        addView(navButton(getString(R.string.nav_add_modules), CarePadHostNavigation.ADD_MODULES, false))
        addView(navButton(getString(R.string.nav_settings), CarePadHostNavigation.SETTINGS, false))
    }

    private fun bottomNavigation(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(6), dp(6), dp(8))
        setBackgroundColor(surfaceColor())
        addView(navButton(getString(R.string.nav_home), CarePadHostNavigation.HOME, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(navButton(getString(R.string.nav_add_modules), CarePadHostNavigation.ADD_MODULES, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(navButton(getString(R.string.nav_settings), CarePadHostNavigation.SETTINGS, false), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun navButton(label: String, destination: String, selected: Boolean): UiButton = UiButton(this).apply {
        text = label
        isAllCaps = false
        isFocusable = true
        minHeight = dp(54)
        textSize = 14f
        setTextColor(textPrimaryColor())
        updateButtonBackground(this, selected, true, false)
        setOnFocusChangeListener { view, focused -> updateButtonBackground(view as UiButton, selected, true, focused) }
        setOnClickListener { view ->
            performFeedback(view)
            requestHostDestination(destination)
        }
    }

    private fun isButtonActive(activeSession: ControlsSession, button: ControlButton): Boolean = when (button) {
        ControlButton.DPAD_UP -> Direction.UP in activeSession.dpadPath.lastOrNull()?.directions.orEmpty()
        ControlButton.DPAD_DOWN -> Direction.DOWN in activeSession.dpadPath.lastOrNull()?.directions.orEmpty()
        ControlButton.DPAD_LEFT -> Direction.LEFT in activeSession.dpadPath.lastOrNull()?.directions.orEmpty()
        ControlButton.DPAD_RIGHT -> Direction.RIGHT in activeSession.dpadPath.lastOrNull()?.directions.orEmpty()
        else -> activeSession.buttonMetrics(button).pressed
    }

    private fun buttonFriendlyName(button: ControlButton): String = getString(when (button) {
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
    })

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

    private fun familyFor(device: DeviceInfo): ControllerFamily {
        val name = device.name.lowercase(Locale.ROOT)
        return when {
            "xbox" in name -> ControllerFamily.XBOX
            "dualshock" in name || "dualsense" in name || "playstation" in name -> ControllerFamily.PLAYSTATION
            "joy-con" in name || "nintendo" in name || "switch" in name -> ControllerFamily.NINTENDO
            else -> ControllerFamily.GENERIC
        }
    }

    private fun familyLabel(family: ControllerFamily): String = getString(when (family) {
        ControllerFamily.PLAYSTATION -> R.string.family_playstation
        ControllerFamily.XBOX -> R.string.family_xbox
        ControllerFamily.NINTENDO -> R.string.family_nintendo
        ControllerFamily.GENERIC -> R.string.family_generic
    })

    private fun pageRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(28))
        setBackgroundColor(pageColor())
    }

    private fun addHeader(parent: LinearLayout, title: String, description: String) {
        addTitleText(parent, title, 30f)
        addSupportingText(parent, description, top = 7, textSize = 17f)
    }

    private fun addBreadcrumbHeader(parent: LinearLayout, title: String) = addTitleText(parent, title, 25f)

    private fun sectionCard(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = roundedBackground(surfaceColor())
        addTitleText(this, title, 19f)
    }

    private fun actionCard(title: String, description: String, enabled: Boolean, primary: Boolean, action: () -> Unit): LinearLayout = sectionCard(title).apply {
        addBodyText(this, description, top = 9)
        addBlock(this, if (primary) primaryButton(title, enabled, action) else secondaryButton(title, enabled, action), top = 14)
    }

    private fun liveTextView(value: String): TextView = TextView(this).apply {
        text = value
        textSize = 16f
        setTextColor(textPrimaryColor())
    }

    private fun infoRow(label: String, value: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedBackground(surfaceVariantColor())
        addView(TextView(this@ControlsActivity).apply { text = label; textSize = 15f; setTextColor(textPrimaryColor()) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@ControlsActivity).apply { text = value; textSize = 15f; gravity = Gravity.END; setTextColor(textSecondaryColor()) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun denseTableRow(first: String, second: String, third: String, header: Boolean, active: Boolean): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedBackground(when { header -> surfaceVariantColor(); active -> activeSurfaceColor(); else -> surfaceColor() })
        listOf(first, second, third).forEach { value ->
            addView(TextView(this@ControlsActivity).apply {
                text = value
                textSize = if (header) 14f else 15f
                setTextColor(if (header) textSecondaryColor() else textPrimaryColor())
                if (header) typeface = android.graphics.Typeface.DEFAULT_BOLD
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(8) })
        }
    }

    private fun primaryButton(text: String, enabled: Boolean, action: () -> Unit): UiButton = styledButton(text, enabled, true, action)
    private fun secondaryButton(text: String, enabled: Boolean, action: () -> Unit): UiButton = styledButton(text, enabled, false, action)

    private fun styledButton(text: String, enabled: Boolean, primary: Boolean, action: () -> Unit): UiButton = UiButton(this).apply {
        this.text = text
        isAllCaps = false
        isEnabled = enabled
        isFocusable = true
        minHeight = dp(if (primary) 56 else 50)
        textSize = if (primary) 17f else 16f
        setTextColor(if (primary) Color.WHITE else textPrimaryColor())
        updateButtonBackground(this, primary, enabled, false)
        setOnFocusChangeListener { view, focused -> updateButtonBackground(view as UiButton, primary, enabled, focused) }
        if (enabled) setOnClickListener { view -> performFeedback(view); action() }
    }

    private fun updateButtonBackground(button: UiButton, primary: Boolean, enabled: Boolean, focused: Boolean) {
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(when { !enabled -> disabledSurfaceColor(); primary -> primaryColor(); else -> secondaryButtonColor() })
            if (focused) setStroke(dp(3), focusOutlineColor())
        }
        button.alpha = if (enabled) 1f else 0.62f
    }

    private fun performFeedback(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        view.playSoundEffect(SoundEffectConstants.CLICK)
    }

    private fun addTitleText(parent: LinearLayout, value: String, textSize: Float) {
        parent.addView(TextView(this).apply { text = value; this.textSize = textSize; setTextColor(textPrimaryColor()); typeface = android.graphics.Typeface.DEFAULT_BOLD })
    }

    private fun addBodyText(parent: LinearLayout, value: String, top: Int = 8) {
        addBlock(parent, TextView(this).apply { text = value; textSize = 16f; setTextColor(textPrimaryColor()); setLineSpacing(0f, 1.12f) }, top)
    }

    private fun addSupportingText(parent: LinearLayout, value: String, top: Int = 5, textSize: Float = 14f) {
        addBlock(parent, TextView(this).apply { text = value; this.textSize = textSize; setTextColor(textSecondaryColor()); setLineSpacing(0f, 1.08f) }, top)
    }

    private fun addBlock(parent: LinearLayout, view: View, top: Int = 12) {
        parent.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) })
    }

    private fun roundedBackground(color: Int): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(18).toFloat(); setColor(color) }

    private fun isWide(): Boolean {
        val configuration = resources.configuration
        return configuration.screenWidthDp >= 600 && configuration.screenWidthDp >= configuration.screenHeightDp
    }

    private fun isDark(): Boolean = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun pageColor(): Int = if (isDark()) Color.rgb(18, 18, 24) else Color.rgb(247, 246, 251)
    private fun surfaceColor(): Int = if (isDark()) Color.rgb(34, 33, 43) else Color.WHITE
    private fun surfaceVariantColor(): Int = if (isDark()) Color.rgb(47, 45, 59) else Color.rgb(239, 236, 247)
    private fun activeSurfaceColor(): Int = if (isDark()) Color.rgb(54, 63, 82) else Color.rgb(231, 238, 255)
    private fun primaryColor(): Int = if (isDark()) Color.rgb(122, 98, 210) else Color.rgb(93, 70, 177)
    private fun secondaryButtonColor(): Int = if (isDark()) Color.rgb(69, 66, 86) else Color.rgb(230, 226, 241)
    private fun disabledSurfaceColor(): Int = if (isDark()) Color.rgb(54, 53, 61) else Color.rgb(222, 220, 226)
    private fun focusOutlineColor(): Int = if (isDark()) Color.WHITE else Color.rgb(42, 30, 82)
    private fun textPrimaryColor(): Int = if (isDark()) Color.rgb(245, 243, 250) else Color.rgb(35, 31, 45)
    private fun textSecondaryColor(): Int = if (isDark()) Color.rgb(194, 190, 204) else Color.rgb(96, 89, 108)
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private inner class ControllerDiagramView : View(this@ControlsActivity) {
        var stage: GuidedStage = GuidedStage.PREPARE
        var family: ControllerFamily = ControllerFamily.GENERIC
        var highlightedControl: DiagramControl? = null
        var showCenterGuide: Boolean = false
        var showMovementGuide: Boolean = false
        var observedPath: List<Pair<Float, Float>> = emptyList()

        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val controlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(230))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            bodyPaint.color = if (isDark()) Color.rgb(76, 73, 91) else Color.rgb(222, 218, 233)
            controlPaint.color = if (isDark()) Color.rgb(182, 176, 197) else Color.rgb(111, 103, 126)
            guidePaint.color = primaryColor()
            labelPaint.color = textSecondaryColor()
            labelPaint.textSize = dp(12).toFloat()
            val pulse = 0.68f + 0.32f * ((sin(SystemClock.uptimeMillis() / 150.0) + 1.0) / 2.0).toFloat()
            highlightPaint.color = primaryColor()
            highlightPaint.alpha = (255 * pulse).toInt()

            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRoundRect(RectF(w * .10f, h * .17f, w * .90f, h * .82f), h * .18f, h * .18f, bodyPaint)
            val geometry = familyGeometry(w, h)
            drawDpad(canvas, geometry.dpadX, geometry.dpadY)
            drawFaceButtons(canvas, geometry.faceX, geometry.faceY)
            drawStick(canvas, geometry.leftStickX, geometry.leftStickY, DiagramControl.LEFT_STICK)
            drawStick(canvas, geometry.rightStickX, geometry.rightStickY, DiagramControl.RIGHT_STICK)
            drawFamilyLabels(canvas, geometry.faceX, geometry.faceY)
            canvas.drawText(familyLabel(family), w / 2f, h * .96f, labelPaint)
            if (highlightedControl != null) postInvalidateDelayed(60L)
        }

        private fun familyGeometry(w: Float, h: Float): Geometry = when (family) {
            ControllerFamily.PLAYSTATION -> Geometry(w*.27f,h*.43f,w*.73f,h*.43f,w*.42f,h*.65f,w*.58f,h*.65f)
            ControllerFamily.XBOX -> Geometry(w*.40f,h*.66f,w*.73f,h*.42f,w*.32f,h*.42f,w*.62f,h*.66f)
            ControllerFamily.NINTENDO -> Geometry(w*.31f,h*.66f,w*.73f,h*.40f,w*.31f,h*.39f,w*.69f,h*.66f)
            ControllerFamily.GENERIC -> Geometry(w*.28f,h*.43f,w*.72f,h*.43f,w*.40f,h*.66f,w*.60f,h*.66f)
        }

        private fun paintFor(control: DiagramControl): Paint = if (highlightedControl == control) highlightPaint else controlPaint

        private fun drawDpad(canvas: Canvas, x: Float, y: Float) {
            val arm = dp(26).toFloat(); val half = dp(7).toFloat()
            canvas.drawRect(x-half, y-arm, x+half, y, paintFor(DiagramControl.DPAD_UP))
            canvas.drawRect(x, y-half, x+arm, y+half, paintFor(DiagramControl.DPAD_RIGHT))
            canvas.drawRect(x-half, y, x+half, y+arm, paintFor(DiagramControl.DPAD_DOWN))
            canvas.drawRect(x-arm, y-half, x, y+half, paintFor(DiagramControl.DPAD_LEFT))
        }

        private fun drawFaceButtons(canvas: Canvas, x: Float, y: Float) {
            val r = dp(9).toFloat(); val o = dp(22).toFloat()
            canvas.drawCircle(x, y+o, r, paintFor(DiagramControl.FACE_BOTTOM))
            canvas.drawCircle(x+o, y, r, paintFor(DiagramControl.FACE_RIGHT))
            canvas.drawCircle(x-o, y, r, paintFor(DiagramControl.FACE_LEFT))
            canvas.drawCircle(x, y-o, r, paintFor(DiagramControl.FACE_TOP))
        }

        private fun drawStick(canvas: Canvas, x: Float, y: Float, control: DiagramControl) {
            val radius = dp(23).toFloat()
            canvas.drawCircle(x, y, radius, paintFor(control))
            if (highlightedControl == control && (showCenterGuide || showMovementGuide)) {
                canvas.drawCircle(x, y, radius + dp(13), guidePaint)
                canvas.drawLine(x-dp(7), y, x+dp(7), y, guidePaint)
                canvas.drawLine(x, y-dp(7), x, y+dp(7), guidePaint)
            }
            if (highlightedControl == control && showMovementGuide && observedPath.isNotEmpty()) {
                var previous: Pair<Float, Float>? = null
                observedPath.forEach { point ->
                    val current =
                        x + point.first.coerceIn(-1f, 1f) * (radius + dp(10)) to
                            y + point.second.coerceIn(-1f, 1f) * (radius + dp(10))
                    previous?.let { last ->
                        canvas.drawLine(last.first, last.second, current.first, current.second, guidePaint)
                    }
                    previous = current
                }
                previous?.let { last -> canvas.drawCircle(last.first, last.second, dp(5).toFloat(), highlightPaint) }
            }
        }

        private fun drawFamilyLabels(canvas: Canvas, x: Float, y: Float) {
            val labels = when (family) {
                ControllerFamily.PLAYSTATION -> listOf("×", "○", "□", "△")
                ControllerFamily.XBOX -> listOf("A", "B", "X", "Y")
                ControllerFamily.NINTENDO -> listOf("B", "A", "Y", "X")
                ControllerFamily.GENERIC -> listOf("1", "2", "3", "4")
            }
            val o = dp(22).toFloat()
            labelPaint.color = if (isDark()) Color.WHITE else Color.rgb(50,45,60)
            labelPaint.textSize = dp(11).toFloat()
            canvas.drawText(labels[0], x, y+o+dp(4), labelPaint)
            canvas.drawText(labels[1], x+o, y+dp(4), labelPaint)
            canvas.drawText(labels[2], x-o, y+dp(4), labelPaint)
            canvas.drawText(labels[3], x, y-o+dp(4), labelPaint)
        }
    }

    private companion object {
        const val ATTEMPT_ARM_DELAY_MS = 250L
        const val ATTEMPT_WINDOW_MS = 1800L
        const val LIVE_ACTIVITY_WINDOW_MS = 520L
        const val MAX_GUIDED_TRAJECTORY_POINTS = 96
    }
}
