plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.carepad.module.performance"

    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "dev.carepad.module.performance"
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
    implementation(project(":carepad-contracts"))
    implementation(project(":carepad-core-android"))
    implementation(project(":performance-runtime"))
    implementation(libs.androidx.core.ktx)
}
