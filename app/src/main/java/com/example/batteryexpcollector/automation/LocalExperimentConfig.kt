package com.example.batteryexpcollector.automation

import android.content.Context
import com.example.batteryexpcollector.CollectionConfig
import org.json.JSONArray
import org.json.JSONObject

data class LocalExperimentConfig(
    val experimentId: String,
    val note: String,
    val delayStartSec: Long,
    val collector: LocalCollectorConfig,
    val phases: List<LocalPhaseConfig>
)

data class LocalCollectorConfig(
    val intervalMs: Long,
    val experimentNote: String,
    val brightnessTarget: Int,
    val enforceBrightness: Boolean,
    val keepScreenOn: Boolean
) {
    fun toCollectionConfig(): CollectionConfig {
        return CollectionConfig(
            intervalMs = intervalMs,
            experimentNote = experimentNote,
            brightnessTarget = brightnessTarget,
            enforceBrightness = enforceBrightness,
            keepScreenOn = keepScreenOn
        )
    }
}

data class LocalPhaseConfig(
    val name: String,
    val durationSec: Long,
    val startMarker: String,
    val endMarker: String,
    val actions: List<LocalActionConfig>
)

data class LocalActionConfig(
    val type: String,
    val durationMs: Long = 0L,
    val markerName: String = "",
    val label: String = "",
    val expectedPackage: String = ""
)

object LocalExperimentConfigLoader {

    fun loadFromAsset(context: Context, assetName: String): LocalExperimentConfig {
        val raw = context.assets.open(assetName).bufferedReader().use { it.readText() }
        return parse(raw)
    }

    fun parse(jsonText: String): LocalExperimentConfig {
        val root = JSONObject(jsonText)
        val collectorObj = root.getJSONObject("collector")
        val phasesArray = root.getJSONArray("phases")

        return LocalExperimentConfig(
            experimentId = root.optString("experimentId", "local_experiment"),
            note = root.optString("note", ""),
            delayStartSec = root.optLong("delayStartSec", 0L),
            collector = LocalCollectorConfig(
                intervalMs = collectorObj.optLong("intervalMs", 1000L),
                experimentNote = collectorObj.optString("experimentNote", ""),
                brightnessTarget = collectorObj.optInt("brightnessTarget", 200),
                enforceBrightness = collectorObj.optBoolean("enforceBrightness", true),
                keepScreenOn = collectorObj.optBoolean("keepScreenOn", true)
            ),
            phases = parsePhases(phasesArray)
        )
    }

    private fun parsePhases(array: JSONArray): List<LocalPhaseConfig> {
        val phases = mutableListOf<LocalPhaseConfig>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            phases += LocalPhaseConfig(
                name = obj.getString("name"),
                durationSec = obj.optLong("durationSec", 0L),
                startMarker = obj.optString("startMarker", ""),
                endMarker = obj.optString("endMarker", ""),
                actions = parseActions(obj.optJSONArray("actions") ?: JSONArray())
            )
        }
        return phases
    }

    private fun parseActions(array: JSONArray): List<LocalActionConfig> {
        val actions = mutableListOf<LocalActionConfig>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            actions += LocalActionConfig(
                type = obj.getString("type"),
                durationMs = obj.optLong("durationMs", 0L),
                markerName = obj.optString("markerName", ""),
                label = obj.optString("label", ""),
                expectedPackage = obj.optString("expectedPackage", "")
            )
        }
        return actions
    }
}