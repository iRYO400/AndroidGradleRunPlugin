package com.androidefficiency.plugin.module

import com.intellij.openapi.project.Project
import java.io.File

/**
 * Detects Gradle module paths from the opened project's settings file.
 *
 * Parses `settings.gradle.kts` / `settings.gradle` for `include(...)` statements
 * (both Kotlin and Groovy DSL) and returns module paths without the leading colon,
 * e.g. ":feature:login" → "feature:login". The [BuildCommandComposer] re-adds the
 * leading colon when assembling the task name (`:$module:$task...`).
 *
 * Mirrors the approach of [com.androidefficiency.plugin.flavor.FlavorDetector]:
 * a pure, testable parsing function plus a project-aware entry point.
 */
object ModuleDetector {

    /**
     * Returns the list of module paths declared in the project's settings file,
     * or an empty list if none are found / the file is absent.
     */
    fun detectModules(project: Project): List<String> {
        val basePath = project.basePath ?: return emptyList()

        val settingsFiles = listOf(
            "$basePath/settings.gradle.kts",
            "$basePath/settings.gradle"
        )

        for (path in settingsFiles) {
            val file = File(path)
            if (!file.exists()) continue
            val modules = extractModulesFromSettings(file.readText())
            if (modules.isNotEmpty()) return modules
        }
        return emptyList()
    }

    /**
     * Extracts module paths from settings file content.
     *
     * Handles:
     *   Kotlin:  include(":app", ":feature:login")
     *            include(
     *                ":app",
     *                ":feature:login",
     *            )
     *   Groovy:  include ':app', ':feature:login'
     *            include ":app"
     *
     * Returns paths without the leading colon, distinct and sorted.
     */
    internal fun extractModulesFromSettings(content: String): List<String> {
        val modules = mutableSetOf<String>()
        val pathRegex = Regex("""["'](:[A-Za-z0-9_.:-]+)["']""")

        // Locate each `include` statement and capture its argument span, which may
        // run across several lines when wrapped in parentheses (Kotlin DSL).
        val includeRegex = Regex("""\binclude\b""")
        includeRegex.findAll(content).forEach { include ->
            val args = captureIncludeArgs(content, include.range.last + 1)
            pathRegex.findAll(args).forEach { match ->
                val path = match.groupValues[1].removePrefix(":")
                if (path.isNotEmpty()) modules.add(path)
            }
        }

        return modules.toList().sorted()
    }

    /**
     * Returns the argument text of an `include` statement starting at [from].
     * If the arguments are parenthesized the span runs to the matching `)`
     * (supporting multi-line `include(...)`); otherwise it runs to end of line.
     */
    private fun captureIncludeArgs(content: String, from: Int): String {
        var i = from
        while (i < content.length && content[i].isWhitespace() && content[i] != '\n') i++

        if (i < content.length && content[i] == '(') {
            var depth = 0
            val start = i
            while (i < content.length) {
                when (content[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return content.substring(start, i + 1)
                    }
                }
                i++
            }
            return content.substring(start)
        }

        val lineEnd = content.indexOf('\n', from).let { if (it == -1) content.length else it }
        return content.substring(from, lineEnd)
    }
}
