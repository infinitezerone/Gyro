pluginManagement {
    includeBuild("build-logic")
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
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
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")
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
include(":core:common")
include(":core:model")
include(":core:database")
include(":core:datastore")
include(":core:data")
include(":core:navigation")
include(":core:engine")
include(":core:designsystem")
include(":core:testing")
include(":feature:settings")
include(":feature:home")
include(":feature:player")
