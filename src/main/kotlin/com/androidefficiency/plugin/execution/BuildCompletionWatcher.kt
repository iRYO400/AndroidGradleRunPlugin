package com.androidefficiency.plugin.execution

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.File
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Watches a marker file for a build's exit code and fires a native IDE balloon
 * notification when it appears.
 *
 * The build runs as a detached shell command in the IDE Terminal, so the IDE has
 * no process handle to await. Instead, [BuildLauncher] appends a shell redirect
 * (`; printf %s "$?" > <marker>`) and this watcher polls the marker until the
 * shell writes the exit code into it.
 *
 * Native IDE notifications need no macOS permission, unlike `osascript`.
 */
object BuildCompletionWatcher {

    private const val NOTIFICATION_GROUP = "AndroidEfficiency.Notifications"
    private const val POLL_INTERVAL_MS = 700L

    /** Give up after this long so a closed terminal / cancelled build can't leak a poller. */
    private const val TIMEOUT_MS = 2 * 60 * 60 * 1000L // 2 hours

    /**
     * Starts polling [marker] for an exit code. On detection (or timeout) the
     * marker file is deleted and the poller stops.
     */
    fun watch(project: Project, marker: File) {
        val scheduler = AppExecutorUtil.getAppScheduledExecutorService()
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        val futureRef = arrayOfNulls<ScheduledFuture<*>>(1)

        val poll = Runnable {
            if (project.isDisposed) {
                stop(futureRef[0], marker)
                return@Runnable
            }
            val exitCode = readExitCode(marker)
            if (exitCode != null) {
                stop(futureRef[0], marker)
                notify(project, exitCode)
            } else if (System.currentTimeMillis() > deadline) {
                stop(futureRef[0], marker)
            }
        }

        futureRef[0] = scheduler.scheduleWithFixedDelay(
            poll, POLL_INTERVAL_MS, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS
        )
    }

    private fun readExitCode(marker: File): Int? {
        if (!marker.exists()) return null
        val text = try {
            marker.readText().trim()
        } catch (e: Exception) {
            return null
        }
        // The shell creates the file then writes into it; treat empty as "not done yet".
        return text.toIntOrNull()
    }

    private fun stop(future: ScheduledFuture<*>?, marker: File) {
        future?.cancel(false)
        runCatching { marker.delete() }
    }

    private fun notify(project: Project, exitCode: Int) {
        val (title, message, type) = if (exitCode == 0) {
            Triple("Build Successful ✓", "Fast Deploy finished successfully.", NotificationType.INFORMATION)
        } else {
            Triple("Build Failed ✗", "Fast Deploy exited with code $exitCode.", NotificationType.ERROR)
        }
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            NotificationGroupManager.getInstance()
                .getNotificationGroup(NOTIFICATION_GROUP)
                .createNotification(title, message, type)
                .notify(project)
        }
    }
}
