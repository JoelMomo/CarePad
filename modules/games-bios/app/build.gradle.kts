plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.carepad.module.gamesbios"

    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "dev.carepad.module.gamesbios"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":games-bios-runtime"))
    implementation(libs.androidx.documentfile)
}
