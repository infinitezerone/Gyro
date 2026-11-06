plugins {
    id("gyro.android.library")
    id("gyro.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.gyro.core.datastore"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    api(libs.androidx.datastore)
    api(libs.androidx.datastore.core)

    testImplementation(project(":core:testing"))
}
