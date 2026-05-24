package com.androidefficiency.plugin.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings page shown in IDE Preferences → Tools → Android Efficiency.
 *
 * Uses plain Swing (FormBuilder) for maximum compatibility across Android Studio versions.
 * Changes are applied only when the user clicks OK or Apply.
 */
class PluginSettingsConfigurable(private val project: Project) : Configurable {

    // Build target fields
    private val moduleField = JBTextField()
    private val taskCombo = ComboBox(arrayOf("install", "assemble", "bundle"))
    private val buildTypeCombo = ComboBox(arrayOf("Debug", "Release"))

    // Flag checkboxes
    private val offlineCheck = JBCheckBox("--offline (use cached dependencies, skip network)")
    private val parallelCheck = JBCheckBox("--parallel (run tasks in parallel)")
    private val configCacheCheck = JBCheckBox("--configuration-cache (cache build configuration)")
    private val buildCacheCheck = JBCheckBox("--build-cache (cache task outputs)")
    private val daemonCheck = JBCheckBox("--daemon (use Gradle daemon)")
    private val configOnDemandCheck = JBCheckBox("--configure-on-demand")
    private val stacktraceCheck = JBCheckBox("--stacktrace (print stacktrace on failure)")
    private val infoCheck = JBCheckBox("--info (INFO log level)")
    private val debugCheck = JBCheckBox("--debug (DEBUG log level, very verbose)")
    private val dryRunCheck = JBCheckBox("--dry-run (show tasks without executing)")

    // Custom flags
    private val customFlagsField = JBTextField()

    private var panel: JComponent? = null

    override fun getDisplayName(): String = "Android Efficiency"

    override fun createComponent(): JComponent {
        reset() // Load current values

        val buildTargetPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Default module:"), moduleField)
            .addLabeledComponent(JBLabel("Default task:"), taskCombo)
            .addLabeledComponent(JBLabel("Default build type:"), buildTypeCombo)
            .panel
            .also { it.border = BorderFactory.createTitledBorder("Build Target") }

        val flagsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createTitledBorder("Gradle Flags")
            listOf(
                offlineCheck, parallelCheck, configCacheCheck, buildCacheCheck,
                daemonCheck, configOnDemandCheck, stacktraceCheck, infoCheck,
                debugCheck, dryRunCheck
            ).forEach { add(it) }
        }

        val customPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Custom flags:"), customFlagsField)
            .addComponentToRightColumn(
                JBLabel("<html><small>Space-separated flags, e.g. <code>-PmyProp=value</code></small></html>"),
                0
            )
            .panel
            .also { it.border = BorderFactory.createTitledBorder("Advanced") }

        val root = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            add(buildTargetPanel)
            add(flagsPanel)
            add(customPanel)
        }

        panel = root
        return root
    }

    override fun isModified(): Boolean {
        val s = PluginSettings.getInstance(project).state
        return moduleField.text != (s.selectedModule ?: "") ||
            taskCombo.selectedItem != (s.gradleTask ?: "install") ||
            buildTypeCombo.selectedItem != (s.buildType ?: "Debug") ||
            offlineCheck.isSelected != s.offlineMode ||
            parallelCheck.isSelected != s.parallelBuild ||
            configCacheCheck.isSelected != s.configurationCache ||
            buildCacheCheck.isSelected != s.buildCache ||
            daemonCheck.isSelected != s.daemon ||
            configOnDemandCheck.isSelected != s.configureOnDemand ||
            stacktraceCheck.isSelected != s.stacktrace ||
            infoCheck.isSelected != s.info ||
            debugCheck.isSelected != s.debug ||
            dryRunCheck.isSelected != s.dryRun ||
            customFlagsField.text != (s.customFlags ?: "")
    }

    override fun apply() {
        val s = PluginSettings.getInstance(project).state
        s.selectedModule = moduleField.text.trim()
        s.gradleTask = (taskCombo.selectedItem as? String) ?: "install"
        s.buildType = (buildTypeCombo.selectedItem as? String) ?: "Debug"
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

    override fun reset() {
        val s = PluginSettings.getInstance(project).state
        moduleField.text = s.selectedModule ?: "app"
        taskCombo.selectedItem = s.gradleTask ?: "install"
        buildTypeCombo.selectedItem = s.buildType ?: "Debug"
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
    }

    override fun disposeUIResources() {
        panel = null
    }
}
