package com.example.batteryexpcollector

import android.content.Context

const val DEFAULT_INTERVAL_MS = 1000L
const val DEFAULT_BRIGHTNESS_TARGET = 200

data class CollectionConfig(
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val experimentNote: String = "",
    val brightnessTarget: Int = DEFAULT_BRIGHTNESS_TARGET,
    val enforceBrightness: Boolean = true,
    val keepScreenOn: Boolean = true
)

data class CollectionSessionState(
    val isCollecting: Boolean,
    val currentFilePath: String,
    val lastFilePath: String,
    val startedAtMillis: Long,
    val config: CollectionConfig
)

object CollectionPrefs {
    private const val PREFS_NAME = "battery_collection_prefs"

    private const val KEY_IS_COLLECTING = "is_collecting"
    private const val KEY_CURRENT_FILE_PATH = "current_file_path"
    private const val KEY_LAST_FILE_PATH = "last_file_path"
    private const val KEY_STARTED_AT_MILLIS = "started_at_millis"

    private const val KEY_INTERVAL_MS = "interval_ms"
    private const val KEY_EXPERIMENT_NOTE = "experiment_note"
    private const val KEY_BRIGHTNESS_TARGET = "brightness_target"
    private const val KEY_ENFORCE_BRIGHTNESS = "enforce_brightness"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveConfig(context: Context, config: CollectionConfig) {
        val normalized = normalizeConfig(config)
        prefs(context).edit()
            .putLong(KEY_INTERVAL_MS, normalized.intervalMs)
            .putString(KEY_EXPERIMENT_NOTE, normalized.experimentNote)
            .putInt(KEY_BRIGHTNESS_TARGET, normalized.brightnessTarget)
            .putBoolean(KEY_ENFORCE_BRIGHTNESS, normalized.enforceBrightness)
            .putBoolean(KEY_KEEP_SCREEN_ON, normalized.keepScreenOn)
            .apply()
    }

    fun loadConfig(context: Context): CollectionConfig {
        val pref = prefs(context)
        return normalizeConfig(
            CollectionConfig(
                intervalMs = pref.getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS),
                experimentNote = pref.getString(KEY_EXPERIMENT_NOTE, "") ?: "",
                brightnessTarget = pref.getInt(KEY_BRIGHTNESS_TARGET, DEFAULT_BRIGHTNESS_TARGET),
                enforceBrightness = pref.getBoolean(KEY_ENFORCE_BRIGHTNESS, true),
                keepScreenOn = pref.getBoolean(KEY_KEEP_SCREEN_ON, true)
            )
        )
    }

    fun saveActiveSession(
        context: Context,
        filePath: String,
        startedAtMillis: Long,
        config: CollectionConfig
    ) {
        val normalized = normalizeConfig(config)
        saveConfig(context, normalized)

        prefs(context).edit()
            .putBoolean(KEY_IS_COLLECTING, true)
            .putString(KEY_CURRENT_FILE_PATH, filePath)
            .putString(KEY_LAST_FILE_PATH, filePath)
            .putLong(KEY_STARTED_AT_MILLIS, startedAtMillis)
            .apply()
    }

    fun clearActiveSession(context: Context, lastFilePath: String = "") {
        val pref = prefs(context)
        val fallbackLastPath = pref.getString(KEY_CURRENT_FILE_PATH, "")
            ?: pref.getString(KEY_LAST_FILE_PATH, "")
            ?: ""

        val finalLastPath = if (lastFilePath.isNotBlank()) lastFilePath else fallbackLastPath

        pref.edit()
            .putBoolean(KEY_IS_COLLECTING, false)
            .putString(KEY_CURRENT_FILE_PATH, "")
            .putString(KEY_LAST_FILE_PATH, finalLastPath)
            .putLong(KEY_STARTED_AT_MILLIS, 0L)
            .apply()
    }

    fun loadSessionState(context: Context): CollectionSessionState {
        val pref = prefs(context)
        return CollectionSessionState(
            isCollecting = pref.getBoolean(KEY_IS_COLLECTING, false),
            currentFilePath = pref.getString(KEY_CURRENT_FILE_PATH, "") ?: "",
            lastFilePath = pref.getString(KEY_LAST_FILE_PATH, "") ?: "",
            startedAtMillis = pref.getLong(KEY_STARTED_AT_MILLIS, 0L),
            config = loadConfig(context)
        )
    }

    private fun normalizeConfig(config: CollectionConfig): CollectionConfig {
        return config.copy(
            intervalMs = config.intervalMs.coerceIn(250L, 10_000L),
            experimentNote = config.experimentNote.trim(),
            brightnessTarget = config.brightnessTarget.coerceIn(0, 255)
        )
    }
}