package com.androidefficiency.plugin.flavor

import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import org.jetbrains.android.facet.AndroidFacet

/**
 * Detects Android product flavors from the project using two strategies:
 *
 * 1. **Primary**: Reads from [AndroidFacet] / GradleAndroidModel (post-sync, accurate).
 * 2. **Fallback**: Parses build.gradle / build.gradle.kts with regex (works without sync).
 *
 * Results are cached in [FlavorCache] and invalidated on file changes.
 */
object FlavorDetector {

    /**
     * Returns a list of detected flavor names for the given project.
     * Falls back to an empty list if the project has no flavors configured.
     *
     * This method is safe to call on the EDT (returns cached values quickly)
     * or on a background thread for initial detection.
     */
    fun detectFlavors(project: Project): List<String> {
        FlavorCache.get(project.basePath ?: "")?.let { return it }

        // Run both strategies and merge: reflection may return partial results
        // (e.g. only the active variant), regex fills in the gaps from build.gradle.
        val fromModel = detectViaAndroidModel(project)
        val fromFile = detectViaBuildGradleParsing(project)
        val flavors = (fromModel + fromFile).distinct().sorted()

        FlavorCache.put(project.basePath ?: "", flavors)
        return flavors
    }

    // ── Strategy 1: AndroidFacet / GradleAndroidModel ─────────────────────────

    private fun detectViaAndroidModel(project: Project): List<String> {
        return try {
            val flavors = mutableSetOf<String>()
            ModuleManager.getInstance(project).modules.forEach { module ->
                val facet = AndroidFacet.getInstance(module) ?: return@forEach
                // Use reflection to avoid hard compile-time dependency on internal API
                // that may change between Android Studio versions
                val modelClass = try {
                    Class.forName("com.android.tools.idea.gradle.project.model.GradleAndroidModel")
                } catch (e: ClassNotFoundException) {
                    // Try legacy class name
                    try {
                        Class.forName("com.android.tools.idea.gradle.project.model.AndroidModuleModel")
                    } catch (e2: ClassNotFoundException) {
                        return@forEach
                    }
                }

                val getMethod = modelClass.getMethod("get", AndroidFacet::class.java)
                val model = getMethod.invoke(null, facet) ?: return@forEach

                // Try to get variants
                try {
                    val variantsMethod = model.javaClass.getMethod("getVariants")
                    @Suppress("UNCHECKED_CAST")
                    val variants = variantsMethod.invoke(model) as? Iterable<*> ?: return@forEach
                    variants.forEach { variant ->
                        val nameMethod = variant?.javaClass?.getMethod("getName")
                        val name = nameMethod?.invoke(variant) as? String ?: return@forEach
                        // Extract flavor part: "devDebug" → "dev", "prodRelease" → "prod"
                        extractFlavorFromVariant(name)?.let { flavors.add(it) }
                    }
                } catch (_: Exception) { /* variants API not available */ }

                // Try productFlavors directly on android project
                try {
                    val projectMethod = model.javaClass.getMethod("getAndroidProject")
                    val androidProject = projectMethod.invoke(model)
                    val flavorsMethod = androidProject?.javaClass?.getMethod("getProductFlavors")
                    @Suppress("UNCHECKED_CAST")
                    val pFlavors = flavorsMethod?.invoke(androidProject) as? Iterable<*>
                    pFlavors?.forEach { pf ->
                        val pfName = pf?.javaClass?.getMethod("getName")?.invoke(pf) as? String
                        pfName?.let { flavors.add(it) }
                    }
                } catch (_: Exception) { /* productFlavors API not available */ }
            }
            flavors.toList().sorted()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Attempts to extract the flavor portion from a variant name.
     * E.g. "devDebug" → "dev", "prodRelease" → "prod", "debug" → null.
     * This is a heuristic — it tries to strip known build type suffixes.
     */
    private fun extractFlavorFromVariant(variantName: String): String? {
        val knownSuffixes = listOf("Debug", "Release", "Staging", "Profile")
        for (suffix in knownSuffixes) {
            if (variantName.endsWith(suffix) && variantName.length > suffix.length) {
                val flavor = variantName.removeSuffix(suffix)
                return flavor.replaceFirstChar { it.lowercaseChar() }
            }
        }
        return null
    }

    // ── Strategy 2: Regex parsing of build.gradle(.kts) ──────────────────────

    private fun detectViaBuildGradleParsing(project: Project): List<String> {
        val basePath = project.basePath ?: return emptyList()
        val flavors = mutableSetOf<String>()

        // Search in common locations: root, app/, module/
        val searchPaths = listOf(
            "$basePath/app/build.gradle.kts",
            "$basePath/app/build.gradle",
            "$basePath/build.gradle.kts",
            "$basePath/build.gradle"
        )

        for (path in searchPaths) {
            val file = java.io.File(path)
            if (!file.exists()) continue
            val content = file.readText()
            flavors.addAll(extractFlavorsFromContent(content))
            if (flavors.isNotEmpty()) break
        }

        return flavors.toList().sorted()
    }

    /**
     * Extracts flavor names from build.gradle content using regex.
     * Handles both Groovy and Kotlin DSL syntax.
     *
     * Groovy:  productFlavors { dev { ... } prod { ... } }
     * Kotlin:  productFlavors { create("dev") { ... } register("prod") { ... } }
     */
    internal fun extractFlavorsFromContent(content: String): List<String> {
        val flavors = mutableListOf<String>()

        // Find productFlavors block
        val blockRegex = Regex(
            """productFlavors\s*\{([^{}]*(?:\{[^{}]*\}[^{}]*)*)\}""",
            RegexOption.DOT_MATCHES_ALL
        )
        val blockMatch = blockRegex.find(content) ?: return emptyList()
        val blockContent = blockMatch.groupValues[1]

        // Groovy DSL: flavor names are bare identifiers followed by {
        val groovyFlavors = Regex("""^\s*([a-zA-Z][a-zA-Z0-9_]*)\s*\{""", RegexOption.MULTILINE)
            .findAll(blockContent)
            .map { it.groupValues[1] }
            .filter { it !in GRADLE_KEYWORDS }

        // Kotlin DSL: create("flavorName") or register("flavorName")
        val kotlinFlavors = Regex("""(?:create|register)\s*\(\s*["']([a-zA-Z][a-zA-Z0-9_]*)["']""")
            .findAll(blockContent)
            .map { it.groupValues[1] }

        flavors.addAll(groovyFlavors)
        flavors.addAll(kotlinFlavors)

        return flavors.distinct().sorted()
    }

    private val GRADLE_KEYWORDS = setOf(
        "android", "kotlin", "java", "dependencies", "buildTypes",
        "defaultConfig", "compileOptions", "kotlinOptions", "buildFeatures",
        "signingConfigs", "lint", "packaging", "flavorDimensions"
    )
}
