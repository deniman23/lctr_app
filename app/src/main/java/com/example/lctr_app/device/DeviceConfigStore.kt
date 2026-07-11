package com.example.lctr_app.device

import android.content.Context
import android.content.SharedPreferences
import android.preference.PreferenceManager
import androidx.core.content.edit
import com.example.lctr_app.BuildConfig
import org.json.JSONArray

class DeviceConfigStore(context: Context) {

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var apiBaseUrl: String?
        get() = prefs.getString(KEY_API_BASE_URL, null)
        set(value) = prefs.edit { putString(KEY_API_BASE_URL, value?.trimEnd('/')) }

    var userId: Int
        get() = prefs.getInt(KEY_USER_ID, -1)
        set(value) = prefs.edit { putInt(KEY_USER_ID, value) }

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit { putString(KEY_API_KEY, value) }

    var locationIntervalSeconds: Long
        get() = prefs.getLong(KEY_LOCATION_INTERVAL_SEC, DEFAULT_LOCATION_INTERVAL_SEC)
        set(value) = prefs.edit { putLong(KEY_LOCATION_INTERVAL_SEC, value.coerceAtLeast(30)) }

    var pollIntervalMs: Long
        get() = prefs.getLong(KEY_POLL_INTERVAL_MS, BuildConfig.LOCATOR_POLL_INTERVAL_MS)
        set(value) = prefs.edit { putLong(KEY_POLL_INTERVAL_MS, value.coerceAtLeast(5_000)) }

    var healthReportIntervalMs: Long
        get() = prefs.getLong(KEY_HEALTH_INTERVAL_MS, DEFAULT_HEALTH_INTERVAL_MS)
        set(value) = prefs.edit { putLong(KEY_HEALTH_INTERVAL_MS, value.coerceAtLeast(60_000)) }

    var trackingPaused: Boolean
        get() = prefs.getBoolean(KEY_TRACKING_PAUSED, false)
        set(value) = prefs.edit { putBoolean(KEY_TRACKING_PAUSED, value) }

