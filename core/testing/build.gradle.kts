plugins {
    id("gyro.android.library")
}

android {
    namespace = "dev.gyro.core.testing"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":core:engine"))
    api(project(":core:model"))
    api(project(":core:database"))
    api(libs.kotlinx.coroutines.test)
    api("junit:junit:4.13.2")

    implementation(libs.kotlinx.coroutines.core)
}
