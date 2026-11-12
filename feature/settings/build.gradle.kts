plugins {
    id("gyro.android.feature")
    id("gyro.android.hilt")
}

android {
    namespace = "dev.gyro.feature.settings"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    // Tests drive the real DataStore-backed SettingsRepository, so they declare the persistence
    // module explicitly instead of inheriting it from :core:data.
    testImplementation(project(":core:datastore"))
}
