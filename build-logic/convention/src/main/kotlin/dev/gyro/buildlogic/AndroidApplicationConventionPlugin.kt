package dev.gyro.buildlogic

import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.application")
            apply("org.jetbrains.kotlin.plugin.compose")
        }

        extensions.configure<ApplicationExtension> {
            configureKotlinAndroid(this)
        }
        configureAndroidCompose(extensions.getByType<ApplicationExtension>())

        dependencies {
            add("implementation", project(":core:model"))
            add("implementation", project(":core:data"))
            add("implementation", project(":core:navigation"))
            add("implementation", project(":core:engine"))
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":feature:home"))
            add("implementation", project(":feature:player"))
            add("implementation", project(":feature:settings"))

            add("implementation", libs.findLibrary("androidx-lifecycle-runtime-ktx").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-runtime-compose").get())
            add("implementation", libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
            add("implementation", libs.findLibrary("androidx-activity-compose").get())
            add("implementation", libs.findLibrary("androidx-navigation3-runtime").get())
            add("implementation", libs.findLibrary("androidx-navigation3-ui").get())
            add("implementation", libs.findLibrary("kotlinx-serialization-core").get())

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
