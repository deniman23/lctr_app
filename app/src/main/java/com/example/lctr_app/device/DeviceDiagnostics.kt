package com.example.lctr_app.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.example.lctr_app.BuildConfig
import com.example.lctr_app.corporate.CorporateSetupHelper
import com.example.lctr_app.corporate.DeviceOwnerManager
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

object DeviceDiagnostics {

  fun buildReport(
    context: Context,
    config: DeviceConfigStore,
    serviceRunning: Boolean,
    commandId: String? = null,
  ): JSONObject {
    val endpoints = config.endpoints()
    val issues = detectIssues(context, config, serviceRunning)
    val apiKey = config.apiKey

    return JSONObject().apply {
      put("reported_at", Instant.now().toString())
      put("app_version", BuildConfig.VERSION_NAME)
      put("app_version_code", BuildConfig.VERSION_CODE)
      put("platform", "android")
      put("os_version", Build.VERSION.RELEASE)
      put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
      commandId?.let { put("command_id", it) }

      put("config", JSONObject().apply {
        put("api_base_url", endpoints.apiBaseUrl)
        put("user_id", config.userId)
        put("api_key_present", apiKey.isNotEmpty())
        put("api_key_last4", apiKey.takeLast(4).ifEmpty { JSONObject.NULL })
        put("location_interval_seconds", config.locationIntervalSeconds)
        put("poll_interval_seconds", config.pollIntervalMs / 1000)
        put("tracking_paused", config.trackingPaused)
        put("pending_app_update_version", config.pendingAppUpdateVersion ?: JSONObject.NULL)
      })

      put("app_update", JSONObject().apply {
        put("state", config.appUpdateState)
        put("target_version", config.pendingAppUpdateVersion ?: JSONObject.NULL)
        put("target_version_code", config.pendingAppUpdateVersionCode.takeIf { it > 0 } ?: JSONObject.NULL)
        put("download_progress", config.appUpdateDownloadProgress)
        put("last_error", config.appUpdateLastError ?: JSONObject.NULL)
      })

      put("corporate", JSONObject().apply {
        put("device_owner", DeviceOwnerManager.isDeviceOwner(context))
        put("can_install_updates", CorporateSetupHelper.canInstallUpdates(context))
        put("battery_unrestricted", CorporateSetupHelper.isBatteryUnrestricted(context))
      })

      put("auth", JSONObject().apply {
        putIsoOrNull("last_me_check_at", config.lastMeCheckAtMs)
        put("last_me_status", config.lastMeStatus.takeIf { it != 0 } ?: JSONObject.NULL)
      })

      put("location", JSONObject().apply {
        put("permission", locationPermissionLabel(context))
        put("background_enabled", hasBackgroundLocation(context))
        put("tracking_paused", config.trackingPaused)
        put("interval_seconds", config.locationIntervalSeconds)
        putIsoOrNull("last_post_at", config.lastPostAtMs)
        put("last_post_status", config.lastPostStatus.takeIf { it != 0 } ?: JSONObject.NULL)
        put("last_post_source", config.lastPostSource ?: JSONObject.NULL)
        put("last_post_error", config.lastPostError ?: JSONObject.NULL)
        put("pending_offline_count", config.pendingOfflineCount())
        config.lastGpsAccuracyM?.let { put("last_gps_accuracy_m", it.toDouble()) }
        put("foreground_service_running", serviceRunning)
      })

      put("poll", JSONObject().apply {
        putIsoOrNull("last_poll_at", config.lastPollAtMs)
        put("last_poll_status", config.lastPollStatus.takeIf { it != 0 } ?: JSONObject.NULL)
        put("last_command_type", config.lastCommandType ?: JSONObject.NULL)
      })

      put("network", networkInfo(context))
      put("battery", batteryInfo(context))
      put("issues", JSONArray(issues))
    }
  }

  fun detectIssues(
    context: Context,
    config: DeviceConfigStore,
    serviceRunning: Boolean,
  ): List<String> {
    val issues = mutableListOf<String>()
    val apiKey = config.apiKey

    if (apiKey.isEmpty()) issues.add("no_api_key")

    val meStatus = config.lastMeStatus
    if (apiKey.isNotEmpty() && meStatus != 0 && meStatus != 200) {
      issues.add("auth_failed")
    }
    if (apiKey.isNotEmpty() && config.lastMeCheckAtMs == 0L) {
      issues.add("auth_not_checked")
    }

    when (locationPermissionLabel(context)) {
      "denied" -> issues.add("location_permission_denied")
      "while_in_use" -> issues.add("location_permission_not_always")
    }

    if (!config.trackingPaused && !serviceRunning) {
      issues.add("background_stopped")
    }

    val postStatus = config.lastPostStatus
    if (config.lastPostAtMs > 0 && postStatus != 200) {
      issues.add("post_failed")
    }
    if (postStatus == 401) issues.add("last_post_401")

    val postAgeMs = System.currentTimeMillis() - config.lastPostAtMs
    val maxPostAgeMs = config.locationIntervalSeconds * 2 * 1000
    if (!config.trackingPaused && serviceRunning && config.lastPostAtMs > 0 &&
      postAgeMs > maxPostAgeMs
    ) {
      issues.add("post_stale")
    }

    val pollAgeMs = System.currentTimeMillis() - config.lastPollAtMs
    val maxPollAgeMs = config.pollIntervalMs * 3
    if (serviceRunning && config.lastPollAtMs > 0 && pollAgeMs > maxPollAgeMs) {
      issues.add("poll_stale")
    }

    if (config.pendingOfflineCount() >= 20) {
      issues.add("offline_queue_large")
    }

    if (config.trackingPaused) {
      issues.add("tracking_paused")
    }

    when (config.appUpdateState) {
      AppUpdateState.DOWNLOADED -> issues.add("app_update_ready")
      AppUpdateState.FAILED -> issues.add("app_update_failed")
      AppUpdateState.DOWNLOADING -> { /* in progress */ }
    }

    return issues.distinct()
  }

  private fun locationPermissionLabel(context: Context): String {
    val fine = ContextCompat.checkSelfPermission(
      context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
      context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    if (!fine && !coarse) return "denied"
    return if (hasBackgroundLocation(context)) "always" else "while_in_use"
  }

  private fun hasBackgroundLocation(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      return ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
      ) == PackageManager.PERMISSION_GRANTED
    }
    return ContextCompat.checkSelfPermission(
      context, Manifest.permission.ACCESS_BACKGROUND_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
  }

  private fun networkInfo(context: Context): JSONObject {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork
    val caps = network?.let { cm.getNetworkCapabilities(it) }
    val type = when {
      caps == null -> "none"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
      caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
      else -> "unknown"
    }
  return JSONObject().apply {
      put("type", type)
      put("vpn", caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
    }
  }

  private fun batteryInfo(context: Context): JSONObject {
    val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    val batteryStatus = context.registerReceiver(null, filter)
    val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
    val percent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return JSONObject().apply {
      if (percent >= 0) put("level_percent", percent)
      put("power_save_mode", pm.isPowerSaveMode)
    }
  }

  private fun JSONObject.putIsoOrNull(key: String, epochMs: Long) {
    if (epochMs <= 0) put(key, JSONObject.NULL)
    else put(key, Instant.ofEpochMilli(epochMs).toString())
  }
}
