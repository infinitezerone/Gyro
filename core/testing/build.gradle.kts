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
    compileOnly(project(":core:engine"))
    compileOnly(project(":core:database"))
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.test)
    api(libs.mockk)
    api("junit:junit:4.13.2")

    implementation(libs.kotlinx.coroutines.core)
    testImplementation(project(":core:engine"))
}
