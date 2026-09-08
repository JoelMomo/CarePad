package dev.carepad.module.controls

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
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
import dev.carepad.module.controls.runtime.AndroidDeviceCatalog
import dev.carepad.module.controls.runtime.AndroidEventMapper
import dev.carepad.module.controls.runtime.Axes
import dev.carepad.module.controls.runtime.Button as ControlButton
import dev.carepad.module.controls.runtime.ControlsSession
import dev.carepad.module.controls.runtime.DeviceInfo
import dev.carepad.module.controls.runtime.Direction
import dev.carepad.module.controls.runtime.Resolution
import dev.carepad.module.controls.runtime.SessionState
import java.util.Locale

class ControlsActivity : Activity(), InputManager.InputDeviceListener {
    private enum class Screen { MAIN, GUIDED, DETECTED }
    private enum class GuidedStep { BUTTONS, LEFT_STICK, RIGHT_STICK, SUMMARY }
    private enum class Outcome { OBSERVED, NOT_DETECTED, INCONCLUSIVE }
    private enum class ControllerFamily { PLAYSTATION, XBOX, NINTENDO, GENERIC }

    private data class InputRow(
        val friendlyName: String,
        val androidLabel: String,
        val state: String,
        val active: Boolean,
    )

    private lateinit var inputManager: InputManager
    private lateinit var deviceCatalog: AndroidDeviceCatalog
    private val handler = Handler(Looper.getMainLooper())
    private val controllerOptionButtons = mutableMapOf<Int, UiButton>()
    private val guidedOutcomes = linkedMapOf<GuidedStep, Outcome>()

    private var screen = Screen.MAIN
    private var selectedDeviceId: Int? = null
    private var session: ControlsSession? = null
    private var guidedStep = GuidedStep.BUTTONS

