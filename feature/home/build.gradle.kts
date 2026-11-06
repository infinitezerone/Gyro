plugins {
    id("gyro.android.feature")
    id("gyro.android.hilt")
}

android {
    namespace = "dev.gyro.feature.home"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}
