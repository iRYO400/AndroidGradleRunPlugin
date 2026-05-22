package com.androidefficiency.plugin.execution

import com.androidefficiency.plugin.settings.PluginSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.Project
import com.androidefficiency.plugin.util.GradlewResolver
import java.nio.charset.Charset

/**
 * Composes a Gradle command from the current plugin settings.
 *
 * Task naming convention:
 *   :{module}:{task}{Flavor}{BuildType}
 *
 * Examples:
 *   - No flavor:   :app:installDebug
 *   - With flavor: :app:installDevDebug
 *   - Assemble:    :app:assembleDevRelease
 *   - Bundle:      :app:bundleProdRelease
 */
class BuildCommandComposer(
    private val project: Project,
    private val settings: PluginSettings
) {

    /**
     * Builds a [GeneralCommandLine] ready to be executed.
     */
    fun compose(): GeneralCommandLine {
        val gradlewPath = GradlewResolver.resolve(project)
        val taskName = buildTaskName()
        val flags = buildFlags()

        return GeneralCommandLine().apply {
            exePath = gradlewPath
            addParameter(taskName)
            addParameters(flags)
            withWorkDirectory(project.basePath)
            withCharset(Charset.forName("UTF-8"))
            // Enable ANSI colors in Gradle output
            withEnvironment("TERM", "xterm-256color")
            withEnvironment("GRADLE_OPTS", "-Dorg.gradle.daemon=false") // handled via flag
            // Combine stdout and stderr so ConsoleView shows everything together
            isRedirectErrorStream = false
        }
    }

    /**
     * Returns a human-readable preview string of the command that would be executed.
     */
    fun getPreviewText(): String {
        val taskName = buildTaskName()
        val flags = buildFlags()
        val allArgs = (listOf(taskName) + flags).joinToString(" \\\n    ")
        return "./gradlew $allArgs"
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun buildTaskName(): String {
        val state = settings.state
        val module = (state.selectedModule ?: "").trim().ifEmpty { "app" }
        val task = state.gradleTask ?: "install"  // "install" | "assemble" | "bundle"
        val flavor = resolvedFlavor().replaceFirstChar { it.uppercaseChar() }
        val buildType = state.buildType ?: "Debug"  // "Debug" | "Release"
        return ":$module:$task$flavor$buildType"
    }

    private fun resolvedFlavor(): String {
        val state = settings.state
        return if (state.useManualFlavor) {
            (state.manualFlavorInput ?: "").trim()
        } else {
            (state.selectedFlavor ?: "").trim()
        }
    }

    private fun buildFlags(): List<String> = buildList {
        val state = settings.state
        if (state.offlineMode)        add("--offline")
        if (state.parallelBuild)      add("--parallel")
        if (state.configurationCache) add("--configuration-cache")
        if (state.buildCache)         add("--build-cache")
        if (state.daemon)             add("--daemon")
        if (state.configureOnDemand)  add("--configure-on-demand")
        if (state.dryRun)             add("--dry-run")
        if (state.stacktrace)         add("--stacktrace")
        if (state.info)               add("--info")
        if (state.debug)              add("--debug")

        // Custom flags (space-separated string → individual args)
        val custom = (state.customFlags ?: "").trim()
        if (custom.isNotEmpty()) {
            addAll(custom.split(Regex("\\s+")))
        }
    }
}
