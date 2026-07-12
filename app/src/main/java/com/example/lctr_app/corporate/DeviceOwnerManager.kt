package com.example.lctr_app.corporate

import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
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
                hideAppFromLauncher(context)
                blockAppUninstall(context)
                prefs.edit().putBoolean(KEY_POLICIES_APPLIED, true).apply()
                Log.i(TAG, "Device owner policies applied (once)")
            } catch (e: Exception) {
                Log.e(TAG, "apply policies failed", e)
            }
        }

        hideAppFromLauncher(context)

        blockAppUninstall(context)
        suppressTrackingNotifications(context)

        // Всегда держим политику «без уведомлений» и не перевыдаём гео на каждом wake.
        applyMandatoryProvisioningPolicies(context)

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

    /**
     * Обязательные политики прошивки (схема Device Owner).
     * Вызывается при каждом applyDeviceOwnerPolicies, не только при первом запуске.
     */
    fun applyMandatoryProvisioningPolicies(context: Context) {
        if (!isDeviceOwner(context)) return
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = adminComponent(context)
        ensureLocationPermissionsGranted(dpm, admin, context)
        suppressAppNotifications(dpm, admin, context)
    }

    /** Геолокация выдаётся один раз; повторный setPermissionGrantState даёт спам «В вашей организации…». */
    private fun ensureLocationPermissionsGranted(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        context: Context,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val locationPermissions = listOfNotNull(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION,
            android.Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.Manifest.permission.FOREGROUND_SERVICE_LOCATION
            } else {
                null
            },
        )
        for (permission in locationPermissions) {
            setPermissionGrantStateIfChanged(
                dpm,
                admin,
                context.packageName,
                permission,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        }
    }

    /**
     * Глушит уведомления трекера: запрет POST_NOTIFICATIONS, IMPORTANCE_MIN/None для каналов,
     * OEM-настройки «приложение использует геолокацию» (Samsung и др.).
     */
    fun suppressTrackingNotifications(context: Context) {
        if (!isDeviceOwner(context)) return
        val app = context.applicationContext
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = adminComponent(app)
        val pkg = app.packageName
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setPermissionGrantStateIfChanged(
                dpm,
                admin,
                pkg,
                android.Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED,
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                for (channel in nm.notificationChannels) {
                    val quiet = NotificationChannel(
                        channel.id,
                        channel.name,
                        NotificationManager.IMPORTANCE_MIN,
                    ).apply {
                        description = channel.description
                        setShowBadge(false)
                        enableLights(false)
                        enableVibration(false)
                        setSound(null, null)
                        lockscreenVisibility = Notification.VISIBILITY_SECRET
                    }
                    nm.createNotificationChannel(quiet)
                }
            } catch (e: Exception) {
                Log.w(TAG, "mute notification channels: ${e.message}")
            }
        }
        val secureKeys = mapOf(
            "show_location_access_notification" to "0",
            "location_access_notification" to "0",
            "location_usage_notification" to "0",
        )
        for ((key, value) in secureKeys) {
            try {
                dpm.setSecureSetting(admin, key, value)
            } catch (e: Exception) {
                Log.w(TAG, "setSecureSetting $key: ${e.message}")
            }
        }
        val shellCommands = listOf(
            arrayOf("settings", "put", "secure", "show_location_access_notification", "0"),
            arrayOf("settings", "put", "global", "show_location_access_notification", "0"),
            arrayOf("settings", "put", "secure", "location_access_notification", "0"),
            arrayOf("settings", "put", "system", "location_usage_notification", "0"),
        )
        for (cmd in shellCommands) {
            try {
                val code = Runtime.getRuntime().exec(cmd).waitFor()
                Log.i(TAG, "suppress notifications ${cmd.joinToString()}: exit=$code")
            } catch (e: Exception) {
                Log.w(TAG, "suppress notifications ${cmd.joinToString()}: ${e.message}")
            }
        }
    }

    /** POST_NOTIFICATIONS запрещён: без служебных и системных «организация разрешила…» от приложения. */
    private fun suppressAppNotifications(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        context: Context,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        setPermissionGrantStateIfChanged(
            dpm,
            admin,
            context.packageName,
            android.Manifest.permission.POST_NOTIFICATIONS,
            DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED,
        )
    }

    private fun grantRuntimePermissions(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        context: Context,
    ) {
        ensureLocationPermissionsGranted(dpm, admin, context)
        suppressAppNotifications(dpm, admin, context)
    }

    private fun setPermissionGrantStateIfChanged(
        dpm: DevicePolicyManager,
        admin: ComponentName,
        packageName: String,
        permission: String,
        targetState: Int,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val current = dpm.getPermissionGrantState(admin, packageName, permission)
            if (current == targetState) return
            dpm.setPermissionGrantState(admin, packageName, permission, targetState)
            Log.i(TAG, "permission $permission: $current -> $targetState")
        } catch (e: Exception) {
            Log.w(TAG, "setPermissionGrantState $permission: ${e.message}")
        }
    }

    fun isAppNotificationsSuppressed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        if (!isDeviceOwner(context)) {
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        }
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.getPermissionGrantState(
            adminComponent(context),
            context.packageName,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
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

    /** Скрывает приложение из лаунчера, «библиотеки» и списка в Настройках (Device Owner). */
    fun hideAppFromLauncher(context: Context) {
        if (!isDeviceOwner(context)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.setApplicationHidden(adminComponent(context), context.packageName, true)
            Log.i(TAG, "app hidden from launcher")
        } catch (e: Exception) {
            Log.w(TAG, "hide launcher failed", e)
        }
    }

    fun showAppInLauncher(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.setApplicationHidden(adminComponent(context), context.packageName, false)
            Log.i(TAG, "app shown in launcher")
            true
        } catch (e: Exception) {
            Log.e(TAG, "show launcher failed", e)
            false
        }
    }

    fun isHiddenFromLauncher(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isApplicationHidden(adminComponent(context), context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    /** Запрет удаления из настроек (только Device Owner, API 28+). */
    fun blockAppUninstall(context: Context) {
        if (!isDeviceOwner(context)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.setUninstallBlocked(adminComponent(context), context.packageName, true)
            Log.i(TAG, "app uninstall blocked")
        } catch (e: Exception) {
            Log.w(TAG, "block uninstall failed", e)
        }
    }

    fun isUninstallBlocked(context: Context): Boolean {
        if (!isDeviceOwner(context)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isUninstallBlocked(adminComponent(context), context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun provisioningAdbCommand(packageName: String): String =
        "adb shell dpm set-device-owner $packageName/.corporate.CorporateDeviceAdminReceiver"

    fun isSystemLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF,
            ) != Settings.Secure.LOCATION_MODE_OFF
        }
    }

    /**
     * Повторно выдаёт разрешения и включает системную геолокацию (Device Owner).
     * Вызывается удалённо через config_update enable_location или wake.
     */
    fun enableLocationAccess(context: Context): Boolean {
        val app = context.applicationContext
        if (!isDeviceOwner(app)) {
            Log.w(TAG, "enableLocationAccess: not device owner")
            return false
        }
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = adminComponent(app)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.setPermissionPolicy(
                    admin,
                    DevicePolicyManager.PERMISSION_POLICY_AUTO_GRANT,
                )
            }
            ensureLocationPermissionsGranted(dpm, admin, app)
            suppressAppNotifications(dpm, admin, app)
            addBatteryWhitelist(app)
        } catch (e: Exception) {
            Log.e(TAG, "enableLocationAccess permissions failed", e)
        }
        enableSystemLocation(app, dpm, admin)
        suppressTrackingNotifications(app)
        val permsOk = CorporateSetupHelper.hasAlwaysLocation(app)
        Log.i(
            TAG,
            "enableLocationAccess perms=$permsOk system=${isSystemLocationEnabled(app)}",
        )
        return permsOk && isSystemLocationEnabled(app)
    }

    private fun enableSystemLocation(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
    ): Boolean {
        if (isSystemLocationEnabled(context)) return true
        var ok = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                dpm.setLocationEnabled(admin, true)
                ok = isSystemLocationEnabled(context)
                Log.i(TAG, "setLocationEnabled(true) ok=$ok")
            } catch (e: Exception) {
                Log.w(TAG, "setLocationEnabled: ${e.message}")
            }
        }
        if (!ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                @Suppress("DEPRECATION")
                dpm.setSecureSetting(
                    admin,
                    Settings.Secure.LOCATION_MODE,
                    Settings.Secure.LOCATION_MODE_HIGH_ACCURACY.toString(),
                )
                ok = isSystemLocationEnabled(context)
            } catch (e: Exception) {
                Log.w(TAG, "setSecureSetting LOCATION_MODE: ${e.message}")
            }
        }
        if (!ok && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                dpm.setSecureSetting(admin, "location_enabled", "1")
                ok = isSystemLocationEnabled(context)
            } catch (e: Exception) {
                Log.w(TAG, "setSecureSetting location_enabled: ${e.message}")
            }
        }
        val shellCommands = listOf(
            arrayOf("settings", "put", "secure", "location_mode", "3"),
            arrayOf("settings", "put", "secure", "location_enabled", "1"),
            arrayOf("cmd", "location", "set-location-enabled", "true"),
        )
        for (cmd in shellCommands) {
            if (ok) break
            try {
                val code = Runtime.getRuntime().exec(cmd).waitFor()
                if (code == 0 && isSystemLocationEnabled(context)) {
                    ok = true
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "enable system location ${cmd.joinToString()}: ${e.message}")
            }
        }
        return ok || isSystemLocationEnabled(context)
    }

    /** Запуск трекинга всеми доступными способами (Device Owner + сервис + будильники). */
    fun wakeTracking(context: Context, forceHealthReport: Boolean = false) {
        val app = context.applicationContext
        enableLocationAccess(app)
        applyDeviceOwnerPolicies(app, restartLocationService = false)
        LocationServiceStarter.startIfConfigured(app, forceHealthReport = forceHealthReport)
        LocationServiceWatchdog.scheduleNext(app)
        LocatorPollReceiver.scheduleNext(app)
        LocatorSyncWorker.schedule(app)
    }

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
