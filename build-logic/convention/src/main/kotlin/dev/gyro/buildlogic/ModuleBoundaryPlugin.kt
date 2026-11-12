package dev.gyro.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/**
 * Fails the build when a module imports a package owned by a layer it must not depend on.
 *
 * The module graph is declared in build files, but nothing stops a `:feature:*` module from
 * importing `dev.gyro.core.database` once `:core:data` re-exports it. This check reads the main
 * sources of every module and enforces the direction those declarations imply, so the boundary is
 * a build failure instead of a review comment.
 *
 * Test sources are deliberately out of scope: tests wire real implementations on purpose.
 */
class ModuleBoundaryPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        val verify = tasks.register("verifyModuleBoundaries") {
            group = "verification"
            description = "Checks that no module imports a package owned by a forbidden layer."

            val sourceRoots = target.subprojects
                .map { it.path to it.file("src/main/kotlin") }
                .filter { it.second.isDirectory }

            inputs.files(sourceRoots.map { (_, dir) -> fileTree(dir) { include("**/*.kt") } })

            doLast {
                val violations = mutableListOf<String>()
                sourceRoots.forEach { (modulePath, dir) ->
                    val boundary = boundaries[modulePath] ?: return@forEach
                    dir.walkTopDown()
                        .filter { it.isFile && it.extension == "kt" }
                        .sortedBy { it.absolutePath }
                        .forEach { file ->
                            val relative = file.relativeTo(target.projectDir).path
                            file.readLines().forEachIndexed { index, rawLine ->
                                val line = rawLine.trim()
                                if (!line.startsWith("import ")) return@forEachIndexed
                                val imported = line.removePrefix("import ").trim()
                                val reason = boundary.violation(imported) ?: return@forEachIndexed
                                violations += "$relative:${index + 1}: $modulePath imports $imported — $reason"
                            }
                        }
                }

                if (violations.isNotEmpty()) {
                    throw IllegalStateException(
                        buildString {
                            appendLine("Module boundaries violated (${violations.size}):")
                            violations.forEach { appendLine("  $it") }
                            appendLine()
                            appendLine("Fix the import, or move the type into a layer both modules may see.")
                        }
                    )
                }
            }
        }

        // Root `check` is the developer-facing entry point; :app:preBuild guarantees the check also
        // runs on `assembleDebug`/`build`, which is what CI actually calls.
        pluginManager.apply("base")
        tasks.named("check") { dependsOn(verify) }
        target.subprojects {
            if (path == ":app") {
                tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(verify) }
            }
        }
    }

    private class Boundary(
        /** Import prefixes this module may never use. */
        val forbidden: List<String> = emptyList(),
        /** When set, any `dev.gyro.engine.*` import must match one of these prefixes. */
        val engineAllowlist: List<String>? = null,
    ) {
        fun violation(imported: String): String? {
            forbidden.firstOrNull { imported == it || imported.startsWith("$it.") }?.let {
                return "forbidden layer $it"
            }
            if (engineAllowlist != null &&
                imported.startsWith("dev.gyro.engine.") &&
                engineAllowlist.none { imported.startsWith(it) }
            ) {
                return "only ${engineAllowlist.joinToString()} may be imported from the engine"
            }
            return null
        }
    }

    private val boundaries: Map<String, Boundary> = mapOf(
        // Foundation layers depend on nothing above them.
        ":core:model" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.common", "dev.gyro.core.data", "dev.gyro.core.database",
                "dev.gyro.core.datastore", "dev.gyro.core.playback", "dev.gyro.engine",
                "dev.gyro.feature", "dev.gyro.player",
            )
        ),
        ":core:common" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.designsystem", "dev.gyro.core.model", "dev.gyro.core.data",
                "dev.gyro.core.database", "dev.gyro.core.datastore", "dev.gyro.core.navigation",
                "dev.gyro.core.playback", "dev.gyro.engine", "dev.gyro.feature", "dev.gyro.player",
            )
        ),
        ":core:designsystem" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.data", "dev.gyro.core.database", "dev.gyro.core.datastore",
                "dev.gyro.core.playback", "dev.gyro.engine", "dev.gyro.feature", "dev.gyro.player",
            )
        ),
        ":core:navigation" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.data", "dev.gyro.core.database", "dev.gyro.core.datastore",
                "dev.gyro.core.playback", "dev.gyro.engine", "dev.gyro.feature", "dev.gyro.player",
            )
        ),
        ":core:database" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.data", "dev.gyro.core.datastore", "dev.gyro.core.playback",
                "dev.gyro.engine", "dev.gyro.feature", "dev.gyro.player",
            )
        ),
        ":core:datastore" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.data", "dev.gyro.core.database", "dev.gyro.core.playback",
                "dev.gyro.engine", "dev.gyro.feature", "dev.gyro.player",
            )
        ),
        ":core:data" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.playback", "dev.gyro.engine", "dev.gyro.feature", "dev.gyro.player",
            )
        ),
        ":core:engine-api" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.data", "dev.gyro.core.database", "dev.gyro.core.datastore",
                "dev.gyro.core.playback", "dev.gyro.feature", "dev.gyro.player",
            )
        ),
        ":core:engine" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.data", "dev.gyro.core.database", "dev.gyro.core.datastore",
                "dev.gyro.core.playback", "dev.gyro.feature", "dev.gyro.player",
            )
        ),
        // Session logic sits above the engine contract but below every screen: it may see the
        // api/model packages and nothing else from the concrete mpv implementation.
        ":core:playback" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.database", "dev.gyro.core.datastore", "dev.gyro.feature",
                "dev.gyro.player",
            ),
            engineAllowlist = listOf("dev.gyro.engine.api.", "dev.gyro.engine.model."),
        ),
        ":feature:home" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.database", "dev.gyro.core.datastore", "dev.gyro.engine",
                "dev.gyro.player",
            )
        ),
        ":feature:settings" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.database", "dev.gyro.core.datastore", "dev.gyro.engine",
                "dev.gyro.player",
            )
        ),
        // :feature:player still owns the Compose surface wrapper (dev.gyro.engine.ui), so the
        // engine is not forbidden here yet. Persistence internals are.
        ":feature:player" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.database", "dev.gyro.core.datastore", "dev.gyro.player",
            )
        ),
    )
}
