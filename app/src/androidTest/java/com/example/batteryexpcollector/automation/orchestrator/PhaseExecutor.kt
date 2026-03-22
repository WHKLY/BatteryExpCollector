package com.example.batteryexpcollector.automation.orchestrator

import android.content.Context
import androidx.test.uiautomator.UiDevice
import com.example.batteryexpcollector.automation.actions.AppLaunchActions
import com.example.batteryexpcollector.automation.actions.DeviceActions
import com.example.batteryexpcollector.automation.config.ActionConfig
import com.example.batteryexpcollector.automation.config.PhaseConfig
import java.util.Locale

class PhaseExecutor(
    private val targetContext: Context,
    private val device: UiDevice,
    private val collectorBridge: CollectorBridge,
    private val logger: ExecutionLogger
) {

    fun execute(phase: PhaseConfig) {
        logger.info("phase_begin name=${phase.name} durationSec=${phase.durationSec}")

        if (phase.startMarker.isNotBlank()) {
            collectorBridge.mark(phase.startMarker)
        }

        val phaseStartMillis = System.currentTimeMillis()

        phase.actions.forEachIndexed { index, action ->
            logger.info("phase_action index=$index type=${action.type}")
            executeAction(action)
        }

        val elapsedMillis = System.currentTimeMillis() - phaseStartMillis
        val targetMillis = phase.durationSec.coerceAtLeast(0L) * 1000L

        if (targetMillis > elapsedMillis) {
            val remain = targetMillis - elapsedMillis
            logger.info("phase_wait_remaining name=${phase.name} remainMs=$remain")
            DeviceActions.waitMs(remain, logger)
        }

        if (phase.endMarker.isNotBlank()) {
            collectorBridge.mark(phase.endMarker)
        }

        logger.info("phase_end name=${phase.name}")
    }

    private fun executeAction(action: ActionConfig) {
        when (action.type.lowercase(Locale.ROOT)) {
            "wait" -> {
                DeviceActions.waitMs(action.durationMs, logger)
            }

            "marker" -> {
                require(action.markerName.isNotBlank()) { "marker action requires markerName" }
                collectorBridge.mark(action.markerName)
            }

            "press_home" -> {
                DeviceActions.pressHome(device, logger)
            }

            "press_back" -> {
                DeviceActions.pressBack(device, logger)
            }

            "launch_app" -> {
                require(action.packageName.isNotBlank()) { "launch_app requires packageName" }
                AppLaunchActions.launchPackage(
                    targetContext = targetContext,
                    device = device,
                    packageName = action.packageName,
                    logger = logger
                )
            }

            else -> {
                throw IllegalArgumentException("Unsupported action type: ${action.type}")
            }
        }
    }
}