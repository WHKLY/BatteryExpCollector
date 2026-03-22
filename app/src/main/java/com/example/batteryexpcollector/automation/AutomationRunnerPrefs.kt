package com.example.batteryexpcollector.automation

import android.content.Context

data class RunnerState(
    val isRunning: Boolean,
    val assetName: String,
    val experimentId: String,
    val currentPhaseIndex: Int
)

object AutomationRunnerPrefs {
    private const val PREFS_NAME = "automation_runner_prefs"

    private const val KEY_IS_RUNNING = "is_running"
    private const val KEY_ASSET_NAME = "asset_name"
    private const val KEY_EXPERIMENT_ID = "experiment_id"
    private const val KEY_CURRENT_PHASE_INDEX = "current_phase_index"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveState(
        context: Context,
        assetName: String,
        experimentId: String,
        currentPhaseIndex: Int,
        isRunning: Boolean = true
    ) {
        prefs(context).edit()
            .putBoolean(KEY_IS_RUNNING, isRunning)
            .putString(KEY_ASSET_NAME, assetName)
            .putString(KEY_EXPERIMENT_ID, experimentId)
            .putInt(KEY_CURRENT_PHASE_INDEX, currentPhaseIndex)
            .apply()
    }

    fun loadState(context: Context): RunnerState {
        val p = prefs(context)
        return RunnerState(
            isRunning = p.getBoolean(KEY_IS_RUNNING, false),
            assetName = p.getString(KEY_ASSET_NAME, "") ?: "",
            experimentId = p.getString(KEY_EXPERIMENT_ID, "") ?: "",
            currentPhaseIndex = p.getInt(KEY_CURRENT_PHASE_INDEX, -1)
        )
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }
}