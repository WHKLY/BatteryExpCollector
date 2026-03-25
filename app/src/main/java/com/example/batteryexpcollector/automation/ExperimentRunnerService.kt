package com.example.batteryexpcollector.automation

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.example.batteryexpcollector.BatteryCollectService
import com.example.batteryexpcollector.MainActivity
import java.util.Locale
import java.util.concurrent.Executors

class ExperimentRunnerService : Service() {

    companion object {
        const val ACTION_START_LOCAL_EXPERIMENT =
            "com.example.batteryexpcollector.action.START_LOCAL_EXPERIMENT"
        const val ACTION_STOP_LOCAL_EXPERIMENT =
            "com.example.batteryexpcollector.action.STOP_LOCAL_EXPERIMENT"
        const val ACTION_PHASE_TIMEOUT =
            "com.example.batteryexpcollector.action.PHASE_TIMEOUT"

        const val EXTRA_ASSET_NAME = "extra_asset_name"
        const val EXTRA_PHASE_INDEX = "extra_phase_index"

        private const val CHANNEL_ID = "ExperimentRunnerChannel"
        private const val NOTIFICATION_ID = 2001
        private const val PHASE_TIMEOUT_REQUEST_CODE = 3001
    }

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var handling = false

    private var logger: AutomationExecutionLogger? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_LOCAL_EXPERIMENT

        if (handling) {
            logger?.warn("service call ignored because handler is busy, action=$action")
            return START_STICKY
        }

        handling = true
        startForeground(
            NOTIFICATION_ID,
            buildNotification("本地实验执行器运行中")
        )

        executor.execute {
            try {
                when (action) {
                    ACTION_START_LOCAL_EXPERIMENT -> handleStart(intent)
                    ACTION_PHASE_TIMEOUT -> handlePhaseTimeout(intent)
                    ACTION_STOP_LOCAL_EXPERIMENT -> handleManualStop()
                }
            } finally {
                handling = false
            }
        }

