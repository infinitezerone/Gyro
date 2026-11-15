plugins {
    id("gyro.android.application")
    id("gyro.android.hilt")
    alias(libs.plugins.kotlin.serialization)
}

// Must match the ABIs :core:engine compiles the Rust engine for, see gyro.nativeAbis.
val nativeAbis: List<String> = providers.gradleProperty("gyro.nativeAbis")
    .map { abis -> abis.split(",").map(String::trim).filter(String::isNotEmpty) }
    .get()

android {
    namespace = "dev.gyro.player"

    defaultConfig {
        applicationId = "dev.gyro.player"
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += nativeAbis
        }
    }

    packaging {
        jniLibs {
            pickFirsts += listOf(
                "**/libmpv.so",
                "**/libavcodec.so",
                "**/libavdevice.so",
                "**/libavfilter.so",
                "**/libavformat.so",
                "**/libavutil.so",
                "**/libswresample.so",
                "**/libswscale.so",
                "**/libplayer.so",
                "**/libc++_shared.so"
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    splits {
        abi {
            // Per-ABI APKs for direct distribution (roughly halves each download), plus the
            // universal one for sideloading onto unknown devices. ABI list stays coupled to
            // the nativeAbis cargo builds for, so a split can never advertise a missing .so.
            isEnable = true
            reset()
            include(*nativeAbis.toTypedArray())
            isUniversalApk = true
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:playback"))
    implementation(libs.androidx.media3.session)
}
