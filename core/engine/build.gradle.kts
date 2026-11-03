plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

// The native engine is compiled from crates/gyro-engine at build time: no .so is committed.
// gyro.nativeAbis is the single source of truth for both the cargo-ndk targets here and the
// abiFilters in :app, so packaging can never advertise an ABI that has no library.
val nativeAbis: List<String> = providers.gradleProperty("gyro.nativeAbis")
    .map { abis -> abis.split(",").map(String::trim).filter(String::isNotEmpty) }
    .get()

// cargo-ndk writes <abi>/libgyro_engine.so under this directory, which is registered as a
// generated jniLibs source below.
val rustJniLibsDir = layout.buildDirectory.dir("rustJniLibs")

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "build"
    description = "Compiles the gyro-engine crate for ${nativeAbis.joinToString()} into jniLibs."

    workingDir(rootProject.projectDir)
    commandLine(
        buildList {
            add("cargo")
            add("ndk")
            nativeAbis.forEach { abi ->
                add("-t")
                add(abi)
            }
            add("--platform") // cargo-ndk uses -P for API level; -p would select a cargo package
            add("29") // minSdk, kept in sync with settings.gradle.kts
            add("-o")
            add(rustJniLibsDir.get().asFile.absolutePath)
            add("build")
            add("--release")
        }
    )

    // Screens out the common failure: a rustup target that was never installed for these ABIs.
    val installedTargets = providers.exec {
        commandLine("rustup", "target", "list", "--installed")
    }.standardOutput.asText.map { output -> output.lines().map(String::trim) }
    inputs.property("installedRustTargets", installedTargets)
    doFirst {
        val expected = mapOf(
            "arm64-v8a" to "aarch64-linux-android",
            "x86_64" to "x86_64-linux-android",
        )
        val missing = nativeAbis.mapNotNull { expected[it] }.filterNot { it in installedTargets.get() }
        check(missing.isEmpty()) {
            "Missing rustup targets for :core:engine: ${missing.joinToString()}. " +
                "Run: rustup target add ${missing.joinToString(" ")}"
        }
    }

    inputs.dir(rootProject.file("crates"))
    inputs.files(
        rootProject.file("Cargo.toml"),
        rootProject.file("Cargo.lock"),
        rootProject.file("rust-toolchain.toml"),
    )
    outputs.dir(rustJniLibsDir)
}

android {
    namespace = "dev.gyro.engine"

    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += nativeAbis
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// The sourceSets DSL cannot express the generated jniLibs directory on a library module in AGP 9,
// so register it through the variant API instead.
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(rustJniLibsDir.get().asFile.absolutePath)
    }
}

// AGP does not infer the dependency between the generated jniLibs directory and the task that
// fills it, so wire it explicitly for every variant.
tasks.named("preBuild").configure { dependsOn(cargoNdkBuild) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(cargoNdkBuild) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }
    .configureEach { dependsOn(cargoNdkBuild) }

dependencies {
    api(project(":core:model"))
    implementation(project(":core:data"))
    implementation(libs.libmpv)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)

    testImplementation(project(":core:testing"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("junit:junit:4.13.2")
}
