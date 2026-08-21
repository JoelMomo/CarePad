pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "DocThor"
include(":app")
include(":carepad-contracts")
include(":module-lab")
include(":carepad-core-android")
project(":carepad-core-android").projectDir = file("core/android")
include(":performance-runtime")
project(":performance-runtime").projectDir = file("modules/performance/runtime")
include(":performance-module")
project(":performance-module").projectDir = file("modules/performance/app")
include(":performance-emulator-fixture")
project(":performance-emulator-fixture").projectDir = file("test-fixtures/performance-emulator")
