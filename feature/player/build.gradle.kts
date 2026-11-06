plugins {
    id("gyro.android.feature")
    id("gyro.android.hilt")
}

android {
    namespace = "dev.gyro.feature.player"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    // The player is the only feature that talks to the native engine directly.
    implementation(project(":core:engine"))
}
