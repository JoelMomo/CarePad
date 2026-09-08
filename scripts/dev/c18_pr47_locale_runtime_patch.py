from pathlib import Path

path = Path("modules/controls/app/src/main/java/dev/carepad/module/controls/ControlsActivity.kt")
text = path.read_text()

old_on_create = '''    override fun onCreate(savedInstanceState: Bundle?) {
        applyHostLocaleOverride()
        super.onCreate(savedInstanceState)
        inputManager = getSystemService(InputManager::class.java)
'''
new_on_create = '''    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyHostLocaleOverride()
        inputManager = getSystemService(InputManager::class.java)
'''
if text.count(old_on_create) != 1:
    raise SystemExit(f"Expected one onCreate locale block, found {text.count(old_on_create)}")
text = text.replace(old_on_create, new_on_create, 1)

old_locale = '''    private fun applyHostLocaleOverride() {
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
'''
new_locale = '''    @Suppress("DEPRECATION")
    private fun applyHostLocaleOverride() {
        val tag = intent.getStringExtra(CarePadHostNavigation.EXTRA_HOST_LOCALE_TAG)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        val locale = Locale.forLanguageTag(tag)
        val override = Configuration(resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        Locale.setDefault(locale)
        resources.updateConfiguration(override, resources.displayMetrics)
    }
'''
if text.count(old_locale) != 1:
    raise SystemExit(f"Expected one locale override block, found {text.count(old_locale)}")
text = text.replace(old_locale, new_locale, 1)

path.write_text(text)
print(f"Patched {path}")
