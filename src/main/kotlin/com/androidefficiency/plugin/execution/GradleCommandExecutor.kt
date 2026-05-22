package com.androidefficiency.plugin.execution

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key

/**
 * Executes a Gradle command and streams its output to a [ConsoleView].
 */
class GradleCommandExecutor(private val project: Project) {

    private var currentProcessHandler: OSProcessHandler? = null

    /**
     * Returns a new [ConsoleView] pre-configured for this project.
     */
    fun createConsoleView(): ConsoleView {
        return TextConsoleBuilderFactory
            .getInstance()
            .createBuilder(project)
            .console
    }

    /**
     * Runs [commandLine] and pipes output into [consoleView].
     * Runs the process on a background thread; UI updates happen on EDT.
     *
     * @param commandLine The command to execute
     * @param consoleView The console to attach output to
     * @param onStarted   Called (on EDT) just after the process starts
     * @param onFinished  Called (on EDT) when the process terminates with exit code
     */
    fun execute(
        commandLine: GeneralCommandLine,
        consoleView: ConsoleView,
        onStarted: () -> Unit = {},
        onFinished: (exitCode: Int) -> Unit = {}
    ) {
        // Clear previous output
        consoleView.clear()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Fast Deploy: Running build…", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Starting Gradle…"

                val processHandler = OSProcessHandler(commandLine)
                currentProcessHandler = processHandler

                // Attach console on EDT
                ApplicationManager.getApplication().invokeLater {
                    consoleView.attachToProcess(processHandler)
                    onStarted()
                }

                // Listen for output to update the progress indicator text
                processHandler.addProcessListener(object : ProcessAdapter() {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        if (outputType == ProcessOutputTypes.STDOUT ||
                            outputType == ProcessOutputTypes.SYSTEM) {
                            val line = event.text.trim()
                            if (line.isNotEmpty()) {
                                indicator.text = line.take(100)
                            }
                        }
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        currentProcessHandler = null
                        val exitCode = event.exitCode
                        ApplicationManager.getApplication().invokeLater {
                            onFinished(exitCode)
                            showCompletionNotification(exitCode)
                        }
                    }
                })

                processHandler.startNotify()
                // Block background thread until process completes
                processHandler.waitFor()
            }

            override fun onCancel() {
                stopCurrentProcess()
            }
        })
    }

    /**
     * Terminates the currently running process, if any.
     */
    fun stopCurrentProcess() {
        currentProcessHandler?.let { handler ->
            if (!handler.isProcessTerminated) {
                handler.destroyProcess()
            }
        }
    }

    /**
     * Returns true if a build is currently running.
     */
    fun isRunning(): Boolean {
        return currentProcessHandler?.let { !it.isProcessTerminated } ?: false
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private fun showCompletionNotification(exitCode: Int) {
        val (title, message, type) = if (exitCode == 0) {
            Triple("Build Successful ✓", "Fast Deploy finished successfully.", NotificationType.INFORMATION)
        } else {
            Triple("Build Failed ✗", "Fast Deploy exited with code $exitCode. Check the console for details.", NotificationType.ERROR)
        }

        NotificationGroupManager.getInstance()
            .getNotificationGroup("AndroidEfficiency.Notifications")
            .createNotification(title, message, type)
            .notify(project)
    }
}
