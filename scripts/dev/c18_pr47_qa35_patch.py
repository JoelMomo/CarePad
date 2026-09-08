from pathlib import Path

path = Path("modules/controls/app/src/main/java/dev/carepad/module/controls/ControlsActivity.kt")
text = path.read_text()


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected one match, found {count}: {old[:100]!r}")
    text = text.replace(old, new, 1)


replace_once(
    "import android.graphics.drawable.GradientDrawable\n",
    "import android.graphics.ColorFilter\n"
    "import android.graphics.PixelFormat\n"
    "import android.graphics.drawable.Drawable\n"
    "import android.graphics.drawable.GradientDrawable\n",
)
replace_once(
    "import android.view.HapticFeedbackConstants\n",
    "import android.view.HapticFeedbackConstants\nimport android.view.InputDevice\n",
)

replace_once(
    "    private enum class Outcome { OBSERVED, NOT_DETECTED, INCONCLUSIVE }\n",
    "    private enum class Outcome { OBSERVED, NOT_DETECTED, INCONCLUSIVE }\n"
    "    private enum class InputMethod { TOUCH, CONTROLLER }\n",
)

replace_once(
    "    private var screen = Screen.MAIN\n"
    "    private var selectedDeviceId: Int? = null\n"
    "    private var launchHostPackage: String? = null\n",
    "    private var screen = Screen.MAIN\n"
    "    private var selectedDeviceId: Int? = null\n"
    "    private var launchHostPackage: String? = null\n"
    "    private var inputMethod = InputMethod.CONTROLLER\n"
    "    private var helpHintView: TextView? = null\n"
    "    private var navigationRailView: View? = null\n"
    "    private var navigationRailHomeButton: View? = null\n"
    "    private var contentFocusTarget: View? = null\n",
)

replace_once(
    "    override fun onCreate(savedInstanceState: Bundle?) {\n"
    "        super.onCreate(savedInstanceState)\n",
    "    override fun onCreate(savedInstanceState: Bundle?) {\n"
    "        applyHostLocaleOverride()\n"
    "        super.onCreate(savedInstanceState)\n",
)

replace_once(
    "    override fun dispatchKeyEvent(event: KeyEvent): Boolean {\n"
    "        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {\n"
    "            noteControllerActivity(event.deviceId)\n"
    "        }\n",
    "    override fun dispatchTouchEvent(event: MotionEvent): Boolean {\n"
    "        if (event.actionMasked == MotionEvent.ACTION_DOWN) {\n"
    "            setInputMethod(InputMethod.TOUCH)\n"
    "        }\n"
    "        return super.dispatchTouchEvent(event)\n"
    "    }\n\n"
    "    override fun dispatchKeyEvent(event: KeyEvent): Boolean {\n"
    "        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {\n"
    "            if (isControllerSource(event.source)) setInputMethod(InputMethod.CONTROLLER)\n"
    "            noteControllerActivity(event.deviceId)\n"
    "        }\n",
)

replace_once(
    "        if (\n"
    "            screen != Screen.MAIN &&\n"
    "            !attemptArmed &&\n"
    "            event.action == KeyEvent.ACTION_DOWN &&\n",
    "        if (\n"
    "            !attemptArmed &&\n"
    "            event.action == KeyEvent.ACTION_DOWN &&\n"
    "            event.repeatCount == 0 &&\n"
    "            event.keyCode == KeyEvent.KEYCODE_BUTTON_L1\n"
    "        ) {\n"
    "            performFeedback(window.decorView)\n"
    "            toggleNavigationFocus()\n"
    "            return true\n"
    "        }\n\n"
    "        if (\n"
    "            screen != Screen.MAIN &&\n"
    "            !attemptArmed &&\n"
    "            event.action == KeyEvent.ACTION_DOWN &&\n",
)

replace_once(
    "    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {\n"
    "        val activeSession = session\n",
    "    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {\n"
    "        if (isControllerSource(event.source)) setInputMethod(InputMethod.CONTROLLER)\n"
    "        val activeSession = session\n",
)

