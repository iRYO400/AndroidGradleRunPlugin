@file:Suppress("DEPRECATION") // TerminalView/ShellTerminalWidget — migrate to TerminalToolWindowManager when sinceBuild >= 253

package com.androidefficiency.plugin.execution

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.util.ui.UIUtil
import org.jetbrains.plugins.terminal.ShellTerminalWidget
import org.jetbrains.plugins.terminal.TerminalView

// TerminalView/ShellTerminalWidget are deprecated in favour of the Reworked Terminal API
// (available from IntelliJ 2025.3, still experimental). We build against Ladybug 242 where
// the new API is absent. Migrate when sinceBuild >= 253 and the API is declared stable.
@Suppress("DEPRECATION")
object TerminalRunner {

    /**
     * Runs [command] in the IDE Terminal tool window.
     *
     * @param reuseActive if true — sends the command to the currently active terminal tab
     *                    (falls back to new tab if the active tab is busy or absent);
     *                    if false — always opens a new "Fast Deploy" tab.
     */
    fun run(project: Project, command: String, reuseActive: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val view = TerminalView.getInstance(project)
                val widget = if (reuseActive) {
                    findActiveWidget(project) ?: createNewTab(project, view)
                } else {
                    createNewTab(project, view)
                }
                widget.executeCommand(command)
            } catch (e: Exception) {
                Messages.showErrorDialog(
                    project,
                    "Cannot open terminal: ${e.message}",
                    "Fast Deploy"
                )
            }
        }
    }

    private fun createNewTab(project: Project, view: TerminalView): ShellTerminalWidget {
        return view.createLocalShellWidget(project.basePath ?: "", "Fast Deploy", true)
    }

    /**
     * Returns the [ShellTerminalWidget] of the currently selected Terminal tab,
     * or null if the Terminal tool window is not open / no tab is selected.
     */
    private fun findActiveWidget(project: Project): ShellTerminalWidget? {
        val toolWindow = ToolWindowManager.getInstance(project)
            .getToolWindow("Terminal") ?: return null
        val content = toolWindow.contentManager.selectedContent ?: return null
        return UIUtil.findComponentOfType(content.component, ShellTerminalWidget::class.java)
    }
}
