package com.androidefficiency.plugin.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/**
 * Factory that creates the "Fast Deploy" tool window content.
 * Registered in plugin.xml under the com.intellij.toolWindow extension point.
 */
class BuildToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = BuildToolWindowPanel(project)
        val content = ContentFactory.getInstance()
            .createContent(panel.getComponent(), "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
