package com.example.batteryexpcollector.automation.config

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ExperimentConfig(
    val experimentId: String,
    val note: String,
    val delayStartSec: Long,
    val targetSocPercent: Int,
    val enforceSocCheck: Boolean,
    val collectorConfig: CollectorLaunchConfig,
    val phases: List<PhaseConfig>
)

data class CollectorLaunchConfig(
    val intervalMs: Long,
    val experimentNote: String,
    val brightnessTarget: Int,
    val enforceBrightness: Boolean,
    val keepScreenOn: Boolean
)

data class PhaseConfig(
    val name: String,
    val durationSec: Long,
    val startMarker: String,
    val endMarker: String,
    val actions: List<ActionConfig>
)

data class ActionConfig(
    val type: String,
    val durationMs: Long = 0L,
    val markerName: String = "",
    val packageName: String = ""
)

object ExperimentConfigLoader {

    fun loadFromAsset(assetContext: Context, assetName: String): ExperimentConfig {
        val raw = assetContext.assets.open(assetName).bufferedReader().use { it.readText() }
        return parse(raw)
    }

    fun parse(jsonText: String): ExperimentConfig {
        val root = JSONObject(jsonText)

        val collector = root.getJSONObject("collector")
        val phasesArray = root.getJSONArray("phases")

        return ExperimentConfig(
            experimentId = root.optString("experimentId", "dev_experiment"),
            note = root.optString("note", ""),
            delayStartSec = root.optLong("delayStartSec", 0L),
            targetSocPercent = root.optInt("targetSocPercent", 80),
            enforceSocCheck = root.optBoolean("enforceSocCheck", false),
            collectorConfig = CollectorLaunchConfig(
                intervalMs = collector.optLong("intervalMs", 1000L),
                experimentNote = collector.optString("experimentNote", ""),
                brightnessTarget = collector.optInt("brightnessTarget", 200),
                enforceBrightness = collector.optBoolean("enforceBrightness", true),
                keepScreenOn = collector.optBoolean("keepScreenOn", true)
            ),
            phases = parsePhases(phasesArray)
        )
    }

    private fun parsePhases(phasesArray: JSONArray): List<PhaseConfig> {
        val phases = mutableListOf<PhaseConfig>()
        for (i in 0 until phasesArray.length()) {
            val phaseObj = phasesArray.getJSONObject(i)
            phases += PhaseConfig(
                name = phaseObj.getString("name"),
                durationSec = phaseObj.optLong("durationSec", 0L),
                startMarker = phaseObj.optString("startMarker", ""),
                endMarker = phaseObj.optString("endMarker", ""),
                actions = parseActions(phaseObj.optJSONArray("actions") ?: JSONArray())
            )
        }
        return phases
    }

    private fun parseActions(actionsArray: JSONArray): List<ActionConfig> {
        val actions = mutableListOf<ActionConfig>()
        for (i in 0 until actionsArray.length()) {
            val actionObj = actionsArray.getJSONObject(i)
            actions += ActionConfig(
                type = actionObj.getString("type"),
                durationMs = actionObj.optLong("durationMs", 0L),
                markerName = actionObj.optString("markerName", ""),
                packageName = actionObj.optString("packageName", "")
            )
        }
        return actions
    }
}