package com.joel.thordoctor.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = ThorLavender,
    onPrimary = Color(0xFF281E40),
    primaryContainer = Color(0xFF43375C),
    onPrimaryContainer = Color(0xFFEDE5FF),
    secondary = ThorSage,
    onSecondary = Color(0xFF1B3020),
    secondaryContainer = Color(0xFF304338),
    onSecondaryContainer = Color(0xFFD9EFDC),
    tertiary = ThorApricot,
    onTertiary = Color(0xFF402413),
    tertiaryContainer = Color(0xFF533829),
    onTertiaryContainer = Color(0xFFFFE2CF),
    background = ThorDarkBackground,
    onBackground = ThorDarkOnSurface,
    surface = ThorDarkSurface,
    onSurface = ThorDarkOnSurface,
    surfaceVariant = ThorDarkSurfaceVariant,
    onSurfaceVariant = ThorDarkOnSurfaceVariant,
    outline = ThorDarkOutline,
    error = Color(0xFFFF8F8A),
    onError = Color(0xFF4F1010),
    errorContainer = Color(0xFF5C2424),
    onErrorContainer = Color(0xFFFFDAD7)
)

private val LightColorScheme = lightColorScheme(
    primary = ThorLavenderStrong,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE4FF),
    onPrimaryContainer = Color(0xFF33244F),
    secondary = ThorSageStrong,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDEBDD),
    onSecondaryContainer = Color(0xFF24382A),
    tertiary = ThorApricotStrong,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF6DFD1),
    onTertiaryContainer = Color(0xFF4E2B18),
    background = ThorLightBackground,
    onBackground = ThorLightOnSurface,
    surface = ThorLightSurface,
    onSurface = ThorLightOnSurface,
    surfaceVariant = ThorLightSurfaceVariant,
    onSurfaceVariant = ThorLightOnSurfaceVariant,
    outline = ThorLightOutline,
    error = Color(0xFFB54843),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD7),
    onErrorContainer = Color(0xFF5F1614)
)

@Composable
fun ThorDoctorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme =
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else if (darkTheme) {
            DarkColorScheme
        } else {
            LightColorScheme
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
