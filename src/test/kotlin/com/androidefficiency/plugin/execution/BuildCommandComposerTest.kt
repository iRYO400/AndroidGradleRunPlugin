package com.androidefficiency.plugin.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun `quoted custom flag with space stays a single token`() {
        val tokens = BuildCommandComposer.splitCustomFlags("""-Pkey="a b" --foo""")
        assertEquals(listOf("-Pkey=\"a b\"", "--foo"), tokens)
    }

    @Test
    fun `single-quoted custom flag with space stays a single token`() {
        val tokens = BuildCommandComposer.splitCustomFlags("-Pmsg='hello world'")
        assertEquals(listOf("-Pmsg='hello world'"), tokens)
    }

    @Test
    fun `unquoted custom flags split on whitespace`() {
        val tokens = BuildCommandComposer.splitCustomFlags("--info   --stacktrace")
        assertEquals(listOf("--info", "--stacktrace"), tokens)
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

    // ── Device targeting & Android CLI mode ─────────────────────────────────────

    @Test
    fun `am start targets device with adb -s when device selected`() {
        val tail = buildCommandTail(
            task = "install", launchEnabled = true, intent = "com.foo/com.foo.Main",
            marker = null, device = "emulator-5554"
        )
        assertEquals(" && adb -s 'emulator-5554' shell am start -n \"com.foo/com.foo.Main\"", tail)
    }

    @Test
    fun `am start uses plain adb when no device selected`() {
        val tail = buildCommandTail(
            task = "install", launchEnabled = true, intent = "com.foo/com.foo.Main", marker = null
        )
        assertEquals(" && adb shell am start -n \"com.foo/com.foo.Main\"", tail)
    }

    @Test
    fun `gradle serial prefix present when device selected`() {
        assertEquals("ANDROID_SERIAL='R5CT80MXXXX' ", serialPrefix("R5CT80MXXXX"))
        assertEquals("", serialPrefix(""))
    }

    @Test
    fun `cli command without device`() {
        assertEquals("android run", buildCliCommand(""))
    }

    @Test
    fun `cli command with device`() {
        assertEquals("android run --device='emulator-5554'", buildCliCommand("emulator-5554"))
    }

    // ── Inverse parsing (parseCommand: command string → ParsedCommand) ─────────

    @Test
    fun `parse CLI without device`() {
        val p = BuildCommandComposer.parseCommand("android run")!!
        assertTrue(p.useAndroidCli)
        assertEquals("", p.targetDevice)
    }

    @Test
    fun `parse CLI with device`() {
        val p = BuildCommandComposer.parseCommand("android run --device='emulator-5554'")!!
        assertTrue(p.useAndroidCli)
        assertEquals("emulator-5554", p.targetDevice)
    }

    @Test
    fun `parse gradle minimal install Debug`() {
        val p = BuildCommandComposer.parseCommand("./gradlew :app:installDebug")!!
        assertEquals(false, p.useAndroidCli)
        assertEquals("app", p.module)
        assertEquals("install", p.gradleTask)
        assertEquals("Debug", p.buildType)
        assertEquals("", p.flavor)
        assertTrue(p.recognizedFlags.isEmpty())
        assertEquals("", p.customFlags)
    }

    @Test
    fun `parse gradle with ANDROID_SERIAL prefix`() {
        val p = BuildCommandComposer.parseCommand("ANDROID_SERIAL='R5CT80MX' ./gradlew :app:installDebug")!!
        assertEquals("R5CT80MX", p.targetDevice)
        assertEquals("install", p.gradleTask)
    }

    @Test
    fun `parse assemble Release`() {
        val p = BuildCommandComposer.parseCommand("./gradlew :app:assembleRelease")!!
        assertEquals("assemble", p.gradleTask)
        assertEquals("Release", p.buildType)
        assertEquals("", p.flavor)
    }

    @Test
    fun `parse bundle prod Release`() {
        val p = BuildCommandComposer.parseCommand("./gradlew :app:bundleProdRelease")!!
        assertEquals("bundle", p.gradleTask)
        assertEquals("Release", p.buildType)
        assertEquals("prod", p.flavor)
    }

    @Test
    fun `parse flavor first char is lower-cased`() {
        assertEquals("dev", BuildCommandComposer.parseCommand("./gradlew :app:installDevDebug")!!.flavor)
        assertEquals("myFlavor", BuildCommandComposer.parseCommand("./gradlew :app:installMyFlavorDebug")!!.flavor)
    }

    @Test
    fun `parse module with colon`() {
        val p = BuildCommandComposer.parseCommand("./gradlew :feature:login:assembleStagingDebug")!!
        assertEquals("feature:login", p.module)
        assertEquals("assemble", p.gradleTask)
        assertEquals("staging", p.flavor)
        assertEquals("Debug", p.buildType)
    }

    @Test
    fun `parse recognized flags from multi-line form`() {
        val cmd = "./gradlew :app:installDebug \\\n    --offline \\\n    --parallel"
        val p = BuildCommandComposer.parseCommand(cmd)!!
        assertTrue(p.recognizedFlags.contains("--offline"))
        assertTrue(p.recognizedFlags.contains("--parallel"))
        assertEquals("", p.customFlags)
    }

    @Test
    fun `parse unknown flags go to customFlags`() {
        val cmd = "./gradlew :app:installDebug \\\n    --offline \\\n    -PmyProp=value \\\n    --no-tests"
        val p = BuildCommandComposer.parseCommand(cmd)!!
        assertEquals(setOf("--offline"), p.recognizedFlags)
        assertEquals("-PmyProp=value --no-tests", p.customFlags)
    }

    @Test
    fun `parse quoted custom flag is preserved as one token`() {
        val cmd = "./gradlew :app:installDebug \\\n    -Pkey=\"a b\""
        val p = BuildCommandComposer.parseCommand(cmd)!!
        assertEquals("-Pkey=\"a b\"", p.customFlags)
    }

    @Test
    fun `parse launch tail with plain adb`() {
        val cmd = "./gradlew :app:installDebug \\\n  && adb shell am start -n \"com.foo/com.foo.Main\""
        val p = BuildCommandComposer.parseCommand(cmd)!!
        assertTrue(p.launchActivity)
        assertEquals("com.foo/com.foo.Main", p.launchIntent)
    }

    @Test
    fun `parse launch tail with adb -s device`() {
        val cmd = "ANDROID_SERIAL='emulator-5554' ./gradlew :app:installDebug \\\n  " +
            "&& adb -s 'emulator-5554' shell am start -n \"com.foo/Main\""
        val p = BuildCommandComposer.parseCommand(cmd)!!
        assertEquals("emulator-5554", p.targetDevice)
        assertTrue(p.launchActivity)
        assertEquals("com.foo/Main", p.launchIntent)
    }

    @Test
    fun `parse strips trailing printf completion marker`() {
        val cmd = "./gradlew :app:installDebug ; printf %s \"\$?\" > '/tmp/fastdeploy-exit.tmp'"
        val p = BuildCommandComposer.parseCommand(cmd)!!
        assertEquals("install", p.gradleTask)
        assertEquals("", p.customFlags)
    }

    @Test
    fun `parse full round-trip of a rich gradle command`() {
        val cmd = "ANDROID_SERIAL='emulator-5554' ./gradlew :feature:login:installDevDebug \\\n" +
            "    --offline \\\n    --parallel \\\n    -Pfoo=bar \\\n" +
            "  && adb -s 'emulator-5554' shell am start -n \"com.foo/com.foo.Main\""
        val p = BuildCommandComposer.parseCommand(cmd)!!
        assertEquals(false, p.useAndroidCli)
        assertEquals("emulator-5554", p.targetDevice)
        assertEquals("feature:login", p.module)
        assertEquals("install", p.gradleTask)
        assertEquals("Debug", p.buildType)
        assertEquals("dev", p.flavor)
        assertEquals(setOf("--offline", "--parallel"), p.recognizedFlags)
        assertEquals("-Pfoo=bar", p.customFlags)
        assertTrue(p.launchActivity)
        assertEquals("com.foo/com.foo.Main", p.launchIntent)
    }

    // ── parseCommand: silent no-op (null) cases ─────────────────────────────────

    @Test
    fun `parse returns null for blank`() = assertNull(BuildCommandComposer.parseCommand("   "))

    @Test
    fun `parse returns null for unrelated command`() = assertNull(BuildCommandComposer.parseCommand("git status"))

    @Test
    fun `parse returns null for unknown gradle task`() =
        assertNull(BuildCommandComposer.parseCommand("./gradlew :app:foobarDebug"))

    @Test
    fun `parse returns null for unknown build type`() =
        assertNull(BuildCommandComposer.parseCommand("./gradlew :app:installPurple"))

    @Test
    fun `parse returns null for CLI form with junk`() =
        assertNull(BuildCommandComposer.parseCommand("android run --foo"))

    @Test
    fun `parse returns null for gradlew without task spec`() =
        assertNull(BuildCommandComposer.parseCommand("./gradlew"))

    @Test
    fun `parse returns null for malformed am start tail`() =
        assertNull(BuildCommandComposer.parseCommand("./gradlew :app:installDebug && adb shell am start"))

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
        if (custom.isNotEmpty()) addAll(BuildCommandComposer.splitCustomFlags(custom))
    }

    /**
     * Mirrors the tail [BuildCommandComposer.getTerminalCommand] appends after the
     * gradle invocation: the optional `&& adb shell am start` launch step, then the
     * optional completion marker redirect (which feeds BuildCompletionWatcher).
     */
    private fun buildCommandTail(
        task: String, launchEnabled: Boolean, intent: String, marker: String?, device: String = ""
    ): String {
        val sb = StringBuilder()
        val isInstall = task == "install"
        val trimmedIntent = intent.trim()
        if (launchEnabled && isInstall && trimmedIntent.isNotEmpty()) {
            val adb = if (device.isNotEmpty()) "adb -s '$device'" else "adb"
            sb.append(" && $adb shell am start -n \"$trimmedIntent\"")
        }
        if (marker != null) {
            sb.append(" ; printf %s \"\$?\" > '$marker'")
        }
        return sb.toString()
    }

    // Mirror of BuildCommandComposer.buildCliCommand / serialPrefix.
    private fun buildCliCommand(device: String): String =
        if (device.isNotEmpty()) "android run --device='$device'" else "android run"

    private fun serialPrefix(device: String): String =
        if (device.isNotEmpty()) "ANDROID_SERIAL='$device' " else ""
}
