package com.example.lctr_app.corporate

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.lctr_app.LocationService
import com.example.lctr_app.device.DeviceConfigStore

/**
 * Периодический будильник: поднимает LocationService и опрос сервера,
 * даже если Android убил процесс (в отличие от Handler внутри сервиса).
 */
class LocatorPollReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val config = DeviceConfigStore(app)
        config.loadFromLegacyPrefsIfNeeded()

        if (config.userId != -1 && config.apiKey.isNotEmpty() && !config.trackingPaused) {
            Log.i(TAG, "alarm tick — wake tracking + force location")
            val intent = Intent(app, LocationService::class.java).apply {
                putExtra(LocationService.EXTRA_FORCE_LOCATION, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
            DeviceOwnerManager.wakeTracking(app, forceHealthReport = false)
        }
        scheduleNext(app)
    }

    companion object {
        private const val TAG = "LocatorPollAlarm"
        private const val REQUEST_CODE = 41_003
        const val ACTION = "com.example.lctr_app.LOCATOR_POLL_ALARM"
        /** Интервал опроса/пробуждения с Device Owner (сек). */
        const val INTERVAL_MS = 2 * 60 * 1000L

        fun scheduleNext(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, LocatorPollReceiver::class.java).setAction(ACTION)
            val pi = PendingIntent.getBroadcast(
                app,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val trigger = SystemClock.elapsedRealtime() + INTERVAL_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
        }
    }
}
