package com.example.batteryexpcollector.automation.orchestrator

import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.batteryexpcollector.BatteryCollectService
import com.example.batteryexpcollector.automation.config.CollectorLaunchConfig

class CollectorBridge(
    private val targetContext: Context,
    private val logger: ExecutionLogger
) {

    fun startCollection(config: CollectorLaunchConfig) {
        val intent = Intent(targetContext, BatteryCollectService::class.java).apply {
            action = BatteryCollectService.ACTION_START_COLLECTION
            putExtra(BatteryCollectService.EXTRA_INTERVAL_MS, config.intervalMs)
            putExtra(BatteryCollectService.EXTRA_EXPERIMENT_NOTE, config.experimentNote)
            putExtra(BatteryCollectService.EXTRA_BRIGHTNESS_TARGET, config.brightnessTarget)
            putExtra(BatteryCollectService.EXTRA_ENFORCE_BRIGHTNESS, config.enforceBrightness)
            putExtra(BatteryCollectService.EXTRA_KEEP_SCREEN_ON, config.keepScreenOn)
        }

        logger.info(
            "start_collection intervalMs=${config.intervalMs}, " +
                    "brightnessTarget=${config.brightnessTarget}, note=${config.experimentNote}"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            targetContext.startForegroundService(intent)
        } else {
            targetContext.startService(intent)
        }
    }

    fun mark(marker: String) {
        if (marker.isBlank()) return

        val intent = Intent(targetContext, BatteryCollectService::class.java).apply {
            action = BatteryCollectService.ACTION_MARK_EVENT
            putExtra(BatteryCollectService.EXTRA_EVENT_MARKER, marker)
        }

        logger.info("marker=$marker")
        targetContext.startService(intent)
    }

    fun stopCollection() {
        val intent = Intent(targetContext, BatteryCollectService::class.java).apply {
            action = BatteryCollectService.ACTION_STOP_COLLECTION
        }

        logger.info("stop_collection")
        targetContext.startService(intent)
    }
}