package com.androidefficiency.plugin.actions

import com.androidefficiency.plugin.execution.BuildLauncher
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Action that triggers a build using the currently saved plugin settings.
 * Accessible via keyboard shortcut (Ctrl+Shift+F10 / Cmd+Shift+F10) and the Run menu.
 *
 * Delegates to [BuildLauncher] — the same path the tool-window "Run" button uses —
 * so the hotkey and the button always behave identically (terminal execution,
 * launch-activity post-action, completion notification).
 */
class QuickBuildAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Bring the Fast Deploy tool window forward so the user sees the run controls.
        ToolWindowManager.getInstance(project).getToolWindow("Fast Deploy")?.activate(null)

        BuildLauncher.launch(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }
}
