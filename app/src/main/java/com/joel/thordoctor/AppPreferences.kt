package com.joel.thordoctor

import android.content.Context
import android.net.Uri
import com.joel.thordoctor.modules.gamesbios.library.GameLibraryRootPreferences

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

enum class ControlGlyphProfile {
    GENERIC,
    ABXY_Y_TOP,
    ABXY_X_TOP,
    SYMBOLS_TRIANGLE_TOP,
}

object AppPreferences {

    private const val PREFERENCES_NAME =
        "thor_doctor_preferences"

    private const val KEY_ONBOARDING_COMPLETE =
        "permissions_onboarding_complete"

    private const val KEY_STORAGE_ONBOARDING_COMPLETE =
        "storage_onboarding_complete"

    private const val KEY_GAME_LIBRARY_ONBOARDING_COMPLETE =
        "game_library_onboarding_complete"

    private const val KEY_THEME_MODE =
        "theme_mode"

    private const val KEY_CONTROL_GLYPH_PROFILE =
        "control_glyph_profile"

    private const val KEY_DIAGNOSTIC_FOLDER_URI =
        "diagnostic_folder_uri"

    fun isOnboardingComplete(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ONBOARDING_COMPLETE, false)

    fun setOnboardingComplete(context: Context, complete: Boolean) {
        preferences(context)
            .edit()
            .putBoolean(KEY_ONBOARDING_COMPLETE, complete)
            .apply()
    }

    fun isStorageOnboardingComplete(context: Context): Boolean =
        preferences(context).getBoolean(KEY_STORAGE_ONBOARDING_COMPLETE, false)

    fun setStorageOnboardingComplete(context: Context, complete: Boolean) {
        preferences(context)
            .edit()
            .putBoolean(KEY_STORAGE_ONBOARDING_COMPLETE, complete)
            .apply()
    }

    fun isGameLibraryOnboardingComplete(context: Context): Boolean =
        preferences(context).getBoolean(KEY_GAME_LIBRARY_ONBOARDING_COMPLETE, false)

    fun setGameLibraryOnboardingComplete(context: Context, complete: Boolean) {
        preferences(context)
            .edit()
            .putBoolean(KEY_GAME_LIBRARY_ONBOARDING_COMPLETE, complete)
            .apply()
    }

    fun getThemeMode(context: Context): AppThemeMode {
        val value = preferences(context)
            .getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name)

        return try {
            AppThemeMode.valueOf(value ?: AppThemeMode.SYSTEM.name)
        } catch (_: IllegalArgumentException) {
            AppThemeMode.SYSTEM
        }
    }

    fun setThemeMode(context: Context, mode: AppThemeMode) {
        preferences(context)
            .edit()
            .putString(KEY_THEME_MODE, mode.name)
            .apply()
    }

    fun getControlGlyphProfile(context: Context): ControlGlyphProfile {
        val value = preferences(context)
            .getString(KEY_CONTROL_GLYPH_PROFILE, ControlGlyphProfile.GENERIC.name)

        return try {
            ControlGlyphProfile.valueOf(value ?: ControlGlyphProfile.GENERIC.name)
        } catch (_: IllegalArgumentException) {
            ControlGlyphProfile.GENERIC
        }
    }

    fun setControlGlyphProfile(context: Context, profile: ControlGlyphProfile) {
        preferences(context)
            .edit()
            .putString(KEY_CONTROL_GLYPH_PROFILE, profile.name)
            .apply()
    }

    fun getDiagnosticFolderUri(context: Context): Uri? =
        readUri(context, KEY_DIAGNOSTIC_FOLDER_URI)

    fun setDiagnosticFolderUri(context: Context, uri: Uri) {
        preferences(context)
            .edit()
            .putString(KEY_DIAGNOSTIC_FOLDER_URI, uri.toString())
            .apply()
    }

    fun clearDiagnosticFolder(context: Context) {
        preferences(context)
            .edit()
            .remove(KEY_DIAGNOSTIC_FOLDER_URI)
            .apply()
    }

    fun getGameFolderUri(context: Context): Uri? =
        GameLibraryRootPreferences.rootFolderUri(
            context
        )

    fun setGameFolderUri(context: Context, uri: Uri) {
        GameLibraryRootPreferences.setRootFolderUri(
            context,
            uri
        )
    }

    fun clearGameFolder(context: Context) {
        GameLibraryRootPreferences.clearRootFolder(
            context
        )
    }

    private fun readUri(context: Context, key: String): Uri? {
        val value = preferences(context).getString(key, null) ?: return null
        return try {
            Uri.parse(value)
        } catch (_: Exception) {
            null
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
}
