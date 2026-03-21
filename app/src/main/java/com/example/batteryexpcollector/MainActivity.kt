package com.example.batteryexpcollector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private var isCollecting by mutableStateOf(false)
    private var currentFilePath by mutableStateOf("")
    private var lastFilePath by mutableStateOf("")

    private var notificationPermissionGranted by mutableStateOf(false)
    private var writeSettingsGranted by mutableStateOf(false)

    private var intervalMs by mutableLongStateOf(DEFAULT_INTERVAL_MS)
    private var noteInput by mutableStateOf("")
    private var brightnessTargetInput by mutableStateOf(DEFAULT_BRIGHTNESS_TARGET.toString())
    private var enforceBrightnessEnabled by mutableStateOf(true)
    private var keepScreenOnEnabled by mutableStateOf(true)
    private var eventMarkerInput by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                                Text(
                                    text = if (isCollecting) "状态：采集中" else "状态：空闲",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(text = "设备：${Build.MANUFACTURER} ${Build.MODEL}")
                                Text(
                                    text = "通知权限：${if (notificationPermissionGranted) "已授权" else "未授权"}"
                                )
                                Text(
                                    text = "修改系统设置权限：${if (writeSettingsGranted) "已授权" else "未授权"}"
                                )
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

                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "实验配置",
                                    style = MaterialTheme.typography.titleMedium
                                )

                                Spacer(modifier = Modifier.height(12.dp))
                                Text(text = "采样间隔")
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    listOf(500L, 1000L, 2000L).forEach { option ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = intervalMs == option,
                                                onClick = { intervalMs = option }
                                            )
                                            Text(text = "${option} ms")
                                            Spacer(modifier = Modifier.width(8.dp))
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                OutlinedTextField(
                                    value = noteInput,
                                    onValueChange = { noteInput = it },
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
                                        onCheckedChange = { enforceBrightnessEnabled = it },
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
                                        onClick = {
                                            openWriteSettingsPage()
                                        },
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
                                            if (ready) {
                                                startCollection()
                                            }
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
    }

    override fun onDestroy() {
        super.onDestroy()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun refreshUiStateFromPrefs() {
        val session = CollectionPrefs.loadSessionState(this)
        isCollecting = session.isCollecting
        currentFilePath = session.currentFilePath
        lastFilePath = session.lastFilePath

        notificationPermissionGranted = hasNotificationPermission()
        writeSettingsGranted = Settings.System.canWrite(this)

        val config = session.config
        intervalMs = config.intervalMs
        noteInput = config.experimentNote
        brightnessTargetInput = config.brightnessTarget.toString()
        enforceBrightnessEnabled = config.enforceBrightness
        keepScreenOnEnabled = config.keepScreenOn

        updateKeepScreenOnFlag()
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

    private fun startCollection() {
        val config = buildConfigFromInputs()
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
        scheduleUiRefresh()
    }

    private fun stopCollection() {
        val stopIntent = Intent(this, BatteryCollectService::class.java).apply {
            action = BatteryCollectService.ACTION_STOP_COLLECTION
        }
        startService(stopIntent)

        Toast.makeText(this, "停止请求已发送", Toast.LENGTH_SHORT).show()
        scheduleUiRefresh()
    }

    private fun sendEventMarker(marker: String) {
        val markerIntent = Intent(this, BatteryCollectService::class.java).apply {
            action = BatteryCollectService.ACTION_MARK_EVENT
            putExtra(BatteryCollectService.EXTRA_EVENT_MARKER, marker)
        }
        startService(markerIntent)
        Toast.makeText(this, "事件标记已写入队列", Toast.LENGTH_SHORT).show()
    }

    private fun scheduleUiRefresh() {
        window.decorView.postDelayed(
            { refreshUiStateFromPrefs() },
            500L
        )
    }

    private fun updateKeepScreenOnFlag() {
        val shouldKeepOn = isCollecting && keepScreenOnEnabled
        if (shouldKeepOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}