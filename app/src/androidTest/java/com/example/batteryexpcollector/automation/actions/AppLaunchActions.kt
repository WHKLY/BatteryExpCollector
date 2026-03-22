package com.example.batteryexpcollector.automation.actions

import android.content.Context
import android.content.Intent
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.batteryexpcollector.automation.orchestrator.ExecutionLogger

object AppLaunchActions {

    fun launchPackage(
        targetContext: Context,
        device: UiDevice,
        packageName: String,
        logger: ExecutionLogger,
        timeoutMs: Long = 10_000L
    ) {
        require(packageName.isNotBlank()) { "packageName cannot be blank" }

        logger.info("action=launch_app package=$packageName")

        val launchIntent = targetContext.packageManager.getLaunchIntentForPackage(packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ?: throw IllegalStateException("No launch intent for package: $packageName")

        targetContext.startActivity(launchIntent)
        device.waitForIdle()

        val appeared = device.wait(Until.hasObject(By.pkg(packageName)), timeoutMs)
        if (!appeared) {
            throw IllegalStateException("Package did not appear in time: $packageName")
        }
    }
}