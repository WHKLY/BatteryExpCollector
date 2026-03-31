package com.example.batteryexpcollector

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.abs

private val AccentBlue = Color(0xFF2D5674)
private val AccentBlueSoft = Color(0xFFEAF1F5)
private val AppBackground = Color(0xFFF7F9FB)
private val CardBackground = Color(0xFFFFFFFF)
private val BorderColor = Color(0xFFD7E0E7)
private val PrimaryText = Color(0xFF1F2A33)
private val SecondaryText = Color(0xFF5B6975)

private enum class ExperimentMode(
    val label: String,
    val description: String
) {
    STANDARD("Standard", "Battery collection only."),
    CPU_HIGH_POWER("CPU High Power", "Adds CPU stress while collecting battery data."),
    NETWORK_POWER("Network Power", "Runs configurable network traffic during collection.");

    companion object {
        fun fromConfig(config: CollectionConfig): ExperimentMode {
            return when {
                config.networkLoadEnabled -> NETWORK_POWER
                config.highPowerEnabled -> CPU_HIGH_POWER
                else -> STANDARD
            }
        }
    }
}

private enum class CpuStressLevel(
    val label: String,
    val threadCount: Int,
    val dutyPercent: Int
) {
    MEDIUM("Medium", 2, 65),
    HIGH("High", 0, 85),
    EXTREME("Extreme", 0, 100);

    companion object {
        fun fromConfig(config: CollectionConfig): CpuStressLevel {
            return entries.firstOrNull { level ->
                level.threadCount == config.cpuStressThreads &&
                    level.dutyPercent == config.cpuStressDutyPercent
            } ?: HIGH
        }
    }
}

private enum class NetworkScenarioUi(
    val label: String,
    val configValue: String
) {
    DOWNLOAD_LOOP("Download Loop", NETWORK_SCENARIO_DOWNLOAD_LOOP),
    UPLOAD_LOOP("Upload Loop", NETWORK_SCENARIO_UPLOAD_LOOP),
    SMALL_REQUEST_BURST("Small Request Burst", NETWORK_SCENARIO_SMALL_REQUEST_BURST);

    companion object {
        fun fromConfig(config: CollectionConfig): NetworkScenarioUi {
            return entries.firstOrNull { it.configValue == config.networkScenario }
                ?: DOWNLOAD_LOOP
        }
    }
}

private enum class NetworkConnectionModeUi(
    val label: String,
    val configValue: String,
    val concurrency: Int
) {
    SINGLE("Single", NETWORK_CONNECTION_SINGLE, 1),
    MULTI("Multi", NETWORK_CONNECTION_MULTI, DEFAULT_NETWORK_MULTI_CONCURRENCY);

    companion object {
        fun fromConfig(config: CollectionConfig): NetworkConnectionModeUi {
            return entries.firstOrNull { it.configValue == config.networkConnectionMode }
                ?: SINGLE
        }
    }
}

class MainActivity : ComponentActivity() {

    private var isCollecting by mutableStateOf(false)
    private var currentFilePath by mutableStateOf("")
    private var lastFilePath by mutableStateOf("")
    private var latestSample by mutableStateOf(LatestSampleSnapshot())

    private var notificationPermissionGranted by mutableStateOf(false)
    private var writeSettingsGranted by mutableStateOf(false)

    private var intervalMs by mutableLongStateOf(DEFAULT_INTERVAL_MS)
    private var noteInput by mutableStateOf("")
    private var brightnessTargetInput by mutableStateOf(DEFAULT_BRIGHTNESS_TARGET.toString())
    private var enforceBrightnessEnabled by mutableStateOf(true)
    private var keepScreenOnEnabled by mutableStateOf(true)
    private var selectedExperimentMode by mutableStateOf(ExperimentMode.STANDARD)
    private var selectedCpuStressLevel by mutableStateOf(CpuStressLevel.HIGH)
    private var selectedNetworkScenario by mutableStateOf(NetworkScenarioUi.DOWNLOAD_LOOP)
    private var selectedNetworkConnectionMode by mutableStateOf(NetworkConnectionModeUi.SINGLE)
    private var networkRetryEnabled by mutableStateOf(true)
    private var networkMaxRetryCountInput by mutableStateOf(DEFAULT_NETWORK_MAX_RETRY_COUNT.toString())
    private var networkDownloadUrlInput by mutableStateOf("")
    private var networkUploadUrlInput by mutableStateOf("")
    private var networkBurstUrlInput by mutableStateOf("")
    private var networkUploadChunkKbInput by mutableStateOf(
        (DEFAULT_NETWORK_UPLOAD_CHUNK_BYTES / 1024).toString()
    )
    private var networkBurstIntervalMsInput by mutableStateOf(
        DEFAULT_NETWORK_BURST_INTERVAL_MS.toString()
    )
    private var eventMarkerInput by mutableStateOf("")

    private var preflightChecks by mutableStateOf<List<PreflightCheckItem>>(emptyList())

