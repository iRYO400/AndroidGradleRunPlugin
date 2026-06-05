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
        if (useCli()) return buildCliCommand()
        val gradleParts = listOf(buildTaskName()) + buildFlags()
        val gradle = serialPrefix() + "./gradlew " + gradleParts.joinToString(" \\\n    ")
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
        val base = if (useCli()) {
            buildCliCommand()
        } else {
            val gradle = serialPrefix() + (listOf("./gradlew", buildTaskName()) + buildFlags()).joinToString(" ")
            appendLaunchActivity(gradle, joiner = " ")
        }
        return if (exitMarker != null) {
            "$base ; printf %s \"\$?\" > '${exitMarker.absolutePath}'"
        } else {
            base
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun useCli(): Boolean = settings.state.useAndroidCli

    /** Selected device serial, or empty for "default / all connected". */
    private fun device(): String = (settings.state.targetDevice ?: "").trim()

    /**
     * Builds the Android CLI deploy command: `android run [--device=<serial>]`.
     * `android run` builds, installs and launches the app on its own, so the
     * Gradle flags / flavor / launch-activity options do not apply here.
     */
    private fun buildCliCommand(): String {
        val dev = device()
        return if (dev.isNotEmpty()) "android run --device='$dev'" else "android run"
    }

    /**
     * `ANDROID_SERIAL='<serial>' ` prefix so a Gradle install targets the chosen
     * device instead of every connected one. Empty when no device is selected.
     */
    private fun serialPrefix(): String {
        val dev = device()
        return if (dev.isNotEmpty()) "ANDROID_SERIAL='$dev' " else ""
    }

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
            val dev = device()
            val adb = if (dev.isNotEmpty()) "adb -s '$dev'" else "adb"
            return "$gradle${joiner}&& $adb shell am start -n \"$intent\""
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

        // Custom flags: split on whitespace but keep quoted values together,
        // so e.g. -Pkey="a b" stays a single token instead of being torn apart.
        val custom = (state.customFlags ?: "").trim()
        if (custom.isNotEmpty()) {
            addAll(splitCustomFlags(custom))
        }
    }

    companion object {
        /**
         * Splits a custom-flags string into tokens, treating single- and double-quoted
         * spans as part of the current token (quotes are preserved so the shell still
         * sees them). Examples:
         *   `--info --stacktrace`        → ["--info", "--stacktrace"]
         *   `-Pkey="a b" --foo`          → ["-Pkey=\"a b\"", "--foo"]
         */
        internal fun splitCustomFlags(input: String): List<String> {
            val tokens = mutableListOf<String>()
            val sb = StringBuilder()
            var quote: Char? = null
            for (c in input) {
                when {
                    quote != null -> {
                        sb.append(c)
                        if (c == quote) quote = null
                    }
                    c == '"' || c == '\'' -> {
                        sb.append(c)
                        quote = c
                    }
                    c.isWhitespace() -> {
                        if (sb.isNotEmpty()) {
                            tokens.add(sb.toString())
                            sb.clear()
                        }
                    }
                    else -> sb.append(c)
                }
            }
            if (sb.isNotEmpty()) tokens.add(sb.toString())
            return tokens
        }
    }
}
