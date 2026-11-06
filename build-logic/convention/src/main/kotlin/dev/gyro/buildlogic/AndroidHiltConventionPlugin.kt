package dev.gyro.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Hilt + KSP for Android modules that own an injection graph entry point.
 *
 * Deliberately does NOT touch :core:engine: the engine module stays free of DI frameworks, and
 * everything it needs is @Provides-ed from :app, which is the only module that knows the whole
 * graph. Applying this plugin to a core module would invert that.
 */
class AndroidHiltConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.google.devtools.ksp")
            apply("com.google.dagger.hilt.android")
        }

        dependencies {
            add("implementation", libs.findLibrary("hilt-android").get())
            add("ksp", libs.findLibrary("hilt-compiler").get())
        }
    }
}
