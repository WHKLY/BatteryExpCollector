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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

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
            val scrollState = rememberScrollState()
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { granted ->
                    notificationPermissionGranted = granted || hasNotificationPermission()
                    if (notificationPermissionGranted) {
                        Toast.makeText(
                            this@MainActivity,
                            "通知权限已获取，可以开始采集",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "通知权限未授权，前台服务通知可能无法正常显示",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    refreshUiStateFromPrefs()
                }
            )

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "BatteryExpCollector 实验采集端",
                            style = MaterialTheme.typography.headlineSmall
                        )

                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                CollapsibleCardHeader(
                                    title = "设备状态",
                                    expanded = deviceStatusExpanded,
                                    summary = buildDeviceStatusSummary(),
                                    onToggle = { deviceStatusExpanded = !deviceStatusExpanded }
                                )

                                AnimatedVisibility(visible = deviceStatusExpanded) {
                                    Column {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = if (isCollecting) "状态：采集中" else "状态：空闲",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(text = "设备：${Build.MANUFACTURER} ${Build.MODEL}")
                                        Text(text = "通知权限：${if (notificationPermissionGranted) "已授权" else "未授权"}")
                                        Text(text = "修改系统设置权限：${if (writeSettingsGranted) "已授权" else "未授权"}")
                                        Text(
                                            text = "数据目录：${
                                                getExternalFilesDir(null)?.resolve("BatteryExpData")?.absolutePath
                                                    ?: "不可用"
                                            }"
                                        )

                                        if (currentFilePath.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(text = "当前文件：$currentFilePath")
                                        } else if (lastFilePath.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(text = "最近文件：$lastFilePath")
                                        }
                                    }
                                }
                            }
                        }

                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                val blockCount = preflightChecks.count { it.level == CheckLevel.BLOCK }
                                val warnCount = preflightChecks.count { it.level == CheckLevel.WARN }

                                CollapsibleCardHeader(
                                    title = "开始前自检",
                                    expanded = preflightExpanded,
                                    summary = "阻止项：$blockCount，警告项：$warnCount",
                                    onToggle = { preflightExpanded = !preflightExpanded }
                                )

                                AnimatedVisibility(visible = preflightExpanded) {
                                    Column {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(text = "阻止项：$blockCount，警告项：$warnCount")

                                        Spacer(modifier = Modifier.height(8.dp))
                                        preflightChecks.forEach { item ->
                                            val color = when (item.level) {
                                                CheckLevel.PASS -> MaterialTheme.colorScheme.onSurface
                                                CheckLevel.WARN -> MaterialTheme.colorScheme.tertiary
                                                CheckLevel.BLOCK -> MaterialTheme.colorScheme.error
                                            }
                                            Text(
                                                text = "${item.level.label} ${item.title}：${item.detail}",
                                                color = color
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))
                                        Button(
                                            onClick = { refreshPreflightChecks() },
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text("刷新自检")
                                        }
                                    }
                                }
                            }
                        }

                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "运行中状态面板",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                if (latestSample.timestampMillis > 0L) {
                                    Text(text = "最近写入时间：${formatTimestamp(latestSample.timestampMillis)}")
                                    Text(text = "已运行：${latestSample.elapsedSec} s")
                                    Text(text = "SOC：${latestSample.socInteger?.toString() ?: "N/A"}")
                                    Text(
                                        text = "电池温度：${
                                            latestSample.batteryTempC?.let {
                                                String.format(Locale.US, "%.2f °C", it)
                                            } ?: "N/A"
                                        }"
                                    )
                                    Text(text = "电流：${latestSample.currentUa?.toString() ?: "N/A"} uA")
                                    Text(text = "亮度：${latestSample.brightness?.toString() ?: "N/A"}")
                                    Text(
                                        text = "屏幕状态：${
                                            when (latestSample.screenOn) {
                                                true -> "亮屏"
                                                false -> "熄屏"
                                                null -> "N/A"
                                            }
                                        }"
                                    )
                                    Text(
                                        text = "网络类型：${
                                            latestSample.netType.ifBlank { "N/A" }
                                        }"
                                    )
                                    Text(
                                        text = "采样文件：${
                                            latestSample.currentFilePath.takeIf { it.isNotBlank() }?.let {
                                                File(it).name
                                            } ?: "N/A"
                                        }"
                                    )
                                } else {
                                    Text(text = "暂无采样快照")
                                }
                            }
                        }

                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "实验配置",
                                    style = MaterialTheme.typography.titleMedium
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                Text(text = "采样间隔")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    listOf(500L, 1000L, 2000L).forEach { option ->
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            RadioButton(
                                                selected = intervalMs == option,
                                                onClick = {
                                                    intervalMs = option
                                                    refreshPreflightChecks()
                                                }
                                            )
                                            Text(text = "${option} ms")
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = noteInput,
                                    onValueChange = {
                                        noteInput = it
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("实验备注") },
                                    placeholder = { Text("例如：25C_run01") },
                                    enabled = !isCollecting
                                )

                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = brightnessTargetInput,
                                    onValueChange = { input ->
                                        brightnessTargetInput = input.filter { it.isDigit() }.take(3)
                                        refreshPreflightChecks()
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("目标亮度 (0-255)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    enabled = !isCollecting
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "尝试锁定亮度")
                                    Switch(
                                        checked = enforceBrightnessEnabled,
                                        onCheckedChange = {
                                            enforceBrightnessEnabled = it
                                            refreshPreflightChecks()
                                        },
                                        enabled = !isCollecting
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(text = "采集中保持屏幕常亮")
                                    Switch(
                                        checked = keepScreenOnEnabled,
                                        onCheckedChange = {
                                            keepScreenOnEnabled = it
                                            updateKeepScreenOnFlag()
                                            refreshPreflightChecks()
                                        },
                                        enabled = !isCollecting
                                    )
                                }
                            }
                        }

                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "控制",
                                    style = MaterialTheme.typography.titleMedium
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                if (!writeSettingsGranted) {
                                    Button(
                                        onClick = { openWriteSettingsPage() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("去开启“修改系统设置”权限")
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    !notificationPermissionGranted
                                ) {
                                    Button(
                                        onClick = {
                                            notificationPermissionLauncher.launch(
                                                Manifest.permission.POST_NOTIFICATIONS
                                            )
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("申请通知权限")
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                Button(
                                    onClick = {
                                        if (isCollecting) {
                                            stopCollection()
                                        } else {
                                            val ready = ensurePermissionsBeforeStart {
                                                notificationPermissionLauncher.launch(
                                                    Manifest.permission.POST_NOTIFICATIONS
                                                )
                                            }
                                            if (!ready) {
                                                refreshPreflightChecks()
                                                return@Button
                                            }

                                            val config = buildConfigFromInputs()
                                            val report = evaluatePreflightChecks(config)
                                            preflightChecks = report.items

                                            if (report.hasBlockers) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "存在阻止项，请先处理自检面板中的问题",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                                if (!preflightExpanded) {
                                                    preflightExpanded = true
                                                }
                                                return@Button
                                            }

                                            startCollection(config)
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (isCollecting) "停止采集" else "开始采集")
                                }
                            }
                        }

                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "事件标记",
                                    style = MaterialTheme.typography.titleMedium
                                )

                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = eventMarkerInput,
                                    onValueChange = { eventMarkerInput = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("事件内容") },
                                    placeholder = { Text("例如：phase1_start") }
                                )

                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        val marker = eventMarkerInput.trim()
                                        if (marker.isBlank()) {
                                            Toast.makeText(
                                                this@MainActivity,
                                                "事件内容不能为空",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            return@Button
                                        }
                                        sendEventMarker(marker)
                                        eventMarkerInput = ""
                                    },
                                    enabled = isCollecting,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("写入事件标记")
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "说明：常亮仅在本 App 保持前台时有效；正式实验时请不要切到后台或锁屏。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
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
    }

    private fun refreshPreflightChecks() {
        preflightChecks = evaluatePreflightChecks(buildConfigFromInputs()).items
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
            Toast.makeText(this, "请先授予通知权限", Toast.LENGTH_SHORT).show()
            onNeedNotificationPermission()
            return false
        }

        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this, "请先授予“修改系统设置”权限", Toast.LENGTH_LONG).show()
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

    private fun buildConfigFromInputs(): CollectionConfig {
        val brightness = brightnessTargetInput.toIntOrNull()?.coerceIn(0, 255)
            ?: DEFAULT_BRIGHTNESS_TARGET

        return CollectionConfig(
            intervalMs = intervalMs,
            experimentNote = noteInput.trim(),
            brightnessTarget = brightness,
            enforceBrightness = enforceBrightnessEnabled,
            keepScreenOn = keepScreenOnEnabled
        )
    }

    private fun evaluatePreflightChecks(config: CollectionConfig): PreflightReport {
        val items = mutableListOf<PreflightCheckItem>()

        items += if (hasNotificationPermission()) {
            PreflightCheckItem.pass("通知权限", "已授权")
        } else {
            PreflightCheckItem.block("通知权限", "未授权")
        }

        items += if (Settings.System.canWrite(this)) {
            PreflightCheckItem.pass("修改系统设置权限", "已授权")
        } else {
            PreflightCheckItem.block("修改系统设置权限", "未授权")
        }

        val dataDir = getExternalFilesDir(null)?.resolve("BatteryExpData")
        val dataDirReady = try {
            dataDir != null && (dataDir.exists() || dataDir.mkdirs()) && dataDir.isDirectory && dataDir.canWrite()
        } catch (_: Exception) {
            false
        }
        items += if (dataDirReady) {
            PreflightCheckItem.pass("数据目录", dataDir?.absolutePath ?: "可写")
        } else {
            PreflightCheckItem.block("数据目录", "不可写或无法创建")
        }

        val brightnessModeItem = try {
            val mode = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE)
            if (mode == Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) {
                PreflightCheckItem.pass("自动亮度", "已关闭")
            } else {
                PreflightCheckItem.warn("自动亮度", "仍为自动模式，建议关闭")
            }
        } catch (_: Exception) {
            PreflightCheckItem.warn("自动亮度", "无法读取")
        }
        items += brightnessModeItem

        val brightnessItem = try {
            val currentBrightness =
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            if (abs(currentBrightness - config.brightnessTarget) <= 10) {
                PreflightCheckItem.pass(
                    "当前亮度",
                    "当前值 $currentBrightness，接近目标 ${config.brightnessTarget}"
                )
            } else {
                PreflightCheckItem.warn(
                    "当前亮度",
                    "当前值 $currentBrightness，与目标 ${config.brightnessTarget} 偏差较大"
                )
            }
        } catch (_: Exception) {
            PreflightCheckItem.warn("当前亮度", "无法读取")
        }
        items += brightnessItem

        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = batteryStatus?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL ||
                plugged > 0

        items += if (charging) {
            PreflightCheckItem.warn("充电状态", "当前处于充电/接电状态，建议拔掉充电线后开始")
        } else {
            PreflightCheckItem.pass("充电状态", "当前未充电")
        }

        items += if (config.enforceBrightness) {
            PreflightCheckItem.pass("亮度锁定策略", "开始采集后会持续尝试锁定亮度")
        } else {
            PreflightCheckItem.warn("亮度锁定策略", "已关闭强制亮度，实验中亮度可能漂移")
        }

        items += if (config.keepScreenOn) {
            PreflightCheckItem.pass("常亮策略", "采集中会在前台请求常亮")
        } else {
            PreflightCheckItem.warn("常亮策略", "未启用常亮，实验中更容易熄屏")
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
        }

        ContextCompat.startForegroundService(this, startIntent)
        isCollecting = true
        updateKeepScreenOnFlag()
        Toast.makeText(this, "采集启动请求已发送", Toast.LENGTH_SHORT).show()

        uiHandler.postDelayed({ refreshUiStateFromPrefs() }, 600L)
    }

    private fun stopCollection() {
        val stopIntent = Intent(this, BatteryCollectService::class.java).apply {
            action = BatteryCollectService.ACTION_STOP_COLLECTION
        }
        startService(stopIntent)

        Toast.makeText(this, "停止请求已发送", Toast.LENGTH_SHORT).show()
        uiHandler.postDelayed({ refreshUiStateFromPrefs() }, 600L)
    }

    private fun sendEventMarker(marker: String) {
        val markerIntent = Intent(this, BatteryCollectService::class.java).apply {
            action = BatteryCollectService.ACTION_MARK_EVENT
            putExtra(BatteryCollectService.EXTRA_EVENT_MARKER, marker)
        }
        startService(markerIntent)
        Toast.makeText(this, "事件标记已写入队列", Toast.LENGTH_SHORT).show()
    }

    private fun updateKeepScreenOnFlag() {
        val shouldKeepOn = isCollecting && keepScreenOnEnabled
        if (shouldKeepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun formatTimestamp(timeMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMillis))
    }

    private fun buildDeviceStatusSummary(): String {
        val statusText = if (isCollecting) "采集中" else "空闲"
        val fileName = when {
            currentFilePath.isNotBlank() -> File(currentFilePath).name
            lastFilePath.isNotBlank() -> File(lastFilePath).name
            else -> "无"
        }
        return "状态：$statusText，文件：$fileName"
    }
}

@androidx.compose.runtime.Composable
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
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = onToggle) {
                Text(if (expanded) "收起" else "展开")
            }
        }
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private enum class CheckLevel(val label: String) {
    PASS("[通过]"),
    WARN("[警告]"),
    BLOCK("[阻止]")
}

private data class PreflightCheckItem(
    val level: CheckLevel,
    val title: String,
    val detail: String
) {
    companion object {
        fun pass(title: String, detail: String) =
            PreflightCheckItem(CheckLevel.PASS, title, detail)

        fun warn(title: String, detail: String) =
            PreflightCheckItem(CheckLevel.WARN, title, detail)

        fun block(title: String, detail: String) =
            PreflightCheckItem(CheckLevel.BLOCK, title, detail)
    }
}

private data class PreflightReport(
    val items: List<PreflightCheckItem>
) {
    val hasBlockers: Boolean
        get() = items.any { it.level == CheckLevel.BLOCK }
}