package com.example.lctr_app.corporate

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

data class CorporateSetupStatus(
    val isDeviceOwner: Boolean,
    val canInstallUpdates: Boolean,
    val batteryUnrestricted: Boolean,
    val notificationsGranted: Boolean,
    val locationWhileInUse: Boolean,
    val locationAlways: Boolean,
    val allReady: Boolean,
)

object CorporateSetupHelper {

    fun evaluate(context: Context): CorporateSetupStatus {
        val locationWhileInUse = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        val locationAlways = hasAlwaysLocation(context)
        val canInstall = canInstallUpdates(context)
        val battery = isBatteryUnrestricted(context)
        val notifications = hasNotifications(context)
        val owner = DeviceOwnerManager.isDeviceOwner(context)
        val allReady = owner || (
            canInstall && battery && notifications && locationAlways
            )
        return CorporateSetupStatus(
            isDeviceOwner = owner,
            canInstallUpdates = canInstall,
            batteryUnrestricted = battery,
            notificationsGranted = notifications,
            locationWhileInUse = locationWhileInUse,
            locationAlways = locationAlways,
            allReady = allReady,
        )
    }

    fun openInstallUpdatesSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = "package:${activity.packageName}".toUri()
        }
        activity.startActivity(intent)
    }

    fun openBatteryOptimizationSettings(activity: Activity) {
        val pm = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(activity.packageName)) return
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = "package:${activity.packageName}".toUri()
        }
        try {
            activity.startActivity(intent)
        } catch (_: Exception) {
            activity.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
        }
    }

    fun openAppSettings(activity: Activity) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = "package:${activity.packageName}".toUri()
        }
        activity.startActivity(intent)
    }

    fun openLocationPermissionSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !hasPermission(activity, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) {
            openAppSettings(activity)
            return
        }
        openAppSettings(activity)
    }

    fun requiredRuntimePermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            list.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        }
        return list.toTypedArray()
    }

    fun backgroundLocationPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        } else null

    fun canInstallUpdates(context: Context): Boolean {
        if (DeviceOwnerManager.isDeviceOwner(context)) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun isBatteryUnrestricted(context: Context): Boolean {
        if (DeviceOwnerManager.isDeviceOwner(context)) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(context.packageName)) return true
            return true
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)
    }

    fun hasAlwaysLocation(context: Context): Boolean {
        val fine = hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return hasPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    }

    private fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}