    var serviceActive: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ACTIVE, false)
        set(value) = prefs.edit { putBoolean(KEY_SERVICE_ACTIVE, value) }

    var lastPostAtMs: Long
        get() = prefs.getLong(KEY_LAST_POST_AT, 0)
        private set(value) = prefs.edit { putLong(KEY_LAST_POST_AT, value) }

    var lastPostStatus: Int
        get() = prefs.getInt(KEY_LAST_POST_STATUS, 0)
        private set(value) = prefs.edit { putInt(KEY_LAST_POST_STATUS, value) }

    var lastPostSource: String?
        get() = prefs.getString(KEY_LAST_POST_SOURCE, null)
        private set(value) = prefs.edit { putString(KEY_LAST_POST_SOURCE, value) }

    var lastPostError: String?
        get() = prefs.getString(KEY_LAST_POST_ERROR, null)
        private set(value) = prefs.edit { putString(KEY_LAST_POST_ERROR, value) }

    var lastGpsAccuracyM: Float?
        get() {
            if (!prefs.contains(KEY_LAST_GPS_ACCURACY)) return null
            return prefs.getFloat(KEY_LAST_GPS_ACCURACY, 0f)
        }
        private set(value) = prefs.edit {
            if (value == null) remove(KEY_LAST_GPS_ACCURACY)
            else putFloat(KEY_LAST_GPS_ACCURACY, value)
        }

    var lastPollAtMs: Long
        get() = prefs.getLong(KEY_LAST_POLL_AT, 0)
        private set(value) = prefs.edit { putLong(KEY_LAST_POLL_AT, value) }

    var lastPollStatus: Int
        get() = prefs.getInt(KEY_LAST_POLL_STATUS, 0)
        private set(value) = prefs.edit { putInt(KEY_LAST_POLL_STATUS, value) }

    var lastCommandType: String?
        get() = prefs.getString(KEY_LAST_COMMAND_TYPE, null)
        private set(value) = prefs.edit { putString(KEY_LAST_COMMAND_TYPE, value) }

    var lastMeCheckAtMs: Long
        get() = prefs.getLong(KEY_LAST_ME_AT, 0)
        private set(value) = prefs.edit { putLong(KEY_LAST_ME_AT, value) }

    var lastMeStatus: Int
        get() = prefs.getInt(KEY_LAST_ME_STATUS, 0)
        private set(value) = prefs.edit { putInt(KEY_LAST_ME_STATUS, value) }

    var lastHealthReportAtMs: Long
        get() = prefs.getLong(KEY_LAST_HEALTH_AT, 0)
        set(value) = prefs.edit { putLong(KEY_LAST_HEALTH_AT, value) }

    var pendingAppUpdateUrl: String?
        get() = prefs.getString(KEY_PENDING_UPDATE_URL, null)
        set(value) = prefs.edit { putString(KEY_PENDING_UPDATE_URL, value) }

    var pendingAppUpdateVersion: String?
        get() = prefs.getString(KEY_PENDING_UPDATE_VERSION, null)
        set(value) = prefs.edit { putString(KEY_PENDING_UPDATE_VERSION, value) }

    var pendingAppUpdateVersionCode: Int
        get() = prefs.getInt(KEY_PENDING_UPDATE_CODE, 0)
        set(value) = prefs.edit { putInt(KEY_PENDING_UPDATE_CODE, value) }

    var pendingAppUpdateSha256: String?
        get() = prefs.getString(KEY_PENDING_UPDATE_SHA256, null)
        set(value) = prefs.edit { putString(KEY_PENDING_UPDATE_SHA256, value) }

    var appUpdateState: String
        get() = prefs.getString(KEY_APP_UPDATE_STATE, AppUpdateState.IDLE) ?: AppUpdateState.IDLE
        set(value) = prefs.edit { putString(KEY_APP_UPDATE_STATE, value) }

    var appUpdateDownloadProgress: Int
        get() = prefs.getInt(KEY_APP_UPDATE_PROGRESS, 0)
        set(value) = prefs.edit { putInt(KEY_APP_UPDATE_PROGRESS, value) }

    var appUpdateLastError: String?
        get() = prefs.getString(KEY_APP_UPDATE_ERROR, null)
        set(value) = prefs.edit { putString(KEY_APP_UPDATE_ERROR, value) }

    var appUpdateDownloadedPath: String?
        get() = prefs.getString(KEY_APP_UPDATE_PATH, null)
        set(value) = prefs.edit { putString(KEY_APP_UPDATE_PATH, value) }

    var appUpdateWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_APP_UPDATE_WIFI_ONLY, false)
        set(value) = prefs.edit { putBoolean(KEY_APP_UPDATE_WIFI_ONLY, value) }

    var appUpdateDeferQuietHours: Boolean
        get() = prefs.getBoolean(KEY_APP_UPDATE_QUIET, true)
        set(value) = prefs.edit { putBoolean(KEY_APP_UPDATE_QUIET, value) }

    var appUpdateForceInstall: Boolean
        get() = prefs.getBoolean(KEY_APP_UPDATE_FORCE, false)
        set(value) = prefs.edit { putBoolean(KEY_APP_UPDATE_FORCE, value) }

    var appUpdateCommandId: String?
        get() = prefs.getString(KEY_APP_UPDATE_CMD_ID, null)
        set(value) = prefs.edit { putString(KEY_APP_UPDATE_CMD_ID, value) }

    var adminPinHash: String
        get() = prefs.getString(KEY_ADMIN_PIN_HASH, "") ?: ""
        set(value) = prefs.edit { putString(KEY_ADMIN_PIN_HASH, value) }

    fun recordAcceptedLocation(latitude: Double, longitude: Double, atMs: Long = System.currentTimeMillis()) {
        prefs.edit {
            putLong(KEY_LAST_SENT_LAT_BITS, java.lang.Double.doubleToRawLongBits(latitude))
            putLong(KEY_LAST_SENT_LON_BITS, java.lang.Double.doubleToRawLongBits(longitude))
            putLong(KEY_LAST_SENT_AT_MS, atMs)
        }
    }

    fun lastSentLocation(): LocationQuality.LastSent? {
        if (!prefs.contains(KEY_LAST_SENT_AT_MS)) return null
        return LocationQuality.LastSent(
            latitude = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_SENT_LAT_BITS, 0)),
            longitude = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LAST_SENT_LON_BITS, 0)),
            atMs = prefs.getLong(KEY_LAST_SENT_AT_MS, 0),
        )
    }

    fun endpoints(): LocatorEndpoints = LocatorEndpoints.fromBuildConfig(validApiBaseOverride())

    private fun validApiBaseOverride(): String? {
        val saved = normalizeApiBaseUrl(apiBaseUrl) ?: return null
        val obsoleteHosts = listOf("localhost", "127.0.0.1", "178.172.235.51")
        return saved.takeUnless { base -> obsoleteHosts.any { base.contains(it) } }
    }

    /** Гарантирует схему http(s); иначе OkHttp даёт «Failed to connect to /host:port». */
    private fun normalizeApiBaseUrl(raw: String?): String? {
        val s = raw?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        if (s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)) {
            return s
        }
        if (s.startsWith("//")) return "http:$s".trimEnd('/')
        return "http://$s"
    }

    fun recordLocationPost(status: Int, source: String, accuracy: Float?, error: String? = null) {
        lastPostAtMs = System.currentTimeMillis()
        lastPostStatus = status
        lastPostSource = source
        lastPostError = error
        lastGpsAccuracyM = accuracy
    }

    fun recordPoll(status: Int, commandType: String?) {
        lastPollAtMs = System.currentTimeMillis()
        lastPollStatus = status
        lastCommandType = commandType
    }

    fun recordMeCheck(status: Int) {
        lastMeCheckAtMs = System.currentTimeMillis()
        lastMeStatus = status
    }

    fun saveCredentials(userId: Int, apiKey: String) {
        this.userId = userId
        this.apiKey = apiKey
        syncLegacyPrefs()
    }

    fun syncLegacyPrefs() {
        PreferenceManager.getDefaultSharedPreferences(appContext).edit {
            putBoolean("location_service_active", serviceActive)
            putInt("user_id", userId)
            putString("api_key", apiKey)
        }
    }

    fun loadFromLegacyPrefsIfNeeded() {
        if (userId != -1 && apiKey.isNotEmpty()) return
        val legacy = PreferenceManager.getDefaultSharedPreferences(appContext)
        val id = legacy.getInt("user_id", -1)
        val key = legacy.getString("api_key", "") ?: ""
        if (id != -1) userId = id
        if (key.isNotEmpty()) apiKey = key
        serviceActive = legacy.getBoolean("location_service_active", false)
    }

    /** После OTA с 5-минутного дефолта переключает сохранённый интервал на 60 с. */
    fun migrateLocationIntervalIfNeeded(appVersionCode: Int) {
        if (appVersionCode < 23) return
        if (prefs.getBoolean(KEY_INTERVAL_MIGRATED_V23, false)) return
        if (locationIntervalSeconds == LEGACY_LOCATION_INTERVAL_SEC) {
            locationIntervalSeconds = DEFAULT_LOCATION_INTERVAL_SEC
        }
        prefs.edit { putBoolean(KEY_INTERVAL_MIGRATED_V23, true) }
    }

    fun pendingOfflineCount(): Int = offlineQueue().size

    fun offlineQueue(): List<String> {
        val raw = prefs.getString(KEY_OFFLINE_QUEUE, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun enqueueOffline(payload: String) {
        val queue = offlineQueue().toMutableList()
        queue.add(payload)
        while (queue.size > MAX_OFFLINE_QUEUE) queue.removeAt(0)
        prefs.edit { putString(KEY_OFFLINE_QUEUE, JSONArray(queue).toString()) }
    }

    fun dequeueOffline(): String? {
        val queue = offlineQueue().toMutableList()
        if (queue.isEmpty()) return null
        val item = queue.removeAt(0)
        prefs.edit { putString(KEY_OFFLINE_QUEUE, JSONArray(queue).toString()) }
        return item
    }

    fun applyRemoteConfig(payload: org.json.JSONObject): Boolean {
        var changed = false
        payload.optString("api_key").ifBlank { null }?.let {
            apiKey = it
            changed = true
        }
        if (payload.has("user_id") && !payload.isNull("user_id")) {
            userId = payload.getInt("user_id")
            changed = true
        }
        payload.optString("api_base_url").ifBlank { null }?.let {
            apiBaseUrl = normalizeApiBaseUrl(it) ?: it
            changed = true
        }
        if (payload.has("location_interval_seconds") && !payload.isNull("location_interval_seconds")) {
            locationIntervalSeconds = payload.getLong("location_interval_seconds")
            changed = true
        }
        if (payload.has("poll_interval_seconds") && !payload.isNull("poll_interval_seconds")) {
            pollIntervalMs = payload.getLong("poll_interval_seconds") * 1000
            changed = true
        }
        if (payload.has("health_report_interval_seconds") && !payload.isNull("health_report_interval_seconds")) {
            healthReportIntervalMs = payload.getLong("health_report_interval_seconds") * 1000
            changed = true
        }
        if (payload.has("tracking_paused")) {
            trackingPaused = payload.getBoolean("tracking_paused")
            changed = true
        }
        payload.optString("app_update_url").ifBlank { null }?.let {
            pendingAppUpdateUrl = it
            changed = true
        }
        payload.optString("app_update_version").ifBlank { null }?.let {
            pendingAppUpdateVersion = it
            changed = true
        }
        if (changed) syncLegacyPrefs()
        return changed
    }

    companion object {
        private const val PREFS_NAME = "locator_device_config"
        private const val MAX_OFFLINE_QUEUE = 200
        const val DEFAULT_LOCATION_INTERVAL_SEC = 60L
        /** Старый дефолт до 1.0.22 — мигрируем на 60 с при обновлении. */
        private const val LEGACY_LOCATION_INTERVAL_SEC = 300L
        private const val KEY_INTERVAL_MIGRATED_V23 = "location_interval_migrated_v23"
        private const val DEFAULT_HEALTH_INTERVAL_MS = 20 * 60 * 1000L

        private const val KEY_API_BASE_URL = "api_base_url"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_LOCATION_INTERVAL_SEC = "location_interval_sec"
        private const val KEY_POLL_INTERVAL_MS = "poll_interval_ms"
        private const val KEY_HEALTH_INTERVAL_MS = "health_interval_ms"
        private const val KEY_TRACKING_PAUSED = "tracking_paused"
        private const val KEY_SERVICE_ACTIVE = "service_active"
        private const val KEY_LAST_POST_AT = "last_post_at"
        private const val KEY_LAST_POST_STATUS = "last_post_status"
        private const val KEY_LAST_POST_SOURCE = "last_post_source"
        private const val KEY_LAST_POST_ERROR = "last_post_error"
        private const val KEY_LAST_GPS_ACCURACY = "last_gps_accuracy"
        private const val KEY_LAST_POLL_AT = "last_poll_at"
        private const val KEY_LAST_POLL_STATUS = "last_poll_status"
        private const val KEY_LAST_COMMAND_TYPE = "last_command_type"
        private const val KEY_LAST_ME_AT = "last_me_at"
        private const val KEY_LAST_ME_STATUS = "last_me_status"
        private const val KEY_LAST_HEALTH_AT = "last_health_at"
        private const val KEY_OFFLINE_QUEUE = "offline_queue"
        private const val KEY_PENDING_UPDATE_URL = "pending_update_url"
        private const val KEY_PENDING_UPDATE_VERSION = "pending_update_version"
        private const val KEY_PENDING_UPDATE_CODE = "pending_update_version_code"
        private const val KEY_PENDING_UPDATE_SHA256 = "pending_update_sha256"
        private const val KEY_APP_UPDATE_STATE = "app_update_state"
        private const val KEY_APP_UPDATE_PROGRESS = "app_update_progress"
        private const val KEY_APP_UPDATE_ERROR = "app_update_error"
        private const val KEY_APP_UPDATE_PATH = "app_update_path"
        private const val KEY_APP_UPDATE_WIFI_ONLY = "app_update_wifi_only"
        private const val KEY_APP_UPDATE_QUIET = "app_update_quiet"
        private const val KEY_APP_UPDATE_FORCE = "app_update_force"
        private const val KEY_APP_UPDATE_CMD_ID = "app_update_cmd_id"
        private const val KEY_ADMIN_PIN_HASH = "admin_pin_hash"
        private const val KEY_LAST_SENT_LAT_BITS = "last_sent_lat_bits"
        private const val KEY_LAST_SENT_LON_BITS = "last_sent_lon_bits"
        private const val KEY_LAST_SENT_AT_MS = "last_sent_at_ms"
    }
}
