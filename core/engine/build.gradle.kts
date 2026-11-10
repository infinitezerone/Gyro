plugins {
    id("gyro.android.library")
    id("gyro.android.compose")
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

fun resolveRustTool(toolName: String): String {
    val cargoHome = System.getenv("CARGO_HOME")?.takeIf { it.isNotBlank() }
        ?: "${System.getProperty("user.home")}/.cargo"
    val candidate = file("$cargoHome/bin/$toolName")
    return if (candidate.canExecute()) candidate.absolutePath else toolName
}

val cargoBin = resolveRustTool("cargo")
val rustupBin = resolveRustTool("rustup")

val cargoNdkBuild = tasks.register<Exec>("cargoNdkBuild") {
    group = "build"
    description = "Compiles the gyro-engine crate for ${nativeAbis.joinToString()} into jniLibs."

    workingDir(rootProject.projectDir)
    // Ensure ~/.cargo/bin is on PATH for subcommands like cargo-ndk and rustc
    val cargoBinDir = file("${System.getProperty("user.home")}/.cargo/bin").absolutePath
    val currentPath = System.getenv("PATH") ?: "/usr/bin:/bin:/usr/sbin:/sbin"
    if (!currentPath.split(File.pathSeparator).contains(cargoBinDir)) {
        environment("PATH", "$cargoBinDir${File.pathSeparator}$currentPath")
    }
    commandLine(
        buildList {
            add(cargoBin)
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
        commandLine(rustupBin, "target", "list", "--installed")
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
tasks.named("preBuild").configure {
    dependsOn(cargoNdkBuild)
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach {
        dependsOn(cargoNdkBuild)
    }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("NativeLibs") }
    .configureEach {
        dependsOn(cargoNdkBuild)
    }

dependencies {
    api(project(":core:engine-api"))
    api(project(":core:model"))
    api(libs.androidx.media3.session)
    api(libs.androidx.media3.common)
    implementation(libs.androidx.mediarouter)
    implementation(libs.libmpv)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(libs.androidx.compose.ui)

    testImplementation(project(":core:testing"))
    // Tests construct real MpvConfigRepository/SettingsRepository instances to drive the
    // injection priority paths; main code has no dependency on :core:data.
    testImplementation(project(":core:data"))
    testImplementation(project(":core:datastore"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("junit:junit:4.13.2")
}
