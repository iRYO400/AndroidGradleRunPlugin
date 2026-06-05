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
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
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
import java.awt.datatransfer.DataFlavor
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
            .addNotificationListener(object : ExternalSystemTaskNotificationListener {
                override fun onSuccess(id: ExternalSystemTaskId) {
                    if (id.type == ExternalSystemTaskType.RESOLVE_PROJECT &&
                        id.findProject() == project
                    ) {
                        ApplicationManager.getApplication().invokeLater { showContent() }
                        // Only the project model changes on a Gradle sync — re-detect
                        // modules & flavors. Devices / CLI availability are unaffected.
                        refreshProjectModel()
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
    private val pasteButton = JButton("Paste", AllIcons.Actions.MenuPaste)

    /** Guards [saveAndRefresh] against the listener storm while [reloadUiFromSettings] runs. */
    private var suppressListeners = false

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
            // Single startup population point — detect modules & flavors against the
            // now-available Gradle model, plus scan devices and probe the Android CLI.
            // The loading card covers the form until this runs, so the dropdowns are
            // never shown empty.
            populateAll()
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

        // Dropdown population & CLI probe happen once from wireReadiness() → populateAll(),
        // after the project is smart — no duplicate scans here.

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

        flavorCombo.addItem("")  // empty = no flavor ("none"); populated by populateAll()

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
        pasteButton.toolTipText = "Fill the form from a Fast Deploy command on the clipboard"
        pasteButton.addActionListener { pasteCommandFromClipboard() }
        panel.add(runButton)
        panel.add(copyButton)
        panel.add(pasteButton)
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
        if (suppressListeners) return
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

    /**
     * Inverse of Copy: parse a Fast Deploy command from the clipboard and fill the form.
     * Unrecognized clipboard content (or no string content) is a silent no-op — the UI
     * is left untouched and no dialog is shown.
     */
    private fun pasteCommandFromClipboard() {
        val text = try {
            CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor) as? String
        } catch (e: Exception) {
            null
        } ?: return
        val parsed = BuildCommandComposer.parseCommand(text) ?: return
        applyParsed(parsed)
    }

    /** Writes a [BuildCommandComposer.ParsedCommand] into settings, then reloads the form. */
    private fun applyParsed(p: BuildCommandComposer.ParsedCommand) {
        val s = settings.state
        // Only switch to CLI mode if the `android` CLI is actually available (radio enabled);
        // otherwise keep Gradle mode but still apply the parsed device.
        s.useAndroidCli = p.useAndroidCli && cliRadio.isEnabled
        s.targetDevice = p.targetDevice

        // Gradle-specific fields are meaningless in CLI mode (greyed out), so leave them as-is there.
        if (!p.useAndroidCli) {
            s.selectedModule = p.module
            s.gradleTask = p.gradleTask
            s.buildType = p.buildType

            // Use the dropdown if it lists this flavor; otherwise fall back to manual input
            // so a flavor the current project hasn't detected still applies.
            val inDropdown = p.flavor.isNotEmpty() &&
                (0 until flavorCombo.itemCount).any { flavorCombo.getItemAt(it) == p.flavor }
            if (p.flavor.isNotEmpty() && !inDropdown) {
                s.useManualFlavor = true
                s.manualFlavorInput = p.flavor
                s.selectedFlavor = ""
            } else {
                s.useManualFlavor = false
                s.manualFlavorInput = ""
                s.selectedFlavor = p.flavor
            }

            s.offlineMode = "--offline" in p.recognizedFlags
            s.parallelBuild = "--parallel" in p.recognizedFlags
            s.configurationCache = "--configuration-cache" in p.recognizedFlags
            s.buildCache = "--build-cache" in p.recognizedFlags
            s.daemon = "--daemon" in p.recognizedFlags
            s.configureOnDemand = "--configure-on-demand" in p.recognizedFlags
            s.dryRun = "--dry-run" in p.recognizedFlags
            s.stacktrace = "--stacktrace" in p.recognizedFlags
            s.info = "--info" in p.recognizedFlags
            s.debug = "--debug" in p.recognizedFlags
            s.customFlags = p.customFlags

            s.launchActivityAfterInstall = p.launchActivity
            s.launchActivityIntent = p.launchIntent
        }

        // State is now authoritative — push it to the widgets without the listener storm.
        suppressListeners = true
        try {
            reloadUiFromSettings()
        } finally {
            suppressListeners = false
        }
        updateModeUi()
        updatePreview()
    }

    /** Inverse of [persistSettings]: push every control's value from `settings.state`. */
    private fun reloadUiFromSettings() {
        val s = settings.state
        if (s.useAndroidCli) cliRadio.isSelected = true else gradleRadio.isSelected = true

        // Ensure the serial is selectable even if the last adb scan didn't list it.
        val dev = (s.targetDevice ?: "").trim()
        if (dev.isNotEmpty() && (0 until deviceCombo.itemCount).none { deviceCombo.getItemAt(it) == dev }) {
            deviceCombo.addItem(dev)
        }
        deviceCombo.selectedItem = dev

        moduleCombo.selectedItem = s.selectedModule ?: "app"  // editable combo accepts off-list values

        when (s.gradleTask ?: "install") {
            "assemble" -> assembleRadio.isSelected = true
            "bundle" -> bundleRadio.isSelected = true
            else -> installRadio.isSelected = true
        }
        if ((s.buildType ?: "Debug") == "Release") releaseRadio.isSelected = true else debugRadio.isSelected = true

        manualFlavorCheck.isSelected = s.useManualFlavor
        manualFlavorField.text = s.manualFlavorInput ?: ""
        flavorCombo.selectedItem = s.selectedFlavor ?: ""
        flavorCombo.isEnabled = !s.useManualFlavor
        manualFlavorField.isEnabled = s.useManualFlavor

        offlineCheck.isSelected = s.offlineMode
        parallelCheck.isSelected = s.parallelBuild
        configCacheCheck.isSelected = s.configurationCache
        buildCacheCheck.isSelected = s.buildCache
        daemonCheck.isSelected = s.daemon
        configOnDemandCheck.isSelected = s.configureOnDemand
        stacktraceCheck.isSelected = s.stacktrace
        infoCheck.isSelected = s.info
        debugCheck.isSelected = s.debug
        dryRunCheck.isSelected = s.dryRun
        customFlagsField.text = s.customFlags ?: ""

        launchActivityCheck.isSelected = s.launchActivityAfterInstall
        launchIntentField.text = s.launchActivityIntent ?: ""
        launchIntentField.isEnabled = s.launchActivityAfterInstall

        // notifyCheck / reuseTerminalCheck are not part of a command — leave them as the user set them.
    }

    // ── Refresh orchestration ──────────────────────────────────────────────────

    /** Re-detect what a Gradle sync can change: modules and flavors. */
    private fun refreshProjectModel() {
        refreshModulesAsync()
        refreshFlavorsAsync()
    }

    /** Full startup population: project model + connected devices + CLI availability. */
    private fun populateAll() {
        refreshProjectModel()
        refreshDevicesAsync()
        checkCliAvailabilityAsync()
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
                val available = AndroidCliExecutor.isCliAvailable()
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
