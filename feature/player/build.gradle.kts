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
    // The player depends strictly on :core:engine-api contracts and :core:domain session/usecases;
    // concrete :core:engine is bound only by :app.
    implementation(project(":core:engine-api"))
    // Session business logic is app-scoped and lives outside the feature layer.
    implementation(project(":core:domain"))

    testImplementation(project(":core:datastore"))
    testImplementation(project(":core:engine"))
    testImplementation(project(":core:database"))
}
