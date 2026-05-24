package com.androidefficiency.plugin.util

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler

/**
 * Resolves connected Android devices and running emulators using `adb devices`.
 * Used to populate the device dropdown in Phase 2 (Android CLI mode).
 */
object DeviceResolver {

    private const val ADB_BINARY = "adb"
    private const val TIMEOUT_MS = 8_000

    /**
     * Returns a list of connected devices/emulators.
     * Each entry is a [DeviceInfo] with serial and display name.
     *
     * Returns an empty list if adb is not available or no devices are connected.
     */
    fun listDevices(): List<DeviceInfo> {
        return try {
            val cmd = GeneralCommandLine(ADB_BINARY, "devices", "-l")
                .withCharset(Charsets.UTF_8)
            val handler = CapturingProcessHandler(cmd)
            val result = handler.runProcess(TIMEOUT_MS)
            if (result.exitCode == 0) {
                parseAdbDevices(result.stdout)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Returns true if adb is available in PATH.
     */
    fun isAdbAvailable(): Boolean {
        return try {
            val cmd = GeneralCommandLine(ADB_BINARY, "version")
                .withCharset(Charsets.UTF_8)
            val handler = CapturingProcessHandler(cmd)
            handler.runProcess(TIMEOUT_MS).exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    // ── Parsing ────────────────────────────────────────────────────────────────

    /**
     * Parses the output of `adb devices -l` into a list of [DeviceInfo].
     *
     * Sample output:
     * ```
     * List of devices attached
     * emulator-5554          device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64
     * R5CT80MXXXXX           device product:beyond3qltesq model:SM-G975U
     * ```
     */
    internal fun parseAdbDevices(output: String): List<DeviceInfo> {
        return output.lines()
            .drop(1) // Skip "List of devices attached"
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val serial = parts[0]
                val status = parts[1]
                if (status != "device") return@mapNotNull null // Skip offline/unauthorized

                // Extract model name from -l output
                val model = parts.drop(2)
                    .find { it.startsWith("model:") }
                    ?.removePrefix("model:")
                    ?.replace("_", " ")

                val displayName = if (model != null) "$model ($serial)" else serial
                DeviceInfo(serial = serial, displayName = displayName)
            }
    }

    // ── Data class ─────────────────────────────────────────────────────────────

    data class DeviceInfo(
        /** ADB serial number (e.g. "emulator-5554" or "R5CT80MXXXXX") */
        val serial: String,
        /** Human-readable name for display in UI */
        val displayName: String
    )
}
