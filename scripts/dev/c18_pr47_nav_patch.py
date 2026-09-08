from pathlib import Path

path = Path("modules/controls/app/src/main/java/dev/carepad/module/controls/ControlsActivity.kt")
text = path.read_text()

text = text.replace("navigationRailView", "navigationContainerView")
text = text.replace("navigationRailHomeButton", "navigationHomeButton")

old = '''        } else {
            navigationContainerView = null
            navigationHomeButton = null
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(pageColor())
                addView(contentColumn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(bottomNavigation(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
'''
new = '''        } else {
            val navigation = bottomNavigation()
            navigationContainerView = navigation
            navigationHomeButton = firstFocusableDescendant(navigation)
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(pageColor())
                addView(contentColumn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
                addView(navigation, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }
'''
if text.count(old) != 1:
    raise SystemExit(f"Expected one compact-navigation block, found {text.count(old)}")
text = text.replace(old, new, 1)
path.write_text(text)
print(f"Patched {path}")
