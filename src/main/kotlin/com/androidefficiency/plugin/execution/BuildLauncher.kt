package com.androidefficiency.plugin.execution

import com.androidefficiency.plugin.settings.PluginSettings
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Single entry point for launching a Fast Deploy build.
 *
 * Both the tool-window "Run" button and the [com.androidefficiency.plugin.actions.QuickBuildAction]
 * hotkey go through here, so they always produce identical behaviour (command,
 * post-actions, terminal tab handling and completion notification).
 *
 * Execution always happens in the IDE Terminal via [TerminalRunner]. When the
 * user enabled "Notify on completion", a marker file is wired into the shell
 * command and [BuildCompletionWatcher] turns its exit code into a native IDE
 * notification — no macOS notification permission required.
 */
object BuildLauncher {

    private val isWindows: Boolean
        get() = System.getProperty("os.name").lowercase().contains("windows")

    /**
     * Builds the command from the project's saved settings and runs it.
     * The caller is responsible for persisting any pending UI edits first.
     */
    fun launch(project: Project) {
        val settings = PluginSettings.getInstance(project)
        val composer = BuildCommandComposer(settings)

        // Marker-based completion notification only works on POSIX shells (zsh/bash).
        // On Windows the `printf %s "$?"` redirect is invalid, so we skip it there.
        val marker: File? = if (settings.state.notifyOnCompletion && !isWindows) {
            createMarker()
        } else null

        val command = composer.getTerminalCommand(marker)

        marker?.let { BuildCompletionWatcher.watch(project, it) }

        TerminalRunner.run(project, command, settings.state.reuseActiveTerminal)
    }

    private fun createMarker(): File =
        File.createTempFile("fastdeploy-exit", ".tmp").apply { deleteOnExit() }
}
