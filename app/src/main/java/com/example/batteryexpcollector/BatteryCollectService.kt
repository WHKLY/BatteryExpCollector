package com.example.batteryexpcollector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class BatteryCollectService : Service() {

    companion object {
        const val ACTION_START_COLLECTION =
            "com.example.batteryexpcollector.action.START_COLLECTION"
        const val ACTION_STOP_COLLECTION =
            "com.example.batteryexpcollector.action.STOP_COLLECTION"
        const val ACTION_MARK_EVENT =
            "com.example.batteryexpcollector.action.MARK_EVENT"

        const val EXTRA_INTERVAL_MS = "extra_interval_ms"
        const val EXTRA_EXPERIMENT_NOTE = "extra_experiment_note"
        const val EXTRA_BRIGHTNESS_TARGET = "extra_brightness_target"
        const val EXTRA_ENFORCE_BRIGHTNESS = "extra_enforce_brightness"
        const val EXTRA_KEEP_SCREEN_ON = "extra_keep_screen_on"
        const val EXTRA_HIGH_POWER_ENABLED = "extra_high_power_enabled"
        const val EXTRA_CPU_STRESS_THREADS = "extra_cpu_stress_threads"
        const val EXTRA_CPU_STRESS_DUTY_PERCENT = "extra_cpu_stress_duty_percent"
        const val EXTRA_EVENT_MARKER = "extra_event_marker"
    }

    private val tag = "BatteryExpCollector"
    private val channelId = "BatteryCollectChannel"
    private val notificationId = 1001

    private var sampleThread: HandlerThread? = null
    private var sampleHandler: Handler? = null

    private var currentCsvUri: Uri? = null
    private var currentCsvDisplayName: String = ""
    private var currentCsvLogicalPath: String = ""
    private var startTimeMillis: Long = 0L
    private var isCollectingSession = false
    private var currentConfig: CollectionConfig = CollectionConfig()
    private var pendingEventMarker: String? = null
    private val cpuStressController = CpuStressController()

    private var sampleCount: Long = 0L
    private var screenOffObserved: Boolean = false
    private var chargingObserved: Boolean = false

    private var batteryReceiver: BroadcastReceiver? = null
    private var batteryManager: BatteryManager? = null
    private var connectivityManager: ConnectivityManager? = null

    private var lastTxBytes: Long? = null
    private var lastRxBytes: Long? = null
    private var lastTrafficTimestamp: Long? = null

    private var batteryLevel: Int? = null
    private var batteryVoltageMv: Int? = null
    private var batteryTemperatureC: Float? = null
    private var batteryCurrentUa: Long? = null
    private var batteryChargeCounterUah: Long? = null
    private var batteryStatus: Int? = null
    private var batteryPlugged: Int? = null
    private var batteryHealth: Int? = null
    private var batteryPresent: Boolean? = null
    private var batteryScale: Int? = null
    private var batteryPctFloat: Float? = null

    private var batteryIntentReadOk = false
    private var batteryPropertyReadOk = false
    private var brightnessSetOk = false

    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (!isCollectingSession) return
            try {
                collectAndLogData()
            } catch (e: Exception) {
                Log.e(tag, "采样失败", e)
            } finally {
                if (isCollectingSession) {
                    sampleHandler?.postDelayed(this, currentConfig.intervalMs)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        sampleThread = HandlerThread("battery-collect-thread").apply { start() }
        sampleHandler = Handler(sampleThread!!.looper)

        createNotificationChannel()
        startForeground(notificationId, buildNotification("采集器待命中"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_COLLECTION
        try {
            when (action) {
                ACTION_START_COLLECTION -> {
                    val extras = intent?.extras
                    if (extras == null || extras.isEmpty) {
                        if (!restoreSessionIfNeeded()) {
                            stopSelf()
                        }
                    } else {
                        startNewSession(parseConfigFromIntent(intent))
                    }
                }

                ACTION_STOP_COLLECTION -> {
                    stopCurrentSessionAndSelf()
                }

                ACTION_MARK_EVENT -> {
                    val marker = intent?.getStringExtra(EXTRA_EVENT_MARKER)?.trim().orEmpty()
                    if (marker.isNotBlank()) {
                        if (!isCollectingSession) {
                            restoreSessionIfNeeded()
                        }
                        if (isCollectingSession) {
                            pendingEventMarker = marker
                            updateNotification("采集中：${currentCsvDisplayName.ifBlank { "未知文件" }} | 已收到标记")
                        }
                    }
                }

                else -> {
                    if (!restoreSessionIfNeeded()) {
                        stopSelf()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "onStartCommand 处理失败", e)
            stopCurrentSessionAndSelf(clearSession = false)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSamplingLoop()
        cpuStressController.stop()
        unregisterBatteryReceiverIfNeeded()

        sampleThread?.quitSafely()
        sampleThread = null
        sampleHandler = null

        super.onDestroy()
    }

    private fun parseConfigFromIntent(intent: Intent): CollectionConfig {
        return CollectionConfig(
            intervalMs = intent.getLongExtra(EXTRA_INTERVAL_MS, DEFAULT_INTERVAL_MS),
            experimentNote = intent.getStringExtra(EXTRA_EXPERIMENT_NOTE).orEmpty(),
            brightnessTarget = intent.getIntExtra(
                EXTRA_BRIGHTNESS_TARGET,
                DEFAULT_BRIGHTNESS_TARGET
            ),
            enforceBrightness = intent.getBooleanExtra(EXTRA_ENFORCE_BRIGHTNESS, true),
            keepScreenOn = intent.getBooleanExtra(EXTRA_KEEP_SCREEN_ON, true),
            highPowerEnabled = intent.getBooleanExtra(EXTRA_HIGH_POWER_ENABLED, false),
            cpuStressThreads = intent.getIntExtra(EXTRA_CPU_STRESS_THREADS, 0),
            cpuStressDutyPercent = intent.getIntExtra(
                EXTRA_CPU_STRESS_DUTY_PERCENT,
                DEFAULT_CPU_STRESS_DUTY_PERCENT
            )
        )
    }

    private fun startNewSession(config: CollectionConfig) {
        stopSamplingLoop()
        cpuStressController.stop()

        currentConfig = CollectionPrefs.loadConfig(this).copy(
            intervalMs = config.intervalMs.coerceIn(250L, 10_000L),
            experimentNote = config.experimentNote.trim(),
            brightnessTarget = config.brightnessTarget.coerceIn(0, 255),
            enforceBrightness = config.enforceBrightness,
            keepScreenOn = config.keepScreenOn,
            highPowerEnabled = config.highPowerEnabled,
            cpuStressThreads = config.cpuStressThreads.coerceAtLeast(0),
            cpuStressDutyPercent = config.cpuStressDutyPercent.coerceIn(10, 100)
        )

        startTimeMillis = System.currentTimeMillis()
        val storedFile = createSessionOutput(currentConfig)
        currentCsvUri = storedFile.uri
        currentCsvDisplayName = storedFile.displayName
        currentCsvLogicalPath = storedFile.logicalPath

        writeMetadataAndHeaderIfNeeded(currentCsvUri!!, currentConfig, startTimeMillis)

        CollectionPrefs.saveActiveSession(
            context = this,
            fileUri = currentCsvUri!!.toString(),
            fileDisplayPath = currentCsvLogicalPath,
            startedAtMillis = startTimeMillis,
            config = currentConfig
        )
        CollectionPrefs.clearLatestSample(this)

        sampleCount = 0L
        screenOffObserved = false
        chargingObserved = false

        ensureBatteryReceiverRegistered()
        initializeTrafficBaseline()

        brightnessSetOk = if (currentConfig.enforceBrightness) {
            applyBrightnessConfiguration(currentConfig.brightnessTarget)
        } else {
            false
        }

        if (currentConfig.highPowerEnabled) {
            cpuStressController.start(
                threadCount = currentConfig.cpuStressThreads,
                dutyPercent = currentConfig.cpuStressDutyPercent
            )
        }

        pendingEventMarker = if (currentConfig.highPowerEnabled) {
            "high_power_mode_start"
        } else {
            null
        }
        isCollectingSession = true

        updateNotification("采集中：$currentCsvDisplayName")
        startSamplingLoop()
    }

    private fun restoreSessionIfNeeded(): Boolean {
        val session = CollectionPrefs.loadSessionState(this)
        if (!session.isCollecting || session.currentFileUri.isBlank()) {
            return false
        }

        currentConfig = session.config
        startTimeMillis = if (session.startedAtMillis > 0L) {
            session.startedAtMillis
        } else {
            System.currentTimeMillis()
        }

        currentCsvUri = Uri.parse(session.currentFileUri)
        currentCsvLogicalPath = session.currentFilePath.ifBlank { session.currentFileUri }
        currentCsvDisplayName = currentCsvLogicalPath.substringAfterLast('/')

        writeMetadataAndHeaderIfNeeded(currentCsvUri!!, currentConfig, startTimeMillis)

        sampleCount = countExistingDataRows(currentCsvUri!!)
        screenOffObserved = false
        chargingObserved = false

        ensureBatteryReceiverRegistered()
        initializeTrafficBaseline()

        brightnessSetOk = if (currentConfig.enforceBrightness) {
            applyBrightnessConfiguration(currentConfig.brightnessTarget)
        } else {
            false
        }

        if (currentConfig.highPowerEnabled) {
            cpuStressController.start(
                threadCount = currentConfig.cpuStressThreads,
                dutyPercent = currentConfig.cpuStressDutyPercent
            )
        } else {
            cpuStressController.stop()
        }

        pendingEventMarker = null
        isCollectingSession = true

        updateNotification("恢复采集中：$currentCsvDisplayName")
        startSamplingLoop()
        return true
    }

    private fun stopCurrentSessionAndSelf(clearSession: Boolean = true) {
        val csvUri = currentCsvUri
        val csvDisplayName = currentCsvDisplayName
        val csvLogicalPath = currentCsvLogicalPath
        val stopTimeMillis = System.currentTimeMillis()

        if (csvUri != null) {
            writeSessionSummaryFile(
                csvUri = csvUri,
                csvDisplayName = csvDisplayName,
                csvLogicalPath = csvLogicalPath,
                config = currentConfig,
                startedAtMillis = startTimeMillis,
                endedAtMillis = stopTimeMillis,
                sampleCount = sampleCount,
                screenOffObserved = screenOffObserved,
                chargingObserved = chargingObserved
            )
        }

        stopSamplingLoop()
        cpuStressController.stop()

        isCollectingSession = false
        pendingEventMarker = null
        currentCsvUri = null
        currentCsvDisplayName = ""
        currentCsvLogicalPath = ""

        if (clearSession) {
            CollectionPrefs.clearActiveSession(this, csvLogicalPath)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startSamplingLoop() {
        sampleHandler?.removeCallbacks(sampleRunnable)
        sampleHandler?.post(sampleRunnable)
    }

    private fun stopSamplingLoop() {
        sampleHandler?.removeCallbacks(sampleRunnable)
    }

    private fun ensureBatteryReceiverRegistered() {
        if (batteryReceiver != null) return

        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    updateBatterySnapshot(intent)
                }
            }
        }

        val stickyIntent = registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        stickyIntent?.let { updateBatterySnapshot(it) }
    }

    private fun unregisterBatteryReceiverIfNeeded() {
        batteryReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        batteryReceiver = null
    }

    private fun updateBatterySnapshot(intent: Intent) {
        batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1).takeIf { it >= 0 }
        batteryVoltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it >= 0 }

        val tempRaw = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        batteryTemperatureC = if (tempRaw == Int.MIN_VALUE) null else tempRaw / 10.0f

        batteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1).takeIf { it >= 0 }
        batteryPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1).takeIf { it >= 0 }
        batteryHealth = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1).takeIf { it >= 0 }
        batteryPresent = if (intent.hasExtra(BatteryManager.EXTRA_PRESENT)) {
            intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
        } else {
            null
        }
        batteryScale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1).takeIf { it > 0 }

        batteryPctFloat = if (batteryLevel != null && batteryScale != null && batteryScale!! > 0) {
            batteryLevel!! * 100f / batteryScale!!.toFloat()
        } else {
            null
        }

        batteryIntentReadOk = batteryLevel != null &&
                batteryVoltageMv != null &&
                batteryTemperatureC != null
    }

    private fun refreshBatteryPropertySnapshot() {
        batteryCurrentUa = getBatteryLongPropertyOrNull(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        batteryChargeCounterUah =
            getBatteryLongPropertyOrNull(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)

        batteryPropertyReadOk = batteryCurrentUa != null || batteryChargeCounterUah != null
    }

    private fun getBatteryLongPropertyOrNull(propertyId: Int): Long? {
        return try {
            val value = batteryManager?.getLongProperty(propertyId) ?: return null
            if (value == Long.MIN_VALUE) null else value
        } catch (_: Exception) {
            null
        }
    }

    private fun initializeTrafficBaseline() {
        val currentTx = TrafficStats.getTotalTxBytes()
        val currentRx = TrafficStats.getTotalRxBytes()

        lastTxBytes = if (currentTx == TrafficStats.UNSUPPORTED.toLong()) null else currentTx
        lastRxBytes = if (currentRx == TrafficStats.UNSUPPORTED.toLong()) null else currentRx
        lastTrafficTimestamp = System.currentTimeMillis()
    }

    private fun collectAndLogData() {
        val now = System.currentTimeMillis()
        refreshBatteryPropertySnapshot()

        if (currentConfig.enforceBrightness) {
            brightnessSetOk = applyBrightnessConfiguration(currentConfig.brightnessTarget)
        }

        val brightnessSnapshot = readBrightnessSnapshot()
        val screenOnSnapshot = readScreenOnSnapshot()
        val cpuFreqSnapshot = readCpuFreqSnapshot()
        val networkSnapshot = readNetworkSnapshot(now)

        val eventMarker = pendingEventMarker
        pendingEventMarker = null

        val dataLine = listOf(
            csvField(now),
            csvField((now - startTimeMillis) / 1000L),

            csvField(batteryLevel),
            csvField(batteryPctFloat),
            csvField(batteryVoltageMv),
            csvField(batteryCurrentUa),
            csvField(batteryTemperatureC),
            csvField(batteryChargeCounterUah),

            csvField(batteryStatusToLabel(batteryStatus)),
            csvField(pluggedTypeToLabel(batteryPlugged)),
            csvField(batteryHealthToLabel(batteryHealth)),
            csvField(booleanFlag(batteryPresent)),
            csvField(batteryScale),

            csvField(currentConfig.brightnessTarget),
            csvField(brightnessSnapshot.value),
            csvField(booleanFlag(if (currentConfig.enforceBrightness) brightnessSetOk else null)),
            csvField(booleanFlag(brightnessSnapshot.readOk)),
            csvField(booleanFlag(screenOnSnapshot.value)),
            csvField(booleanFlag(screenOnSnapshot.readOk)),

            csvField(cpuFreqSnapshot.value),
            csvField(booleanFlag(cpuFreqSnapshot.readOk)),

            csvField(networkSnapshot.netType),
            csvField(networkSnapshot.txBytesTotal),
            csvField(networkSnapshot.rxBytesTotal),
            csvField(networkSnapshot.txRateBps),
            csvField(networkSnapshot.rxRateBps),
            csvField(booleanFlag(networkSnapshot.readOk)),

            csvField(booleanFlag(batteryIntentReadOk)),
            csvField(booleanFlag(batteryPropertyReadOk)),

            csvField(booleanFlag(currentConfig.highPowerEnabled)),
            csvField(currentConfig.cpuStressThreads),
            csvField(currentConfig.cpuStressDutyPercent),
            csvField(currentConfig.experimentNote),
            csvField(eventMarker)
        ).joinToString(",")

        writeLine(dataLine)

        sampleCount += 1
        if (screenOnSnapshot.value == false) {
            screenOffObserved = true
        }
        if (isChargingOrPlugged()) {
            chargingObserved = true
        }

        CollectionPrefs.saveLatestSample(
            this,
            LatestSampleSnapshot(
                timestampMillis = now,
                elapsedSec = (now - startTimeMillis) / 1000L,
                socInteger = batteryLevel,
                batteryTempC = batteryTemperatureC,
                currentUa = batteryCurrentUa,
                brightness = brightnessSnapshot.value,
                screenOn = screenOnSnapshot.value,
                netType = networkSnapshot.netType,
                currentFilePath = currentCsvLogicalPath
            )
        )

        Log.d(tag, dataLine)
    }

    private fun isChargingOrPlugged(): Boolean {
        return batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                batteryStatus == BatteryManager.BATTERY_STATUS_FULL ||
                (batteryPlugged ?: 0) > 0
    }

    private fun readBrightnessSnapshot(): IntSnapshot {
        return try {
            IntSnapshot(
                value = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS),
                readOk = true
            )
        } catch (_: Exception) {
            IntSnapshot(value = null, readOk = false)
        }
    }

    private fun applyBrightnessConfiguration(targetBrightness: Int): Boolean {
        if (!Settings.System.canWrite(this)) return false

        return try {
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )

            val current = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )

            if (current != targetBrightness) {
                Settings.System.putInt(
                    contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    targetBrightness
                )
            }

            val after = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            after == targetBrightness
        } catch (_: Exception) {
            false
        }
    }

    private fun readScreenOnSnapshot(): BooleanSnapshot {
        return try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val value = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                powerManager.isInteractive
            } else {
                @Suppress("DEPRECATION")
                powerManager.isScreenOn
            }
            BooleanSnapshot(value = value, readOk = true)
        } catch (_: Exception) {
            BooleanSnapshot(value = null, readOk = false)
        }
    }

    private fun readCpuFreqSnapshot(): LongSnapshot {
        val candidatePaths = listOf(
            "/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq",
            "/sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq"
        )

        for (path in candidatePaths) {
            try {
                RandomAccessFile(path, "r").use { reader ->
                    val raw = reader.readLine()?.trim().orEmpty()
                    val value = raw.toLongOrNull()
                    if (value != null) {
                        return LongSnapshot(value = value, readOk = true)
                    }
                }
            } catch (_: Exception) {
            }
        }

        return LongSnapshot(value = null, readOk = false)
    }

    private fun readNetworkSnapshot(now: Long): NetworkSnapshot {
        val netType = readNetTypeLabel()

        return try {
            val currentTx = TrafficStats.getTotalTxBytes()
            val currentRx = TrafficStats.getTotalRxBytes()

            if (currentTx == TrafficStats.UNSUPPORTED.toLong() ||
                currentRx == TrafficStats.UNSUPPORTED.toLong()
            ) {
                NetworkSnapshot(
                    netType = netType,
                    txBytesTotal = null,
                    rxBytesTotal = null,
                    txRateBps = null,
                    rxRateBps = null,
                    readOk = false
                )
            } else {
                val previousTx = lastTxBytes
                val previousRx = lastRxBytes
                val previousTs = lastTrafficTimestamp

                val txRate = if (previousTx != null && previousTs != null && now > previousTs) {
                    ((currentTx - previousTx) * 1000L / (now - previousTs)).coerceAtLeast(0L)
                } else {
                    0L
                }

                val rxRate = if (previousRx != null && previousTs != null && now > previousTs) {
                    ((currentRx - previousRx) * 1000L / (now - previousTs)).coerceAtLeast(0L)
                } else {
                    0L
                }

                lastTxBytes = currentTx
                lastRxBytes = currentRx
                lastTrafficTimestamp = now

                NetworkSnapshot(
                    netType = netType,
                    txBytesTotal = currentTx,
                    rxBytesTotal = currentRx,
                    txRateBps = txRate,
                    rxRateBps = rxRate,
                    readOk = true
                )
            }
        } catch (_: Exception) {
            NetworkSnapshot(
                netType = netType,
                txBytesTotal = null,
                rxBytesTotal = null,
                txRateBps = null,
                rxRateBps = null,
                readOk = false
            )
        }
    }

    private fun readNetTypeLabel(): String {
        return try {
            val network = connectivityManager?.activeNetwork ?: return "NoNet"
            val caps = connectivityManager?.getNetworkCapabilities(network) ?: return "NoNet"

            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
                else -> "Other"
            }
        } catch (_: Exception) {
            "Error"
        }
    }

    private fun createSessionOutput(config: CollectionConfig): SharedResultsStore.StoredTextFile {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val notePart = sanitizeFilePart(config.experimentNote).takeIf { it.isNotBlank() }
        val modelPart = sanitizeFilePart(Build.MODEL).ifBlank { "device" }

        val fileName = buildString {
            append(timestamp)
            if (!notePart.isNullOrBlank()) {
                append("_")
                append(notePart.take(32))
            }
            append("_")
            append(modelPart.take(32))
            append(".csv")
        }

        return SharedResultsStore.createTextFile(
            context = this,
            displayName = fileName,
            mimeType = "text/csv"
        )
    }

    private fun writeMetadataAndHeaderIfNeeded(
        uri: Uri,
        config: CollectionConfig,
        sessionStartMillis: Long
    ) {
        if (SharedResultsStore.getFileSize(this, uri) > 0L) {
            return
        }

        val sessionStartText = isoTime(sessionStartMillis)
        val versionLabel = getAppVersionLabel()

        val metadataLines = listOf(
            "# app_version=$versionLabel",
            "# manufacturer=${Build.MANUFACTURER}",
            "# model=${Build.MODEL}",
            "# device=${Build.DEVICE}",
            "# android_release=${Build.VERSION.RELEASE}",
            "# sdk_int=${Build.VERSION.SDK_INT}",
            "# timezone=${TimeZone.getDefault().id}",
            "# session_start=$sessionStartText",
            "# interval_ms=${config.intervalMs}",
            "# target_brightness=${config.brightnessTarget}",
            "# enforce_brightness=${config.enforceBrightness}",
            "# keep_screen_on=${config.keepScreenOn}",
            "# high_power_enabled=${config.highPowerEnabled}",
            "# cpu_stress_threads=${config.cpuStressThreads}",
            "# cpu_stress_duty_percent=${config.cpuStressDutyPercent}",
            "# experiment_note=${config.experimentNote}",
            "# results_directory=${SharedResultsStore.RESULTS_DIRECTORY_LABEL}"
        )

        metadataLines.forEach { writeLineInternal(uri, it) }

        writeLineInternal(
            uri,
            "Timestamp,ElapsedTime_S," +
                    "SOC_Integer,BatteryPct_Float,Voltage_mV,Current_uA,BatteryTemp_C,ChargeCounter_uAh," +
                    "BatteryStatus,PluggedType,BatteryHealth,BatteryPresent,BatteryScale," +
                    "TargetBrightness,Brightness,BrightnessSetOk,BrightnessReadOk,ScreenOn,ScreenOnReadOk," +
                    "CPU0_Freq_kHz,CPUFreqReadOk," +
                    "NetType,TxBytes_Total,RxBytes_Total,Tx_Rate_Bps,Rx_Rate_Bps,NetStatsReadOk," +
                    "BatteryIntentReadOk,BatteryPropertyReadOk," +
                    "HighPowerEnabled,CpuStressThreads,CpuStressDutyPercent," +
                    "ExperimentNote,EventMarker"
        )
    }

    private fun writeLine(line: String) {
        val uri = currentCsvUri ?: return
        writeLineInternal(uri, line)
    }

    private fun writeLineInternal(uri: Uri, line: String) {
        try {
            SharedResultsStore.appendText(this, uri, "$line\n")
        } catch (e: Exception) {
            Log.e(tag, "CSV 写入失败", e)
        }
    }

    private fun writeSessionSummaryFile(
        csvUri: Uri,
        csvDisplayName: String,
        csvLogicalPath: String,
        config: CollectionConfig,
        startedAtMillis: Long,
        endedAtMillis: Long,
        sampleCount: Long,
        screenOffObserved: Boolean,
        chargingObserved: Boolean
    ) {
        try {
            val summaryDisplayName =
                csvDisplayName.removeSuffix(".csv") + ".summary.txt"

            val summaryFile = SharedResultsStore.createTextFile(
                context = this,
                displayName = summaryDisplayName,
                mimeType = "text/plain"
            )

            val summaryText = buildString {
                appendLine("BatteryExpCollector Session Summary")
                appendLine("csv_file=$csvLogicalPath")
                appendLine("csv_uri=$csvUri")
                appendLine("summary_file=${summaryFile.logicalPath}")
                appendLine("session_start=${isoTime(startedAtMillis)}")
                appendLine("session_end=${isoTime(endedAtMillis)}")
                appendLine("duration_seconds=${((endedAtMillis - startedAtMillis).coerceAtLeast(0L)) / 1000L}")
                appendLine("sample_count=$sampleCount")
                appendLine("target_brightness=${config.brightnessTarget}")
                appendLine("enforce_brightness=${config.enforceBrightness}")
                appendLine("keep_screen_on=${config.keepScreenOn}")
                appendLine("high_power_enabled=${config.highPowerEnabled}")
                appendLine("cpu_stress_threads=${config.cpuStressThreads}")
                appendLine("cpu_stress_duty_percent=${config.cpuStressDutyPercent}")
                appendLine("screen_off_observed=${if (screenOffObserved) 1 else 0}")
                appendLine("charging_observed=${if (chargingObserved) 1 else 0}")
                appendLine("experiment_note=${config.experimentNote}")
                appendLine("results_directory=${SharedResultsStore.RESULTS_DIRECTORY_LABEL}")
            }

            SharedResultsStore.overwriteText(this, summaryFile.uri, summaryText)
        } catch (e: Exception) {
            Log.e(tag, "写入会话摘要失败", e)
        }
    }

    private fun countExistingDataRows(uri: Uri): Long {
        return try {
            SharedResultsStore.readLines(this, uri).count { line ->
                line.isNotBlank() &&
                        !line.startsWith("#") &&
                        !line.startsWith("Timestamp,")
            }.toLong()
        } catch (_: Exception) {
            0L
        }
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, channelId)
            .setContentTitle("BatteryExpCollector")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "电池实验采集",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }

    private fun getAppVersionLabel(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }

            "${packageInfo.versionName ?: "unknown"} (${packageInfo.longVersionCode})"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun batteryStatusToLabel(status: Int?): String {
        return when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "Full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NotCharging"
            BatteryManager.BATTERY_STATUS_UNKNOWN -> "Unknown"
            null -> ""
            else -> "Other($status)"
        }
    }

    private fun pluggedTypeToLabel(plugged: Int?): String {
        return when (plugged) {
            0 -> "Unplugged"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            null -> ""
            else -> "Other($plugged)"
        }
    }

    private fun batteryHealthToLabel(health: Int?): String {
        return when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OverVoltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            BatteryManager.BATTERY_HEALTH_UNKNOWN -> "Unknown"
            null -> ""
            else -> "Other($health)"
        }
    }

    private fun sanitizeFilePart(input: String): String {
        return input.trim().replace(Regex("[^A-Za-z0-9._-]+"), "_")
    }

    private fun isoTime(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date(timeMillis))
    }

    private fun booleanFlag(value: Boolean?): String? {
        return when (value) {
            true -> "1"
            false -> "0"
            null -> null
        }
    }

    private fun csvField(value: Any?): String {
        return when (value) {
            null -> ""
            is String -> escapeCsv(value)
            is Float -> String.format(Locale.US, "%.2f", value)
            is Double -> String.format(Locale.US, "%.2f", value)
            else -> value.toString()
        }
    }

    private fun escapeCsv(value: String): String {
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            return value
        }
        return "\"${value.replace("\"", "\"\"")}\""
    }

    private data class IntSnapshot(
        val value: Int?,
        val readOk: Boolean
    )

    private data class LongSnapshot(
        val value: Long?,
        val readOk: Boolean
    )

    private data class BooleanSnapshot(
        val value: Boolean?,
        val readOk: Boolean
    )

    private data class NetworkSnapshot(
        val netType: String,
        val txBytesTotal: Long?,
        val rxBytesTotal: Long?,
        val txRateBps: Long?,
        val rxRateBps: Long?,
        val readOk: Boolean
    )
}
