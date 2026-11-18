package dev.gyro.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

/**
 * Fails the build when a module imports or references (via FQN) a package owned by a forbidden layer.
 *
 * The module graph is declared in build files, but transitive `api` exports can expose lower-layer
 * packages. This check scans the stripped main Kotlin sources (ignoring comments and string literals)
 * of every module and enforces both import and inline FQN architectural boundaries at build time.
 *
 * Test sources are deliberately out of scope: tests wire real implementations on purpose.
 */
class ModuleBoundaryPlugin : Plugin<Project> {

    override fun apply(target: Project) = with(target) {
        val verify = tasks.register("verifyModuleBoundaries") {
            group = "verification"
            description = "Checks that no module imports or references a symbol from a forbidden layer."

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
                            val isDiSource = relative.replace('\\', '/').contains("/di/")
                            val stripped = stripCommentsAndStringsPreservingLines(file.readText())
                            stripped.lines().forEachIndexed { index, rawLine ->
                                val line = rawLine.trim()
                                if (line.isEmpty() || line.startsWith("package ")) return@forEachIndexed
                                if (line.startsWith("import ")) {
                                    val imported = line.removePrefix("import ")
                                        .substringBefore(" as ")
                                        .trim()
                                    val reason = boundary.violation(imported, isDiSource)
                                        ?: return@forEachIndexed
                                    violations += "$relative:${index + 1}: $modulePath imports $imported — $reason"
                                } else {
                                    inlineFqnRegex.findAll(rawLine).forEach { match ->
                                        val symbol = match.value
                                        val reason = boundary.violation(symbol, isDiSource)
                                            ?: return@forEach
                                        violations += "$relative:${index + 1}: $modulePath references $symbol — $reason"
                                    }
                                }
                            }
                        }
                }

