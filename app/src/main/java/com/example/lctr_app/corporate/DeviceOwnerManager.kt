package com.example.lctr_app.corporate

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.example.lctr_app.device.DeviceConfigStore
import com.example.lctr_app.LocationService
import java.io.File
import java.io.FileInputStream

object DeviceOwnerManager {

    private const val TAG = "DeviceOwnerManager"

    fun adminComponent(context: Context): ComponentName =
        ComponentName(context, CorporateDeviceAdminReceiver::class.java)

    fun isDeviceOwner(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isDeviceOwnerApp(context.packageName)
    }

    fun isDeviceAdmin(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(adminComponent(context))
    }

    fun applyDeviceOwnerPolicies(
        context: Context,
        restartLocationService: Boolean = false,
    ) {
        if (!isDeviceOwner(context)) return
        val prefs = context.getSharedPreferences(PREFS_DO, Context.MODE_PRIVATE)
        val policiesAlreadyApplied = prefs.getBoolean(KEY_POLICIES_APPLIED, false)

        if (!policiesAlreadyApplied) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = adminComponent(context)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.setPermissionPolicy(
                        admin,
                        DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT,
                    )
                }
                grantRuntimePermissions(dpm, admin, context)
                addBatteryWhitelist(context)
                prefs.edit().putBoolean(KEY_POLICIES_APPLIED, true).apply()
                Log.i(TAG, "Device owner policies applied (once)")
            } catch (e: Exception) {
                Log.e(TAG, "apply policies failed", e)
            }
        }

        if (!restartLocationService) return
        val config = DeviceConfigStore(context)
        val shouldRun = config.serviceActive ||
            (config.userId != -1 && config.apiKey.isNotEmpty())
        if (!shouldRun || ServiceRunningHelper.isLocationServiceRunning(context)) return

        val intent = Intent(context, LocationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private const val PREFS_DO = "locator_device_owner"
    private const val KEY_POLICIES_APPLIED = "policies_applied"

    private fun grantRuntimePermissions(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        context: Context,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pkg = context.packageName
        val permissions = listOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.FOREGROUND_SERVICE_LOCATION,
        )
        for (permission in permissions) {
            try {
                dpm.setPermissionGrantState(
                    admin,
                    pkg,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            } catch (e: Exception) {
                Log.w(TAG, "grant $permission: ${e.message}")
            }
        }
    }

    /** Whitelist для Doze / оптимизации батареи (работает на Device Owner). */
    fun addBatteryWhitelist(context: Context) {
        if (!isDeviceOwner(context)) return
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("cmd", "deviceidle", "whitelist", "+${context.packageName}"),
            )
            val code = process.waitFor()
            Log.i(TAG, "battery whitelist exit=$code")
        } catch (e: Exception) {
            Log.w(TAG, "battery whitelist failed", e)
        }
    }

    fun installApkSilently(context: Context, apk: File): Boolean {
        if (!isDeviceOwner(context)) return false
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                FileInputStream(apk).use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, InstallResultReceiver::class.java).apply {
                    action = InstallResultReceiver.ACTION_INSTALL_RESULT
                }
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
            Log.i(TAG, "silent install session started")
            true
        } catch (e: Exception) {
            Log.e(TAG, "silent install failed", e)
            false
        }
    }

    fun provisioningAdbCommand(packageName: String): String =
        "adb shell dpm set-device-owner $packageName/.corporate.CorporateDeviceAdminReceiver"

    /** Debug-only: drop Device Owner so the app can be uninstalled (e.g. switch debug → release signing). */
    fun clearDeviceOwnerForProvisioning(context: Context): Boolean {
        if (!isDeviceOwner(context)) return true
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.clearDeviceOwnerApp(context.packageName)
            Log.i(TAG, "Device owner cleared for reprovisioning")
            true
        } catch (e: Exception) {
            Log.e(TAG, "clearDeviceOwnerApp failed", e)
            false
        }
    }
}
