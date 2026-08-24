plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.carepad.fixture.emulator"

    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "org.ppsspp.ppsspp"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0-fixture"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
