package com.androidefficiency.plugin.toolwindow

import com.androidefficiency.plugin.execution.BuildCommandComposer
import com.androidefficiency.plugin.execution.TerminalRunner
import com.androidefficiency.plugin.flavor.FlavorCache
import com.androidefficiency.plugin.flavor.FlavorDetector
import com.androidefficiency.plugin.settings.PluginSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListenerAdapter
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.border.TitledBorder

class BuildToolWindowPanel(private val project: Project, parentDisposable: Disposable) {

    private val settings = PluginSettings.getInstance(project)

    init {
        // Re-detect flavors after every Gradle Sync.
        // ExternalSystemProgressNotificationManager is app-level, so filter by project.
        val disposable = Disposer.newDisposable("FastDeploy:FlavorSync")
        Disposer.register(parentDisposable, disposable)
        ExternalSystemProgressNotificationManager.getInstance()
            .addNotificationListener(object : ExternalSystemTaskNotificationListenerAdapter(null) {
                override fun onSuccess(id: ExternalSystemTaskId) {
                    if (id.type == ExternalSystemTaskType.RESOLVE_PROJECT &&
                        id.findProject() == project
                    ) {
                        refreshFlavorsAsync()
                    }
                }
            }, disposable)
    }

    // ── UI Components ─────────────────────────────────────────────────────────
    private val moduleField = JBTextField(settings.state.selectedModule ?: "app")
    private val taskGroup = ButtonGroup()
    private val installRadio = JRadioButton("install", (settings.state.gradleTask ?: "install") == "install")
    private val assembleRadio = JRadioButton("assemble", (settings.state.gradleTask ?: "install") == "assemble")
    private val bundleRadio = JRadioButton("bundle", (settings.state.gradleTask ?: "install") == "bundle")

    private val typeGroup = ButtonGroup()
    private val debugRadio = JRadioButton("Debug", (settings.state.buildType ?: "Debug") == "Debug")
    private val releaseRadio = JRadioButton("Release", (settings.state.buildType ?: "Debug") == "Release")

    private val flavorCombo = ComboBox<String>()
    private val manualFlavorCheck = JCheckBox("Manual input:", settings.state.useManualFlavor)
    private val manualFlavorField = JBTextField(settings.state.manualFlavorInput ?: "", 12)

    // Gradle flag checkboxes
    private val offlineCheck = JCheckBox("--offline", settings.state.offlineMode)
    private val parallelCheck = JCheckBox("--parallel", settings.state.parallelBuild)
    private val configCacheCheck = JCheckBox("--configuration-cache", settings.state.configurationCache)
    private val buildCacheCheck = JCheckBox("--build-cache", settings.state.buildCache)
    private val daemonCheck = JCheckBox("--daemon", settings.state.daemon)
    private val configOnDemandCheck = JCheckBox("--configure-on-demand", settings.state.configureOnDemand)
    private val stacktraceCheck = JCheckBox("--stacktrace", settings.state.stacktrace)
    private val infoCheck = JCheckBox("--info", settings.state.info)
    private val debugCheck = JCheckBox("--debug", settings.state.debug)
    private val dryRunCheck = JCheckBox("--dry-run", settings.state.dryRun)

    private val customFlagsField = JBTextField(settings.state.customFlags ?: "")

    private val launchActivityCheck = JCheckBox("Launch activity:", settings.state.launchActivityAfterInstall)
    private val launchIntentField = JBTextField(settings.state.launchActivityIntent ?: "", 24)
    private val notifyCheck = JCheckBox("Notify on completion (macOS)", settings.state.notifyOnCompletion)

