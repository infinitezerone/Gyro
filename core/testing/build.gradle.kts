plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.gyro.core.testing"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.test)
    api("junit:junit:4.13.2")

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
}
