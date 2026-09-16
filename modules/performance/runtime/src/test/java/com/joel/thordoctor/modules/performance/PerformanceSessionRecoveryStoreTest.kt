package com.joel.thordoctor.modules.performance

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PerformanceSessionRecoveryStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun monitoringMetadataSurvivesProcessDeathBeforeAsyncWritesFlush() {
        val storage = DelayedPreferences()
        val context = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int) = storage.preferences
            override fun getFilesDir(): File = temporaryFolder.root
        }
        PerformanceSessionRecoveryStore.markWaiting(context)
        storage.flushAsyncWrites()

        val expected = PerformanceRecoveryState.Monitoring(
            sessionId = "original-session",
            emulatorName = "PPSSPP",
            emulatorPackage = "org.ppsspp.ppsspp",
            startedAt = 1_000L
        )
        PerformanceSessionRecoveryStore.markMonitoring(
            context,
            expected.sessionId,
            expected.emulatorName,
            expected.emulatorPackage,
            expected.startedAt
        )

        // The service can persist its first sample as soon as markMonitoring returns.
        // A process kill must not revert that sample's metadata to WAITING_EMULATOR.
        storage.killProcess()
        assertEquals(expected, PerformanceSessionRecoveryStore.load(context))
    }

    // Model apply's immediate memory update and deferred disk write independently.
    // Process death discards only unflushed changes; commit persists before returning.
    private class DelayedPreferences {
        private var memory = mutableMapOf<String, Any?>()
        private var disk = emptyMap<String, Any?>()

        val preferences = proxy(SharedPreferences::class.java) { method, args ->
            when (method) {
                "edit" -> editor()
                "getBoolean", "getString", "getLong" -> memory[args[0]] ?: args[1]
                else -> error("Unexpected SharedPreferences method: $method")
            }
        }

        fun flushAsyncWrites() {
            disk = memory.toMap()
        }

        fun killProcess() {
            memory = disk.toMutableMap()
        }

        private fun editor(): SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            lateinit var editor: SharedPreferences.Editor
            editor = proxy(SharedPreferences.Editor::class.java) { method, args ->
                when (method) {
                    "putBoolean", "putString", "putLong" -> {
                        changes[args[0] as String] = args[1]
                        editor
                    }
                    "apply", "commit" -> {
                        memory.putAll(changes)
                        if (method == "commit") {
                            flushAsyncWrites()
                            true
                        } else null
                    }
                    else -> error("Unexpected Editor method: $method")
                }
            }
            return editor
        }

        private fun <T : Any> proxy(type: Class<T>, call: (String, Array<out Any?>) -> Any?): T =
            requireNotNull(type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                call(method.name, args ?: emptyArray())
            }))
    }
}