    private var deviceStatusExpanded by mutableStateOf(false)
    private var preflightExpanded by mutableStateOf(true)

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiRefreshRunnable = object : Runnable {
        override fun run() {
            refreshUiStateFromPrefs()
            uiHandler.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncConfigInputsFromState(CollectionPrefs.loadConfig(this))
        refreshUiStateFromPrefs()

        setContent {
            val pagerState = rememberPagerState(pageCount = { 2 })
            val coroutineScope = rememberCoroutineScope()
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { granted ->
                    notificationPermissionGranted = granted || hasNotificationPermission()
                    val message = if (notificationPermissionGranted) {
                        "Notification permission granted."
                    } else {
                        "Notification permission denied. Foreground service notices may be hidden."
                    }
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                    refreshUiStateFromPrefs()
                }
            )

            MaterialTheme {
                Scaffold(
                    containerColor = AppBackground,
                    topBar = {
                        Surface(color = AppBackground) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 12.dp)
                            ) {
                                Text(
                                    text = "Battery Experiment Collector",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = AccentBlue,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Swipe or use the bottom switcher to move between Monitor and Configuration.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = SecondaryText
                                )
                            }
                        }
                    },
                    bottomBar = {
                        BottomPagerSwitcher(
                            currentPage = pagerState.currentPage,
                            onPageSelected = { page ->
                                coroutineScope.launch {
                                    pagerState.animateScrollToPage(page)
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) { page ->
                            when (page) {
                                0 -> MonitorPage(
                                    isCollecting = isCollecting,
                                    selectedExperimentMode = selectedExperimentMode,
                                    latestSample = latestSample,
                                    currentFilePath = currentFilePath,
                                    lastFilePath = lastFilePath,
                                    notificationPermissionGranted = notificationPermissionGranted,
                                    writeSettingsGranted = writeSettingsGranted,
                                    preflightChecks = preflightChecks,
                                    preflightExpanded = preflightExpanded,
                                    onTogglePreflight = { preflightExpanded = !preflightExpanded },
                                    deviceStatusExpanded = deviceStatusExpanded,
                                    onToggleDeviceStatus = { deviceStatusExpanded = !deviceStatusExpanded },
                                    eventMarkerInput = eventMarkerInput,
                                    onEventMarkerChange = { eventMarkerInput = it },
                                    onRefreshPreflight = { refreshPreflightChecks() },
                                    onOpenWriteSettings = { openWriteSettingsPage() },
                                    onRequestNotificationPermission = {
                                        notificationPermissionLauncher.launch(
                                            Manifest.permission.POST_NOTIFICATIONS
                                        )
                                    },
                                    onStartCollection = {
                                        startSelectedCollection {
                                            notificationPermissionLauncher.launch(
                                                Manifest.permission.POST_NOTIFICATIONS
                                            )
                                        }
                                    },
                                    onStopCollection = { stopCollection() },
                                    onSubmitMarker = {
                                        val marker = eventMarkerInput.trim()
                                        if (marker.isBlank()) {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "Marker text cannot be empty.",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } else {
                                            sendEventMarker(marker)
                                            eventMarkerInput = ""
                                        }
                                    },
                                    deviceStatusSummary = buildDeviceStatusSummary()
                                )

                                1 -> ConfigurationPage(
                                    isCollecting = isCollecting,
                                    selectedExperimentMode = selectedExperimentMode,
                                    onExperimentModeSelected = { mode ->
                                        selectedExperimentMode = mode
                                        refreshPreflightChecks()
                                    },
                                    intervalMs = intervalMs,
                                    onIntervalSelected = { option ->
                                        intervalMs = option
                                        refreshPreflightChecks()
                                    },
                                    noteInput = noteInput,
                                    onNoteChange = { noteInput = it },
                                    brightnessTargetInput = brightnessTargetInput,
                                    onBrightnessTargetChange = { input ->
                                        brightnessTargetInput = input.filter { it.isDigit() }.take(3)
                                        refreshPreflightChecks()
                                    },
                                    enforceBrightnessEnabled = enforceBrightnessEnabled,
                                    onEnforceBrightnessChanged = {
                                        enforceBrightnessEnabled = it
                                        refreshPreflightChecks()
                                    },
                                    keepScreenOnEnabled = keepScreenOnEnabled,
                                    onKeepScreenOnChanged = {
                                        keepScreenOnEnabled = it
                                        updateKeepScreenOnFlag()
                                        refreshPreflightChecks()
                                    },
                                    selectedCpuStressLevel = selectedCpuStressLevel,
                                    onCpuStressLevelSelected = {
                                        selectedCpuStressLevel = it
                                        refreshPreflightChecks()
                                    },
                                    selectedNetworkScenario = selectedNetworkScenario,
                                    onNetworkScenarioSelected = {
                                        selectedNetworkScenario = it
                                        refreshPreflightChecks()
                                    },
                                    selectedNetworkConnectionMode = selectedNetworkConnectionMode,
                                    onNetworkConnectionModeSelected = {
                                        selectedNetworkConnectionMode = it
                                        refreshPreflightChecks()
                                    },
                                    networkRetryEnabled = networkRetryEnabled,
                                    onNetworkRetryEnabledChanged = {
                                        networkRetryEnabled = it
                                        refreshPreflightChecks()
                                    },
                                    networkMaxRetryCountInput = networkMaxRetryCountInput,
                                    onNetworkMaxRetryCountChange = { input ->
                                        networkMaxRetryCountInput = input.filter { it.isDigit() }.take(2)
                                        refreshPreflightChecks()
                                    },
                                    networkDownloadUrlInput = networkDownloadUrlInput,
                                    onNetworkDownloadUrlChange = {
                                        networkDownloadUrlInput = it
                                        refreshPreflightChecks()
                                    },
                                    networkUploadUrlInput = networkUploadUrlInput,
                                    onNetworkUploadUrlChange = {
                                        networkUploadUrlInput = it
                                        refreshPreflightChecks()
                                    },
                                    networkBurstUrlInput = networkBurstUrlInput,
                                    onNetworkBurstUrlChange = {
                                        networkBurstUrlInput = it
                                        refreshPreflightChecks()
                                    },
                                    networkUploadChunkKbInput = networkUploadChunkKbInput,
                                    onNetworkUploadChunkKbChange = { input ->
                                        networkUploadChunkKbInput = input.filter { it.isDigit() }.take(5)
                                        refreshPreflightChecks()
                                    },
                                    networkBurstIntervalMsInput = networkBurstIntervalMsInput,
                                    onNetworkBurstIntervalMsChange = { input ->
                                        networkBurstIntervalMsInput = input.filter { it.isDigit() }.take(6)
                                        refreshPreflightChecks()
                                    },
                                    configurationSummary = buildConfigurationSummary(buildConfigForSelectedMode())
                                )
                            }
                        }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUiStateFromPrefs()
        startUiRefreshLoop()
    }

    override fun onPause() {
        super.onPause()
        stopUiRefreshLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopUiRefreshLoop()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun refreshUiStateFromPrefs() {
        val session = CollectionPrefs.loadSessionState(this)
        isCollecting = session.isCollecting
        currentFilePath = session.currentFilePath
        lastFilePath = session.lastFilePath
        latestSample = CollectionPrefs.loadLatestSample(this)

        notificationPermissionGranted = hasNotificationPermission()
        writeSettingsGranted = Settings.System.canWrite(this)

        if (session.isCollecting) {
            syncConfigInputsFromState(session.config)
        }

        updateKeepScreenOnFlag()
        refreshPreflightChecks()
    }

    private fun syncConfigInputsFromState(config: CollectionConfig) {
        intervalMs = config.intervalMs
        noteInput = config.experimentNote
        brightnessTargetInput = config.brightnessTarget.toString()
        enforceBrightnessEnabled = config.enforceBrightness
        keepScreenOnEnabled = config.keepScreenOn
        selectedExperimentMode = ExperimentMode.fromConfig(config)
        selectedCpuStressLevel = CpuStressLevel.fromConfig(config)
        selectedNetworkScenario = NetworkScenarioUi.fromConfig(config)
        selectedNetworkConnectionMode = NetworkConnectionModeUi.fromConfig(config)
        networkRetryEnabled = config.networkRetryEnabled
        networkMaxRetryCountInput = config.networkMaxRetryCount.toString()
        networkDownloadUrlInput = config.networkDownloadUrl
        networkUploadUrlInput = config.networkUploadUrl
        networkBurstUrlInput = config.networkBurstUrl
        networkUploadChunkKbInput = (config.networkUploadChunkBytes / 1024).toString()
        networkBurstIntervalMsInput = config.networkBurstIntervalMs.toString()
    }

    private fun refreshPreflightChecks() {
        preflightChecks = evaluatePreflightChecks(buildConfigForSelectedMode()).items
    }

    private fun startUiRefreshLoop() {
        uiHandler.removeCallbacks(uiRefreshRunnable)
        uiHandler.post(uiRefreshRunnable)
    }

    private fun stopUiRefreshLoop() {
        uiHandler.removeCallbacks(uiRefreshRunnable)
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun ensurePermissionsBeforeStart(onNeedNotificationPermission: () -> Unit): Boolean {
        if (!hasNotificationPermission()) {
            Toast.makeText(this, "Grant notification permission before starting.", Toast.LENGTH_SHORT)
                .show()
            onNeedNotificationPermission()
            return false
        }

        if (!Settings.System.canWrite(this)) {
            Toast.makeText(
                this,
                "Grant Modify System Settings permission before starting.",
                Toast.LENGTH_LONG
            ).show()
            openWriteSettingsPage()
            return false
        }

        return true
    }

    private fun openWriteSettingsPage() {
        val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun buildConfigForSelectedMode(): CollectionConfig {
        return when (selectedExperimentMode) {
            ExperimentMode.STANDARD -> buildConfigFromInputs()
            ExperimentMode.CPU_HIGH_POWER -> buildConfigFromInputs(
                highPowerEnabled = true,
                stressLevel = selectedCpuStressLevel
            )
            ExperimentMode.NETWORK_POWER -> buildConfigFromInputs(
                highPowerEnabled = false,
                networkLoadEnabled = true,
                networkScenario = selectedNetworkScenario,
                networkConnectionMode = selectedNetworkConnectionMode
            )
        }
    }

    private fun buildConfigFromInputs(
        highPowerEnabled: Boolean = false,
        stressLevel: CpuStressLevel = selectedCpuStressLevel,
        networkLoadEnabled: Boolean = false,
        networkScenario: NetworkScenarioUi = selectedNetworkScenario,
        networkConnectionMode: NetworkConnectionModeUi = selectedNetworkConnectionMode
    ): CollectionConfig {
        val brightness = brightnessTargetInput.toIntOrNull()?.coerceIn(0, 255)
            ?: DEFAULT_BRIGHTNESS_TARGET
        val uploadChunkBytes =
            (networkUploadChunkKbInput.toIntOrNull()
                ?: (DEFAULT_NETWORK_UPLOAD_CHUNK_BYTES / 1024)).coerceAtLeast(1) * 1024
        val burstIntervalMs = networkBurstIntervalMsInput.toLongOrNull()
            ?.coerceAtLeast(50L)
            ?: DEFAULT_NETWORK_BURST_INTERVAL_MS
        val maxRetryCount = networkMaxRetryCountInput.toIntOrNull()
            ?.coerceAtLeast(0)
            ?: DEFAULT_NETWORK_MAX_RETRY_COUNT

        return CollectionConfig(
            intervalMs = intervalMs,
            experimentNote = noteInput.trim(),
            brightnessTarget = brightness,
            enforceBrightness = enforceBrightnessEnabled,
            keepScreenOn = keepScreenOnEnabled,
            highPowerEnabled = highPowerEnabled,
            cpuStressThreads = if (highPowerEnabled) stressLevel.threadCount else 0,
            cpuStressDutyPercent = if (highPowerEnabled) {
                stressLevel.dutyPercent
            } else {
                DEFAULT_CPU_STRESS_DUTY_PERCENT
            },
            networkLoadEnabled = networkLoadEnabled,
            networkScenario = if (networkLoadEnabled) {
                networkScenario.configValue
            } else {
                NETWORK_SCENARIO_DOWNLOAD_LOOP
            },
            networkConnectionMode = if (networkLoadEnabled) {
                networkConnectionMode.configValue
            } else {
                NETWORK_CONNECTION_SINGLE
            },
            networkConcurrency = if (networkLoadEnabled) networkConnectionMode.concurrency else 1,
            networkRetryEnabled = if (networkLoadEnabled) networkRetryEnabled else true,
            networkMaxRetryCount = if (networkLoadEnabled) {
                maxRetryCount
            } else {
                DEFAULT_NETWORK_MAX_RETRY_COUNT
            },
            networkDownloadUrl = networkDownloadUrlInput.trim(),
            networkUploadUrl = networkUploadUrlInput.trim(),
            networkBurstUrl = networkBurstUrlInput.trim(),
            networkUploadChunkBytes = if (networkLoadEnabled) {
                uploadChunkBytes
            } else {
                DEFAULT_NETWORK_UPLOAD_CHUNK_BYTES
            },
            networkBurstIntervalMs = if (networkLoadEnabled) {
                burstIntervalMs
            } else {
                DEFAULT_NETWORK_BURST_INTERVAL_MS
            }
        )
    }

    private fun evaluatePreflightChecks(config: CollectionConfig): PreflightReport {
        val items = mutableListOf<PreflightCheckItem>()

        items += if (hasNotificationPermission()) {
            PreflightCheckItem.pass("Notification Permission", "Granted")
        } else {
            PreflightCheckItem.block("Notification Permission", "Not granted")
        }

        items += if (Settings.System.canWrite(this)) {
            PreflightCheckItem.pass("Modify System Settings", "Granted")
        } else {
            PreflightCheckItem.block("Modify System Settings", "Not granted")
        }

        items += PreflightCheckItem.pass("Results Directory", SharedResultsStore.RESULTS_DIRECTORY_LABEL)

        val brightnessModeItem = try {
            val mode = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
            if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                PreflightCheckItem.pass("Auto Brightness", "Disabled")
            } else {
                PreflightCheckItem.warn(
                    "Auto Brightness",
                    "Still enabled. Manual brightness is recommended for experiments."
                )
            }
        } catch (_: Exception) {
            PreflightCheckItem.warn("Auto Brightness", "Unable to read current state")
        }
        items += brightnessModeItem

        val brightnessItem = try {
            val currentBrightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            if (abs(currentBrightness - config.brightnessTarget) <= 10) {
                PreflightCheckItem.pass(
                    "Current Brightness",
                    "Current value $currentBrightness is close to target ${config.brightnessTarget}"
                )
            } else {
                PreflightCheckItem.warn(
                    "Current Brightness",
                    "Current value $currentBrightness is far from target ${config.brightnessTarget}"
                )
            }
        } catch (_: Exception) {
            PreflightCheckItem.warn("Current Brightness", "Unable to read current value")
        }
        items += brightnessItem

        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged > 0

        items += if (charging) {
            PreflightCheckItem.warn(
                "Charging State",
                "Device is charging or plugged in. Unplug it for battery experiments."
            )
        } else {
            PreflightCheckItem.pass("Charging State", "Device is running on battery")
        }

        items += if (config.enforceBrightness) {
            PreflightCheckItem.pass(
                "Brightness Control",
                "The app will try to keep the target brightness during collection"
            )
        } else {
            PreflightCheckItem.warn(
                "Brightness Control",
                "Brightness locking is disabled. Screen brightness may drift."
            )
        }

        items += if (config.keepScreenOn) {
            PreflightCheckItem.pass(
                "Keep Screen Awake",
                "The app will request the screen to stay on while collecting"
            )
        } else {
            PreflightCheckItem.warn(
                "Keep Screen Awake",
                "Screen may turn off during the experiment"
            )
        }

        if (config.networkLoadEnabled) {
            val relevantUrl = when (config.networkScenario) {
                NETWORK_SCENARIO_DOWNLOAD_LOOP -> config.networkDownloadUrl
                NETWORK_SCENARIO_UPLOAD_LOOP -> config.networkUploadUrl
                NETWORK_SCENARIO_SMALL_REQUEST_BURST -> config.networkBurstUrl
                else -> ""
            }

            items += if (relevantUrl.isBlank()) {
                PreflightCheckItem.block("Network Target URL", "A URL is required for the selected scenario")
            } else if (!isLikelyHttpUrl(relevantUrl)) {
                PreflightCheckItem.block("Network Target URL", "Use an http:// or https:// URL")
            } else {
                PreflightCheckItem.pass("Network Target URL", relevantUrl)
            }

            items += PreflightCheckItem.pass(
                "Network Load Setup",
                "Scenario=${config.networkScenario}, workers=${config.networkConcurrency}, retry=${config.networkRetryEnabled}"
            )
        }

        return PreflightReport(items)
    }

    private fun startCollection(config: CollectionConfig) {
        CollectionPrefs.saveConfig(this, config)

        val startIntent = Intent(this, BatteryCollectService::class.java).apply {
            action = BatteryCollectService.ACTION_START_COLLECTION
            putExtra(BatteryCollectService.EXTRA_INTERVAL_MS, config.intervalMs)
            putExtra(BatteryCollectService.EXTRA_EXPERIMENT_NOTE, config.experimentNote)
            putExtra(BatteryCollectService.EXTRA_BRIGHTNESS_TARGET, config.brightnessTarget)
            putExtra(BatteryCollectService.EXTRA_ENFORCE_BRIGHTNESS, config.enforceBrightness)
            putExtra(BatteryCollectService.EXTRA_KEEP_SCREEN_ON, config.keepScreenOn)
            putExtra(BatteryCollectService.EXTRA_HIGH_POWER_ENABLED, config.highPowerEnabled)
            putExtra(BatteryCollectService.EXTRA_CPU_STRESS_THREADS, config.cpuStressThreads)
            putExtra(
                BatteryCollectService.EXTRA_CPU_STRESS_DUTY_PERCENT,
                config.cpuStressDutyPercent
            )
            putExtra(BatteryCollectService.EXTRA_NETWORK_LOAD_ENABLED, config.networkLoadEnabled)
            putExtra(BatteryCollectService.EXTRA_NETWORK_SCENARIO, config.networkScenario)
            putExtra(
                BatteryCollectService.EXTRA_NETWORK_CONNECTION_MODE,
                config.networkConnectionMode
            )
            putExtra(BatteryCollectService.EXTRA_NETWORK_CONCURRENCY, config.networkConcurrency)
            putExtra(
                BatteryCollectService.EXTRA_NETWORK_RETRY_ENABLED,
                config.networkRetryEnabled
            )
            putExtra(
                BatteryCollectService.EXTRA_NETWORK_MAX_RETRY_COUNT,
                config.networkMaxRetryCount
            )
            putExtra(
                BatteryCollectService.EXTRA_NETWORK_DOWNLOAD_URL,
                config.networkDownloadUrl
            )
            putExtra(
                BatteryCollectService.EXTRA_NETWORK_UPLOAD_URL,
                config.networkUploadUrl
            )
            putExtra(BatteryCollectService.EXTRA_NETWORK_BURST_URL, config.networkBurstUrl)
            putExtra(
                BatteryCollectService.EXTRA_NETWORK_UPLOAD_CHUNK_BYTES,
                config.networkUploadChunkBytes
            )
            putExtra(
                BatteryCollectService.EXTRA_NETWORK_BURST_INTERVAL_MS,
                config.networkBurstIntervalMs
            )
        }

        ContextCompat.startForegroundService(this, startIntent)
        isCollecting = true
        updateKeepScreenOnFlag()
        Toast.makeText(this, "Collection start request sent.", Toast.LENGTH_SHORT).show()

        uiHandler.postDelayed({ refreshUiStateFromPrefs() }, 600L)
    }

    private fun startSelectedCollection(onNeedNotificationPermission: () -> Unit) {
        when (selectedExperimentMode) {
            ExperimentMode.STANDARD -> attemptStartCollection(
                highPowerEnabled = false,
                onNeedNotificationPermission = onNeedNotificationPermission
            )
            ExperimentMode.CPU_HIGH_POWER -> attemptStartCollection(
                highPowerEnabled = true,
                stressLevel = selectedCpuStressLevel,
                onNeedNotificationPermission = onNeedNotificationPermission
            )
            ExperimentMode.NETWORK_POWER -> attemptStartCollection(
                highPowerEnabled = false,
                networkLoadEnabled = true,
                networkScenario = selectedNetworkScenario,
                networkConnectionMode = selectedNetworkConnectionMode,
                onNeedNotificationPermission = onNeedNotificationPermission
            )
        }
    }

    private fun attemptStartCollection(
        highPowerEnabled: Boolean,
        stressLevel: CpuStressLevel = selectedCpuStressLevel,
        networkLoadEnabled: Boolean = false,
        networkScenario: NetworkScenarioUi = selectedNetworkScenario,
        networkConnectionMode: NetworkConnectionModeUi = selectedNetworkConnectionMode,
        onNeedNotificationPermission: () -> Unit
    ) {
        val ready = ensurePermissionsBeforeStart(onNeedNotificationPermission)
        if (!ready) {
            refreshPreflightChecks()
            return
        }

        val config = buildConfigFromInputs(
            highPowerEnabled = highPowerEnabled,
            stressLevel = stressLevel,
            networkLoadEnabled = networkLoadEnabled,
            networkScenario = networkScenario,
            networkConnectionMode = networkConnectionMode
        )
        val report = evaluatePreflightChecks(config)
        preflightChecks = report.items

        if (report.hasBlockers) {
            Toast.makeText(
                this,
                "Resolve blocking preflight items before starting the experiment.",
                Toast.LENGTH_LONG
            ).show()
            if (!preflightExpanded) {
                preflightExpanded = true
            }
            return
        }

        startCollection(config)
    }

    private fun isLikelyHttpUrl(value: String): Boolean {
        val normalized = value.trim().lowercase(Locale.US)
        return normalized.startsWith("http://") || normalized.startsWith("https://")
    }

    private fun stopCollection() {
        val stopIntent = Intent(this, BatteryCollectService::class.java).apply {
            action = BatteryCollectService.ACTION_STOP_COLLECTION
        }
        startService(stopIntent)

        Toast.makeText(this, "Collection stop request sent.", Toast.LENGTH_SHORT).show()
        uiHandler.postDelayed({ refreshUiStateFromPrefs() }, 600L)
    }

    private fun sendEventMarker(marker: String) {
        val markerIntent = Intent(this, BatteryCollectService::class.java).apply {
            action = BatteryCollectService.ACTION_MARK_EVENT
            putExtra(BatteryCollectService.EXTRA_EVENT_MARKER, marker)
        }
        startService(markerIntent)
        Toast.makeText(this, "Event marker queued.", Toast.LENGTH_SHORT).show()
    }

    private fun updateKeepScreenOnFlag() {
        val shouldKeepOn = isCollecting && keepScreenOnEnabled
        if (shouldKeepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun buildDeviceStatusSummary(): String {
        val statusText = if (isCollecting) "Collecting" else "Idle"
        val fileName = when {
            currentFilePath.isNotBlank() -> File(currentFilePath).name
            lastFilePath.isNotBlank() -> File(lastFilePath).name
            else -> "No file"
        }
        return "Status: $statusText, File: $fileName"
    }

    private fun buildConfigurationSummary(config: CollectionConfig): String {
        val modeText = ExperimentMode.fromConfig(config).label
        val baseSummary = buildString {
            append("Mode: $modeText")
            append("\nInterval: ${config.intervalMs} ms")
            append("\nBrightness target: ${config.brightnessTarget}")
            append("\nKeep screen awake: ${if (config.keepScreenOn) "On" else "Off"}")
            append("\nBrightness control: ${if (config.enforceBrightness) "On" else "Off"}")
        }

        return when {
            config.networkLoadEnabled -> {
                val url = when (config.networkScenario) {
                    NETWORK_SCENARIO_DOWNLOAD_LOOP -> config.networkDownloadUrl
                    NETWORK_SCENARIO_UPLOAD_LOOP -> config.networkUploadUrl
                    NETWORK_SCENARIO_SMALL_REQUEST_BURST -> config.networkBurstUrl
                    else -> ""
                }
                "$baseSummary\nScenario: ${config.networkScenario}\nWorkers: ${config.networkConcurrency}\nTarget: ${url.ifBlank { "Not set" }}"
            }
            config.highPowerEnabled -> {
                "$baseSummary\nCPU threads: ${if (config.cpuStressThreads == 0) "Auto" else config.cpuStressThreads}\nDuty: ${config.cpuStressDutyPercent}%"
            }
            else -> baseSummary
        }
    }
}

@Composable
private fun MonitorPage(
    isCollecting: Boolean,
    selectedExperimentMode: ExperimentMode,
    latestSample: LatestSampleSnapshot,
    currentFilePath: String,
    lastFilePath: String,
    notificationPermissionGranted: Boolean,
    writeSettingsGranted: Boolean,
    preflightChecks: List<PreflightCheckItem>,
    preflightExpanded: Boolean,
    onTogglePreflight: () -> Unit,
    deviceStatusExpanded: Boolean,
    onToggleDeviceStatus: () -> Unit,
    eventMarkerInput: String,
    onEventMarkerChange: (String) -> Unit,
    onRefreshPreflight: () -> Unit,
    onOpenWriteSettings: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStartCollection: () -> Unit,
    onStopCollection: () -> Unit,
    onSubmitMarker: () -> Unit,
    deviceStatusSummary: String
) {
    val scrollState = rememberScrollState()
    val blockCount = preflightChecks.count { it.level == CheckLevel.BLOCK }
    val warnCount = preflightChecks.count { it.level == CheckLevel.WARN }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppSectionCard(title = "Execution Overview") {
            DetailRow("Status", if (isCollecting) "Collecting" else "Idle")
            DetailRow("Active Mode", selectedExperimentMode.label)
            DetailRow(
                "Current File",
                when {
                    currentFilePath.isNotBlank() -> File(currentFilePath).name
                    lastFilePath.isNotBlank() -> File(lastFilePath).name
                    else -> "No file yet"
                }
            )
        }

        AppSectionCard(
            title = "Execution Control",
            subtitle = "Start or stop collection with the currently selected experiment mode."
        ) {
            if (!writeSettingsGranted) {
                Button(
                    onClick = onOpenWriteSettings,
                    modifier = Modifier.fillMaxWidth(),
                    colors = primaryButtonColors()
                ) {
                    Text("Open Modify System Settings Permission")
                }
                SpacerLine()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationPermissionGranted) {
                Button(
                    onClick = onRequestNotificationPermission,
                    modifier = Modifier.fillMaxWidth(),
                    colors = primaryButtonColors()
                ) {
                    Text("Request Notification Permission")
                }
                SpacerLine()
            }

            Button(
                onClick = {
                    if (isCollecting) onStopCollection() else onStartCollection()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = primaryButtonColors()
            ) {
                Text(if (isCollecting) "Stop Collection" else "Start ${selectedExperimentMode.label} Collection")
            }
        }

        AppSectionCard(title = "Live Metrics") {
            if (latestSample.timestampMillis > 0L) {
                DetailRow("Last Sample Time", formatSnapshotTimestamp(latestSample.timestampMillis))
                DetailRow("Elapsed Time", "${latestSample.elapsedSec} s")
                DetailRow("Battery Level", latestSample.socInteger?.toString() ?: "N/A")
                DetailRow(
                    "Battery Temperature",
                    latestSample.batteryTempC?.let { String.format(Locale.US, "%.2f degC", it) } ?: "N/A"
                )
                DetailRow("Current", latestSample.currentUa?.let { "$it uA" } ?: "N/A")
                DetailRow("Brightness", latestSample.brightness?.toString() ?: "N/A")
                DetailRow(
                    "Screen State",
                    when (latestSample.screenOn) {
                        true -> "On"
                        false -> "Off"
                        null -> "N/A"
                    }
                )
                DetailRow("Network Type", latestSample.netType.ifBlank { "N/A" })
                DetailRow(
                    "Sample File",
                    latestSample.currentFilePath.takeIf { it.isNotBlank() }?.let { File(it).name }
                        ?: "N/A"
                )
            } else {
                Text(
                    text = "No sample has been written yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SecondaryText
                )
            }
        }

        AppSectionCard(title = "Preflight Checks") {
            CollapsibleCardHeader(
                title = "Preflight Checks",
                expanded = preflightExpanded,
                summary = "Blocks: $blockCount, Warnings: $warnCount",
                onToggle = onTogglePreflight
            )

            AnimatedVisibility(visible = preflightExpanded) {
                Column {
                    SpacerLine()
                    preflightChecks.forEach { item ->
                        val color = when (item.level) {
                            CheckLevel.PASS -> AccentBlue
                            CheckLevel.WARN -> MaterialTheme.colorScheme.tertiary
                            CheckLevel.BLOCK -> MaterialTheme.colorScheme.error
                        }
                        Text(
                            text = "${item.level.label} ${item.title}: ${item.detail}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = color
                        )
                        SpacerLine()
                    }

                    OutlinedButton(
                        onClick = onRefreshPreflight,
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, AccentBlue),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentBlue)
                    ) {
                        Text("Refresh Checks")
                    }
                }
            }
        }

        AppSectionCard(title = "Device Status") {
            CollapsibleCardHeader(
                title = "Device Status",
                expanded = deviceStatusExpanded,
                summary = deviceStatusSummary,
                onToggle = onToggleDeviceStatus
            )

            AnimatedVisibility(visible = deviceStatusExpanded) {
                Column {
                    SpacerLine()
                    DetailRow("Device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    DetailRow(
                        "Notification Permission",
                        if (notificationPermissionGranted) "Granted" else "Not granted"
                    )
                    DetailRow(
                        "Modify System Settings",
                        if (writeSettingsGranted) "Granted" else "Not granted"
                    )
                    DetailRow("Results Directory", SharedResultsStore.RESULTS_DIRECTORY_LABEL)
                    if (currentFilePath.isNotBlank()) {
                        DetailRow("Current File Path", currentFilePath)
                    } else if (lastFilePath.isNotBlank()) {
                        DetailRow("Last File Path", lastFilePath)
                    }
                }
            }
        }

        AppSectionCard(
            title = "Event Marker",
            subtitle = "Use markers to annotate key moments during a running experiment."
        ) {
            OutlinedTextField(
                value = eventMarkerInput,
                onValueChange = onEventMarkerChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Marker Text") },
                placeholder = { Text("Example: phase1_start") },
                colors = appOutlinedTextFieldColors()
            )
            SpacerLine()
            Button(
                onClick = onSubmitMarker,
                enabled = isCollecting,
                modifier = Modifier.fillMaxWidth(),
                colors = primaryButtonColors()
            ) {
                Text("Add Marker")
            }
            SpacerLine()
            Text(
                text = "Keep the app in the foreground during formal experiments so monitoring remains visible.",
                style = MaterialTheme.typography.bodySmall,
                color = SecondaryText
            )
        }
    }
}

@Composable
private fun ConfigurationPage(
    isCollecting: Boolean,
    selectedExperimentMode: ExperimentMode,
    onExperimentModeSelected: (ExperimentMode) -> Unit,
    intervalMs: Long,
    onIntervalSelected: (Long) -> Unit,
    noteInput: String,
    onNoteChange: (String) -> Unit,
    brightnessTargetInput: String,
    onBrightnessTargetChange: (String) -> Unit,
    enforceBrightnessEnabled: Boolean,
    onEnforceBrightnessChanged: (Boolean) -> Unit,
    keepScreenOnEnabled: Boolean,
    onKeepScreenOnChanged: (Boolean) -> Unit,
    selectedCpuStressLevel: CpuStressLevel,
    onCpuStressLevelSelected: (CpuStressLevel) -> Unit,
    selectedNetworkScenario: NetworkScenarioUi,
    onNetworkScenarioSelected: (NetworkScenarioUi) -> Unit,
    selectedNetworkConnectionMode: NetworkConnectionModeUi,
    onNetworkConnectionModeSelected: (NetworkConnectionModeUi) -> Unit,
    networkRetryEnabled: Boolean,
    onNetworkRetryEnabledChanged: (Boolean) -> Unit,
    networkMaxRetryCountInput: String,
    onNetworkMaxRetryCountChange: (String) -> Unit,
    networkDownloadUrlInput: String,
    onNetworkDownloadUrlChange: (String) -> Unit,
    networkUploadUrlInput: String,
    onNetworkUploadUrlChange: (String) -> Unit,
    networkBurstUrlInput: String,
    onNetworkBurstUrlChange: (String) -> Unit,
    networkUploadChunkKbInput: String,
    onNetworkUploadChunkKbChange: (String) -> Unit,
    networkBurstIntervalMsInput: String,
    onNetworkBurstIntervalMsChange: (String) -> Unit,
    configurationSummary: String
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppSectionCard(
            title = "Experiment Mode",
            subtitle = "Select one experiment path. CPU and Network modes are intentionally exclusive."
        ) {
            ExperimentMode.entries.forEach { mode ->
                OptionRow(
                    title = mode.label,
                    description = mode.description,
                    selected = selectedExperimentMode == mode,
                    enabled = !isCollecting,
                    onClick = { onExperimentModeSelected(mode) }
                )
                SpacerLine()
            }
        }

        AppSectionCard(title = "Common Collection Settings") {
            Text(
                text = "Sampling Interval",
                style = MaterialTheme.typography.titleSmall,
                color = AccentBlue
            )
            SpacerLine()
            listOf(500L, 1000L, 2000L).forEach { option ->
                OptionRow(
                    title = "$option ms",
                    selected = intervalMs == option,
                    enabled = !isCollecting,
                    onClick = { onIntervalSelected(option) }
                )
                SpacerLine()
            }

            OutlinedTextField(
                value = noteInput,
                onValueChange = onNoteChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Experiment Note") },
                placeholder = { Text("Example: 5C_run01") },
                enabled = !isCollecting,
                colors = appOutlinedTextFieldColors()
            )
            SpacerLine()
            OutlinedTextField(
                value = brightnessTargetInput,
                onValueChange = onBrightnessTargetChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Target Brightness (0-255)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !isCollecting,
                colors = appOutlinedTextFieldColors()
            )
            SpacerLine()
            ToggleRow(
                label = "Enforce Brightness",
                checked = enforceBrightnessEnabled,
                enabled = !isCollecting,
                onCheckedChange = onEnforceBrightnessChanged
            )
            SpacerLine()
            ToggleRow(
                label = "Keep Screen Awake During Collection",
                checked = keepScreenOnEnabled,
                enabled = !isCollecting,
                onCheckedChange = onKeepScreenOnChanged
            )
        }

        AnimatedVisibility(visible = selectedExperimentMode == ExperimentMode.CPU_HIGH_POWER) {
            AppSectionCard(
                title = "CPU High Power Settings",
                subtitle = "Pick the CPU stress profile used while battery collection is running."
            ) {
                CpuStressLevel.entries.forEach { level ->
                    OptionRow(
                        title = level.label,
                        description = "Threads=${if (level.threadCount == 0) "Auto" else level.threadCount}, Duty=${level.dutyPercent}%",
                        selected = selectedCpuStressLevel == level,
                        enabled = !isCollecting,
                        onClick = { onCpuStressLevelSelected(level) }
                    )
                    SpacerLine()
                }
            }
        }

        AnimatedVisibility(visible = selectedExperimentMode == ExperimentMode.NETWORK_POWER) {
            AppSectionCard(
                title = "Network Power Settings",
                subtitle = "Configure the live network workload that will run during battery collection."
            ) {
                Text(
                    text = "Scenario",
                    style = MaterialTheme.typography.titleSmall,
                    color = AccentBlue
                )
                SpacerLine()
                NetworkScenarioUi.entries.forEach { scenario ->
                    OptionRow(
                        title = scenario.label,
                        selected = selectedNetworkScenario == scenario,
                        enabled = !isCollecting,
                        onClick = { onNetworkScenarioSelected(scenario) }
                    )
                    SpacerLine()
                }

                Text(
                    text = "Connection Mode",
                    style = MaterialTheme.typography.titleSmall,
                    color = AccentBlue
                )
                SpacerLine()
                NetworkConnectionModeUi.entries.forEach { mode ->
                    OptionRow(
                        title = mode.label,
                        description = "Workers: ${mode.concurrency}",
                        selected = selectedNetworkConnectionMode == mode,
                        enabled = !isCollecting,
                        onClick = { onNetworkConnectionModeSelected(mode) }
                    )
                    SpacerLine()
                }

                OutlinedTextField(
                    value = networkDownloadUrlInput,
                    onValueChange = onNetworkDownloadUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Download URL") },
                    enabled = !isCollecting,
                    colors = appOutlinedTextFieldColors()
                )
                SpacerLine()
                OutlinedTextField(
                    value = networkUploadUrlInput,
                    onValueChange = onNetworkUploadUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Upload URL") },
                    enabled = !isCollecting,
                    colors = appOutlinedTextFieldColors()
                )
                SpacerLine()
                OutlinedTextField(
                    value = networkBurstUrlInput,
                    onValueChange = onNetworkBurstUrlChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Burst URL") },
                    enabled = !isCollecting,
                    colors = appOutlinedTextFieldColors()
                )
                SpacerLine()
                ToggleRow(
                    label = "Enable Retry",
                    checked = networkRetryEnabled,
                    enabled = !isCollecting,
                    onCheckedChange = onNetworkRetryEnabledChanged
                )
                SpacerLine()
                OutlinedTextField(
                    value = networkMaxRetryCountInput,
                    onValueChange = onNetworkMaxRetryCountChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Max Retry Count") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !isCollecting,
                    colors = appOutlinedTextFieldColors()
                )
                SpacerLine()
                OutlinedTextField(
                    value = networkUploadChunkKbInput,
                    onValueChange = onNetworkUploadChunkKbChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Upload Chunk KB") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !isCollecting,
                    colors = appOutlinedTextFieldColors()
                )
                SpacerLine()
                OutlinedTextField(
                    value = networkBurstIntervalMsInput,
                    onValueChange = onNetworkBurstIntervalMsChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Burst Interval ms") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !isCollecting,
                    colors = appOutlinedTextFieldColors()
                )
            }
        }

        AppSectionCard(
            title = "Configuration Summary",
            subtitle = "Review the currently selected setup before moving back to Monitor."
        ) {
            Text(
                text = configurationSummary,
                style = MaterialTheme.typography.bodyMedium,
                color = PrimaryText
            )
        }
    }
}

@Composable
private fun AppSectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = CardBackground)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AccentBlue,
                fontWeight = FontWeight.SemiBold
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Column(modifier = Modifier.padding(top = 12.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun BottomPagerSwitcher(
    currentPage: Int,
    onPageSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppBackground)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        PagerButton(
            label = "Monitor",
            selected = currentPage == 0,
            onClick = { onPageSelected(0) },
            modifier = Modifier.fillMaxWidth(0.48f)
        )
        PagerButton(
            label = "Configuration",
            selected = currentPage == 1,
            onClick = { onPageSelected(1) },
            modifier = Modifier.fillMaxWidth(0.48f)
        )
    }
}

@Composable
private fun PagerButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = modifier,
            colors = primaryButtonColors()
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            border = BorderStroke(1.dp, AccentBlue),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentBlue)
        ) {
            Text(label)
        }
    }
}