old_shell = '''    private fun setModuleContent(root: LinearLayout) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(pageColor())
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val shell = if (isWide()) {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(pageColor())
                val rail = navigationRail()
                addView(rail, LinearLayout.LayoutParams(dp(RAIL_COMPACT_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT))
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
'''
new_shell = '''    private fun setModuleContent(root: LinearLayout) {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(pageColor())
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val contentColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pageColor())
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(View(this@ControlsActivity).apply { setBackgroundColor(outlineColor()) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            helpHintView = TextView(this@ControlsActivity).apply {
                textSize = 12f
                setTextColor(textSecondaryColor())
                setPadding(dp(18), dp(10), dp(18), dp(10))
                isFocusable = false
            }
            addView(helpHintView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        updateHelpHint()
        contentFocusTarget = firstFocusableDescendant(root)

        val shell = if (isWide()) {
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundColor(pageColor())
                val rail = navigationRail()
                addView(rail, LinearLayout.LayoutParams(dp(RAIL_COMPACT_WIDTH_DP), ViewGroup.LayoutParams.MATCH_PARENT))
                addView(contentColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
        } else {
            navigationRailView = null
            navigationRailHomeButton = null
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(pageColor())
                addView(contentColumn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(bottomNavigation(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
        setContentView(shell)
    }
'''
replace_once(old_shell, new_shell)

replace_once(
    '''        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setBackgroundColor(surfaceColor())
        }
''',
    '''        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setBackgroundColor(surfaceColor())
        }
        navigationRailView = rail
''',
)

replace_once(
    '''                binding.button.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    getDrawable(binding.iconRes)?.mutate()?.apply {
                        setTint(if (binding.selected) Color.WHITE else textPrimaryColor())
                    },
                    null,
                    null,
                )
''',
    '''                binding.button.setTextColor(textPrimaryColor())
                binding.button.setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    RailIconDrawable(binding.iconRes, binding.selected),
                    null,
                    null,
                )
''',
)

replace_once(
    '''                setTextColor(if (selected) Color.WHITE else textPrimaryColor())
                updateRailButtonBackground(this, selected, false)
                setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    getDrawable(iconRes)?.mutate()?.apply {
                        setTint(if (selected) Color.WHITE else textPrimaryColor())
                    },
                    null,
                    null,
                )
''',
    '''                setTextColor(textPrimaryColor())
                updateRailButtonBackground(this, selected, false)
                setCompoundDrawablesWithIntrinsicBounds(
                    null,
                    RailIconDrawable(iconRes, selected),
                    null,
                    null,
                )
''',
)

replace_once(
    "            val binding = RailButtonBinding(button, label, iconRes, selected)\n"
    "            bindings += binding\n",
    "            val binding = RailButtonBinding(button, label, iconRes, selected)\n"
    "            bindings += binding\n"
    "            if (destination == CarePadHostNavigation.HOME) navigationRailHomeButton = button\n",
)

old_bg = '''    private fun updateRailButtonBackground(button: UiButton, selected: Boolean, focused: Boolean) {
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(
                when {
                    selected -> primaryColor()
                    focused -> secondaryButtonColor()
                    else -> Color.TRANSPARENT
                },
            )
            if (focused) setStroke(dp(3), focusOutlineColor())
        }
    }
'''
new_bg = '''    private fun updateRailButtonBackground(button: UiButton, selected: Boolean, focused: Boolean) {
        button.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16).toFloat()
            setColor(if (focused) surfaceVariantColor() else Color.TRANSPARENT)
            if (focused) setStroke(dp(2), focusOutlineColor())
        }
        button.setTextColor(textPrimaryColor())
        button.compoundDrawables[1]?.let { drawable ->
            if (drawable is RailIconDrawable) drawable.invalidateSelf()
        }
    }
'''
replace_once(old_bg, new_bg)

