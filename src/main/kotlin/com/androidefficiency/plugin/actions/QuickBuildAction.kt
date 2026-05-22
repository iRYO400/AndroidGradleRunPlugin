package com.androidefficiency.plugin.actions

import com.androidefficiency.plugin.execution.BuildCommandComposer
import com.androidefficiency.plugin.execution.GradleCommandExecutor
import com.androidefficiency.plugin.settings.PluginSettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Action that triggers a build using the currently saved plugin settings.
 * Accessible via keyboard shortcut (Ctrl+Shift+F10 / Cmd+Shift+F10) and toolbar.
 *
 * Activates the Fast Deploy tool window so the console output is visible,
 * then runs the build with the current saved configuration.
 */
class QuickBuildAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = PluginSettings.getInstance(project)

        // Activate the tool window so the user can see console output
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Fast Deploy")
        toolWindow?.activate(null)

        try {
            val composer = BuildCommandComposer(project, settings)
            val commandLine = composer.compose()

            val executor = GradleCommandExecutor(project)
            if (executor.isRunning()) {
                Messages.showInfoMessage(project, "A build is already running. Check the Fast Deploy tool window.", "Fast Deploy")
                return
            }

            val consoleView = executor.createConsoleView()

            // If the tool window has content, replace its console; otherwise just run
            toolWindow?.contentManager?.let { cm ->
                val content = com.intellij.ui.content.ContentFactory.getInstance()
                    .createContent(consoleView.component, "Output", false)
                cm.removeAllContents(true)
                cm.addContent(content)
            }

            executor.execute(commandLine = commandLine, consoleView = consoleView)

        } catch (ex: IllegalStateException) {
            Messages.showErrorDialog(project, ex.message, "Fast Deploy Error")
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}

