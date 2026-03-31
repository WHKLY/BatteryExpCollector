package com.example.batteryexpcollector

import android.content.Context
import android.content.SharedPreferences

const val DEFAULT_INTERVAL_MS = 1000L
const val DEFAULT_BRIGHTNESS_TARGET = 200
const val DEFAULT_CPU_STRESS_DUTY_PERCENT = 85

data class CollectionConfig(
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val experimentNote: String = "",
    val brightnessTarget: Int = DEFAULT_BRIGHTNESS_TARGET,
    val enforceBrightness: Boolean = true,
    val keepScreenOn: Boolean = true,
    val highPowerEnabled: Boolean = false,
    val cpuStressThreads: Int = 0,
    val cpuStressDutyPercent: Int = DEFAULT_CPU_STRESS_DUTY_PERCENT
)

data class CollectionSessionState(
    val isCollecting: Boolean,
    val currentFilePath: String,
    val lastFilePath: String,
    val startedAtMillis: Long,
    val config: CollectionConfig,
    val currentFileUri: String
)

data class LatestSampleSnapshot(
    val timestampMillis: Long = 0L,
    val elapsedSec: Long = 0L,
    val socInteger: Int? = null,
    val batteryTempC: Float? = null,
    val currentUa: Long? = null,
    val brightness: Int? = null,
    val screenOn: Boolean? = null,
    val netType: String = "",
    val currentFilePath: String = ""
)

object CollectionPrefs {
    private const val PREFS_NAME = "battery_collection_prefs"

    private const val KEY_IS_COLLECTING = "is_collecting"
    private const val KEY_CURRENT_FILE_PATH = "current_file_path"
    private const val KEY_CURRENT_FILE_URI = "current_file_uri"
    private const val KEY_LAST_FILE_PATH = "last_file_path"
    private const val KEY_STARTED_AT_MILLIS = "started_at_millis"

    private const val KEY_INTERVAL_MS = "interval_ms"
    private const val KEY_EXPERIMENT_NOTE = "experiment_note"
    private const val KEY_BRIGHTNESS_TARGET = "brightness_target"
    private const val KEY_ENFORCE_BRIGHTNESS = "enforce_brightness"
    private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
    private const val KEY_HIGH_POWER_ENABLED = "high_power_enabled"
    private const val KEY_CPU_STRESS_THREADS = "cpu_stress_threads"
    private const val KEY_CPU_STRESS_DUTY_PERCENT = "cpu_stress_duty_percent"

