package dev.gyro.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project

/*
 * AGP 9 ships built-in Kotlin support: the org.jetbrains.kotlin.android plugin is no longer
 * applied, and Kotlin's jvmTarget follows compileOptions.targetCompatibility. That is why only
 * the Java compatibility level is configured here.
 */

internal fun Project.configureKotlinAndroid(extension: ApplicationExtension) {
    extension.apply {
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
        testOptions {
            unitTests.isReturnDefaultValues = true
        }
    }
}

internal fun Project.configureKotlinAndroid(extension: LibraryExtension) {
    extension.apply {
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
        testOptions {
            unitTests.isReturnDefaultValues = true
        }
    }
}