                if (violations.isNotEmpty()) {
                    throw IllegalStateException(
                        buildString {
                            appendLine("Module boundaries violated (${violations.size}):")
                            violations.forEach { appendLine("  $it") }
                            appendLine()
                            appendLine("Fix the import/reference, or move the type into a layer both modules may see.")
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
        /** Import/FQN prefixes this module may never use. */
        val forbidden: List<String> = emptyList(),
        /** When non-null, any `dev.gyro.engine.*` symbol must match one of these prefixes/types. */
        val engineAllowlist: List<String>? = null,
        /** Additional `dev.gyro.engine.*` entries permitted exclusively in `/di/` sources. */
        val diEngineAllowlist: List<String> = emptyList(),
    ) {
        fun violation(symbol: String, isDiSource: Boolean = false): String? {
            forbidden.firstOrNull { symbol == it || symbol.startsWith("$it.") }?.let {
                return "forbidden layer $it"
            }
            if (engineAllowlist != null && symbol.startsWith("dev.gyro.engine.")) {
                val effectiveAllowlist = if (isDiSource) engineAllowlist + diEngineAllowlist else engineAllowlist
                val allowed = effectiveAllowlist.any { entry ->
                    if (entry.endsWith(".")) {
                        symbol.startsWith(entry)
                    } else {
                        symbol == entry || symbol.startsWith("$entry.")
                    }
                }
                if (!allowed) {
                    return "only ${effectiveAllowlist.joinToString()} may be used from the engine"
                }
            }
            return null
        }
    }

    private companion object {
        val inlineFqnRegex = Regex(
            """\b(?:dev\.gyro\.[A-Za-z0-9_.]+|kotlinx\.coroutines\.Dispatchers(?:\.[A-Za-z0-9_.]+)?)\b"""
        )

        fun stripCommentsAndStringsPreservingLines(source: String): String {
            val len = source.length
            val out = StringBuilder(len)
            var i = 0
            var blockDepth = 0
            var inLineComment = false
            var inString = false
            var inRawString = false
            var inChar = false

            while (i < len) {
                val ch = source[i]
                when {
                    inLineComment -> {
                        if (ch == '\n') {
                            inLineComment = false
                            out.append('\n')
                        } else {
                            out.append(' ')
                        }
                        i++
                    }
                    blockDepth > 0 -> {
                        if (ch == '\n') {
                            out.append('\n')
                            i++
                        } else if (ch == '/' && i + 1 < len && source[i + 1] == '*') {
                            blockDepth++
                            out.append("  ")
                            i += 2
                        } else if (ch == '*' && i + 1 < len && source[i + 1] == '/') {
                            blockDepth--
                            out.append("  ")
                            i += 2
                        } else {
                            out.append(' ')
                            i++
                        }
                    }
                    inRawString -> {
                        if (ch == '\n') {
                            out.append('\n')
                            i++
                        } else if (ch == '"' && i + 2 < len && source[i + 1] == '"' && source[i + 2] == '"') {
                            while (i + 3 < len && source[i + 3] == '"') {
                                out.append(' ')
                                i++
                            }
                            out.append("   ")
                            i += 3
                            inRawString = false
                        } else {
                            out.append(' ')
                            i++
                        }
                    }
                    inString -> {
                        if (ch == '\n') {
                            inString = false
                            out.append('\n')
                            i++
                        } else if (ch == '\\' && i + 1 < len) {
                            out.append("  ")
                            i += 2
                        } else if (ch == '"') {
                            inString = false
                            out.append(' ')
                            i++
                        } else {
                            out.append(' ')
                            i++
                        }
                    }
                    inChar -> {
                        if (ch == '\n') {
                            inChar = false
                            out.append('\n')
                            i++
                        } else if (ch == '\\' && i + 1 < len) {
                            out.append("  ")
                            i += 2
                        } else if (ch == '\'') {
                            inChar = false
                            out.append(' ')
                            i++
                        } else {
                            out.append(' ')
                            i++
                        }
                    }
                    else -> {
                        if (ch == '/' && i + 1 < len && source[i + 1] == '/') {
                            inLineComment = true
                            out.append("  ")
                            i += 2
                        } else if (ch == '/' && i + 1 < len && source[i + 1] == '*') {
                            blockDepth = 1
                            out.append("  ")
                            i += 2
                        } else if (ch == '"' && i + 2 < len && source[i + 1] == '"' && source[i + 2] == '"') {
                            inRawString = true
                            out.append("   ")
                            i += 3
                        } else if (ch == '"') {
                            inString = true
                            out.append(' ')
                            i++
                        } else if (ch == '\'') {
                            inChar = true
                            out.append(' ')
                            i++
                        } else {
                            out.append(ch)
                            i++
                        }
                    }
                }
            }
            return out.toString()
        }
    }

    private val boundaries: Map<String, Boundary> = mapOf(
        // Foundation layers depend on nothing above them.
        ":core:model" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.common", "dev.gyro.core.data", "dev.gyro.core.database",
                "dev.gyro.core.datastore", "dev.gyro.core.domain", "dev.gyro.engine",
                "dev.gyro.feature", "dev.gyro.player",
            )
        ),
        ":core:common" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.designsystem", "dev.gyro.core.model", "dev.gyro.core.data",
                "dev.gyro.core.database", "dev.gyro.core.datastore", "dev.gyro.core.navigation",
                "dev.gyro.core.domain", "dev.gyro.engine", "dev.gyro.feature", "dev.gyro.player",
            )
        ),
        ":core:designsystem" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.data", "dev.gyro.core.database", "dev.gyro.core.datastore",
                "dev.gyro.core.domain", "dev.gyro.engine", "dev.gyro.feature", "dev.gyro.player",
            )
        ),
        ":core:navigation" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.data", "dev.gyro.core.database", "dev.gyro.core.datastore",
                "dev.gyro.core.domain", "dev.gyro.engine", "dev.gyro.feature", "dev.gyro.player",
            )
        ),
        ":core:database" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.data", "dev.gyro.core.datastore", "dev.gyro.core.domain",
                "dev.gyro.engine", "dev.gyro.feature", "dev.gyro.player",
            )
        ),
        ":core:datastore" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.data", "dev.gyro.core.database", "dev.gyro.core.domain",
                "dev.gyro.engine", "dev.gyro.feature", "dev.gyro.player",
                "kotlinx.coroutines.Dispatchers",
            )
        ),
        ":core:data" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.domain", "dev.gyro.engine", "dev.gyro.feature", "dev.gyro.player",
                "kotlinx.coroutines.Dispatchers",
            )
        ),
        ":core:engine-api" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.data", "dev.gyro.core.database", "dev.gyro.core.datastore",
                "dev.gyro.core.domain", "dev.gyro.feature", "dev.gyro.player",
                "kotlinx.coroutines.Dispatchers",
            )
        ),
        ":core:engine" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.data", "dev.gyro.core.database", "dev.gyro.core.datastore",
                "dev.gyro.core.domain", "dev.gyro.feature", "dev.gyro.player",
            )
        ),
        // Domain logic sits above the engine contract and data repositories, but below every screen:
        // it may see the api/model packages and nothing else from the concrete mpv implementation,
        // and must never switch coroutine dispatchers directly (main-safety belongs in :core:data/:core:engine).
        ":core:domain" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.database", "dev.gyro.core.datastore", "dev.gyro.feature",
                "dev.gyro.player", "kotlinx.coroutines.Dispatchers",
            ),
            engineAllowlist = listOf("dev.gyro.engine.api.", "dev.gyro.engine.model."),
        ),
        ":feature:home" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.database", "dev.gyro.core.datastore", "dev.gyro.engine",
                "dev.gyro.player", "kotlinx.coroutines.Dispatchers",
            )
        ),
        ":feature:settings" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.database", "dev.gyro.core.datastore", "dev.gyro.engine",
                "dev.gyro.player", "kotlinx.coroutines.Dispatchers",
            )
        ),
        // :feature:player owns the Compose surface host (dev.gyro.feature.player.surface) and consumes
        // only RenderController, immutable engine models, and switch tracing — never PlayerEngine.
        ":feature:player" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.database", "dev.gyro.core.datastore", "dev.gyro.player",
                "kotlinx.coroutines.Dispatchers",
            ),
            engineAllowlist = listOf(
                "dev.gyro.engine.api.controller.RenderController",
                "dev.gyro.engine.model.",
                "dev.gyro.engine.trace.",
            ),
        ),
        ":app" to Boundary(
            forbidden = listOf(
                "dev.gyro.core.database", "dev.gyro.core.datastore",
                "kotlinx.coroutines.Dispatchers",
            ),
            engineAllowlist = listOf("dev.gyro.engine.model."),
            diEngineAllowlist = listOf(
                "dev.gyro.engine.GyroPlayerEngine",
                "dev.gyro.engine.api.PlayerEngine",
            ),
        ),
    )
}
