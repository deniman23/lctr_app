package com.example.lctr_app.corporate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.lctr_app.device.AppUpdateManager
import com.example.lctr_app.device.DeviceConfigStore

/** Автозапуск трекинга и возобновление OTA после reboot или обновления APK. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        Log.i(TAG, "Received $action — start service and resume OTA")
        val app = context.applicationContext
        LocationServiceStarter.startIfConfigured(app, forceHealthReport = true)
        val config = DeviceConfigStore(app)
        AppUpdateManager(app, config).resumePendingWork()
    }

    companion object {
        private const val TAG = "BootCompleted"
    }
}
