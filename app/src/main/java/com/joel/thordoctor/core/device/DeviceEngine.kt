package com.joel.thordoctor.core.device

import android.os.Build

object DeviceEngine {

    fun currentProfile(): DeviceProfile =
        DeviceProfile(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            androidVersion = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            hardware = Build.HARDWARE,
            board = Build.BOARD
        )
}
