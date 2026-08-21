package com.joel.thordoctor.core.device

data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidVersion: String,
    val sdk: Int,
    val hardware: String,
    val board: String
)
