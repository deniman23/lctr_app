package com.example.lctr_app.corporate

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.example.lctr_app.device.AppUpdateState
import com.example.lctr_app.device.DeviceConfigStore

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_INSTALL_RESULT) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val config = DeviceConfigStore(context)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                config.appUpdateState = AppUpdateState.IDLE
                config.appUpdateLastError = null
                Log.i(TAG, "silent install success")
            }
            else -> {
                config.appUpdateState = AppUpdateState.FAILED
                config.appUpdateLastError = "silent_install_$status: $message"
                Log.e(TAG, "silent install failed: $status $message")
            }
        }
    }

    companion object {
        private const val TAG = "InstallResult"
        const val ACTION_INSTALL_RESULT = "com.example.lctr_app.INSTALL_RESULT"
    }
}