@Composable
private fun OptionRow(
    title: String,
    description: String? = null,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) AccentBlueSoft else CardBackground)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            colors = RadioButtonDefaults.colors(
                selectedColor = AccentBlue,
                unselectedColor = SecondaryText
            )
        )
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = PrimaryText,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = PrimaryText
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = CardBackground,
                checkedTrackColor = AccentBlue,
                checkedBorderColor = AccentBlue,
                uncheckedThumbColor = CardBackground,
                uncheckedTrackColor = AccentBlueSoft,
                uncheckedBorderColor = BorderColor
            )
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = SecondaryText
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = PrimaryText
        )
    }
    SpacerLine()
}

@Composable
private fun CollapsibleCardHeader(
    title: String,
    expanded: Boolean,
    summary: String,
    onToggle: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = AccentBlue,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(
                onClick = onToggle,
                colors = ButtonDefaults.textButtonColors(contentColor = AccentBlue)
            ) {
                Text(if (expanded) "Collapse" else "Expand")
            }
        }
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            color = SecondaryText
        )
    }
}

@Composable
private fun SpacerLine(height: Int = 8) {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(height.dp))
}

@Composable
private fun appOutlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AccentBlue,
    focusedLabelColor = AccentBlue,
    cursorColor = AccentBlue,
    unfocusedBorderColor = BorderColor,
    unfocusedLabelColor = SecondaryText
)

@Composable
private fun primaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = AccentBlue,
    contentColor = CardBackground
)

private fun formatSnapshotTimestamp(timeMillis: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMillis))
}

private enum class CheckLevel(val label: String) {
    PASS("[PASS]"),
    WARN("[WARN]"),
    BLOCK("[BLOCK]")
}

private data class PreflightCheckItem(
    val level: CheckLevel,
    val title: String,
    val detail: String
) {
    companion object {
        fun pass(title: String, detail: String) = PreflightCheckItem(CheckLevel.PASS, title, detail)
        fun warn(title: String, detail: String) = PreflightCheckItem(CheckLevel.WARN, title, detail)
        fun block(title: String, detail: String) = PreflightCheckItem(CheckLevel.BLOCK, title, detail)
    }
}

private data class PreflightReport(
    val items: List<PreflightCheckItem>
) {
    val hasBlockers: Boolean
        get() = items.any { it.level == CheckLevel.BLOCK }
}
