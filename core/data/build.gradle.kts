plugins {
    id("gyro.android.library")
    id("gyro.android.hilt")
}

android {
    namespace = "dev.gyro.core.data"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    api(project(":core:common"))
    api(project(":core:model"))
    // Persistence stays an implementation detail: consumers observe repository interfaces, they
    // must not reach for GyroDatabase/DAO/DataStore factories directly.
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
}
