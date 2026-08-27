plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.carepad.fixture.controlsinput"

    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "dev.carepad.fixture.controlsinput"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-lab"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":controls-runtime"))
}
