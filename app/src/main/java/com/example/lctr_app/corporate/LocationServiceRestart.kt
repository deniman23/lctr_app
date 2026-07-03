package com.example.lctr_app.corporate

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.example.lctr_app.LocationService

/** Перезапуск LocationService после убийства процесса системой. */
object LocationServiceRestart {

    fun scheduleRestart(context: Context, delayMs: Long = 1_000L) {
        val app = context.applicationContext
        val config = DeviceConfigStore(app)
        if (config.userId == -1 || config.apiKey.isEmpty() || config.trackingPaused) return

        val restart = Intent(app, LocationService::class.java)
        val pi = PendingIntent.getService(
            app,
            RESTART_REQUEST_CODE,
            restart,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val trigger = SystemClock.elapsedRealtime() + delayMs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
        } else {
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
        }
    }

    private const val RESTART_REQUEST_CODE = 41_001
}