    private val previewLabel = JLabel().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        foreground = JBColor.GRAY
    }

    private val useTerminalRadio = JRadioButton("IDE Terminal", true)
    private val useConsoleRadio = JRadioButton("Plugin Console", false).apply { isEnabled = false }
    private val reuseTerminalCheck = JCheckBox("Use active tab", settings.state.reuseActiveTerminal)

    private val runButton = JButton("Run in Terminal", AllIcons.Actions.Execute)
    private val copyButton = JButton("Copy", AllIcons.Actions.Copy)

    // ── Build ─────────────────────────────────────────────────────────────────

    fun getComponent(): JComponent {
        val topPanel = buildConfigPanel()

        // NORTH anchor: topPanel gets full width but only its preferred height,
        // avoiding the BoxLayout Y_AXIS viewport-width propagation issue.
        val topWrapper = JPanel(BorderLayout()).apply {
            add(topPanel, BorderLayout.NORTH)
        }

        return JBScrollPane(topWrapper).apply {
            border = null
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
    }

    // ── Configuration panel ───────────────────────────────────────────────────

    private fun buildConfigPanel(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }

        panel.add(buildModuleTaskSection())
        panel.add(Box.createVerticalStrut(6))
        panel.add(buildFlavorSection())
        panel.add(Box.createVerticalStrut(6))
        panel.add(buildFlagsSection())
        panel.add(Box.createVerticalStrut(6))
        panel.add(buildCustomFlagsSection())
        panel.add(Box.createVerticalStrut(6))
        panel.add(buildPostActionsSection())
        panel.add(Box.createVerticalStrut(6))
        panel.add(buildPreviewSection())
        panel.add(Box.createVerticalStrut(6))
        panel.add(buildRunModeSection())
        panel.add(Box.createVerticalStrut(8))
        panel.add(buildButtonsSection())
        panel.add(Box.createVerticalGlue())

        // Initial preview update
        updatePreview()

        // Wire up all change listeners
        attachChangeListeners()

        return panel
    }

    private fun buildModuleTaskSection(): JPanel {
        val panel = titledPanel("Build Target")

        // Module row — BorderLayout so the text field fills remaining width
        val moduleRow = JPanel(BorderLayout(4, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyLeft(4)
        }
        moduleRow.add(JBLabel("Module:"), BorderLayout.WEST)
        moduleRow.add(moduleField, BorderLayout.CENTER)
        panel.add(moduleRow)

        // Task row
        taskGroup.add(installRadio)
        taskGroup.add(assembleRadio)
        taskGroup.add(bundleRadio)

        val taskRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        taskRow.add(JBLabel("Task:"))
        taskRow.add(installRadio)
        taskRow.add(assembleRadio)
        taskRow.add(bundleRadio)
        panel.add(taskRow)

        // Build type row
        typeGroup.add(debugRadio)
        typeGroup.add(releaseRadio)

        val typeRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        typeRow.add(JBLabel("Type:"))
        typeRow.add(debugRadio)
        typeRow.add(releaseRadio)
        panel.add(typeRow)

        return panel
    }

    private fun buildFlavorSection(): JPanel {
        val panel = titledPanel("Flavor")

        // Show "none" for the empty flavor option instead of a blank entry
        flavorCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val display = if (value is String && value.isEmpty()) "none" else value
                return super.getListCellRendererComponent(list, display, index, isSelected, cellHasFocus)
            }
        }

        flavorCombo.addItem("")  // empty = no flavor ("none")
        refreshFlavorsAsync()

        val flavorRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        flavorRow.add(JBLabel("Flavor:"))
        flavorRow.add(flavorCombo)
        panel.add(flavorRow)

        // Manual flavor row
        manualFlavorField.isEnabled = manualFlavorCheck.isSelected

        val manualRow = JPanel(BorderLayout(4, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        manualRow.add(manualFlavorCheck, BorderLayout.WEST)
        manualRow.add(manualFlavorField, BorderLayout.CENTER)
        panel.add(manualRow)

        manualFlavorCheck.addActionListener {
            manualFlavorField.isEnabled = manualFlavorCheck.isSelected
            flavorCombo.isEnabled = !manualFlavorCheck.isSelected
            saveAndRefresh()
        }

        return panel
    }

    private fun buildFlagsSection(): JPanel {
        val panel = titledPanel("Gradle Flags")

        val grid = JPanel(GridLayout(0, 2, 4, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        listOf(
            offlineCheck, parallelCheck,
            configCacheCheck, buildCacheCheck,
            daemonCheck, configOnDemandCheck,
            stacktraceCheck, infoCheck,
            dryRunCheck, debugCheck
        ).forEach { grid.add(it) }

        panel.add(grid)
        return panel
    }

    private fun buildCustomFlagsSection(): JPanel {
        val panel = titledPanel("Custom Flags")
        val row = JPanel(BorderLayout(4, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyLeft(4)
        }
        row.add(JBLabel("Extra:"), BorderLayout.WEST)
        row.add(customFlagsField, BorderLayout.CENTER)
        panel.add(row)
        return panel
    }

    private fun buildPostActionsSection(): JPanel {
        val panel = titledPanel("Post-Build Actions")

        launchIntentField.isEnabled = launchActivityCheck.isSelected
        launchIntentField.toolTipText = "Example format: com.app.id/com.package.SplashActivity"

        val launchRow = JPanel(BorderLayout(4, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        launchRow.add(launchActivityCheck, BorderLayout.WEST)
        launchRow.add(launchIntentField, BorderLayout.CENTER)
        panel.add(launchRow)
        panel.add(notifyCheck)

        launchActivityCheck.addActionListener {
            launchIntentField.isEnabled = launchActivityCheck.isSelected
            saveAndRefresh()
        }
        return panel
    }

    private fun buildPreviewSection(): JPanel {
        val panel = titledPanel("Command Preview")
        previewLabel.text = "..."
        panel.add(previewLabel)
        return panel
    }

    private fun buildRunModeSection(): JPanel {
        val panel = titledPanel("Run Mode")

        reuseTerminalCheck.addActionListener { saveAndRefresh() }

        val terminalRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        terminalRow.add(useTerminalRadio)
        terminalRow.add(reuseTerminalCheck)

        val consoleRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        consoleRow.add(useConsoleRadio)

        panel.add(terminalRow)
        panel.add(consoleRow)
        return panel
    }

    private fun buildButtonsSection(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        runButton.addActionListener { runBuild() }
        copyButton.addActionListener { copyCommandToClipboard() }
        panel.add(runButton)
        panel.add(copyButton)
        return panel
    }

    // ── Change listeners ──────────────────────────────────────────────────────

    private fun attachChangeListeners() {
        val onChange = { saveAndRefresh() }

        moduleField.document.addDocumentListener(simpleDocumentListener(onChange))
        customFlagsField.document.addDocumentListener(simpleDocumentListener(onChange))
        manualFlavorField.document.addDocumentListener(simpleDocumentListener(onChange))
        launchIntentField.document.addDocumentListener(simpleDocumentListener(onChange))
        notifyCheck.addActionListener { saveAndRefresh() }

        listOf(
            installRadio, assembleRadio, bundleRadio,
            debugRadio, releaseRadio
        ).forEach { it.addActionListener { saveAndRefresh() } }

        listOf(
            offlineCheck, parallelCheck, configCacheCheck, buildCacheCheck, daemonCheck,
            configOnDemandCheck, stacktraceCheck, infoCheck, debugCheck, dryRunCheck
        ).forEach { it.addActionListener { saveAndRefresh() } }

        flavorCombo.addActionListener { saveAndRefresh() }
    }

    private fun saveAndRefresh() {
        persistSettings()
        updatePreview()
    }

    // ── Settings persistence ──────────────────────────────────────────────────

    private fun persistSettings() {
        val s = settings.state
        s.selectedModule = moduleField.text.trim()
        s.gradleTask = when {
            installRadio.isSelected -> "install"
            assembleRadio.isSelected -> "assemble"
            bundleRadio.isSelected -> "bundle"
            else -> "install"
        }
        s.buildType = if (debugRadio.isSelected) "Debug" else "Release"
        s.selectedFlavor = (flavorCombo.selectedItem as? String) ?: ""
        s.useManualFlavor = manualFlavorCheck.isSelected
        s.manualFlavorInput = manualFlavorField.text.trim()
        s.offlineMode = offlineCheck.isSelected
        s.parallelBuild = parallelCheck.isSelected
        s.configurationCache = configCacheCheck.isSelected
        s.buildCache = buildCacheCheck.isSelected
        s.daemon = daemonCheck.isSelected
        s.configureOnDemand = configOnDemandCheck.isSelected
        s.stacktrace = stacktraceCheck.isSelected
        s.info = infoCheck.isSelected
        s.debug = debugCheck.isSelected
        s.dryRun = dryRunCheck.isSelected
        s.customFlags = customFlagsField.text.trim()
        s.reuseActiveTerminal = reuseTerminalCheck.isSelected
        s.launchActivityAfterInstall = launchActivityCheck.isSelected
        s.launchActivityIntent = launchIntentField.text.trim()
        s.notifyOnCompletion = notifyCheck.isSelected
    }

    // ── Preview update ────────────────────────────────────────────────────────

    private fun updatePreview() {
        try {
            val composer = BuildCommandComposer(project, settings)
            val preview = composer.getPreviewText()
            previewLabel.text = "<html><pre style='margin:0'>${preview.replace("\n", "<br/>")}</pre></html>"
        } catch (e: Exception) {
            previewLabel.text = "<html><i>Error: ${e.message}</i></html>"
        }
    }

    // ── Build execution ───────────────────────────────────────────────────────

    private fun runBuild() {
        persistSettings()
        val composer = BuildCommandComposer(project, settings)
        TerminalRunner.run(project, composer.getTerminalCommand(), settings.state.reuseActiveTerminal)
    }

    private fun copyCommandToClipboard() {
        try {
            persistSettings()
            val text = BuildCommandComposer(project, settings).getPreviewText()
            CopyPasteManager.getInstance().setContents(StringSelection(text))
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message, "Fast Deploy")
        }
    }

    // ── Flavor detection ──────────────────────────────────────────────────────

    private fun refreshFlavorsAsync() {
        project.basePath?.let { FlavorCache.invalidate(it) }  // always fresh — panel open or post-sync
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Fast Deploy: Detecting flavors…", false
        ) {
            override fun run(indicator: ProgressIndicator) {
                val flavors = FlavorDetector.detectFlavors(project)
                ApplicationManager.getApplication().invokeLater {
                    updateFlavorCombo(flavors)
                }
            }
        })
    }

    private fun updateFlavorCombo(flavors: List<String>) {
        val currentSelection = (settings.state.selectedFlavor ?: "")
        flavorCombo.removeAllItems()
        flavorCombo.addItem("")  // No flavor option
        flavors.forEach { flavorCombo.addItem(it) }

        // Restore saved selection
        if (currentSelection.isNotEmpty() && flavors.contains(currentSelection)) {
            flavorCombo.selectedItem = currentSelection
        }
        flavorCombo.isEnabled = !manualFlavorCheck.isSelected
        updatePreview()
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private fun titledPanel(title: String): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createTitledBorder(
                BorderFactory.createEtchedBorder(),
                title,
                TitledBorder.LEFT,
                TitledBorder.TOP
            )
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    private fun simpleDocumentListener(action: () -> Unit) =
        object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = action()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = action()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = action()
        }
}
