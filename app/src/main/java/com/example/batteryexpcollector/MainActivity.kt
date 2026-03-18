package com.example.batteryexpcollector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    // 采集状态标记
    private var isCollecting by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // 界面主体
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // 权限申请器
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { isGranted ->
                        if (isGranted && checkAllPermissions()) {
                            Toast.makeText(this@MainActivity, "权限已获取，点击开始采集", Toast.LENGTH_SHORT).show()
                        }
                    }
                )

                // 启停按钮
                Button(
                    onClick = {
                        if (isCollecting) {
                            stopCollection()
                        } else {
                            if (checkAllPermissions()) {
                                startCollection()
                            } else {
                                // 申请权限
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                // 引导开启修改系统设置权限
                                if (!Settings.System.canWrite(this@MainActivity)) {
                                    Toast.makeText(this@MainActivity, "请开启“修改系统设置”权限", Toast.LENGTH_LONG).show()
                                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                                    intent.data = Uri.parse("package:$packageName")
                                    startActivity(intent)
                                }
                            }
                        }
                    }
                ) {
                    Text(
                        text = if (isCollecting) "停止采集" else "开始采集",
                        fontSize = 20.sp
                    )
                }
            }
        }
    }

    // 权限检查
    private fun checkAllPermissions(): Boolean {
        // 1. 检查通知权限 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
        }
        // 2. 检查修改系统设置权限
        if (!Settings.System.canWrite(this)) {
            return false
        }
        return true
    }

    // 启动采集服务
    private fun startCollection() {
        val serviceIntent = Intent(this, BatteryCollectService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        isCollecting = true
        Toast.makeText(this, "采集已开始", Toast.LENGTH_SHORT).show()
    }

    // 停止采集服务
    private fun stopCollection() {
        val serviceIntent = Intent(this, BatteryCollectService::class.java)
        stopService(serviceIntent)
        isCollecting = false
        Toast.makeText(this, "采集已停止，数据已保存", Toast.LENGTH_LONG).show()
    }
}