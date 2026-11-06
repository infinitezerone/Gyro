package dev.gyro.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Common wiring for `:feature:*` modules: Android library + Compose, the core modules every
 * feature observes, and the shared Compose/lifecycle stack.
 *
 * Feature-specific dependencies (for example :core:engine for :feature:player) are added by the
 * module itself so this plugin stays a floor, not a ceiling.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("gyro.android.library")
            apply("gyro.android.compose")
        }

        dependencies {
            add("implementation", project(":core:model"))
            add("implementation", project(":core:data"))
            add("implementation", project(":core:navigation"))
            add("implementation", project(":core:designsystem"))

            add("implementation", libs.findLibrary("androidx-lifecycle-runtime-ktx").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
            add("implementation", libs.findLibrary("androidx-activity-compose").get())

            add("implementation", libs.findLibrary("androidx-compose-ui").get())
            add("implementation", libs.findLibrary("androidx-compose-ui-graphics").get())
            add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
            add("implementation", libs.findLibrary("androidx-compose-material3").get())
            add("implementation", libs.findLibrary("androidx-compose-material-icons-extended").get())
            add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())

            add("testImplementation", project(":core:testing"))
        }
    }
}
