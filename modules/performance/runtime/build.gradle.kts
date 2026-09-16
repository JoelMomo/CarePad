plugins {
    id("com.android.library")
}

android {
    namespace = "com.joel.thordoctor.modules.performance"

    compileSdk {
        version = release(36)
    }

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        // Log/SystemClock/ContextWrapper stubs; recovery storage is supplied by the test.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(libs.junit)
}
