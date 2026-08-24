package com.joel.thordoctor.core.emulator

data class EmulatorDefinition(
    val id: String,
    val displayName: String,
    val packageNames: List<String>,
    val platforms: List<String> = emptyList()
)

data class InstalledEmulator(
    val definition: EmulatorDefinition,
    val packageName: String,
    val versionName: String
)
