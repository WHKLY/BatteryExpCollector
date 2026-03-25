package com.example.batteryexpcollector.automation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.batteryexpcollector.automation.config.ExperimentConfigLoader
import com.example.batteryexpcollector.automation.orchestrator.ExperimentOrchestrator
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExperimentOrchestratorTest {

    @Test
    fun runDevExperiment() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testContext = instrumentation.context
        val targetContext = instrumentation.targetContext

        val config = ExperimentConfigLoader.loadFromAsset(
            assetContext = testContext,
            assetName = "experiment_config_dev.json"
        )

        val orchestrator = ExperimentOrchestrator(
            instrumentation = instrumentation,
            targetContext = targetContext
        )

        orchestrator.run(config)
    }
}