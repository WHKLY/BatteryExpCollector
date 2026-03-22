package com.example.batteryexpcollector.automation.orchestrator

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.test.uiautomator.UiDevice
import com.example.batteryexpcollector.automation.config.ExperimentConfig

class ExperimentOrchestrator(
    instrumentation: Instrumentation,
    private val targetContext: Context
) {

    private val device: UiDevice = UiDevice.getInstance(instrumentation)

    fun run(config: ExperimentConfig) {
        val logger = ExecutionLogger(targetContext, config.experimentId)
        val collectorBridge = CollectorBridge(targetContext, logger)
        val phaseExecutor = PhaseExecutor(targetContext, device, collectorBridge, logger)

        var collectorStarted = false

        try {
            logger.info("experiment_id=${config.experimentId}")
            logger.info("note=${config.note}")
            logger.info("delayStartSec=${config.delayStartSec}")
            logger.info("targetSocPercent=${config.targetSocPercent}")
            logger.info("enforceSocCheck=${config.enforceSocCheck}")

            val currentSoc = getCurrentSocPercent()
            logger.info("current_soc=$currentSoc")

            if (config.enforceSocCheck && currentSoc != null && currentSoc != config.targetSocPercent) {
                throw IllegalStateException(
                    "Current SOC $currentSoc does not match target ${config.targetSocPercent}"
                )
            }

            if (config.delayStartSec > 0) {
                logger.info("delay_before_start=${config.delayStartSec}s")
                Thread.sleep(config.delayStartSec * 1000L)
            }

            collectorBridge.startCollection(config.collectorConfig)
            collectorStarted = true

            Thread.sleep(1500L)

            collectorBridge.mark("experiment_start")

            config.phases.forEach { phase ->
                phaseExecutor.execute(phase)
            }

            collectorBridge.mark("experiment_end")
            Thread.sleep(800L)
            collectorBridge.stopCollection()
            collectorStarted = false

            logger.info("experiment_completed_successfully")
        } catch (t: Throwable) {
            logger.error("experiment_failed=${t.javaClass.simpleName}: ${t.message}")
            if (collectorStarted) {
                runCatching { collectorBridge.mark("experiment_abort") }
                runCatching { Thread.sleep(500L) }
                runCatching { collectorBridge.stopCollection() }
            }
            throw t
        } finally {
            logger.info("orchestrator_finished")
            logger.close()
        }
    }

    private fun getCurrentSocPercent(): Int? {
        val intent = targetContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return null

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null

        return (level * 100f / scale.toFloat()).toInt()
    }
}