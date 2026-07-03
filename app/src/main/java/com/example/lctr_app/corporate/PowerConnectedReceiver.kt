package com.example.lctr_app.corporate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.lctr_app.device.DeviceConfigStore

/** Подзарядка / USB в машине — повод перезапустить трекинг. */
class PowerConnectedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_POWER_CONNECTED) return
        val app = context.applicationContext
        val config = DeviceConfigStore(app)
        if (config.userId == -1 || config.apiKey.isEmpty() || config.trackingPaused) return
        Log.i(TAG, "power connected — wake tracking")
        DeviceOwnerManager.wakeTracking(app, forceHealthReport = true)
    }

    companion object {
        private const val TAG = "PowerConnected"
    }
}
