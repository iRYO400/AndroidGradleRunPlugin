package com.androidefficiency.plugin.execution

import com.androidefficiency.plugin.settings.PluginSettings
import java.io.File

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
    private val settings: PluginSettings
) {

    /**
     * Returns a human-readable preview string of the command that would be executed.
     * Kept free of execution plumbing (e.g. the completion marker) so it's also
     * what "Copy" puts on the clipboard.
     */
    fun getPreviewText(): String {
        val gradleParts = listOf(buildTaskName()) + buildFlags()
        val gradle = "./gradlew " + gradleParts.joinToString(" \\\n    ")
        return appendLaunchActivity(gradle, joiner = " \\\n  ")
    }

    /**
     * Returns a single-line command string for execution in a terminal shell.
     * Uses `./gradlew` (relative) since the terminal starts in the project directory.
     *
     * @param exitMarker if non-null, a `; printf %s "$?" > <marker>` redirect is appended
     *        so [BuildCompletionWatcher] can pick up the exit code and fire an IDE
     *        notification. POSIX shells only (zsh/bash).
     */
    fun getTerminalCommand(exitMarker: File? = null): String {
        val gradle = (listOf("./gradlew", buildTaskName()) + buildFlags()).joinToString(" ")
        val withLaunch = appendLaunchActivity(gradle, joiner = " ")
        return if (exitMarker != null) {
            "$withLaunch ; printf %s \"\$?\" > '${exitMarker.absolutePath}'"
        } else {
            withLaunch
        }
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

    /**
     * Appends the optional `&& adb shell am start` launch step for install builds.
     *
     * Completion notification is no longer chained in the shell — it is handled
     * natively by [BuildCompletionWatcher] via the exit marker.
     */
    private fun appendLaunchActivity(gradle: String, joiner: String): String {
        val state = settings.state
        val isInstall = (state.gradleTask ?: "install") == "install"
        val intent = (state.launchActivityIntent ?: "").trim()
        if (state.launchActivityAfterInstall && isInstall && intent.isNotEmpty()) {
            return "$gradle${joiner}&& adb shell am start -n \"$intent\""
        }
        return gradle
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
