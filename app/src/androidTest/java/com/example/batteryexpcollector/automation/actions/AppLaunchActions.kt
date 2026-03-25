package com.example.batteryexpcollector.automation.actions

import android.content.Context
import android.content.Intent
import androidx.test.uiautomator.UiDevice
import com.example.batteryexpcollector.automation.orchestrator.ExecutionLogger

object AppLaunchActions {

    fun launchPackage(
        targetContext: Context,
        packageName: String,
        logger: ExecutionLogger,
        timeoutMs: Long = 8_000L
    ) {
        require(packageName.isNotBlank()) { "packageName cannot be blank" }

        logger.info("action=launch_app package=$packageName")

        val launchIntent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ?: throw IllegalStateException("No launch intent for package: $packageName")

        targetContext.startActivity(launchIntent)

        Thread.sleep(1000L)
        logger.info("launch_app_assumed_visible package=$packageName timeoutMs=$timeoutMs")
    }

    @Suppress("UNUSED_PARAMETER")
    fun launchComponent(
        targetContext: Context,
        device: UiDevice,
        packageName: String,
        componentName: String,
        logger: ExecutionLogger,
        timeoutMs: Long = 8_000L
    ) {
        require(packageName.isNotBlank()) { "packageName cannot be blank" }
        require(componentName.isNotBlank()) { "componentName cannot be blank" }

        val normalizedComponent = normalizeComponentName(packageName, componentName)
        val componentSpec = "$packageName/$normalizedComponent"
        val shellCmd = "am start -n $componentSpec"

        logger.info(
            "action=launch_component package=$packageName component=$normalizedComponent"
        )
        logger.info("launch_component_shell=$shellCmd")

        val shellOutput = device.executeShellCommand(shellCmd)
            .replace("\n", " | ")
            .trim()

        logger.info(
            "launch_component_shell_output=" +
                    (if (shellOutput.isBlank()) "<empty>" else shellOutput)
        )

        Thread.sleep(1200L)
        logger.info(
            "launch_component_assumed_visible package=$packageName " +
                    "component=$normalizedComponent timeoutMs=$timeoutMs"
        )

        logForegroundSnapshot(device, logger)
    }

    private fun logForegroundSnapshot(
        device: UiDevice,
        logger: ExecutionLogger
    ) {
        val dump = device.executeShellCommand("dumpsys window windows")
        val snapshot = dump.lineSequence()
            .map { it.trim() }
            .filter {
                it.contains("mCurrentFocus") ||
                        it.contains("mFocusedApp")
            }
            .joinToString(" | ")
            .ifBlank { "<empty>" }

        logger.info("foreground_snapshot=$snapshot")
    }

    private fun normalizeComponentName(packageName: String, componentName: String): String {
        return when {
            componentName.startsWith(".") -> packageName + componentName
            componentName.contains("/") -> componentName.substringAfter("/")
            else -> componentName
        }
    }
}