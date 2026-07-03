package com.example.lctr_app.corporate

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.lctr_app.device.DeviceConfigStore

/**
 * Каждые ~5 мин проверяет, что трекинг запущен. Если Android убил сервис — поднимает снова.
 */
class LocationServiceWatchdog : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val config = DeviceConfigStore(app)
        config.loadFromLegacyPrefsIfNeeded()

        if (config.userId != -1 && config.apiKey.isNotEmpty() && !config.trackingPaused) {
            val running = ServiceRunningHelper.isLocationServiceRunning(app)
            if (!running) {
                Log.w(TAG, "LocationService not running — restarting")
                LocationServiceStarter.startIfConfigured(app, forceHealthReport = true)
            }
        }

        scheduleNext(app)
    }

    companion object {
        private const val TAG = "LocationWatchdog"
        private const val REQUEST_CODE = 41_002
        const val INTERVAL_MS = 5 * 60 * 1000L

        fun scheduleNext(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, LocationServiceWatchdog::class.java).setAction(ACTION)
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

        private const val ACTION = "com.example.lctr_app.LOCATION_WATCHDOG"
    }
}
