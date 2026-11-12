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
    implementation(project(":core:engine-api"))
    implementation(project(":core:engine"))
    // Session business logic is app-scoped and lives outside the feature layer.
    implementation(project(":core:playback"))
    implementation(libs.androidx.palette)

    testImplementation(project(":core:datastore"))
}
