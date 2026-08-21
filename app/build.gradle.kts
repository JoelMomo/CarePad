plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val carePadLabHost = providers.gradleProperty("carepadLabHost")
    .map(String::toBoolean)
    .orElse(false)

android {
    namespace = "com.joel.thordoctor"

    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.joel.thordoctor"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["docThorMainEnabled"] = true
    }

    buildTypes {
        getByName("debug") {
            if (carePadLabHost.get()) {
                applicationIdSuffix = ".carepadlabhost"
                versionNameSuffix = "-carepad-lab-host"
                manifestPlaceholders["docThorMainEnabled"] = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":carepad-contracts"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.documentfile)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
