package com.example.lctr_app.corporate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.example.lctr_app.device.AppUpdateState
import com.example.lctr_app.device.DeviceConfigStore
import com.example.lctr_app.device.LocatorHttpClient

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_INSTALL_RESULT) return
        val pending = goAsync()
        val app = context.applicationContext
        val config = DeviceConfigStore(app)
        val http = LocatorHttpClient(config)
        val commandId = config.appUpdateCommandId

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                config.appUpdateState = AppUpdateState.IDLE
                config.appUpdateLastError = null
                config.pendingAppUpdateUrl = null
                config.appUpdateCommandId = null
                Log.i(TAG, "silent install success")
                commandId?.let { http.ackCommand(it, "success", message) }
                LocationServiceStarter.startIfConfigured(app, forceHealthReport = true)
            }
            else -> {
                val err = "silent_install_$status: ${message ?: "unknown"}"
                config.appUpdateState = AppUpdateState.FAILED
                config.appUpdateLastError = err
                Log.e(TAG, "silent install failed: $err")
                commandId?.let { http.ackCommand(it, "install_failed", err) }
            }
        }
        pending.finish()
    }

    companion object {
        private const val TAG = "InstallResult"
        const val ACTION_INSTALL_RESULT = "com.example.lctr_app.INSTALL_RESULT"
    }
}
