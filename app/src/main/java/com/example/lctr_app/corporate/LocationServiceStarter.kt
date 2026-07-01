package com.example.lctr_app.corporate

import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.lctr_app.LocationService
import com.example.lctr_app.device.DeviceConfigStore

object LocationServiceStarter {
    fun startIfConfigured(context: Context, forceHealthReport: Boolean = false) {
        val app = context.applicationContext
        val config = DeviceConfigStore(app)
        config.loadFromLegacyPrefsIfNeeded()
        if (config.userId == -1 || config.apiKey.isEmpty()) return

        val intent = Intent(app, LocationService::class.java).apply {
            putExtra(LocationService.EXTRA_USER_ID, config.userId)
            putExtra(LocationService.EXTRA_API_KEY, config.apiKey)
            if (forceHealthReport) {
                putExtra(LocationService.EXTRA_FORCE_HEALTH_REPORT, true)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }
        config.serviceActive = true
        config.syncLegacyPrefs()
    }
}
