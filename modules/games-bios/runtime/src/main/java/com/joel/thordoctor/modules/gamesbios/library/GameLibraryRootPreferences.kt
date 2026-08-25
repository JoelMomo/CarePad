package com.joel.thordoctor.modules.gamesbios.library

import android.content.Context
import android.net.Uri

object GameLibraryRootPreferences {

    private const val PREFERENCES_NAME =
        "thor_doctor_preferences"

    private const val KEY_GAME_FOLDER_URI =
        "game_folder_uri"

    fun rootFolderUri(
        context: Context
    ): Uri? {
        val value =
            preferences(context)
                .getString(
                    KEY_GAME_FOLDER_URI,
                    null
                )
                ?: return null

        return try {
            Uri.parse(value)
        } catch (_: Exception) {
            null
        }
    }

    fun setRootFolderUri(
        context: Context,
        uri: Uri
    ) {
        preferences(context)
            .edit()
            .putString(
                KEY_GAME_FOLDER_URI,
                uri.toString()
            )
            .apply()
    }

    fun clearRootFolder(
        context: Context
    ) {
        preferences(context)
            .edit()
            .remove(KEY_GAME_FOLDER_URI)
            .apply()
    }

    private fun preferences(
        context: Context
    ) =
        context.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
}
