package com.androidefficiency.plugin.flavor

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory cache for detected build flavors.
 * Key is the project base path; value is the list of flavor names.
 *
 * The cache is invalidated by calling [invalidate] or [invalidateAll].
 * Typical invalidation triggers: build.gradle file change, Gradle sync completion.
 */
object FlavorCache {

    private val cache = ConcurrentHashMap<String, List<String>>()

    /**
     * Returns cached flavors for the given project path, or null if not cached.
     */
    fun get(projectBasePath: String): List<String>? = cache[projectBasePath]

    /**
     * Stores flavors in cache for the given project path.
     */
    fun put(projectBasePath: String, flavors: List<String>) {
        cache[projectBasePath] = flavors
    }

    /**
     * Removes cached flavors for the given project path.
     * Call this when build.gradle changes or Gradle sync completes.
     */
    fun invalidate(projectBasePath: String) {
        cache.remove(projectBasePath)
    }

    /**
     * Removes all cached entries. Use sparingly (e.g., on IDE restart is not needed since
     * the cache is in-memory and doesn't survive restarts anyway).
     */
    fun invalidateAll() {
        cache.clear()
    }

    /**
     * Returns true if the cache has an entry for the given project path.
     */
    fun contains(projectBasePath: String): Boolean = cache.containsKey(projectBasePath)
}
