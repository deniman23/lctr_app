package com.example.lctr_app.corporate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.lctr_app.device.DeviceConfigStore

/**
 * Разблокировка экрана — запускаем трекинг (без входа в приложение).
 */
class UserPresentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_USER_PRESENT) return
        val app = context.applicationContext
        val config = DeviceConfigStore(app)
        config.loadFromLegacyPrefsIfNeeded()
        if (config.userId == -1 || config.apiKey.isEmpty() || config.trackingPaused) return
        Log.i(TAG, "user present — wake tracking")
        DeviceOwnerManager.wakeTracking(app, forceHealthReport = true)
    }

    companion object {
        private const val TAG = "UserPresent"
    }
}
