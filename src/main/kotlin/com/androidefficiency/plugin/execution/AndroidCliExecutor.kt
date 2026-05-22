package com.androidefficiency.plugin.execution

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.project.Project
import java.nio.charset.Charset

/**
 * Executor for Android CLI commands (`android` binary).
 *
 * Android CLI was released in May 2026 and provides a unified interface for:
 * - Building and deploying apps: `android run`
 * - Project description: `android describe`
 * - Emulator management: `android emulator list/start/stop`
 *
 * This is Phase 2 functionality — requires the `android` CLI to be installed.
 * Installation: curl -fsSL https://dl.google.com/android/cli/latest/darwin_arm64/install.sh | bash
 */
class AndroidCliExecutor(private val project: Project) {

    companion object {
        private const val CLI_BINARY = "android"
        private const val CLI_TIMEOUT_MS = 10_000
    }

    /**
     * Returns true if the `android` CLI binary is available in PATH.
     */
    fun isCliAvailable(): Boolean {
        return try {
            val cmd = GeneralCommandLine(CLI_BINARY, "--version")
                .withCharset(Charset.forName("UTF-8"))
            val handler = CapturingProcessHandler(cmd)
            val result = handler.runProcess(CLI_TIMEOUT_MS)
            result.exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the Android CLI version string, or null if unavailable.
     */
    fun getCliVersion(): String? {
        return try {
            val cmd = GeneralCommandLine(CLI_BINARY, "--version")
                .withCharset(Charset.forName("UTF-8"))
            val handler = CapturingProcessHandler(cmd)
            val result = handler.runProcess(CLI_TIMEOUT_MS)
            if (result.exitCode == 0) result.stdout.trim() else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Creates a [GeneralCommandLine] for `android run`.
     *
     * @param device Serial number of the target device/emulator (optional)
     * @param apkPaths Paths to APK files to install. If empty, CLI auto-discovers them.
     * @param debugMode If true, passes --debug flag
     */
    fun buildRunCommand(
        device: String? = null,
        apkPaths: List<String> = emptyList(),
        debugMode: Boolean = false
    ): GeneralCommandLine {
        return GeneralCommandLine(CLI_BINARY, "run").apply {
            device?.takeIf { it.isNotBlank() }?.let { addParameter("--device=$it") }
            if (apkPaths.isNotEmpty()) {
                addParameter("--apks=${apkPaths.joinToString(",")}")
            }
            if (debugMode) addParameter("--debug")
            withWorkDirectory(project.basePath)
            withCharset(Charset.forName("UTF-8"))
            withEnvironment("TERM", "xterm-256color")
        }
    }

    /**
     * Runs `android describe` to analyze the project structure.
     * Returns a [ProjectDescription] with build targets and artifact paths,
     * or null if the command fails.
     */
    fun describeProject(): ProjectDescription? {
        return try {
            val cmd = GeneralCommandLine(
                CLI_BINARY, "describe",
                "--project_dir=${project.basePath}"
            ).withCharset(Charset.forName("UTF-8"))

            val handler = CapturingProcessHandler(cmd)
            val result = handler.runProcess(CLI_TIMEOUT_MS)

            if (result.exitCode == 0) {
                parseDescribeOutput(result.stdout)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Returns a [GeneralCommandLine] for `android emulator list`.
     */
    fun buildEmulatorListCommand(): GeneralCommandLine {
        return GeneralCommandLine(CLI_BINARY, "emulator", "list")
            .withCharset(Charset.forName("UTF-8"))
    }

    /**
     * Returns a [GeneralCommandLine] for `android emulator start <name>`.
     */
    fun buildEmulatorStartCommand(avdName: String): GeneralCommandLine {
        return GeneralCommandLine(CLI_BINARY, "emulator", "start", avdName)
            .withCharset(Charset.forName("UTF-8"))
    }

    /**
     * Returns a [GeneralCommandLine] for `android emulator stop <name>`.
     */
    fun buildEmulatorStopCommand(avdName: String): GeneralCommandLine {
        return GeneralCommandLine(CLI_BINARY, "emulator", "stop", avdName)
            .withCharset(Charset.forName("UTF-8"))
    }

    // ── Parsing ────────────────────────────────────────────────────────────────

    private fun parseDescribeOutput(output: String): ProjectDescription {
        // The describe command outputs paths to JSON files describing build targets.
        // We extract the paths for further processing.
        val jsonPaths = output.lines()
            .map { it.trim() }
            .filter { it.endsWith(".json") }

        return ProjectDescription(jsonDescriptorPaths = jsonPaths)
    }

    // ── Data classes ───────────────────────────────────────────────────────────

    data class ProjectDescription(
        /** Paths to JSON descriptor files output by `android describe` */
        val jsonDescriptorPaths: List<String> = emptyList()
    )
}
