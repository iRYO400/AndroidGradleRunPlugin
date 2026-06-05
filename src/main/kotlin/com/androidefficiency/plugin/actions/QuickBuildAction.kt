package com.androidefficiency.plugin.actions

import com.androidefficiency.plugin.execution.BuildLauncher
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Action that triggers a build using the currently saved plugin settings.
 * Accessible via keyboard shortcut (Ctrl+Shift+F10 / Cmd+Shift+F10) and the Run menu.
 *
 * Delegates to [BuildLauncher] — the same path the tool-window "Run" button uses —
 * so the hotkey and the button always behave identically (terminal execution,
 * launch-activity post-action, completion notification). [BuildLauncher] runs the
 * command in the IDE Terminal and focuses it, so both Run paths land in the terminal.
 */
class QuickBuildAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        BuildLauncher.launch(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    // update() only reads e.project, so it's safe to run off the EDT.
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