insert_before_button_active = '''    private fun applyHostLocaleOverride() {
        val tag = intent.getStringExtra(CarePadHostNavigation.EXTRA_HOST_LOCALE_TAG)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val locale = Locale.forLanguageTag(tag)
        val override = Configuration().apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        applyOverrideConfiguration(override)
    }

    private fun setInputMethod(method: InputMethod) {
        if (inputMethod == method) return
        inputMethod = method
        updateHelpHint()
    }

    private fun updateHelpHint() {
        helpHintView?.text = getString(
            if (inputMethod == InputMethod.TOUCH) R.string.control_hint_touch
            else R.string.control_hint_controller,
        )
    }

    private fun isControllerSource(source: Int): Boolean =
        source and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
            source and InputDevice.SOURCE_DPAD == InputDevice.SOURCE_DPAD

    private fun toggleNavigationFocus() {
        val rail = navigationRailView
        val focused = window.decorView.findFocus()
        val focusIsInRail = rail != null && focused != null && isDescendantOf(focused, rail)
        val target = if (focusIsInRail) contentFocusTarget else navigationRailHomeButton
        target?.requestFocus()
    }

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var current: Any? = view
        while (current is View) {
            if (current === ancestor) return true
            current = current.parent
        }
        return false
    }

    private fun firstFocusableDescendant(view: View): View? {
        if (view.isFocusable && view.isEnabled && view.visibility == View.VISIBLE) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                firstFocusableDescendant(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private inner class RailIconDrawable(
        iconRes: Int,
        private val selected: Boolean,
    ) : Drawable() {
        private val icon = requireNotNull(getDrawable(iconRes)).mutate()
        private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun getIntrinsicWidth(): Int = dp(if (selected) 56 else 24)
        override fun getIntrinsicHeight(): Int = dp(if (selected) 32 else 24)

        override fun draw(canvas: Canvas) {
            if (selected) {
                indicatorPaint.color = secondaryContainerColor()
                canvas.drawRoundRect(
                    RectF(bounds.left.toFloat(), bounds.top.toFloat(), bounds.right.toFloat(), bounds.bottom.toFloat()),
                    dp(16).toFloat(),
                    dp(16).toFloat(),
                    indicatorPaint,
                )
            }
            icon.setTint(if (selected) onSecondaryContainerColor() else textSecondaryColor())
            val size = dp(24)
            val left = bounds.centerX() - size / 2
            val top = bounds.centerY() - size / 2
            icon.setBounds(left, top, left + size, top + size)
            icon.draw(canvas)
        }

        override fun setAlpha(alpha: Int) {
            icon.alpha = alpha
            indicatorPaint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            icon.colorFilter = colorFilter
        }

        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

'''
replace_once(
    "    private fun isButtonActive(activeSession: ControlsSession, button: ControlButton): Boolean = when (button) {\n",
    insert_before_button_active + "    private fun isButtonActive(activeSession: ControlsSession, button: ControlButton): Boolean = when (button) {\n",
)

old_colors = '''    private fun isDark(): Boolean = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
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
'''
new_colors = '''    private fun isDark(): Boolean = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private fun pageColor(): Int = if (isDark()) Color.rgb(23, 20, 23) else Color.rgb(246, 241, 234)
    private fun surfaceColor(): Int = if (isDark()) Color.rgb(33, 29, 33) else Color.rgb(255, 251, 246)
    private fun surfaceVariantColor(): Int = if (isDark()) Color.rgb(44, 39, 45) else Color.rgb(240, 231, 223)
    private fun activeSurfaceColor(): Int = if (isDark()) Color.rgb(67, 55, 92) else Color.rgb(237, 228, 255)
    private fun primaryColor(): Int = if (isDark()) Color.rgb(199, 184, 242) else Color.rgb(120, 103, 168)
    private fun secondaryContainerColor(): Int = if (isDark()) Color.rgb(48, 67, 56) else Color.rgb(221, 235, 221)
    private fun onSecondaryContainerColor(): Int = if (isDark()) Color.rgb(217, 239, 220) else Color.rgb(36, 56, 42)
    private fun secondaryButtonColor(): Int = surfaceVariantColor()
    private fun disabledSurfaceColor(): Int = surfaceVariantColor()
    private fun focusOutlineColor(): Int = primaryColor()
    private fun outlineColor(): Int = if (isDark()) Color.rgb(98, 88, 97) else Color.rgb(182, 170, 160)
    private fun textPrimaryColor(): Int = if (isDark()) Color.rgb(244, 237, 240) else Color.rgb(48, 42, 46)
    private fun textSecondaryColor(): Int = if (isDark()) Color.rgb(201, 190, 196) else Color.rgb(110, 100, 105)
'''
replace_once(old_colors, new_colors)

path.write_text(text)
print(f"Patched {path}")
