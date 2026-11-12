plugins {
    id("gyro.android.library")
    id("gyro.android.hilt")
}

android {
    namespace = "dev.gyro.core.playback"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    // Repository interfaces and PlayerEngine both appear in the controller's public constructor,
    // so consumers (feature screens, tests) need them on their compile classpath.
    api(project(":core:data"))
    api(project(":core:engine-api"))

    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)

    // Tests drive a real GyroPlayerEngine against FakeMpvNativeBridge and a DataStore-backed
    // SettingsRepository, so the concrete engine and persistence modules are declared explicitly.
    testImplementation(project(":core:testing"))
    testImplementation(project(":core:datastore"))
}
