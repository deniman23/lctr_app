package com.example.lctr_app.corporate

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device Owner / Device Admin для корпоративных телефонов.
 * Назначение (один раз в мастерской):
 * adb shell dpm set-device-owner com.example.lctr_app/.corporate.CorporateDeviceAdminReceiver
 */
class CorporateDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device admin enabled")
        DeviceOwnerManager.applyDeviceOwnerPolicies(
            context,
            restartLocationService = true,
        )
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device admin disabled")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Profile provisioning complete")
        DeviceOwnerManager.applyDeviceOwnerPolicies(
            context,
            restartLocationService = true,
        )
    }

    companion object {
        private const val TAG = "CorpDeviceAdmin"
    }
}
