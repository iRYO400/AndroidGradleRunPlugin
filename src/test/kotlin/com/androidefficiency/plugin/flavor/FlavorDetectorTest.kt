package com.androidefficiency.plugin.flavor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the regex-based flavor parsing fallback in [FlavorDetector].
 */
class FlavorDetectorTest {

    @Test
    fun `detects flavors in Groovy DSL`() {
        val content = """
            android {
                productFlavors {
                    dev {
                        applicationIdSuffix ".dev"
                    }
                    prod {
                        // production
                    }
                }
            }
        """.trimIndent()

        val flavors = FlavorDetector.extractFlavorsFromContent(content)
        assertEquals(listOf("dev", "prod"), flavors)
    }

    @Test
    fun `detects flavors in Kotlin DSL with create()`() {
        val content = """
            android {
                productFlavors {
                    create("dev") {
                        applicationIdSuffix = ".dev"
                    }
                    create("staging") { }
                    create("prod") { }
                }
            }
        """.trimIndent()

        val flavors = FlavorDetector.extractFlavorsFromContent(content)
        assertEquals(listOf("dev", "prod", "staging"), flavors)
    }

    @Test
    fun `detects flavors in Kotlin DSL with register()`() {
        val content = """
            productFlavors {
                register("free") {}
                register("paid") {}
            }
        """.trimIndent()

        val flavors = FlavorDetector.extractFlavorsFromContent(content)
        assertEquals(listOf("free", "paid"), flavors)
    }

    @Test
    fun `returns empty list when no productFlavors block`() {
        val content = """
            android {
                buildTypes {
                    release { minifyEnabled = true }
                    debug {}
                }
            }
        """.trimIndent()

        val flavors = FlavorDetector.extractFlavorsFromContent(content)
        assertTrue(flavors.isEmpty())
    }

    @Test
    fun `does not include gradle keywords as flavors`() {
        val content = """
            productFlavors {
                dev {}
                prod {}
            }
            buildTypes {
                debug {}
                release {}
            }
        """.trimIndent()

        val flavors = FlavorDetector.extractFlavorsFromContent(content)
        // Should NOT include "buildTypes", "debug", "release"
        assertTrue(!flavors.contains("buildTypes"))
        assertTrue(!flavors.contains("debug"))
        assertTrue(!flavors.contains("release"))
    }

    @Test
    fun `results are sorted alphabetically`() {
        val content = """
            productFlavors {
                create("zebra") {}
                create("apple") {}
                create("mango") {}
            }
        """.trimIndent()

        val flavors = FlavorDetector.extractFlavorsFromContent(content)
        assertEquals(listOf("apple", "mango", "zebra"), flavors)
    }
}
