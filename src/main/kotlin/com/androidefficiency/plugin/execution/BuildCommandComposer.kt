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

    /**
     * Result of [parseCommand] (the inverse of [getPreviewText]); defaults are safe to
     * apply even when only some fields were parsed. Declared on the class (not the
     * companion) so it's referenced as `BuildCommandComposer.ParsedCommand`.
     */
    data class ParsedCommand(
        val useAndroidCli: Boolean,
        val targetDevice: String = "",
        val module: String = "app",
        val gradleTask: String = "install",   // install | assemble | bundle
        val buildType: String = "Debug",       // Debug | Release
        val flavor: String = "",                // first char lower-cased; "" = none
        val recognizedFlags: Set<String> = emptySet(),
        val customFlags: String = "",
        val launchActivity: Boolean = false,
        val launchIntent: String = "",
    )

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

        // ── Inverse parsing (clipboard → settings) ──────────────────────────────
        // Mirrors getPreviewText(): the "Paste" button reverses a Fast-Deploy command
        // string back into UI state. Only the format this composer produces is parsed;
        // anything else returns null so the caller leaves the UI untouched.

        /** The Gradle flag tokens this composer knows how to emit (see [buildFlags]). */
        internal val KNOWN_GRADLE_FLAGS = listOf(
            "--offline", "--parallel", "--configuration-cache", "--build-cache", "--daemon",
            "--configure-on-demand", "--dry-run", "--stacktrace", "--info", "--debug"
        )

        private val MARKER_TAIL = Regex(""" ; printf %s "\$\?" > '[^']*'\s*$""")
        private val CONTINUATION = Regex("""\\\n\s*""")
        private val CLI_RE = Regex("""^android run(?: --device='([^']*)')?$""")
        private val SERIAL_PREFIX_RE = Regex("""^ANDROID_SERIAL='([^']*)' """)
        private val LAUNCH_TAIL_RE =
            Regex("""\s*&&\s*adb(?: -s '([^']*)')? shell am start -n "([^"]*)"\s*$""")
        private val TASK_PREFIXES = listOf("install", "assemble", "bundle")

        /**
         * Reverses a Fast-Deploy command string (as produced by [getPreviewText]) into a
         * [ParsedCommand]. Returns null when the input is not a recognizable Fast-Deploy
         * command, so the caller can no-op silently.
         */
        internal fun parseCommand(raw: String): ParsedCommand? {
            var text = raw.trim()
            if (text.isEmpty()) return null

            // Defensive: drop a trailing completion-marker redirect (getTerminalCommand adds it).
            text = MARKER_TAIL.replace(text, "")
            // Collapse multi-line "\<newline><indent>" continuations into single spaces.
            // Only the literal continuation is touched, so quoted values keep their spacing.
            text = CONTINUATION.replace(text, " ").trim()

            // ── Android CLI form ────────────────────────────────────────────────
            CLI_RE.matchEntire(text)?.let { m ->
                return ParsedCommand(useAndroidCli = true, targetDevice = m.groupValues[1])
            }
            if (text.startsWith("android run")) return null  // our CLI form but with junk

            // ── Gradle form ─────────────────────────────────────────────────────
            var device = ""
            SERIAL_PREFIX_RE.find(text)?.let { m ->
                device = m.groupValues[1]
                text = text.removeRange(m.range)
            }
            if (!text.startsWith("./gradlew ")) return null
            var body = text.removePrefix("./gradlew ").trim()

            // Split off the launch-activity tail before tokenizing flags.
            var launchActivity = false
            var launchIntent = ""
            LAUNCH_TAIL_RE.find(body)?.let { m ->
                launchActivity = true
                launchIntent = m.groupValues[2]
                val adbSerial = m.groupValues[1]
                if (device.isEmpty()) device = adbSerial  // ANDROID_SERIAL is canonical
                body = body.removeRange(m.range).trim()
            }
            // A launch step that's present but malformed means this isn't our output.
            if (!launchActivity && body.contains("am start")) return null

            val tokens = splitCustomFlags(body)
            val spec = tokens.firstOrNull() ?: return null
            if (!spec.startsWith(":")) return null

            // Reverse ":module:task<Flavor><Type>" — module may contain ':' (e.g. feature:login).
            val afterColon = spec.removePrefix(":")
            if (!afterColon.contains(":")) return null
            val module = afterColon.substringBeforeLast(":")
            val taskCamel = afterColon.substringAfterLast(":")
            if (module.isEmpty() || taskCamel.isEmpty()) return null

            val buildType = when {
                taskCamel.endsWith("Debug") -> "Debug"
                taskCamel.endsWith("Release") -> "Release"
                else -> return null
            }
            val taskPrefix = TASK_PREFIXES.firstOrNull { taskCamel.startsWith(it) } ?: return null
            val middle = taskCamel.substring(taskPrefix.length, taskCamel.length - buildType.length)
            val flavor = middle.replaceFirstChar { it.lowercaseChar() }

            val recognized = linkedSetOf<String>()
            val unknown = mutableListOf<String>()
            tokens.drop(1).forEach { tok ->
                if (tok in KNOWN_GRADLE_FLAGS) recognized.add(tok) else unknown.add(tok)
            }

            return ParsedCommand(
                useAndroidCli = false,
                targetDevice = device,
                module = module,
                gradleTask = taskPrefix,
                buildType = buildType,
                flavor = flavor,
                recognizedFlags = recognized,
                customFlags = unknown.joinToString(" "),
                launchActivity = launchActivity,
                launchIntent = launchIntent,
            )
        }
    }
}
