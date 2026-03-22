package com.example.batteryexpcollector.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class ExperimentAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val phaseIndex = intent?.getIntExtra(
            ExperimentRunnerService.EXTRA_PHASE_INDEX,
            -1
        ) ?: -1

        val serviceIntent = Intent(context, ExperimentRunnerService::class.java).apply {
            action = ExperimentRunnerService.ACTION_PHASE_TIMEOUT
            putExtra(ExperimentRunnerService.EXTRA_PHASE_INDEX, phaseIndex)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}