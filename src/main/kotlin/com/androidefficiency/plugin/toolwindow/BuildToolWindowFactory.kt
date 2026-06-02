package com.androidefficiency.plugin.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Factory that creates the "Fast Deploy" tool window content.
 * Registered in plugin.xml under the com.intellij.toolWindow extension point.
 *
 * Implements [DumbAware] so the platform builds our content during indexing instead
 * of showing the generic "not available during indexing" placeholder — that's what
 * lets the waiting splash actually appear while the project is still indexing.
 */
class BuildToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = BuildToolWindowPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance()
            .createContent(panel.getComponent(), "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
