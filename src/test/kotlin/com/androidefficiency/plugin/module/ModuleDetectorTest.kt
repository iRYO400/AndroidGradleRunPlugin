package com.androidefficiency.plugin.module

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the settings-file parsing in [ModuleDetector].
 */
class ModuleDetectorTest {

    @Test
    fun `detects modules in Kotlin DSL single-line include`() {
        val content = """
            rootProject.name = "MyApp"
            include(":app", ":feature:login")
        """.trimIndent()

        val modules = ModuleDetector.extractModulesFromSettings(content)
        assertEquals(listOf("app", "feature:login"), modules)
    }

    @Test
    fun `detects modules in Kotlin DSL multi-line include`() {
        val content = """
            include(
                ":app",
                ":core:network",
                ":feature:login",
            )
        """.trimIndent()

        val modules = ModuleDetector.extractModulesFromSettings(content)
        assertEquals(listOf("app", "core:network", "feature:login"), modules)
    }

    @Test
    fun `detects modules in Groovy DSL comma list`() {
        val content = """
            rootProject.name = 'MyApp'
            include ':app', ':lib'
        """.trimIndent()

        val modules = ModuleDetector.extractModulesFromSettings(content)
        assertEquals(listOf("app", "lib"), modules)
    }

    @Test
    fun `detects modules across multiple include statements`() {
        val content = """
            include ":app"
            include ":feature:home"
            include(":core")
        """.trimIndent()

        val modules = ModuleDetector.extractModulesFromSettings(content)
        assertEquals(listOf("app", "core", "feature:home"), modules)
    }

    @Test
    fun `strips leading colon and keeps nested path`() {
        val content = """include(":payments:transfers:crossborder")"""

        val modules = ModuleDetector.extractModulesFromSettings(content)
        assertEquals(listOf("payments:transfers:crossborder"), modules)
    }

    @Test
    fun `returns empty list when no include statements`() {
        val content = """
            pluginManagement {
                repositories { mavenCentral() }
            }
            rootProject.name = "MyApp"
        """.trimIndent()

        val modules = ModuleDetector.extractModulesFromSettings(content)
        assertTrue(modules.isEmpty())
    }

    @Test
    fun `results are distinct and sorted`() {
        val content = """
            include ":zebra"
            include ":apple"
            include ":apple"
            include ":mango"
        """.trimIndent()

        val modules = ModuleDetector.extractModulesFromSettings(content)
        assertEquals(listOf("apple", "mango", "zebra"), modules)
    }
}