    private const val KEY_LATEST_TIMESTAMP_MILLIS = "latest_timestamp_millis"
    private const val KEY_LATEST_ELAPSED_SEC = "latest_elapsed_sec"
    private const val KEY_LATEST_SOC_INTEGER = "latest_soc_integer"
    private const val KEY_LATEST_BATTERY_TEMP_C = "latest_battery_temp_c"
    private const val KEY_LATEST_CURRENT_UA = "latest_current_ua"
    private const val KEY_LATEST_BRIGHTNESS = "latest_brightness"
    private const val KEY_LATEST_SCREEN_ON = "latest_screen_on"
    private const val KEY_LATEST_NET_TYPE = "latest_net_type"
    private const val KEY_LATEST_CURRENT_FILE_PATH = "latest_current_file_path"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveConfig(context: Context, config: CollectionConfig) {
        val normalized = normalizeConfig(config)
        prefs(context).edit()
            .putLong(KEY_INTERVAL_MS, normalized.intervalMs)
            .putString(KEY_EXPERIMENT_NOTE, normalized.experimentNote)
            .putInt(KEY_BRIGHTNESS_TARGET, normalized.brightnessTarget)
            .putBoolean(KEY_ENFORCE_BRIGHTNESS, normalized.enforceBrightness)
            .putBoolean(KEY_KEEP_SCREEN_ON, normalized.keepScreenOn)
            .putBoolean(KEY_HIGH_POWER_ENABLED, normalized.highPowerEnabled)
            .putInt(KEY_CPU_STRESS_THREADS, normalized.cpuStressThreads)
            .putInt(KEY_CPU_STRESS_DUTY_PERCENT, normalized.cpuStressDutyPercent)
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
                keepScreenOn = pref.getBoolean(KEY_KEEP_SCREEN_ON, true),
                highPowerEnabled = pref.getBoolean(KEY_HIGH_POWER_ENABLED, false),
                cpuStressThreads = pref.getInt(KEY_CPU_STRESS_THREADS, 0),
                cpuStressDutyPercent = pref.getInt(
                    KEY_CPU_STRESS_DUTY_PERCENT,
                    DEFAULT_CPU_STRESS_DUTY_PERCENT
                )
            )
        )
    }

    fun saveActiveSession(
        context: Context,
        fileUri: String,
        fileDisplayPath: String,
        startedAtMillis: Long,
        config: CollectionConfig
    ) {
        val normalized = normalizeConfig(config)
        saveConfig(context, normalized)

        prefs(context).edit()
            .putBoolean(KEY_IS_COLLECTING, true)
            .putString(KEY_CURRENT_FILE_URI, fileUri)
            .putString(KEY_CURRENT_FILE_PATH, fileDisplayPath)
            .putString(KEY_LAST_FILE_PATH, fileDisplayPath)
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
            .remove(KEY_CURRENT_FILE_URI)
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
            config = loadConfig(context),
            currentFileUri = pref.getString(KEY_CURRENT_FILE_URI, "") ?: ""
        )
    }

    fun saveLatestSample(context: Context, snapshot: LatestSampleSnapshot) {
        prefs(context).edit()
            .putLong(KEY_LATEST_TIMESTAMP_MILLIS, snapshot.timestampMillis)
            .putLong(KEY_LATEST_ELAPSED_SEC, snapshot.elapsedSec)
            .putNullableInt(KEY_LATEST_SOC_INTEGER, snapshot.socInteger)
            .putNullableFloat(KEY_LATEST_BATTERY_TEMP_C, snapshot.batteryTempC)
            .putNullableLong(KEY_LATEST_CURRENT_UA, snapshot.currentUa)
            .putNullableInt(KEY_LATEST_BRIGHTNESS, snapshot.brightness)
            .putNullableBoolean(KEY_LATEST_SCREEN_ON, snapshot.screenOn)
            .putString(KEY_LATEST_NET_TYPE, snapshot.netType)
            .putString(KEY_LATEST_CURRENT_FILE_PATH, snapshot.currentFilePath)
            .apply()
    }

    fun loadLatestSample(context: Context): LatestSampleSnapshot {
        val pref = prefs(context)
        return LatestSampleSnapshot(
            timestampMillis = pref.getLong(KEY_LATEST_TIMESTAMP_MILLIS, 0L),
            elapsedSec = pref.getLong(KEY_LATEST_ELAPSED_SEC, 0L),
            socInteger = pref.getNullableInt(KEY_LATEST_SOC_INTEGER),
            batteryTempC = pref.getNullableFloat(KEY_LATEST_BATTERY_TEMP_C),
            currentUa = pref.getNullableLong(KEY_LATEST_CURRENT_UA),
            brightness = pref.getNullableInt(KEY_LATEST_BRIGHTNESS),
            screenOn = pref.getNullableBoolean(KEY_LATEST_SCREEN_ON),
            netType = pref.getString(KEY_LATEST_NET_TYPE, "") ?: "",
            currentFilePath = pref.getString(KEY_LATEST_CURRENT_FILE_PATH, "") ?: ""
        )
    }

    fun clearLatestSample(context: Context) {
        prefs(context).edit()
            .remove(KEY_LATEST_TIMESTAMP_MILLIS)
            .remove(KEY_LATEST_ELAPSED_SEC)
            .remove(KEY_LATEST_SOC_INTEGER)
            .remove(KEY_LATEST_BATTERY_TEMP_C)
            .remove(KEY_LATEST_CURRENT_UA)
            .remove(KEY_LATEST_BRIGHTNESS)
            .remove(KEY_LATEST_SCREEN_ON)
            .remove(KEY_LATEST_NET_TYPE)
            .remove(KEY_LATEST_CURRENT_FILE_PATH)
            .apply()
    }

    private fun normalizeConfig(config: CollectionConfig): CollectionConfig {
        return config.copy(
            intervalMs = config.intervalMs.coerceIn(250L, 10_000L),
            experimentNote = config.experimentNote.trim(),
            brightnessTarget = config.brightnessTarget.coerceIn(0, 255),
            cpuStressThreads = config.cpuStressThreads.coerceAtLeast(0),
            cpuStressDutyPercent = config.cpuStressDutyPercent.coerceIn(10, 100)
        )
    }

    private fun SharedPreferences.Editor.putNullableInt(key: String, value: Int?): SharedPreferences.Editor {
        if (value == null) remove(key) else putInt(key, value)
        return this
    }

    private fun SharedPreferences.Editor.putNullableLong(key: String, value: Long?): SharedPreferences.Editor {
        if (value == null) remove(key) else putLong(key, value)
        return this
    }

    private fun SharedPreferences.Editor.putNullableFloat(key: String, value: Float?): SharedPreferences.Editor {
        if (value == null) remove(key) else putFloat(key, value)
        return this
    }

    private fun SharedPreferences.Editor.putNullableBoolean(key: String, value: Boolean?): SharedPreferences.Editor {
        if (value == null) remove(key) else putBoolean(key, value)
        return this
    }

    private fun SharedPreferences.getNullableInt(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null

    private fun SharedPreferences.getNullableLong(key: String): Long? =
        if (contains(key)) getLong(key, 0L) else null

    private fun SharedPreferences.getNullableFloat(key: String): Float? =
        if (contains(key)) getFloat(key, 0f) else null

    private fun SharedPreferences.getNullableBoolean(key: String): Boolean? =
        if (contains(key)) getBoolean(key, false) else null
}
