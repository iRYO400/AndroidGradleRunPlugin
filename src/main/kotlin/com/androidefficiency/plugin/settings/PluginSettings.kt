package com.androidefficiency.plugin.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

/**
 * Project-level persistent settings for the Android Efficiency plugin.
 * Settings are stored in .idea/AndroidEfficiencyPlugin.xml and survive IDE restarts.
 */
@State(
    name = "AndroidEfficiencySettings",
    storages = [Storage("AndroidEfficiencyPlugin.xml")]
)
@Service(Service.Level.PROJECT)
class PluginSettings : SimplePersistentStateComponent<PluginSettings.State>(State()) {

    class State : BaseState() {
        // ── Gradle flags ──────────────────────────────────────────────────────
        /** --offline: Uses cached dependencies, skips network requests */
        var offlineMode by property(true)

        /** --parallel: Runs tasks in parallel across modules */
        var parallelBuild by property(true)

        /** --configuration-cache: Caches build configuration for faster re-runs */
        var configurationCache by property(true)

        /** --build-cache: Caches task outputs across builds */
        var buildCache by property(true)

        /** --daemon: Uses the Gradle daemon for faster subsequent builds */
        var daemon by property(true)

        /** --configure-on-demand: Only configures required sub-projects */
        var configureOnDemand by property(false)

        /** --dry-run: Shows which tasks would execute without running them */
        var dryRun by property(false)

        /** --stacktrace: Prints the stacktrace on build failure */
        var stacktrace by property(false)

        /** --info: Sets log level to INFO */
        var info by property(false)

        /** --debug: Sets log level to DEBUG (very verbose) */
        var debug by property(false)

        // ── Build configuration ───────────────────────────────────────────────
        /** Target module (e.g. "app", "feature:login") */
        var selectedModule by string("app")

        /** Gradle task type: "install", "assemble", or "bundle" */
        var gradleTask by string("install")

        /** Build type: "Debug" or "Release" */
        var buildType by string("Debug")

        /** Selected flavor name (empty = no flavor) */
        var selectedFlavor by string("")

        /** If true, uses the manualFlavorInput instead of the detected dropdown value */
        var useManualFlavor by property(false)

        /** Manually typed flavor (used when useManualFlavor = true) */
        var manualFlavorInput by string("")

        /** Additional custom flags, e.g. "-PmyProp=value --no-tests" */
        var customFlags by string("")

        // ── Execution mode ────────────────────────────────────────────────────
        /** Phase 2: Use Android CLI (android run) instead of Gradle */
        var useAndroidCli by property(false)

        /** Phase 2: Target device serial (empty = first connected device) */
        var targetDevice by string("")
    }

    companion object {
        fun getInstance(project: Project): PluginSettings =
            project.getService(PluginSettings::class.java)
    }
}
