package dev.gyro.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

internal fun Project.configureAndroidCompose(extension: ApplicationExtension) {
    extension.apply {
        buildFeatures {
            compose = true
        }
    }
    dependencies {
        add("implementation", platform(libs.findLibrary("androidx-compose-bom").get()))
    }
}

internal fun Project.configureAndroidCompose(extension: LibraryExtension) {
    extension.apply {
        buildFeatures {
            compose = true
        }
    }
    dependencies {
        add("implementation", platform(libs.findLibrary("androidx-compose-bom").get()))
    }
}
