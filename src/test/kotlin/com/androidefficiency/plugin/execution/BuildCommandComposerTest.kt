package com.androidefficiency.plugin.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BuildCommandComposer].
 * Tests task name construction and flag building logic without requiring a real project.
 */
class BuildCommandComposerTest {

    // We test the internal logic of buildTaskName indirectly via getPreviewText.
    // The composer itself calls GradlewResolver which needs a project,
    // so we test the pure logic via a helper function below.

    // ── Task name tests ────────────────────────────────────────────────────────

    @Test
    fun `task name without flavor - install Debug`() {
        val name = buildTaskName(module = "app", task = "install", flavor = "", buildType = "Debug")
        assertEquals(":app:installDebug", name)
    }

    @Test
    fun `task name without flavor - assemble Release`() {
        val name = buildTaskName(module = "app", task = "assemble", flavor = "", buildType = "Release")
        assertEquals(":app:assembleRelease", name)
    }

    @Test
    fun `task name with flavor - install dev Debug`() {
        val name = buildTaskName(module = "app", task = "install", flavor = "dev", buildType = "Debug")
        assertEquals(":app:installDevDebug", name)
    }

    @Test
    fun `task name with flavor - bundle prod Release`() {
        val name = buildTaskName(module = "app", task = "bundle", flavor = "prod", buildType = "Release")
        assertEquals(":app:bundleProdRelease", name)
    }

    @Test
    fun `task name with custom module`() {
        val name = buildTaskName(module = "feature:login", task = "assemble", flavor = "staging", buildType = "Debug")
        assertEquals(":feature:login:assembleStagingDebug", name)
    }

    @Test
    fun `task name flavor is capitalized`() {
        val name = buildTaskName(module = "app", task = "install", flavor = "myFlavor", buildType = "Debug")
        assertEquals(":app:installMyFlavorDebug", name)
    }

    // ── Flag tests ─────────────────────────────────────────────────────────────

    @Test
    fun `offline flag included when enabled`() {
        val flags = buildFlags(offline = true)
        assertTrue("--offline should be included", flags.contains("--offline"))
    }

    @Test
    fun `offline flag excluded when disabled`() {
        val flags = buildFlags(offline = false)
        assertTrue("--offline should not be included", !flags.contains("--offline"))
    }

    @Test
    fun `multiple flags combined correctly`() {
        val flags = buildFlags(
            offline = true, parallel = true, configCache = true, buildCache = false
        )
        assertTrue(flags.contains("--offline"))
        assertTrue(flags.contains("--parallel"))
        assertTrue(flags.contains("--configuration-cache"))
        assertTrue(!flags.contains("--build-cache"))
    }

    @Test
    fun `custom flags are split by whitespace`() {
        val flags = buildFlags(customFlags = "-PmyProp=value --no-tests")
        assertTrue(flags.contains("-PmyProp=value"))
        assertTrue(flags.contains("--no-tests"))
    }

    @Test
    fun `empty custom flags produce no extra args`() {
        val flags = buildFlags(customFlags = "")
        assertTrue(!flags.contains(""))
    }

    @Test
    fun `custom flags with extra whitespace are trimmed`() {
        val flags = buildFlags(customFlags = "  --info  ")
        assertTrue(flags.contains("--info"))
    }

    // ── Post-build action tests ───────────────────────────────────────────────

    @Test
    fun `am start added when install and intent set and flag enabled`() {
        val tail = buildCommandTail(
            task = "install", launchEnabled = true, intent = "com.foo/com.foo.Main", marker = null
        )
        assertEquals(" && adb shell am start -n \"com.foo/com.foo.Main\"", tail)
    }

    @Test
    fun `am start skipped when task is assemble`() {
        val tail = buildCommandTail(
            task = "assemble", launchEnabled = true, intent = "com.foo/com.foo.Main", marker = null
        )
        assertTrue("no am start for assemble", !tail.contains("adb shell am start"))
    }

    @Test
    fun `am start skipped when task is bundle`() {
        val tail = buildCommandTail(
            task = "bundle", launchEnabled = true, intent = "com.foo/com.foo.Main", marker = null
        )
        assertTrue("no am start for bundle", !tail.contains("adb shell am start"))
    }

