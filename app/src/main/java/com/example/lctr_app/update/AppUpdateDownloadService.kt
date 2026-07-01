package com.example.lctr_app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.lctr_app.device.AppUpdateManager
import com.example.lctr_app.device.LocatorHttpClient
import com.example.lctr_app.device.DeviceConfigStore

/**
 * Foreground download so обновление не обрывается при сворачивании приложения.
 */
class AppUpdateDownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val commandId = intent?.getStringExtra(EXTRA_COMMAND_ID)
        createChannel()
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Обновление приложения")
            .setContentText("Скачивание новой версии…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()
        startForeground(NOTIF_ID, notif)

        val config = DeviceConfigStore(this)
        val manager = AppUpdateManager(this, config)
        val http = LocatorHttpClient(config)
        Thread {
            try {
                val ok = manager.downloadPendingUpdate { progress ->
                    config.appUpdateDownloadProgress = progress
                    updateProgress(progress)
                }
                if (ok) {
                    manager.onDownloadFinished(commandId, http)
                } else {
                    manager.onDownloadFailed(commandId, config.appUpdateLastError ?: "download_failed", http)
                }
            } catch (e: Exception) {
                Log.e(TAG, "download service error", e)
                manager.onDownloadFailed(commandId, e.message ?: "unknown", http)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()

        return START_NOT_STICKY
    }

    private fun updateProgress(percent: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Обновление приложения")
            .setContentText("Скачивание: $percent%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "Обновления приложения",
            NotificationManager.IMPORTANCE_LOW,
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    companion object {
        private const val TAG = "AppUpdateDownload"
        private const val CHANNEL_ID = "app_update_download"
        private const val NOTIF_ID = 42
        const val EXTRA_COMMAND_ID = "command_id"

        fun start(context: Context, commandId: String?) {
            val intent = Intent(context, AppUpdateDownloadService::class.java).apply {
                putExtra(EXTRA_COMMAND_ID, commandId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
