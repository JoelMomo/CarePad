plugins {
    alias(libs.plugins.android.application)
}

val labVersionCode = providers.gradleProperty("carepadModuleLabVersionCode")
    .map(String::toInt)
    .orElse(1)
val labVersionName = providers.gradleProperty("carepadModuleLabVersionName")
    .orElse("0.1-lab")
val labProtocolMin = providers.gradleProperty("carepadModuleLabProtocolMin")
    .map(String::toInt)
    .orElse(1)
val labProtocolMax = providers.gradleProperty("carepadModuleLabProtocolMax")
    .map(String::toInt)
    .orElse(1)
val labAlwaysCrash = providers.gradleProperty("carepadModuleLabAlwaysCrash")
    .map(String::toBoolean)
    .orElse(false)

android {
    namespace = "com.joel.thordoctor.modulelab"

    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.joel.thordoctor.modulelab"
        minSdk = 24
        targetSdk = 36
        versionCode = labVersionCode.get()
        versionName = labVersionName.get()
        manifestPlaceholders["carepadModuleLabProtocolMin"] = labProtocolMin.get()
        manifestPlaceholders["carepadModuleLabProtocolMax"] = labProtocolMax.get()
        manifestPlaceholders["carepadModuleLabAlwaysCrash"] = labAlwaysCrash.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":carepad-contracts"))
}
