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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["docThorMainEnabled"] = true
        manifestPlaceholders["carepadRecoveryLabEnabled"] = false
    }

    buildTypes {
        getByName("debug") {
            if (carePadLabHost.get()) {
                applicationIdSuffix = ".carepadlabhost"
                versionNameSuffix = "-carepad-lab-host"
                manifestPlaceholders["docThorMainEnabled"] = true
                manifestPlaceholders["carepadRecoveryLabEnabled"] = true
            }
        }
    }

    if (carePadLabHost.get()) {
        sourceSets.getByName("debug").kotlin.srcDir("src/recoveryLab/java")
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
    implementation(project(":carepad-core-android"))
    implementation(project(":performance-runtime"))
    implementation(project(":games-bios-runtime"))
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

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}