package com.androidefficiency.plugin.toolwindow

import com.androidefficiency.plugin.execution.AndroidCliExecutor
import com.androidefficiency.plugin.execution.BuildCommandComposer
import com.androidefficiency.plugin.execution.BuildLauncher
import com.androidefficiency.plugin.flavor.FlavorCache
import com.androidefficiency.plugin.flavor.FlavorDetector
import com.androidefficiency.plugin.module.ModuleDetector
import com.androidefficiency.plugin.settings.PluginSettings
import com.androidefficiency.plugin.util.DeviceResolver
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
import com.intellij.openapi.project.DumbService
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
import javax.swing.text.JTextComponent

class BuildToolWindowPanel(private val project: Project, parentDisposable: Disposable) {

    private val settings = PluginSettings.getInstance(project)

    init {
        // Re-detect modules & flavors after every Gradle Sync.
        // ExternalSystemProgressNotificationManager is app-level, so filter by project.
        val disposable = Disposer.newDisposable("FastDeploy:GradleSync")
        Disposer.register(parentDisposable, disposable)
        ExternalSystemProgressNotificationManager.getInstance()
            .addNotificationListener(object : ExternalSystemTaskNotificationListenerAdapter(null) {
                override fun onSuccess(id: ExternalSystemTaskId) {
                    if (id.type == ExternalSystemTaskType.RESOLVE_PROJECT &&
                        id.findProject() == project
                    ) {
                        ApplicationManager.getApplication().invokeLater { showContent() }
                        refreshFlavorsAsync()
                        refreshModulesAsync()
                    }
                }
            }, disposable)
    }

    // ── UI Components ─────────────────────────────────────────────────────────
    // Run-via mode: Gradle (./gradlew) vs Android CLI (android run).
    private val runViaGroup = ButtonGroup()
    private val gradleRadio = JRadioButton("Gradle", !settings.state.useAndroidCli)
    private val cliRadio = JRadioButton("Android CLI", settings.state.useAndroidCli)

    // Target device. "" = default / all connected. Labels are looked up from the last scan.
    private val deviceCombo = ComboBox<String>()
    private var deviceLabels: Map<String, String> = emptyMap()
    private val refreshDevicesButton = JButton(AllIcons.Actions.Refresh)

    // Gradle-only sections, greyed out in Android CLI mode.
    private lateinit var moduleTaskSection: JPanel
    private lateinit var flavorSection: JPanel
    private lateinit var flagsSection: JPanel
    private lateinit var customFlagsSection: JPanel

    // Editable combo: shows auto-detected Gradle modules, but the user can still type one.
    private val moduleCombo = ComboBox<String>().apply {
        isEditable = true
        selectedItem = settings.state.selectedModule ?: "app"
    }
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
    private val notifyCheck = JCheckBox("Notify on completion (IDE)", settings.state.notifyOnCompletion)

