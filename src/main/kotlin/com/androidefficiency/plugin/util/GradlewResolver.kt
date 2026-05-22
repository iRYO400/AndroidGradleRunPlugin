package com.androidefficiency.plugin.util

import com.intellij.openapi.project.Project
import java.io.File

/**
 * Resolves the gradlew executable path within the project and ensures it has execute permissions.
 */
object GradlewResolver {

    /**
     * Returns the path to the gradlew script for the given project.
     * On macOS/Linux returns "gradlew", on Windows returns "gradlew.bat".
     * Also ensures the script has +x permission on Unix systems.
     *
     * @throws IllegalStateException if gradlew cannot be found in the project directory
     */
    fun resolve(project: Project): String {
        val basePath = project.basePath
            ?: throw IllegalStateException("Project base path is null")

        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val scriptName = if (isWindows) "gradlew.bat" else "gradlew"

        val gradlewFile = File(basePath, scriptName)

        if (!gradlewFile.exists()) {
            throw IllegalStateException(
                "Cannot find '$scriptName' in project root: $basePath. " +
                "Make sure the project uses the Gradle wrapper."
            )
        }

        // Ensure execute permission on Unix
        if (!isWindows && !gradlewFile.canExecute()) {
            val success = gradlewFile.setExecutable(true)
            if (!success) {
                throw IllegalStateException(
                    "Cannot set execute permission on $gradlewFile. " +
                    "Try running: chmod +x $gradlewFile"
                )
            }
        }

        return gradlewFile.absolutePath
    }

    /**
     * Returns true if gradlew exists in the project root.
     */
    fun exists(project: Project): Boolean {
        val basePath = project.basePath ?: return false
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val scriptName = if (isWindows) "gradlew.bat" else "gradlew"
        return File(basePath, scriptName).exists()
    }
}
