plugins {
    id("gyro.android.library")
    id("gyro.android.compose")
}

android {
    namespace = "dev.gyro.core.designsystem"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(project(":core:testing"))
}
