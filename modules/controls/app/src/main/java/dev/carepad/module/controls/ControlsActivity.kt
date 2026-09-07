package dev.carepad.module.controls

import android.app.Activity
import android.hardware.input.InputManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.carepad.module.controls.runtime.AndroidDeviceCatalog
import dev.carepad.module.controls.runtime.AndroidEventMapper
import dev.carepad.module.controls.runtime.AxisMetrics
import dev.carepad.module.controls.runtime.ControlsSession
import dev.carepad.module.controls.runtime.DeviceInfo
import java.util.Locale

class ControlsActivity : Activity(), InputManager.InputDeviceListener {
    private lateinit var inputManager: InputManager
    private lateinit var deviceCatalog: AndroidDeviceCatalog
    private lateinit var devicesContainer: LinearLayout
    private lateinit var statusText: TextView
    private var session: ControlsSession? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        inputManager = getSystemService(InputManager::class.java)
        deviceCatalog = AndroidDeviceCatalog(inputManager)
        setContentView(buildContent())
        refreshDevices()
        renderStatus()
    }

    override fun onStart() {
        super.onStart()
        inputManager.registerInputDeviceListener(this, null)
        refreshDevices()
        renderStatus()
    }

    override fun onStop() {
        session?.interrupt()
        inputManager.unregisterInputDeviceListener(this)
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val activeSession = session ?: return super.dispatchKeyEvent(event)
        val sample = AndroidEventMapper.key(event) ?: return super.dispatchKeyEvent(event)
        val result = activeSession.acceptKey(sample)
        if (result.changed) renderStatus()
        return if (result.consumeInTestMode) true else super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val activeSession = session ?: return super.dispatchGenericMotionEvent(event)
        if (event.deviceId != activeSession.device.deviceId) {
            return super.dispatchGenericMotionEvent(event)
        }

        val frames = AndroidEventMapper.motion(
            event,
            AndroidEventMapper.axes(activeSession.mapping),
        )
        var consume = false
        var changed = false
        frames.forEach { frame ->
            val result = activeSession.acceptMotion(frame)
            consume = consume || result.consumeInTestMode
            changed = changed || result.changed
        }
        if (changed) renderStatus()
        return if (consume) true else super.dispatchGenericMotionEvent(event)
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        refreshDevices()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        session?.onRemoved(deviceId)
        refreshDevices()
        renderStatus()
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        session?.onChanged(deviceId)
        refreshDevices()
        renderStatus()
    }

    private fun buildContent(): ScrollView {
        val padding = dp(24)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 28f
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.controls_intro)
            textSize = 16f
            setPadding(0, dp(8), 0, dp(16))
        })
        root.addView(Button(this).apply {
            text = getString(R.string.refresh_devices)
            setOnClickListener { refreshDevices() }
        })

        devicesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(
            devicesContainer,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        statusText = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, dp(16), 0, 0)
        }
        root.addView(statusText)

        return ScrollView(this).apply { addView(root) }
    }

    private fun refreshDevices() {
        if (!::devicesContainer.isInitialized) return
        devicesContainer.removeAllViews()

        val candidates = deviceCatalog.candidates()
        if (candidates.isEmpty()) {
            devicesContainer.addView(TextView(this).apply {
                text = getString(R.string.no_devices)
            })
            return
        }

        candidates.forEach { device ->
            devicesContainer.addView(Button(this).apply {
                text = getString(R.string.select_device, deviceLabel(device))
                isAllCaps = false
                setOnClickListener {
                    deviceCatalog.byId(device.deviceId)?.let { refreshed ->
                        session = ControlsSession(refreshed)
                        renderStatus()
                    }
                }
            })
        }
    }

    private fun renderStatus() {
        if (!::statusText.isInitialized) return
        val activeSession = session
        statusText.text = if (activeSession == null) {
            getString(R.string.no_device_selected)
        } else {
            val left = activeSession.leftMetrics()
            val right = activeSession.rightMetrics()
            buildString {
                appendLine(getString(R.string.selected_device, deviceLabel(activeSession.device)))
                appendLine(getString(R.string.session_state, activeSession.state.name))
                appendLine(getString(R.string.mapping_state, activeSession.mapping.left.state.name, activeSession.mapping.right.state.name, activeSession.mapping.dpad.name))
                appendLine(getString(R.string.session_issues, activeSession.issues.takeIf { it.isNotEmpty() }?.joinToString() ?: getString(R.string.none)))
                appendLine(getString(R.string.received_events, activeSession.rawKeys.size, activeSession.rawMotion.size))
                appendLine(getString(R.string.left_stick, axisSummary(left.x), axisSummary(left.y)))
                appendLine(getString(R.string.right_stick, axisSummary(right.x), axisSummary(right.y)))
                appendLine(getString(R.string.controls_scope_note))
            }
        }
    }

    private fun deviceLabel(device: DeviceInfo): String =
        "${device.name} · ${device.vendorId}:${device.productId} · #${device.deviceId}"

    private fun axisSummary(metrics: AxisMetrics?): String = metrics?.let {
        String.format(
            Locale.US,
            "n=%d min=%.2f max=%.2f",
            it.count,
            it.min,
            it.max,
        )
    } ?: getString(R.string.no_samples)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
