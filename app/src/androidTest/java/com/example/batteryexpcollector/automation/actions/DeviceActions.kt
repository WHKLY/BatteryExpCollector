package com.example.batteryexpcollector.automation.actions

import androidx.test.uiautomator.UiDevice
import com.example.batteryexpcollector.automation.orchestrator.ExecutionLogger

object DeviceActions {

    fun pressHome(device: UiDevice, logger: ExecutionLogger) {
        logger.info("action=press_home")
        device.pressHome()
        device.waitForIdle()
    }

    fun pressBack(device: UiDevice, logger: ExecutionLogger) {
        logger.info("action=press_back")
        device.pressBack()
        device.waitForIdle()
    }

    fun waitMs(durationMs: Long, logger: ExecutionLogger) {
        logger.info("action=wait durationMs=$durationMs")
        Thread.sleep(durationMs.coerceAtLeast(0L))
    }
}