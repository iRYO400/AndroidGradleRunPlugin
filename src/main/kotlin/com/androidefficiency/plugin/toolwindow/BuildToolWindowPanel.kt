package com.androidefficiency.plugin.toolwindow

import com.androidefficiency.plugin.execution.AndroidCliExecutor
import com.androidefficiency.plugin.execution.BuildCommandComposer
import com.androidefficiency.plugin.execution.GradleCommandExecutor
import com.androidefficiency.plugin.flavor.FlavorCache
import com.androidefficiency.plugin.flavor.FlavorDetector
import com.androidefficiency.plugin.settings.PluginSettings
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.border.TitledBorder

/**
 * The main Tool Window panel for the Fast Deploy plugin.
 *
 * Layout:
 * ┌─────────────────────────────────────┐
 * │  Top: Configuration panel           │
 * │  ├─ Module & Task                   │
 * │  ├─ Flavor                          │
 * │  ├─ Gradle flags checkboxes         │
 * │  ├─ Custom flags                    │
 * │  └─ Preview + Run/Stop/Copy buttons │
 * ├─────────────────────────────────────┤
 * │  Bottom: ConsoleView                │
 * └─────────────────────────────────────┘
 */
class BuildToolWindowPanel(private val project: Project) {

    private val settings = PluginSettings.getInstance(project)
    private val executor = GradleCommandExecutor(project)
    private val consoleView = executor.createConsoleView()

    // ── UI Components ─────────────────────────────────────────────────────────
    private val moduleField = JBTextField(settings.state.selectedModule ?: "app", 15)
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

    private val customFlagsField = JBTextField(settings.state.customFlags ?: "", 30)
    private val previewLabel = JLabel().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        foreground = JBColor.GRAY
    }

    private val runButton = JButton("▶  Run Build", AllIcons.Actions.Execute)
    private val stopButton = JButton("⏹  Stop", AllIcons.Actions.Suspend).apply { isEnabled = false }
    private val copyButton = JButton("⎘  Copy", AllIcons.Actions.Copy)

    // ── Build ─────────────────────────────────────────────────────────────────

    fun getComponent(): JComponent {
        val topPanel = buildConfigPanel()
        val bottomPanel = consoleView.component

        return JBSplitter(true, 0.55f).apply {
            firstComponent = JBScrollPane(topPanel).apply {
                border = null
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }
            secondComponent = bottomPanel
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
        panel.add(buildPreviewSection())
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

        // Module row
        val moduleRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        moduleRow.add(JBLabel("Module:"))
        moduleRow.add(moduleField)
        panel.add(moduleRow)

        // Task row
        taskGroup.add(installRadio)
        taskGroup.add(assembleRadio)
        taskGroup.add(bundleRadio)

        val taskRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        taskRow.add(JBLabel("Task:"))
        taskRow.add(installRadio)
        taskRow.add(assembleRadio)
        taskRow.add(bundleRadio)
        panel.add(taskRow)

        // Build type row
        typeGroup.add(debugRadio)
        typeGroup.add(releaseRadio)

        val typeRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        typeRow.add(JBLabel("Type:"))
        typeRow.add(debugRadio)
        typeRow.add(releaseRadio)
        panel.add(typeRow)

        return panel
    }

    private fun buildFlavorSection(): JPanel {
        val panel = titledPanel("Flavor")

        // Populate combo with detected flavors
        flavorCombo.addItem("")  // empty = no flavor
        refreshFlavorsAsync()

        val flavorRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        flavorRow.add(JBLabel("Flavor:"))
        flavorRow.add(flavorCombo)

        val refreshBtn = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = "Refresh detected flavors"
            preferredSize = Dimension(28, 28)
            addActionListener {
                project.basePath?.let { FlavorCache.invalidate(it) }
                refreshFlavorsAsync()
            }
        }
        flavorRow.add(refreshBtn)
        panel.add(flavorRow)

        // Manual flavor row
        manualFlavorField.isEnabled = manualFlavorCheck.isSelected

        val manualRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        manualRow.add(manualFlavorCheck)
        manualRow.add(manualFlavorField)
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

        val grid = JPanel(GridLayout(0, 2, 4, 2))
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
        val row = JPanel(BorderLayout(4, 0))
        row.add(JBLabel("Extra:"), BorderLayout.WEST)
        row.add(customFlagsField, BorderLayout.CENTER)
        panel.add(row)
        return panel
    }

    private fun buildPreviewSection(): JPanel {
        val panel = titledPanel("Command Preview")
        previewLabel.text = "..."
        panel.add(previewLabel)
        return panel
    }

    private fun buildButtonsSection(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))

        runButton.addActionListener { runBuild() }
        stopButton.addActionListener { stopBuild() }
        copyButton.addActionListener { copyCommandToClipboard() }

        panel.add(runButton)
        panel.add(stopButton)
        panel.add(copyButton)
        return panel
    }

    // ── Change listeners ──────────────────────────────────────────────────────

    private fun attachChangeListeners() {
        val onChange = { saveAndRefresh() }

        moduleField.document.addDocumentListener(simpleDocumentListener(onChange))
        customFlagsField.document.addDocumentListener(simpleDocumentListener(onChange))
        manualFlavorField.document.addDocumentListener(simpleDocumentListener(onChange))

        listOf(installRadio, assembleRadio, bundleRadio,
               debugRadio, releaseRadio).forEach { it.addActionListener { saveAndRefresh() } }

        listOf(offlineCheck, parallelCheck, configCacheCheck, buildCacheCheck, daemonCheck,
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
        if (executor.isRunning()) {
            Messages.showInfoMessage(project, "A build is already running.", "Fast Deploy")
            return
        }

        try {
            persistSettings()
            val composer = BuildCommandComposer(project, settings)
            val commandLine = composer.compose()

            runButton.isEnabled = false
            stopButton.isEnabled = true

            executor.execute(
                commandLine = commandLine,
                consoleView = consoleView,
                onStarted = { /* already on EDT */ },
                onFinished = {
                    runButton.isEnabled = true
                    stopButton.isEnabled = false
                }
            )
        } catch (e: IllegalStateException) {
            Messages.showErrorDialog(project, e.message, "Fast Deploy Error")
        }
    }

    private fun stopBuild() {
        executor.stopCurrentProcess()
        runButton.isEnabled = true
        stopButton.isEnabled = false
    }

    private fun copyCommandToClipboard() {
        try {
            persistSettings()
            val composer = BuildCommandComposer(project, settings)
            val text = composer.getPreviewText()
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            clipboard.setContents(StringSelection(text), null)
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message, "Fast Deploy")
        }
    }

    // ── Flavor detection ──────────────────────────────────────────────────────

    private fun refreshFlavorsAsync() {
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
