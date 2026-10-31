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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("com.android.settings") version "9.3.2"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

android {
    compileSdk = 37
    minSdk = 29
    targetSdk = 37
}

rootProject.name = "Gyro"

include(":app")
include(":core:engine")