    private val previewLabel = JLabel().apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        foreground = JBColor.GRAY
    }

    private val reuseTerminalCheck = JCheckBox("Use active terminal tab", settings.state.reuseActiveTerminal)

    private val runButton = JButton("Run in Terminal", AllIcons.Actions.Execute)
    private val copyButton = JButton("Copy", AllIcons.Actions.Copy)

    // Two-card root: a waiting splash while the project is still indexing, then the form.
    private val rootPanel = JPanel(CardLayout())
    private var cardsReady = false
    private var contentShown = false

    // ── Build ─────────────────────────────────────────────────────────────────

    fun getComponent(): JComponent {
        val topPanel = buildConfigPanel()

        // NORTH anchor: topPanel gets full width but only its preferred height,
        // avoiding the BoxLayout Y_AXIS viewport-width propagation issue.
        val topWrapper = JPanel(BorderLayout()).apply {
            add(topPanel, BorderLayout.NORTH)
        }
        val contentScroll = JBScrollPane(topWrapper).apply {
            border = null
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }

        // Loading card first → it's the default shown until the project is ready.
        rootPanel.add(buildLoadingPanel(), CARD_LOADING)
        rootPanel.add(contentScroll, CARD_CONTENT)
        cardsReady = true

        wireReadiness()
        return rootPanel
    }

    /**
     * Shows the splash until the project leaves "dumb" (indexing) mode, then swaps in
     * the form. [DumbService.runWhenSmart] runs immediately when the project is already
     * smart, so a freshly-opened, still-indexing project sees the splash and an
     * already-synced one goes straight to the form — no risk of getting stuck.
     */
    private fun wireReadiness() {
        DumbService.getInstance(project).runWhenSmart {
            showContent()
            // Re-detect with the now-available Gradle model for accurate results.
            refreshModulesAsync()
            refreshFlavorsAsync()
        }
    }

    private fun showContent() {
        if (!cardsReady || contentShown) return
        contentShown = true
        (rootPanel.layout as CardLayout).show(rootPanel, CARD_CONTENT)
    }

    private fun buildLoadingPanel(): JPanel {
        val art = JTextArea(ANDROID_ART).apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            foreground = JBColor.GRAY
            isEditable = false
            isFocusable = false
            isOpaque = false
            border = null
            // Pin to preferred size so BoxLayout centers the block instead of stretching it.
            maximumSize = preferredSize
            alignmentX = Component.CENTER_ALIGNMENT
        }
        val message = JLabel(
            "<html><div style='text-align:center'>" +
                "⚡ <b>Fast Deploy</b><br/><br/>" +
                "Waiting for the project to be ready…<br/>" +
                "<small>indexing / Gradle sync in progress —<br/>" +
                "the controls will appear automatically</small>" +
                "</div></html>"
        ).apply {
            foreground = JBColor.GRAY
            horizontalAlignment = SwingConstants.CENTER
            alignmentX = Component.CENTER_ALIGNMENT
            maximumSize = preferredSize
        }
        val progress = JProgressBar().apply {
            isIndeterminate = true
            maximumSize = Dimension(180, preferredSize.height)
            alignmentX = Component.CENTER_ALIGNMENT
        }

        val column = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(art)
            add(Box.createVerticalStrut(16))
            add(message)
            add(Box.createVerticalStrut(16))
            add(progress)
        }

        // GridBag centers the column both vertically and horizontally.
        return JPanel(GridBagLayout()).apply {
            border = JBUI.Borders.empty(16)
            add(column, GridBagConstraints())
        }
    }

    // ── Configuration panel ───────────────────────────────────────────────────

    private fun buildConfigPanel(): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
        }

        moduleTaskSection = buildModuleTaskSection()
        flavorSection = buildFlavorSection()
        flagsSection = buildFlagsSection()
        customFlagsSection = buildCustomFlagsSection()

        panel.add(buildRunViaSection())
        panel.add(Box.createVerticalStrut(6))
        panel.add(buildDeviceSection())
        panel.add(Box.createVerticalStrut(6))
        panel.add(moduleTaskSection)
        panel.add(Box.createVerticalStrut(6))
        panel.add(flavorSection)
        panel.add(Box.createVerticalStrut(6))
        panel.add(flagsSection)
        panel.add(Box.createVerticalStrut(6))
        panel.add(customFlagsSection)
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

        // Populate dropdowns and probe the Android CLI
        refreshModulesAsync()
        refreshDevicesAsync()
        checkCliAvailabilityAsync()

        // Wire up all change listeners
        attachChangeListeners()

        // Reflect the current run-via mode (grey out Gradle controls if CLI).
        updateModeUi()

        return panel
    }

    private fun buildRunViaSection(): JPanel {
        val panel = titledPanel("Run via")
        runViaGroup.add(gradleRadio)
        runViaGroup.add(cliRadio)
        cliRadio.isEnabled = false  // enabled after the async availability probe
        cliRadio.toolTipText = "Checking for the 'android' CLI…"

        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        row.add(gradleRadio)
        row.add(cliRadio)
        panel.add(row)
        return panel
    }

    private fun buildDeviceSection(): JPanel {
        val panel = titledPanel("Device")
        deviceCombo.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean
            ): Component {
                val display = when {
                    value is String && value.isEmpty() -> "Default (all connected)"
                    value is String -> deviceLabels[value] ?: value
                    else -> value
                }
                return super.getListCellRendererComponent(list, display, index, isSelected, cellHasFocus)
            }
        }
        deviceCombo.addItem("")  // default

        val row = JPanel(BorderLayout(4, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyLeft(4)
        }
        refreshDevicesButton.toolTipText = "Rescan connected devices (adb)"
        refreshDevicesButton.addActionListener { refreshDevicesAsync() }
        row.add(JBLabel("Device:"), BorderLayout.WEST)
        row.add(deviceCombo, BorderLayout.CENTER)
        row.add(refreshDevicesButton, BorderLayout.EAST)
        panel.add(row)
        return panel
    }

    private fun buildModuleTaskSection(): JPanel {
        val panel = titledPanel("Build Target")

        // Module row — BorderLayout so the combo fills remaining width
        val moduleRow = JPanel(BorderLayout(4, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyLeft(4)
        }
        moduleRow.add(JBLabel("Module:"), BorderLayout.WEST)
        moduleRow.add(moduleCombo, BorderLayout.CENTER)
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
        val panel = titledPanel("Terminal")
        reuseTerminalCheck.toolTipText =
            "Reuse the currently selected terminal tab instead of opening a new 'Fast Deploy' tab"
        reuseTerminalCheck.alignmentX = Component.LEFT_ALIGNMENT
        reuseTerminalCheck.addActionListener { saveAndRefresh() }

        val row = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        row.add(reuseTerminalCheck)
        panel.add(row)
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

        moduleEditor()?.document?.addDocumentListener(simpleDocumentListener(onChange))
        moduleCombo.addActionListener { saveAndRefresh() }
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
        deviceCombo.addActionListener { saveAndRefresh() }

        val onModeChange = {
            updateModeUi()
            saveAndRefresh()
        }
        gradleRadio.addActionListener { onModeChange() }
        cliRadio.addActionListener { onModeChange() }
    }

    private fun saveAndRefresh() {
        persistSettings()
        updatePreview()
    }

    // ── Settings persistence ──────────────────────────────────────────────────

    /** Current module text from the editable combo (typed value takes precedence). */
    private fun currentModule(): String =
        (moduleCombo.editor.item as? String ?: moduleCombo.selectedItem as? String).orEmpty().trim()

    private fun moduleEditor(): JTextComponent? =
        moduleCombo.editor.editorComponent as? JTextComponent

    private fun persistSettings() {
        val s = settings.state
        s.useAndroidCli = cliRadio.isSelected
        s.targetDevice = (deviceCombo.selectedItem as? String)?.trim() ?: ""
        s.selectedModule = currentModule()
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
            val composer = BuildCommandComposer(settings)
            val preview = composer.getPreviewText()
            previewLabel.text = "<html><pre style='margin:0'>${preview.replace("\n", "<br/>")}</pre></html>"
        } catch (e: Exception) {
            previewLabel.text = "<html><i>Error: ${e.message}</i></html>"
        }
    }

    // ── Build execution ───────────────────────────────────────────────────────

    private fun runBuild() {
        persistSettings()
        BuildLauncher.launch(project)
    }

    private fun copyCommandToClipboard() {
        try {
            persistSettings()
            val text = BuildCommandComposer(settings).getPreviewText()
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

    // ── Module detection ──────────────────────────────────────────────────────

    private fun refreshModulesAsync() {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Fast Deploy: Detecting modules…", false
        ) {
            override fun run(indicator: ProgressIndicator) {
                val modules = ModuleDetector.detectModules(project)
                ApplicationManager.getApplication().invokeLater {
                    updateModuleCombo(modules)
                }
            }
        })
    }

    private fun updateModuleCombo(modules: List<String>) {
        // Preserve whatever the user has selected/typed so detection never clobbers it.
        val current = currentModule().ifEmpty { settings.state.selectedModule ?: "app" }
        moduleCombo.removeAllItems()
        modules.forEach { moduleCombo.addItem(it) }
        if (modules.isEmpty()) moduleCombo.addItem("app")
        moduleCombo.selectedItem = current  // editable combo accepts values outside the list
        updatePreview()
    }

    // ── Run-via mode & devices ──────────────────────────────────────────────────

    /** Greys out the Gradle-specific controls when Android CLI mode is selected. */
    private fun updateModeUi() {
        setGradleControlsEnabled(!cliRadio.isSelected)
    }

    private fun setGradleControlsEnabled(enabled: Boolean) {
        listOf(moduleTaskSection, flavorSection, flagsSection, customFlagsSection)
            .forEach { setEnabledRecursively(it, enabled) }
        // Launch-activity is Gradle-only (android run launches the app itself);
        // notify-on-completion works in both modes, so it stays enabled.
        launchActivityCheck.isEnabled = enabled
        launchIntentField.isEnabled = enabled && launchActivityCheck.isSelected
        if (enabled) {
            // Restore sub-control states that depend on their own checkboxes.
            flavorCombo.isEnabled = !manualFlavorCheck.isSelected
            manualFlavorField.isEnabled = manualFlavorCheck.isSelected
        }
    }

    private fun setEnabledRecursively(component: Component, enabled: Boolean) {
        component.isEnabled = enabled
        if (component is Container) component.components.forEach { setEnabledRecursively(it, enabled) }
    }

    private fun checkCliAvailabilityAsync() {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Fast Deploy: Checking Android CLI…", false
        ) {
            override fun run(indicator: ProgressIndicator) {
                val available = AndroidCliExecutor(project).isCliAvailable()
                ApplicationManager.getApplication().invokeLater {
                    cliRadio.isEnabled = available
                    cliRadio.toolTipText = if (available) null else "'android' CLI not found on PATH"
                    if (!available && cliRadio.isSelected) {
                        gradleRadio.isSelected = true
                        updateModeUi()
                        saveAndRefresh()
                    }
                }
            }
        })
    }

    private fun refreshDevicesAsync() {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Fast Deploy: Scanning devices…", false
        ) {
            override fun run(indicator: ProgressIndicator) {
                val devices = DeviceResolver.listDevices()
                ApplicationManager.getApplication().invokeLater {
                    updateDeviceCombo(devices)
                }
            }
        })
    }

    private fun updateDeviceCombo(devices: List<DeviceResolver.DeviceInfo>) {
        val saved = (settings.state.targetDevice ?: "").trim()
        deviceLabels = devices.associate { it.serial to it.displayName }
        deviceCombo.removeAllItems()
        deviceCombo.addItem("")  // default / all connected
        devices.forEach { deviceCombo.addItem(it.serial) }
        deviceCombo.selectedItem =
            if (saved.isNotEmpty() && devices.any { it.serial == saved }) saved else ""
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

    companion object {
        private const val CARD_LOADING = "loading"
        private const val CARD_CONTENT = "content"

        private val ANDROID_ART = """
                \         /
            .-----------------.
            |   .-.     .-.   |
            |   '-'     '-'   |
            |                 |
            |   `._______.'   |
            '-----------------'
              /             \
        """.trimIndent()
    }
}
