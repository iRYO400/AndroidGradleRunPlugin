package com.androidefficiency.plugin.compat

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.util.BuildNumber

/**
 * IDE version compatibility checks for feature gating.
 *
 * Phase 2 (Android CLI) requires Android Studio Panda 4 (2025.3.4, build 253.32098.37) or newer.
 * The `android` CLI tool was released in May 2026 — users on older IDE versions won't have it.
 * Note: always pair with [com.androidefficiency.plugin.execution.AndroidCliExecutor.isCliAvailable]
 * to verify the CLI binary is actually installed.
 */
object IdeCompat {

    private val PHASE_2_MIN: BuildNumber = BuildNumber.fromString("253.32098.37")!!

    fun isPhase2Supported(): Boolean {
        val current = ApplicationInfo.getInstance().build
        return current >= PHASE_2_MIN
    }
}
