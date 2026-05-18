package com.example.lctr_app.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.lctr_app.device.AppUpdateManager
import com.example.lctr_app.device.DeviceConfigStore

/** Кнопка «Установить» в уведомлении об обновлении. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AppUpdateManager(context, DeviceConfigStore(context)).promptInstallIfReady()
    }
}
