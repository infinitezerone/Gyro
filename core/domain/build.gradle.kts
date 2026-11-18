plugins {
    id("gyro.android.library")
    id("gyro.android.hilt")
}

android {
    namespace = "dev.gyro.core.domain"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":core:engine-api"))
    implementation(project(":core:data"))

    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)

    // Tests drive a real GyroPlayerEngine against FakeMpvNativeBridge and a DataStore-backed
    // SettingsRepository, so the concrete engine and persistence modules are declared explicitly.
    testImplementation(project(":core:testing"))
    testImplementation(project(":core:engine"))
    testImplementation(project(":core:database"))
    testImplementation(project(":core:datastore"))
}