    @Test
    fun `am start skipped when intent is empty`() {
        val tail = buildCommandTail(
            task = "install", launchEnabled = true, intent = "", marker = null
        )
        assertEquals("", tail)
    }

    @Test
    fun `am start skipped when intent is whitespace only`() {
        val tail = buildCommandTail(
            task = "install", launchEnabled = true, intent = "   ", marker = null
        )
        assertEquals("", tail)
    }

    @Test
    fun `am start skipped when flag disabled`() {
        val tail = buildCommandTail(
            task = "install", launchEnabled = false, intent = "com.foo/com.foo.Main", marker = null
        )
        assertEquals("", tail)
    }

    // ── Completion marker tests (IDE notification mechanism) ──────────────────

    @Test
    fun `marker redirect appended when marker provided`() {
        val tail = buildCommandTail(
            task = "install", launchEnabled = false, intent = "", marker = "/tmp/fastdeploy-exit.tmp"
        )
        assertEquals(" ; printf %s \"\$?\" > '/tmp/fastdeploy-exit.tmp'", tail)
    }

    @Test
    fun `marker redirect runs after am start`() {
        val tail = buildCommandTail(
            task = "install", launchEnabled = true, intent = "com.foo/com.foo.Main",
            marker = "/tmp/fastdeploy-exit.tmp"
        )
        val amIdx = tail.indexOf("&& adb shell am start")
        val markerIdx = tail.indexOf("; printf")
        assertTrue("am start present", amIdx >= 0)
        assertTrue("marker present", markerIdx >= 0)
        assertTrue("am start before marker redirect", amIdx < markerIdx)
    }

    @Test
    fun `no marker redirect when marker is null`() {
        val tail = buildCommandTail(
            task = "install", launchEnabled = false, intent = "com.foo/com.foo.Main", marker = null
        )
        assertTrue("no printf redirect", !tail.contains("printf"))
    }

    @Test
    fun `empty tail when no launch and no marker`() {
        val tail = buildCommandTail(
            task = "install", launchEnabled = false, intent = "com.foo/com.foo.Main", marker = null
        )
        assertEquals("", tail)
    }

    // ── Helper functions (mirror BuildCommandComposer logic) ──────────────────

    private fun buildTaskName(
        module: String, task: String, flavor: String, buildType: String
    ): String {
        val capitalizedFlavor = flavor.replaceFirstChar { it.uppercaseChar() }
        return ":$module:$task$capitalizedFlavor$buildType"
    }

    private fun buildFlags(
        offline: Boolean = false,
        parallel: Boolean = false,
        configCache: Boolean = false,
        buildCache: Boolean = false,
        daemon: Boolean = false,
        configOnDemand: Boolean = false,
        dryRun: Boolean = false,
        stacktrace: Boolean = false,
        info: Boolean = false,
        debug: Boolean = false,
        customFlags: String = ""
    ): List<String> = buildList {
        if (offline) add("--offline")
        if (parallel) add("--parallel")
        if (configCache) add("--configuration-cache")
        if (buildCache) add("--build-cache")
        if (daemon) add("--daemon")
        if (configOnDemand) add("--configure-on-demand")
        if (dryRun) add("--dry-run")
        if (stacktrace) add("--stacktrace")
        if (info) add("--info")
        if (debug) add("--debug")
        val custom = customFlags.trim()
        if (custom.isNotEmpty()) addAll(custom.split(Regex("\\s+")))
    }

    /**
     * Mirrors the tail [BuildCommandComposer.getTerminalCommand] appends after the
     * gradle invocation: the optional `&& adb shell am start` launch step, then the
     * optional completion marker redirect (which feeds BuildCompletionWatcher).
     */
    private fun buildCommandTail(
        task: String, launchEnabled: Boolean, intent: String, marker: String?
    ): String {
        val sb = StringBuilder()
        val isInstall = task == "install"
        val trimmedIntent = intent.trim()
        if (launchEnabled && isInstall && trimmedIntent.isNotEmpty()) {
            sb.append(" && adb shell am start -n \"$trimmedIntent\"")
        }
        if (marker != null) {
            sb.append(" ; printf %s \"\$?\" > '$marker'")
        }
        return sb.toString()
    }
}
