package dev.gyro.buildlogic

import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/**
 * Pure-JVM modules (currently :core:model) do use org.jetbrains.kotlin.jvm, where jvmTarget
 * defaults to the running JDK rather than to the Java compatibility level. The build daemon can
 * sit on a newer JDK than 21, so the mismatch has to be stated explicitly:
 *
 *   Inconsistent JVM-target compatibility detected for tasks 'compileJava' (21) and
 *   'compileKotlin' (25).
 */
internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
}
