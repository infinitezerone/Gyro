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

    // Release signing. Real credentials come from the environment (CI or a distribution build);
    // without them the debug key signs the release APK, so a locally built release stays
    // installable and R8/shrinking can be exercised on device instead of only compiled.
    val releaseKeystorePath = providers.environmentVariable("GYRO_KEYSTORE_PATH").orNull
    signingConfigs {
        create("release") {
            if (releaseKeystorePath == null) {
                initWith(getByName("debug"))
            } else {
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("GYRO_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("GYRO_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("GYRO_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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

/**
 * libmpv is loaded with dlopen, and its .so files are not committed (see .gitignore). Without this
 * check a fresh clone fails deep in the linker; the message below says what to actually run.
 */
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    doFirst {
        val mpv = file("src/main/jniLibs/arm64-v8a/libmpv.so")
        if (!mpv.exists()) {
            throw GradleException(
                "mpv native libraries are missing from src/main/jniLibs. " +
                    "Run scripts/fetch_mpv_libs.sh once before building."
            )
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:domain"))
    implementation(libs.androidx.media3.session)
}
