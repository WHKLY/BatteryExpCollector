package com.example.batteryexpcollector

import android.content.Context
import android.content.SharedPreferences

const val DEFAULT_INTERVAL_MS = 1000L
const val DEFAULT_BRIGHTNESS_TARGET = 200
const val DEFAULT_CPU_STRESS_DUTY_PERCENT = 85
const val DEFAULT_NETWORK_MAX_RETRY_COUNT = 3
const val DEFAULT_NETWORK_UPLOAD_CHUNK_BYTES = 262_144
const val DEFAULT_NETWORK_BURST_INTERVAL_MS = 250L
const val DEFAULT_NETWORK_MULTI_CONCURRENCY = 4

const val NETWORK_SCENARIO_DOWNLOAD_LOOP = "download_loop"
const val NETWORK_SCENARIO_UPLOAD_LOOP = "upload_loop"
const val NETWORK_SCENARIO_SMALL_REQUEST_BURST = "small_request_burst"

const val NETWORK_CONNECTION_SINGLE = "single"
const val NETWORK_CONNECTION_MULTI = "multi"

data class CollectionConfig(
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val experimentNote: String = "",
    val brightnessTarget: Int = DEFAULT_BRIGHTNESS_TARGET,
    val enforceBrightness: Boolean = true,
    val keepScreenOn: Boolean = true,
    val highPowerEnabled: Boolean = false,
    val cpuStressThreads: Int = 0,
    val cpuStressDutyPercent: Int = DEFAULT_CPU_STRESS_DUTY_PERCENT,
    val networkLoadEnabled: Boolean = false,
    val networkScenario: String = NETWORK_SCENARIO_DOWNLOAD_LOOP,
    val networkConnectionMode: String = NETWORK_CONNECTION_SINGLE,
    val networkConcurrency: Int = 1,
    val networkRetryEnabled: Boolean = true,
    val networkMaxRetryCount: Int = DEFAULT_NETWORK_MAX_RETRY_COUNT,
    val networkDownloadUrl: String = "",
    val networkUploadUrl: String = "",
    val networkBurstUrl: String = "",
    val networkUploadChunkBytes: Int = DEFAULT_NETWORK_UPLOAD_CHUNK_BYTES,
    val networkBurstIntervalMs: Long = DEFAULT_NETWORK_BURST_INTERVAL_MS
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
    private const val KEY_NETWORK_LOAD_ENABLED = "network_load_enabled"
    private const val KEY_NETWORK_SCENARIO = "network_scenario"
    private const val KEY_NETWORK_CONNECTION_MODE = "network_connection_mode"
    private const val KEY_NETWORK_CONCURRENCY = "network_concurrency"
    private const val KEY_NETWORK_RETRY_ENABLED = "network_retry_enabled"
    private const val KEY_NETWORK_MAX_RETRY_COUNT = "network_max_retry_count"
    private const val KEY_NETWORK_DOWNLOAD_URL = "network_download_url"
    private const val KEY_NETWORK_UPLOAD_URL = "network_upload_url"
    private const val KEY_NETWORK_BURST_URL = "network_burst_url"
    private const val KEY_NETWORK_UPLOAD_CHUNK_BYTES = "network_upload_chunk_bytes"
    private const val KEY_NETWORK_BURST_INTERVAL_MS = "network_burst_interval_ms"

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
            .putBoolean(KEY_NETWORK_LOAD_ENABLED, normalized.networkLoadEnabled)
            .putString(KEY_NETWORK_SCENARIO, normalized.networkScenario)
            .putString(KEY_NETWORK_CONNECTION_MODE, normalized.networkConnectionMode)
            .putInt(KEY_NETWORK_CONCURRENCY, normalized.networkConcurrency)
            .putBoolean(KEY_NETWORK_RETRY_ENABLED, normalized.networkRetryEnabled)
            .putInt(KEY_NETWORK_MAX_RETRY_COUNT, normalized.networkMaxRetryCount)
            .putString(KEY_NETWORK_DOWNLOAD_URL, normalized.networkDownloadUrl)
            .putString(KEY_NETWORK_UPLOAD_URL, normalized.networkUploadUrl)
            .putString(KEY_NETWORK_BURST_URL, normalized.networkBurstUrl)
            .putInt(KEY_NETWORK_UPLOAD_CHUNK_BYTES, normalized.networkUploadChunkBytes)
            .putLong(KEY_NETWORK_BURST_INTERVAL_MS, normalized.networkBurstIntervalMs)
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
                ),
                networkLoadEnabled = pref.getBoolean(KEY_NETWORK_LOAD_ENABLED, false),
                networkScenario = pref.getString(
                    KEY_NETWORK_SCENARIO,
                    NETWORK_SCENARIO_DOWNLOAD_LOOP
                ) ?: NETWORK_SCENARIO_DOWNLOAD_LOOP,
                networkConnectionMode = pref.getString(
                    KEY_NETWORK_CONNECTION_MODE,
                    NETWORK_CONNECTION_SINGLE
                ) ?: NETWORK_CONNECTION_SINGLE,
                networkConcurrency = pref.getInt(KEY_NETWORK_CONCURRENCY, 1),
                networkRetryEnabled = pref.getBoolean(KEY_NETWORK_RETRY_ENABLED, true),
                networkMaxRetryCount = pref.getInt(
                    KEY_NETWORK_MAX_RETRY_COUNT,
                    DEFAULT_NETWORK_MAX_RETRY_COUNT
                ),
                networkDownloadUrl = pref.getString(KEY_NETWORK_DOWNLOAD_URL, "") ?: "",
                networkUploadUrl = pref.getString(KEY_NETWORK_UPLOAD_URL, "") ?: "",
                networkBurstUrl = pref.getString(KEY_NETWORK_BURST_URL, "") ?: "",
                networkUploadChunkBytes = pref.getInt(
                    KEY_NETWORK_UPLOAD_CHUNK_BYTES,
                    DEFAULT_NETWORK_UPLOAD_CHUNK_BYTES
                ),
                networkBurstIntervalMs = pref.getLong(
                    KEY_NETWORK_BURST_INTERVAL_MS,
                    DEFAULT_NETWORK_BURST_INTERVAL_MS
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
            cpuStressDutyPercent = config.cpuStressDutyPercent.coerceIn(10, 100),
            networkScenario = when (config.networkScenario) {
                NETWORK_SCENARIO_DOWNLOAD_LOOP,
                NETWORK_SCENARIO_UPLOAD_LOOP,
                NETWORK_SCENARIO_SMALL_REQUEST_BURST -> config.networkScenario
                else -> NETWORK_SCENARIO_DOWNLOAD_LOOP
            },
            networkConnectionMode = when (config.networkConnectionMode) {
                NETWORK_CONNECTION_SINGLE,
                NETWORK_CONNECTION_MULTI -> config.networkConnectionMode
                else -> NETWORK_CONNECTION_SINGLE
            },
            networkConcurrency = config.networkConcurrency.coerceIn(1, 8),
            networkMaxRetryCount = config.networkMaxRetryCount.coerceIn(0, 10),
            networkDownloadUrl = config.networkDownloadUrl.trim(),
            networkUploadUrl = config.networkUploadUrl.trim(),
            networkBurstUrl = config.networkBurstUrl.trim(),
            networkUploadChunkBytes = config.networkUploadChunkBytes.coerceIn(1_024, 5_242_880),
            networkBurstIntervalMs = config.networkBurstIntervalMs.coerceIn(50L, 60_000L)
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