    private lateinit var guidedObservationText: TextView
    private lateinit var detectedRowsContainer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inputManager = getSystemService(InputManager::class.java)
        deviceCatalog = AndroidDeviceCatalog(inputManager)
        syncSelection()
        renderMain()
    }

    override fun onStart() {
        super.onStart()
        inputManager.registerInputDeviceListener(this, null)
        syncSelection()
        if (screen != Screen.MAIN && session?.state == SessionState.INVALIDATED) {
            screen = Screen.MAIN
            session = null
        }
        renderCurrent()
    }

    override fun onStop() {
        session?.interrupt()
        inputManager.unregisterInputDeviceListener(this)
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            noteControllerActivity(event.deviceId)
        }

        val activeSession = session
        if (screen != Screen.MAIN && activeSession != null) {
            AndroidEventMapper.key(event)?.let { sample ->
                val result = activeSession.acceptKey(sample)
                if (result.changed) updateLiveSurface()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val activeSession = session
        if (screen != Screen.MAIN && activeSession != null && event.deviceId == activeSession.device.deviceId) {
            val frames = AndroidEventMapper.motion(
                event,
                AndroidEventMapper.axes(activeSession.mapping),
            )
            var changed = false
            frames.forEach { frame ->
                changed = activeSession.acceptMotion(frame).changed || changed
            }
            if (changed) updateLiveSurface()
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
    override fun onBackPressed() {
        when (screen) {
            Screen.MAIN -> super.onBackPressed()
            Screen.DETECTED -> exitSecondarySurface()
            Screen.GUIDED -> {
                if (guidedStep == GuidedStep.SUMMARY) {
                    exitSecondarySurface()
                } else {
                    confirmAbandonGuidedTest()
                }
            }
        }
    }

    private fun renderCurrent() {
        when (screen) {
            Screen.MAIN -> renderMain()
            Screen.GUIDED -> renderGuided()
            Screen.DETECTED -> renderDetectedInputs()
        }
    }

    private fun renderMain() {
        screen = Screen.MAIN
        controllerOptionButtons.clear()
        val candidates = syncSelection()
        val selected = selectedDevice(candidates)

        val root = pageRoot()
        addHeader(
            root,
            getString(R.string.app_name),
            getString(R.string.controls_intro),
        )

        val content = LinearLayout(this).apply {
            orientation = if (isWide()) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        addBlock(root, content, top = 18)

        val controllerCard = sectionCard(getString(R.string.controller_section))
        renderControllerSection(controllerCard, candidates, selected)

        val actionsColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val guidedCard = actionCard(
            title = getString(R.string.guided_test),
            description = actionDescription(
                selected,
                candidates,
                R.string.guided_test_description,
                R.string.connect_controller_to_start,
                R.string.choose_controller_to_start,
            ),
            enabled = selected != null,
            primary = true,
        ) { startGuidedTest() }
        val detectedCard = actionCard(
            title = getString(R.string.detected_inputs),
            description = actionDescription(
                selected,
                candidates,
                R.string.detected_inputs_description,
                R.string.connect_controller_to_view_inputs,
                R.string.choose_controller_to_view_inputs,
            ),
            enabled = selected != null,
            primary = false,
        ) { startDetectedInputs() }
        addBlock(actionsColumn, guidedCard, top = 0)
        addBlock(actionsColumn, detectedCard, top = 12)

        if (isWide()) {
            controllerCard.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { rightMargin = dp(12) }
            actionsColumn.layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f,
            ).apply { leftMargin = dp(12) }
            content.addView(controllerCard)
            content.addView(actionsColumn)
        } else {
            addBlock(content, controllerCard, top = 0)
            addBlock(content, actionsColumn, top = 12)
        }

        setContentView(scrollPage(root))
    }

    private fun renderControllerSection(
        card: LinearLayout,
        candidates: List<DeviceInfo>,
        selected: DeviceInfo?,
    ) {
        when {
            candidates.isEmpty() -> {
                addBodyText(card, getString(R.string.no_devices_friendly))
            }

            candidates.size == 1 && selected != null -> {
                addTitleText(card, selected.name.ifBlank { getString(R.string.unnamed_controller) }, 20f)
                addSupportingText(card, getString(R.string.controller_connected))
            }

            else -> {
                if (selected == null) {
                    addBodyText(card, getString(R.string.choose_controller_help))
                } else {
                    addTitleText(card, selected.name.ifBlank { getString(R.string.unnamed_controller) }, 20f)
                    addSupportingText(card, getString(R.string.controller_connected))
                    addSupportingText(card, getString(R.string.change_controller_help))
                }
                candidates.forEach { device ->
                    val option = secondaryButton(controllerButtonLabel(device), enabled = true) {
                        selectController(device.deviceId)
                    }
                    controllerOptionButtons[device.deviceId] = option
                    addBlock(card, option, top = 10)
                }
                addSupportingText(card, getString(R.string.activity_help), top = 10)
            }
        }
    }

    private fun actionDescription(
        selected: DeviceInfo?,
        candidates: List<DeviceInfo>,
        availableRes: Int,
        noDeviceRes: Int,
        chooseDeviceRes: Int,
    ): String = when {
        selected != null -> getString(availableRes)
        candidates.isEmpty() -> getString(noDeviceRes)
        else -> getString(chooseDeviceRes)
    }

    private fun startGuidedTest() {
        val device = freshSelectedDevice() ?: return
        session?.interrupt()
        session = ControlsSession(device)
        guidedStep = GuidedStep.BUTTONS
        guidedOutcomes.clear()
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
        val activeSession = session ?: run {
            exitSecondarySurface()
            return
        }

        if (guidedStep == GuidedStep.SUMMARY) {
            renderGuidedSummary()
            return
        }

        val root = pageRoot()
        addBreadcrumbHeader(root, getString(R.string.guided_breadcrumb))
        addSupportingText(root, activeSession.device.name.ifBlank { getString(R.string.unnamed_controller) }, top = 8)

        val phaseCard = sectionCard(guidedStepTitle())
        addSupportingText(phaseCard, guidedStepCounter(), top = 0)
        addBodyText(phaseCard, guidedStepInstruction(), top = 10)

        val diagram = ControllerDiagramView().apply {
            family = familyFor(activeSession.device)
            step = guidedStep
            contentDescription = guidedStepInstruction()
        }
        addBlock(phaseCard, diagram, top = 16)

        guidedObservationText = TextView(this).apply {
            textSize = 16f
            setTextColor(textPrimaryColor())
            text = guidedLiveMessage()
        }
        addBlock(phaseCard, guidedObservationText, top = 14)
        addBlock(root, phaseCard, top = 18)

        val navigation = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = secondaryButton(getString(R.string.back), enabled = true) {
            if (guidedStep == GuidedStep.BUTTONS) {
                confirmAbandonGuidedTest()
            } else {
                guidedOutcomes.remove(previousGuidedStep(guidedStep))
                guidedStep = previousGuidedStep(guidedStep)
                renderGuided()
            }
        }
        val nextText = if (guidedStep == GuidedStep.RIGHT_STICK) {
            getString(R.string.view_summary)
        } else {
            getString(R.string.next)
        }
        val next = primaryButton(nextText, enabled = true) {
            guidedOutcomes[guidedStep] = evaluateGuidedStep(guidedStep)
            guidedStep = when (guidedStep) {
                GuidedStep.BUTTONS -> GuidedStep.LEFT_STICK
                GuidedStep.LEFT_STICK -> GuidedStep.RIGHT_STICK
                GuidedStep.RIGHT_STICK -> GuidedStep.SUMMARY
                GuidedStep.SUMMARY -> GuidedStep.SUMMARY
            }
            renderGuided()
        }
        navigation.addView(
            back,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(6)
            },
        )
        navigation.addView(
            next,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(6)
            },
        )
        addBlock(root, navigation, top = 16)

        setContentView(scrollPage(root))
    }

    private fun renderGuidedSummary() {
        val activeSession = session ?: run {
            exitSecondarySurface()
            return
        }
        val root = pageRoot()
        addBreadcrumbHeader(root, getString(R.string.guided_breadcrumb))
        addSupportingText(root, activeSession.device.name.ifBlank { getString(R.string.unnamed_controller) }, top = 8)

        val hasReview = guidedOutcomes.values.any { it != Outcome.OBSERVED }
        val summaryCard = sectionCard(getString(R.string.test_finished))
        addBodyText(
            summaryCard,
            getString(
                if (hasReview) R.string.summary_review_items else R.string.summary_nothing_unusual,
            ),
        )
        listOf(
            GuidedStep.BUTTONS,
            GuidedStep.LEFT_STICK,
            GuidedStep.RIGHT_STICK,
        ).forEach { step ->
            val row = infoRow(
                guidedStepTitle(step),
                outcomeLabel(guidedOutcomes[step] ?: Outcome.INCONCLUSIVE),
            )
            addBlock(summaryCard, row, top = 10)
        }
        addSupportingText(summaryCard, getString(R.string.controls_scope_note), top = 14)
        addBlock(root, summaryCard, top = 18)
        addBlock(
            root,
            primaryButton(getString(R.string.back_to_controls), enabled = true) {
                exitSecondarySurface()
            },
            top = 16,
        )
        setContentView(scrollPage(root))
    }

    private fun renderDetectedInputs() {
        val activeSession = session ?: run {
            exitSecondarySurface()
            return
        }
        val root = pageRoot()
        addBreadcrumbHeader(root, getString(R.string.detected_breadcrumb))
        addSupportingText(root, activeSession.device.name.ifBlank { getString(R.string.unnamed_controller) }, top = 8)
        addSupportingText(root, getString(R.string.detected_inputs_scope_note), top = 6)

        val listCard = sectionCard(getString(R.string.detected_inputs))
        detectedRowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        addBlock(listCard, detectedRowsContainer, top = 10)
        renderDetectedRows()
        addBlock(root, listCard, top = 18)
        addBlock(
            root,
            secondaryButton(getString(R.string.back), enabled = true) {
                exitSecondarySurface()
            },
            top = 16,
        )
        setContentView(scrollPage(root))
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
            detectedRowsContainer.addView(
                denseTableRow(
                    getString(R.string.input_column),
                    getString(R.string.android_label_column),
                    getString(R.string.state_column),
                    header = true,
                    active = false,
                ),
            )
            rows.forEach { row ->
                addBlock(
                    detectedRowsContainer,
                    denseTableRow(
                        row.friendlyName,
                        row.androidLabel,
                        row.state,
                        header = false,
                        active = row.active,
                    ),
                    top = 6,
                )
            }
        } else {
            rows.forEach { row ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    background = roundedBackground(
                        if (row.active) activeSurfaceColor() else surfaceVariantColor(),
                    )
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
        activeSession.device.keys
            .sortedBy { it.ordinal }
            .forEach { button ->
                val active = isButtonActive(activeSession, button)
                add(
                    InputRow(
                        friendlyName = buttonFriendlyName(button),
                        androidLabel = buttonAndroidLabel(button),
                        state = getString(if (active) R.string.input_active else R.string.input_idle),
                        active = active,
                    ),
                )
            }

        val left = activeSession.mapping.left.pair
        val leftLast = activeSession.leftMetrics().trajectory.lastOrNull()
        if (left != null) {
            addAxisRow(
                getString(R.string.left_stick_x),
                left.x,
                leftLast?.normalizedX,
            )
            addAxisRow(
                getString(R.string.left_stick_y),
                left.y,
                leftLast?.normalizedY,
            )
        }

        val right = activeSession.mapping.right.pair
        val rightLast = activeSession.rightMetrics().trajectory.lastOrNull()
        if (right != null) {
            addAxisRow(
                getString(R.string.right_stick_x),
                right.x,
                rightLast?.normalizedX,
            )
            addAxisRow(
                getString(R.string.right_stick_y),
                right.y,
                rightLast?.normalizedY,
            )
        }

        activeSession.mapping.hat?.let { hat ->
            addAxisRow(getString(R.string.dpad_horizontal), hat.x, null)
            addAxisRow(getString(R.string.dpad_vertical), hat.y, null)
        }
    }

    private fun MutableList<InputRow>.addAxisRow(
        friendlyName: String,
        axis: Int,
        value: Float?,
    ) {
        add(
            InputRow(
                friendlyName = friendlyName,
                androidLabel = axisAndroidLabel(axis),
                state = value?.let {
                    getString(R.string.input_value, String.format(Locale.getDefault(), "%+.2f", it))
                } ?: getString(R.string.no_signal_yet),
                active = value != null,
            ),
        )
    }

    private fun updateLiveSurface() {
        when (screen) {
            Screen.GUIDED -> if (guidedStep != GuidedStep.SUMMARY && ::guidedObservationText.isInitialized) {
                guidedObservationText.text = guidedLiveMessage()
            }
            Screen.DETECTED -> renderDetectedRows()
            Screen.MAIN -> Unit
        }
    }

    private fun guidedLiveMessage(): String {
        val activeSession = session ?: return getString(R.string.waiting_for_input)
        return when (guidedStep) {
            GuidedStep.BUTTONS -> {
                val controls = activeSession.rawKeys.mapNotNull { it.button }.toSet().size
                if (controls > 0 || activeSession.dpadPath.isNotEmpty()) {
                    getString(R.string.digital_inputs_seen, controls)
                } else {
                    getString(R.string.try_highlighted_controls)
                }
            }

            GuidedStep.LEFT_STICK -> stickLiveMessage(
                activeSession.mapping.left.state,
                activeSession.leftMetrics().trajectory.lastOrNull()?.normalizedX,
                activeSession.leftMetrics().trajectory.lastOrNull()?.normalizedY,
            )

            GuidedStep.RIGHT_STICK -> stickLiveMessage(
                activeSession.mapping.right.state,
                activeSession.rightMetrics().trajectory.lastOrNull()?.normalizedX,
                activeSession.rightMetrics().trajectory.lastOrNull()?.normalizedY,
            )

            GuidedStep.SUMMARY -> getString(R.string.test_finished)
        }
    }

    private fun stickLiveMessage(state: Resolution, x: Float?, y: Float?): String {
        if (state != Resolution.STANDARD) return getString(R.string.mapping_inconclusive)
        if (x == null || y == null) return getString(R.string.move_highlighted_stick)
        return getString(
            R.string.stick_input_seen,
            String.format(Locale.getDefault(), "%+.2f", x),
            String.format(Locale.getDefault(), "%+.2f", y),
        )
    }

    private fun evaluateGuidedStep(step: GuidedStep): Outcome {
        val activeSession = session ?: return Outcome.INCONCLUSIVE
        if (activeSession.state == SessionState.INVALIDATED) return Outcome.INCONCLUSIVE
        return when (step) {
            GuidedStep.BUTTONS -> {
                if (activeSession.rawKeys.isNotEmpty() || activeSession.dpadPath.isNotEmpty()) {
                    Outcome.OBSERVED
                } else {
                    Outcome.NOT_DETECTED
                }
            }

            GuidedStep.LEFT_STICK -> when {
                activeSession.mapping.left.state != Resolution.STANDARD -> Outcome.INCONCLUSIVE
                activeSession.leftMetrics().trajectory.isNotEmpty() -> Outcome.OBSERVED
                else -> Outcome.NOT_DETECTED
            }

            GuidedStep.RIGHT_STICK -> when {
                activeSession.mapping.right.state != Resolution.STANDARD -> Outcome.INCONCLUSIVE
                activeSession.rightMetrics().trajectory.isNotEmpty() -> Outcome.OBSERVED
                else -> Outcome.NOT_DETECTED
            }

            GuidedStep.SUMMARY -> Outcome.INCONCLUSIVE
        }
    }

    private fun guidedStepTitle(step: GuidedStep = guidedStep): String = when (step) {
        GuidedStep.BUTTONS -> getString(R.string.buttons_and_dpad)
        GuidedStep.LEFT_STICK -> getString(R.string.left_stick)
        GuidedStep.RIGHT_STICK -> getString(R.string.right_stick)
        GuidedStep.SUMMARY -> getString(R.string.test_finished)
    }

    private fun guidedStepInstruction(): String = when (guidedStep) {
        GuidedStep.BUTTONS -> getString(R.string.buttons_instruction)
        GuidedStep.LEFT_STICK -> getString(R.string.left_stick_instruction)
        GuidedStep.RIGHT_STICK -> getString(R.string.right_stick_instruction)
        GuidedStep.SUMMARY -> getString(R.string.test_finished)
    }

    private fun guidedStepCounter(): String = when (guidedStep) {
        GuidedStep.BUTTONS -> getString(R.string.step_counter, 1, 3)
        GuidedStep.LEFT_STICK -> getString(R.string.step_counter, 2, 3)
        GuidedStep.RIGHT_STICK -> getString(R.string.step_counter, 3, 3)
        GuidedStep.SUMMARY -> ""
    }

    private fun previousGuidedStep(step: GuidedStep): GuidedStep = when (step) {
        GuidedStep.BUTTONS -> GuidedStep.BUTTONS
        GuidedStep.LEFT_STICK -> GuidedStep.BUTTONS
        GuidedStep.RIGHT_STICK -> GuidedStep.LEFT_STICK
        GuidedStep.SUMMARY -> GuidedStep.RIGHT_STICK
    }

    private fun outcomeLabel(outcome: Outcome): String = when (outcome) {
        Outcome.OBSERVED -> getString(R.string.observed)
        Outcome.NOT_DETECTED -> getString(R.string.not_detected)
        Outcome.INCONCLUSIVE -> getString(R.string.inconclusive)
    }

    private fun confirmAbandonGuidedTest() {
        AlertDialog.Builder(this)
            .setTitle(R.string.leave_test_title)
            .setMessage(R.string.leave_test_message)
            .setNegativeButton(R.string.keep_testing, null)
            .setPositiveButton(R.string.leave_test) { _, _ -> exitSecondarySurface() }
            .show()
    }

    private fun exitSecondarySurface() {
        session?.interrupt()
        session = null
        screen = Screen.MAIN
        guidedStep = GuidedStep.BUTTONS
        guidedOutcomes.clear()
        syncSelection()
        renderMain()
    }

    private fun returnToMainAfterDeviceLoss(messageRes: Int) {
        session = null
        screen = Screen.MAIN
        guidedStep = GuidedStep.BUTTONS
        guidedOutcomes.clear()
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

    private fun selectedDevice(candidates: List<DeviceInfo>): DeviceInfo? =
        selectedDeviceId?.let { id -> candidates.firstOrNull { it.deviceId == id } }

    private fun freshSelectedDevice(): DeviceInfo? =
        selectedDeviceId?.let(deviceCatalog::byId)

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
        button.text = getString(
            R.string.controller_activity_option,
            device.name.ifBlank { getString(R.string.unnamed_controller) },
        )
        handler.postDelayed({
            if (screen == Screen.MAIN && controllerOptionButtons[deviceId] === button) {
                button.text = controllerButtonLabel(device)
            }
        }, 900L)
    }

    private fun controllerButtonLabel(device: DeviceInfo): String =
        device.name.ifBlank { getString(R.string.unnamed_controller) }

    private fun isButtonActive(activeSession: ControlsSession, button: ControlButton): Boolean = when (button) {
        ControlButton.DPAD_UP -> Direction.UP in activeSession.dpadPath.lastOrNull()?.directions.orEmpty()
        ControlButton.DPAD_DOWN -> Direction.DOWN in activeSession.dpadPath.lastOrNull()?.directions.orEmpty()
        ControlButton.DPAD_LEFT -> Direction.LEFT in activeSession.dpadPath.lastOrNull()?.directions.orEmpty()
        ControlButton.DPAD_RIGHT -> Direction.RIGHT in activeSession.dpadPath.lastOrNull()?.directions.orEmpty()
        else -> activeSession.buttonMetrics(button).pressed
    }

    private fun buttonFriendlyName(button: ControlButton): String = getString(
        when (button) {
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
        },
    )

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

    private fun familyLabel(family: ControllerFamily): String = getString(
        when (family) {
            ControllerFamily.PLAYSTATION -> R.string.family_playstation
            ControllerFamily.XBOX -> R.string.family_xbox
            ControllerFamily.NINTENDO -> R.string.family_nintendo
            ControllerFamily.GENERIC -> R.string.family_generic
        },
    )

    private fun pageRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(24), dp(24), dp(28))
        setBackgroundColor(pageColor())
    }

    private fun scrollPage(root: LinearLayout): ScrollView = ScrollView(this).apply {
        isFillViewport = true
        setBackgroundColor(pageColor())
        addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    private fun addHeader(parent: LinearLayout, title: String, description: String) {
        addTitleText(parent, title, 30f)
        addSupportingText(parent, description, top = 7, textSize = 17f)
    }

    private fun addBreadcrumbHeader(parent: LinearLayout, title: String) {
        addTitleText(parent, title, 25f)
    }

    private fun sectionCard(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = roundedBackground(surfaceColor())
        addTitleText(this, title, 19f)
    }

    private fun actionCard(
        title: String,
        description: String,
        enabled: Boolean,
        primary: Boolean,
        action: () -> Unit,
    ): LinearLayout = sectionCard(title).apply {
        addBodyText(this, description, top = 9)
        addBlock(
            this,
            if (primary) {
                primaryButton(title, enabled, action)
            } else {
                secondaryButton(title, enabled, action)
            },
            top = 14,
        )
    }

    private fun infoRow(label: String, value: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedBackground(surfaceVariantColor())
        addView(
            TextView(this@ControlsActivity).apply {
                text = label
                textSize = 15f
                setTextColor(textPrimaryColor())
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            TextView(this@ControlsActivity).apply {
                text = value
                textSize = 15f
                gravity = Gravity.END
                setTextColor(textSecondaryColor())
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    private fun denseTableRow(
        first: String,
        second: String,
        third: String,
        header: Boolean,
        active: Boolean,
    ): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = roundedBackground(
            when {
                header -> surfaceVariantColor()
                active -> activeSurfaceColor()
                else -> surfaceColor()
            },
        )
        listOf(first, second, third).forEach { value ->
            addView(
                TextView(this@ControlsActivity).apply {
                    text = value
                    textSize = if (header) 14f else 15f
                    setTextColor(if (header) textSecondaryColor() else textPrimaryColor())
                    if (header) typeface = android.graphics.Typeface.DEFAULT_BOLD
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    rightMargin = dp(8)
                },
            )
        }
    }

    private fun primaryButton(text: String, enabled: Boolean, action: () -> Unit): UiButton =
        styledButton(text, enabled, primary = true, action = action)

    private fun secondaryButton(text: String, enabled: Boolean, action: () -> Unit): UiButton =
        styledButton(text, enabled, primary = false, action = action)

    private fun styledButton(
        text: String,
        enabled: Boolean,
        primary: Boolean,
        action: () -> Unit,
    ): UiButton = UiButton(this).apply {
        this.text = text
        isAllCaps = false
        isEnabled = enabled
        isFocusable = true
        minHeight = dp(if (primary) 56 else 50)
        textSize = if (primary) 17f else 16f
        setTextColor(if (primary) Color.WHITE else textPrimaryColor())
        backgroundTintList = ColorStateList.valueOf(
            when {
                !enabled -> disabledSurfaceColor()
                primary -> primaryColor()
                else -> secondaryButtonColor()
            },
        )
        alpha = if (enabled) 1f else 0.62f
        if (enabled) {
            setOnClickListener { view ->
                view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                view.playSoundEffect(SoundEffectConstants.CLICK)
                action()
            }
        }
    }

    private fun addTitleText(parent: LinearLayout, value: String, textSize: Float) {
        parent.addView(TextView(this).apply {
            text = value
            this.textSize = textSize
            setTextColor(textPrimaryColor())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
    }

    private fun addBodyText(parent: LinearLayout, value: String, top: Int = 8) {
        val text = TextView(this).apply {
            this.text = value
            textSize = 16f
            setTextColor(textPrimaryColor())
            setLineSpacing(0f, 1.12f)
        }
        addBlock(parent, text, top)
    }

    private fun addSupportingText(
        parent: LinearLayout,
        value: String,
        top: Int = 5,
        textSize: Float = 14f,
    ) {
        val text = TextView(this).apply {
            this.text = value
            this.textSize = textSize
            setTextColor(textSecondaryColor())
            setLineSpacing(0f, 1.08f)
        }
        addBlock(parent, text, top)
    }

    private fun addBlock(parent: LinearLayout, view: View, top: Int = 12) {
        parent.addView(
            view,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(top) },
        )
    }

    private fun roundedBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(18).toFloat()
        setColor(color)
    }

    private fun isWide(): Boolean = resources.configuration.screenWidthDp >= 720

    private fun isDark(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun pageColor(): Int = if (isDark()) Color.rgb(18, 18, 24) else Color.rgb(247, 246, 251)
    private fun surfaceColor(): Int = if (isDark()) Color.rgb(34, 33, 43) else Color.WHITE
    private fun surfaceVariantColor(): Int = if (isDark()) Color.rgb(47, 45, 59) else Color.rgb(239, 236, 247)
    private fun activeSurfaceColor(): Int = if (isDark()) Color.rgb(54, 63, 82) else Color.rgb(231, 238, 255)
    private fun primaryColor(): Int = if (isDark()) Color.rgb(122, 98, 210) else Color.rgb(93, 70, 177)
    private fun secondaryButtonColor(): Int = if (isDark()) Color.rgb(69, 66, 86) else Color.rgb(230, 226, 241)
    private fun disabledSurfaceColor(): Int = if (isDark()) Color.rgb(54, 53, 61) else Color.rgb(222, 220, 226)
    private fun textPrimaryColor(): Int = if (isDark()) Color.rgb(245, 243, 250) else Color.rgb(35, 31, 45)
    private fun textSecondaryColor(): Int = if (isDark()) Color.rgb(194, 190, 204) else Color.rgb(96, 89, 108)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private inner class ControllerDiagramView : View(this@ControlsActivity) {
        var step: GuidedStep = GuidedStep.BUTTONS
            set(value) {
                field = value
                invalidate()
            }
        var family: ControllerFamily = ControllerFamily.GENERIC
            set(value) {
                field = value
                invalidate()
            }

        private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDark()) Color.rgb(76, 73, 91) else Color.rgb(222, 218, 233)
            style = Paint.Style.FILL
        }
        private val controlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDark()) Color.rgb(182, 176, 197) else Color.rgb(111, 103, 126)
            style = Paint.Style.FILL
        }
        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDark()) Color.rgb(174, 150, 255) else Color.rgb(104, 76, 201)
            style = Paint.Style.FILL
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textSecondaryColor()
            textSize = dp(13).toFloat()
            textAlign = Paint.Align.CENTER
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            setMeasuredDimension(width, dp(210))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val body = RectF(w * 0.12f, h * 0.20f, w * 0.88f, h * 0.82f)
            canvas.drawRoundRect(body, h * 0.18f, h * 0.18f, bodyPaint)

            val dpadX = w * 0.30f
            val buttonsX = w * 0.70f
            val upperY = h * 0.43f
            val stickY = h * 0.65f
            val leftStickX = w * 0.40f
            val rightStickX = w * 0.60f

            val digitalPaint = if (step == GuidedStep.BUTTONS) highlightPaint else controlPaint
            val leftPaint = if (step == GuidedStep.LEFT_STICK) highlightPaint else controlPaint
            val rightPaint = if (step == GuidedStep.RIGHT_STICK) highlightPaint else controlPaint

            canvas.drawRect(dpadX - dp(28), upperY - dp(7), dpadX + dp(28), upperY + dp(7), digitalPaint)
            canvas.drawRect(dpadX - dp(7), upperY - dp(28), dpadX + dp(7), upperY + dp(28), digitalPaint)
            canvas.drawCircle(buttonsX, upperY - dp(18), dp(8).toFloat(), digitalPaint)
            canvas.drawCircle(buttonsX + dp(20), upperY, dp(8).toFloat(), digitalPaint)
            canvas.drawCircle(buttonsX, upperY + dp(18), dp(8).toFloat(), digitalPaint)
            canvas.drawCircle(buttonsX - dp(20), upperY, dp(8).toFloat(), digitalPaint)
            canvas.drawCircle(leftStickX, stickY, dp(22).toFloat(), leftPaint)
            canvas.drawCircle(rightStickX, stickY, dp(22).toFloat(), rightPaint)

            canvas.drawText(familyLabel(family), w / 2f, h * 0.96f, labelPaint)
        }
    }
}
