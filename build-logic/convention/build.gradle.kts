plugins {
    `kotlin-dsl`
}

group = "dev.gyro.buildlogic"

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.compose.compiler.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("kotlinJvm") {
            id = "gyro.kotlin.jvm"
            implementationClass = "dev.gyro.buildlogic.KotlinJvmConventionPlugin"
        }
        register("androidLibrary") {
            id = "gyro.android.library"
            implementationClass = "dev.gyro.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidCompose") {
            id = "gyro.android.compose"
            implementationClass = "dev.gyro.buildlogic.AndroidComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "gyro.android.feature"
            implementationClass = "dev.gyro.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("androidApplication") {
            id = "gyro.android.application"
            implementationClass = "dev.gyro.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidHilt") {
            id = "gyro.android.hilt"
            implementationClass = "dev.gyro.buildlogic.AndroidHiltConventionPlugin"
        }
        register("verifyModuleBoundaries") {
            id = "gyro.verify.boundaries"
            implementationClass = "dev.gyro.buildlogic.ModuleBoundaryPlugin"
        }
    }
}
