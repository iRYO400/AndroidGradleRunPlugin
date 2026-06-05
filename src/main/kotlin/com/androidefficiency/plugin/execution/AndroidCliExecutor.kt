package com.androidefficiency.plugin.execution

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler

/**
 * Probes for the Android CLI (`android` binary).
 *
 * Android CLI provides a unified `android run` command that builds, installs and
 * launches an app. The plugin runs it as a terminal command (composed in
 * [BuildCommandComposer]); here we only need to know whether the binary is on PATH
 * so the tool window can enable/disable the "Android CLI" run mode.
 *
 * Installation: curl -fsSL https://dl.google.com/android/cli/latest/darwin_arm64/install.sh | bash
 */
object AndroidCliExecutor {

    private const val CLI_BINARY = "android"
    private const val CLI_TIMEOUT_MS = 10_000

    /**
     * Returns true if the `android` CLI binary is available in PATH.
     */
    fun isCliAvailable(): Boolean {
        return try {
            val cmd = GeneralCommandLine(CLI_BINARY, "--version")
                .withCharset(Charsets.UTF_8)
            val handler = CapturingProcessHandler(cmd)
            handler.runProcess(CLI_TIMEOUT_MS).exitCode == 0
        } catch (e: Exception) {
            false
        }
    }
}
