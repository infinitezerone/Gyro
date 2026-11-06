plugins {
    id("gyro.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.gyro.core.navigation"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.kotlinx.serialization.core)

    testImplementation(project(":core:testing"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}
