package dev.carepad.module.performance

import android.content.Context
import org.json.JSONObject
import java.io.File

object PerformanceLastSessionStore {
    private const val LAST_SESSION_FILENAME = "last_session.json"
    private const val TEMP_FILENAME = "last_session.json.tmp"

    @Synchronized
    fun write(context: Context, text: String) {
        val target = File(context.filesDir, LAST_SESSION_FILENAME)
        val temporary = File(context.filesDir, TEMP_FILENAME)

        temporary.writeText(text, Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            temporary.delete()
        }
    }

    @Synchronized
    fun load(context: Context): JSONObject? {
        val file = File(context.filesDir, LAST_SESSION_FILENAME)
        if (!file.exists() || !file.canRead()) return null

        return try {
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }
}
