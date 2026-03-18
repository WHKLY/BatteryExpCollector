package com.example.batteryexpcollector

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class BatteryCollectService : Service() {
    private val TAG = "BatteryExpData"
    private val CHANNEL_ID = "BatteryCollectChannel"
    private val NOTIFICATION_ID = 1001

    private var collectTimer: Timer? = null
    private var csvWriter: FileWriter? = null
    private var startTimeMillis: Long = 0

    // 流量缓存
    private var lastTxBytes: Long = 0
    private var lastRxBytes: Long = 0
    private var lastTimeStamp: Long = 0

    private var batteryManager: BatteryManager? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var connectivityManager: ConnectivityManager? = null

    // 缓存电池数据
    private var batteryLevel = 0
    private var batteryVoltage = 0
    private var batteryCurrent = 0
    private var batteryTemperature = 0f
    private var batteryCapacity = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, getNotification())

        batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        registerBatteryReceiver()

        // 初始化流量
        lastTxBytes = android.net.TrafficStats.getTotalTxBytes()
        lastRxBytes = android.net.TrafficStats.getTotalRxBytes()
        lastTimeStamp = System.currentTimeMillis()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startCollecting()
        } catch (e: Exception) {
            Log.e(TAG, "启动失败", e)
        }
        return START_STICKY
    }

    private fun startCollecting() {
        startTimeMillis = System.currentTimeMillis()
        initCsvFile()

        collectTimer?.cancel()
        collectTimer = Timer()
        collectTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    collectAndLogDataSafe()
                } catch (e: Exception) {
                    Log.e(TAG, "采集异常", e)
                }
            }
        }, 0, 1000)
    }

    private fun collectAndLogDataSafe() {
        val timestamp = System.currentTimeMillis()
        val elapsedTime = (timestamp - startTimeMillis) / 1000

        // 计算网络吞吐率 (安全版)
        val (txRate, rxRate) = calculateNetRatesSafe(timestamp)

        // 【核心】所有数据安全读取 + 字符串拼接，绝对不崩
        val dataLine = "" +
                timestamp + "," +
                elapsedTime + "," +
                batteryLevel + "," +
                batteryVoltage + "," +
                batteryCurrent + "," +
                batteryTemperature + "," +
                batteryCapacity + "," +
                getScreenBrightnessSafe() + "," +
                (if (isScreenOnSafe()) 1 else 0) + "," +
                getCpuFreqSafe() + "," + // 新增：CPU频率
                getNetTypeSafe() + "," +   // 新增：网络类型
                txRate + "," +             // 新增：上行
                rxRate                      // 新增：下行

        Log.d(TAG, dataLine)
        writeToCsvSafe(dataLine)
    }

    // ---------------- 新增：安全的CPU频率读取 ----------------
    private fun getCpuFreqSafe(): String {
        return try {
            val reader = RandomAccessFile("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", "r")
            val freq = reader.readLine().trim()
            reader.close()
            freq
        } catch (e: Exception) {
            "N/A" // 读不到就返回N/A，不崩
        }
    }

    // ---------------- 新增：安全的网络类型读取 ----------------
    private fun getNetTypeSafe(): String {
        return try {
            val network = connectivityManager?.activeNetwork ?: return "NoNet"
            val caps = connectivityManager?.getNetworkCapabilities(network) ?: return "NoNet"

            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Other"
            }
        } catch (e: Exception) {
            "Error"
        }
    }

    // ---------------- 新增：安全的吞吐率计算 ----------------
    private fun calculateNetRatesSafe(currentTime: Long): Pair<Long, Long> {
        return try {
            val currentTx = android.net.TrafficStats.getTotalTxBytes()
            val currentRx = android.net.TrafficStats.getTotalRxBytes()
            val timeDelta = (currentTime - lastTimeStamp) / 1000.0

            val tx = if (timeDelta > 0) ((currentTx - lastTxBytes) / timeDelta).toLong() else 0L
            val rx = if (timeDelta > 0) ((currentRx - lastRxBytes) / timeDelta).toLong() else 0L

            lastTxBytes = currentTx
            lastRxBytes = currentRx
            lastTimeStamp = currentTime

            Pair(tx, rx)
        } catch (e: Exception) {
            Pair(0L, 0L)
        }
    }

    // ---------------- 电池数据读取 ----------------
    private fun registerBatteryReceiver() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (Intent.ACTION_BATTERY_CHANGED == intent?.action) {
                    batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    batteryVoltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
                    batteryTemperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0f

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        batteryCurrent = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.toInt() ?: 0
                        batteryCapacity = batteryManager?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0L
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)
    }

    // ---------------- 基础辅助方法 ----------------
    private fun getScreenBrightnessSafe(): Int {
        return try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) { 0 }
    }

    private fun isScreenOnSafe(): Boolean {
        return try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) pm.isInteractive else @Suppress("DEPRECATION") pm.isScreenOn
        } catch (e: Exception) { true }
    }

    private fun initCsvFile() {
        try {
            val dir = File(getExternalFilesDir(null), "BatteryExpData")
            if (!dir.exists()) dir.mkdirs()
            val fileName = SimpleDateFormat("yyyyMMdd_HHmmss'.csv'", Locale.US).format(Date())
            val csvFile = File(dir, fileName)
            csvWriter = FileWriter(csvFile, true)

            // 【更新】完整表头
            csvWriter?.append("Timestamp,ElapsedTime_S,SOC_Integer,Voltage_mV,Current_uA,BatteryTemp_C,ChargeCounter_uAh,Brightness,ScreenOn,CPU0_Freq_kHz,NetType,Tx_Rate_Bps,Rx_Rate_Bps\n")
            csvWriter?.flush()
        } catch (e: Exception) { Log.e(TAG, "CSV创建失败", e) }
    }

    private fun writeToCsvSafe(line: String) {
        csvWriter?.let {
            try { it.append(line).append("\n").flush() } catch (e: Exception) { Log.e(TAG, "写入失败", e) }
        }
    }

    // ---------------- 通知 ----------------
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "电池采集", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun getNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("电池实验采集中")
            .setContentText("请勿关闭")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        collectTimer?.cancel()
        batteryReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) {} }
        try { csvWriter?.close() } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}