        return START_STICKY
    }

    private fun handleStart(intent: Intent?) {
        val state = AutomationRunnerPrefs.loadState(this)
        if (state.isRunning) {
            ensureLogger(state.experimentId).warn("start ignored because experiment is already running")
            return
        }

        val assetName = intent?.getStringExtra(EXTRA_ASSET_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: "local_experiment_dev.json"

        val config = LocalExperimentConfigLoader.loadFromAsset(this, assetName)
        val log = ensureLogger(config.experimentId)

        log.info("experiment_id=${config.experimentId}")
        log.info("note=${config.note}")
        log.info("asset_name=$assetName")

        val automation = AutomationAccessibilityService.current()
            ?: throw IllegalStateException(
                "Accessibility service is not connected. Enable BatteryExpCollector in Accessibility settings first."
            )

        AutomationRunnerPrefs.saveState(
            context = this,
            assetName = assetName,
            experimentId = config.experimentId,
            currentPhaseIndex = -1,
            isRunning = true
        )

        if (config.delayStartSec > 0) {
            log.info("delay_before_start=${config.delayStartSec}s")
            Thread.sleep(config.delayStartSec * 1000L)
        }

        startCollector(config.collector)
        Thread.sleep(1500L)
        mark("experiment_start")

        moveToNextPhase(config, automation, log)
    }

    private fun handlePhaseTimeout(intent: Intent?) {
        val state = AutomationRunnerPrefs.loadState(this)
        if (!state.isRunning || state.assetName.isBlank()) {
            stopSelfSafely("phase timeout ignored because runner state is not active")
            return
        }

        val config = LocalExperimentConfigLoader.loadFromAsset(this, state.assetName)
        val log = ensureLogger(state.experimentId.ifBlank { config.experimentId })

        val phaseIndex = intent?.getIntExtra(EXTRA_PHASE_INDEX, -1) ?: -1
        if (phaseIndex != state.currentPhaseIndex || phaseIndex !in config.phases.indices) {
            log.warn(
                "stale_or_invalid_phase_timeout received=$phaseIndex current=${state.currentPhaseIndex}"
            )
            return
        }

        val phase = config.phases[phaseIndex]
        if (phase.endMarker.isNotBlank()) {
            mark(phase.endMarker)
        }
        log.info("phase_end name=${phase.name}")

        val automation = AutomationAccessibilityService.current()
            ?: throw IllegalStateException("Accessibility service disconnected during phase timeout")

        moveToNextPhase(config, automation, log)
    }

    private fun handleManualStop() {
        val state = AutomationRunnerPrefs.loadState(this)
        val log = ensureLogger(state.experimentId.ifBlank { "local_runner" })

        log.warn("manual stop requested")
        runCatching { cancelPhaseTimeoutAlarm() }
        runCatching { mark("experiment_abort") }
        runCatching { stopCollector() }
        runCatching { AutomationAccessibilityService.current()?.goHome(log) }
        AutomationRunnerPrefs.clear(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        log.info("runner_finished")
    }

    private fun moveToNextPhase(
        config: LocalExperimentConfig,
        automation: AutomationAccessibilityService,
        log: AutomationExecutionLogger
    ) {
        val prevState = AutomationRunnerPrefs.loadState(this)
        val nextIndex = prevState.currentPhaseIndex + 1

        if (nextIndex !in config.phases.indices) {
            mark("experiment_end")
            Thread.sleep(800L)
            stopCollector()
            runCatching { automation.goHome(log) }
            log.info("experiment_completed_successfully")
            cancelPhaseTimeoutAlarm()
            AutomationRunnerPrefs.clear(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            log.info("runner_finished")
            return
        }

        val phase = config.phases[nextIndex]
        AutomationRunnerPrefs.saveState(
            context = this,
            assetName = prevState.assetName.ifBlank { "local_experiment_dev.json" },
            experimentId = config.experimentId,
            currentPhaseIndex = nextIndex,
            isRunning = true
        )

        log.info("phase_begin name=${phase.name} durationSec=${phase.durationSec}")

        if (phase.startMarker.isNotBlank()) {
            mark(phase.startMarker)
        }

        // 关键修复：先设 phase 结束闹钟，再执行可能导致线程被挂起的动作
        schedulePhaseTimeout(nextIndex, phase.durationSec, log)

        executePhaseActions(phase, automation, log)
    }

    private fun executePhaseActions(
        phase: LocalPhaseConfig,
        automation: AutomationAccessibilityService,
        log: AutomationExecutionLogger
    ) {
        phase.actions.forEachIndexed { index, action ->
            log.info("phase_action index=$index type=${action.type}")
            when (action.type.lowercase(Locale.ROOT)) {
                "wait" -> {
                    log.info("action=wait durationMs=${action.durationMs}")
                    Thread.sleep(action.durationMs.coerceAtLeast(0L))
                }

                "marker" -> {
                    require(action.markerName.isNotBlank()) { "marker action requires markerName" }
                    mark(action.markerName)
                }

                "home" -> {
                    automation.goHome(log)
                }

                "launch_home_icon" -> {
                    require(action.label.isNotBlank()) { "launch_home_icon requires label" }
                    automation.launchHomeIcon(
                        label = action.label,
                        expectedPackage = action.expectedPackage,
                        logger = log
                    )
                }

                else -> {
                    throw IllegalArgumentException("Unsupported local action type: ${action.type}")
                }
            }
        }
    }

    private fun schedulePhaseTimeout(
        phaseIndex: Int,
        durationSec: Long,
        log: AutomationExecutionLogger
    ) {
        val triggerAtMillis = System.currentTimeMillis() + durationSec.coerceAtLeast(0L) * 1000L
        val alarmManager = getSystemService(AlarmManager::class.java)
        val pendingIntent = phaseTimeoutPendingIntent(phaseIndex)

        alarmManager.cancel(pendingIntent)

        val exactScheduled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                true
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                false
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            true
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
            true
        }

        log.info(
            "phase_timeout_scheduled phaseIndex=$phaseIndex durationSec=$durationSec " +
                    "triggerAt=$triggerAtMillis exact=$exactScheduled"
        )
    }

    private fun phaseTimeoutPendingIntent(phaseIndex: Int): PendingIntent {
        val intent = Intent(this, ExperimentAlarmReceiver::class.java).apply {
            action = ACTION_PHASE_TIMEOUT
            putExtra(EXTRA_PHASE_INDEX, phaseIndex)
        }
        return PendingIntent.getBroadcast(
            this,
            PHASE_TIMEOUT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelPhaseTimeoutAlarm() {
        val alarmManager = getSystemService(AlarmManager::class.java)
        val intent = Intent(this, ExperimentAlarmReceiver::class.java).apply {
            action = ACTION_PHASE_TIMEOUT
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            PHASE_TIMEOUT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    private fun startCollector(config: LocalCollectorConfig) {
        val intent = Intent(this, BatteryCollectService::class.java).apply {
            action = BatteryCollectService.ACTION_START_COLLECTION
            putExtra(BatteryCollectService.EXTRA_INTERVAL_MS, config.intervalMs)
            putExtra(BatteryCollectService.EXTRA_EXPERIMENT_NOTE, config.experimentNote)
            putExtra(BatteryCollectService.EXTRA_BRIGHTNESS_TARGET, config.brightnessTarget)
            putExtra(BatteryCollectService.EXTRA_ENFORCE_BRIGHTNESS, config.enforceBrightness)
            putExtra(BatteryCollectService.EXTRA_KEEP_SCREEN_ON, config.keepScreenOn)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        logger?.info(
            "collector_started intervalMs=${config.intervalMs} " +
                    "brightnessTarget=${config.brightnessTarget} note=${config.experimentNote}"
        )
    }

    private fun mark(marker: String) {
        if (marker.isBlank()) return

        val intent = Intent(this, BatteryCollectService::class.java).apply {
            action = BatteryCollectService.ACTION_MARK_EVENT
            putExtra(BatteryCollectService.EXTRA_EVENT_MARKER, marker)
        }
        startService(intent)
        logger?.info("marker=$marker")
    }

    private fun stopCollector() {
        val intent = Intent(this, BatteryCollectService::class.java).apply {
            action = BatteryCollectService.ACTION_STOP_COLLECTION
        }
        startService(intent)
        logger?.info("collector_stopped")
    }

    private fun ensureLogger(experimentId: String): AutomationExecutionLogger {
        if (logger == null) {
            logger = AutomationExecutionLogger(this, experimentId.ifBlank { "local_runner" })
        }
        return logger!!
    }

    private fun stopSelfSafely(reason: String) {
        logger?.warn(reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("BatteryExpCollector 本地实验执行器")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "本地实验执行器",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)
        }
    }
}