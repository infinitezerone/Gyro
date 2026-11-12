plugins {
    id("gyro.android.library")
}

android {
    namespace = "dev.gyro.engine.api"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    // Public API: PlaybackSnapshot exposes ChapterInfo/GlslShaderState and PlayerEngine exposes
    // MediaInfo, so consumers need these types on their compile classpath.
    api(project(":core:model"))
    implementation(project(":core:common"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":core:testing"))
}
