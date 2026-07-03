package com.example.lctr_app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.example.lctr_app.device.AppUpdateManager
import com.example.lctr_app.device.DeviceConfigStore
import com.example.lctr_app.device.DeviceReportSender
import com.example.lctr_app.device.LocationQuality
import com.example.lctr_app.device.LocatorHttpClient
import com.example.lctr_app.device.PollCommand
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class LocationService : Service() {

    private lateinit var fusedLocationClient: com.google.android.gms.location.FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private lateinit var config: DeviceConfigStore
    private lateinit var http: LocatorHttpClient
    private lateinit var reportSender: DeviceReportSender
    private lateinit var appUpdateManager: AppUpdateManager

    private val pollHandler = Handler(Looper.getMainLooper())
    private val isFetchingOnDemand = AtomicBoolean(false)
    private val isFlushingOffline = AtomicBoolean(false)
    private var locationUpdatesActive = false
    private var lastFreshLocationAttemptMs = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollDeviceCommands()
            reportSender.sendPeriodicIfDue(isServiceRunning())
            appUpdateManager.resumePendingWork()
            flushOfflineQueue()
            pollHandler.postDelayed(this, config.pollIntervalMs)
        }
    }

    override fun onCreate() {
        super.onCreate()
        config = DeviceConfigStore(this)
        config.loadFromLegacyPrefsIfNeeded()
        http = LocatorHttpClient(config)
        reportSender = DeviceReportSender(this, config, http)
        appUpdateManager = AppUpdateManager(this, config)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startForegroundServiceWithNotification()
        rebuildLocationRequest()
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (config.trackingPaused) return
                val location = result.lastLocation ?: return
                if (!sendLocation(location, source = "periodic")) {
                    maybeRequestFreshLocation()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { applyStartIntent(it) }

        if (config.userId == -1 || config.apiKey.isEmpty()) {
            Log.e(TAG, "credentials missing: user_id=${config.userId}")
        }

        config.serviceActive = true
        config.syncLegacyPrefs()

        applyTrackingState()
        startRequestPolling()
        fetchAndSendLocationNow(null)

        if (intent?.getBooleanExtra(EXTRA_FORCE_HEALTH_REPORT, false) == true) {
            reportSender.sendFullReport(isServiceRunning())
        }

        return START_STICKY
    }

    private fun applyStartIntent(intent: Intent) {
        when (intent.action) {
            ACTION_RELOAD_CONFIG -> { /* prefs already updated */ }
        }
        if (intent.hasExtra(EXTRA_USER_ID)) {
            config.userId = intent.getIntExtra(EXTRA_USER_ID, config.userId)
        }
        if (intent.hasExtra(EXTRA_API_KEY)) {
            config.apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: config.apiKey
        }
        config.loadFromLegacyPrefsIfNeeded()
    }

    private fun applyTrackingState() {
        if (config.trackingPaused) {
            stopLocationUpdates()
        } else {
            startLocationUpdates()
        }
        updateForegroundNotification()
    }

    private fun rebuildLocationRequest() {
        val intervalMs = config.locationIntervalSeconds * 1000
        locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .setMaxUpdateDelayMillis(intervalMs + 30_000)
            .setMaxUpdateAgeMillis(LocationQuality.MAX_LOCATION_REQUEST_AGE_MS)
            .build()
    }

    private fun restartLocationUpdates() {
        stopLocationUpdates()
        rebuildLocationRequest()
        if (!config.trackingPaused) startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (locationUpdatesActive) return
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            locationUpdatesActive = true
        } catch (e: SecurityException) {
            Log.e(TAG, "location permission missing for updates")
        }
    }

    private fun stopLocationUpdates() {
        if (!locationUpdatesActive) return
        fusedLocationClient.removeLocationUpdates(locationCallback)
        locationUpdatesActive = false
    }

    private fun startRequestPolling() {
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.post(pollRunnable)
    }

    private fun stopRequestPolling() {
        pollHandler.removeCallbacks(pollRunnable)
    }

    private fun isServiceRunning(): Boolean = locationUpdatesActive || config.serviceActive

    private fun pollDeviceCommands() {
        if (config.apiKey.isEmpty()) return

        val endpoints = config.endpoints()
        http.get(endpoints.devicePoll) { status, body, error ->
            config.recordPoll(status, null)
            if (error != null) {
                Log.w(TAG, "poll error: $error")
                tryLegacyPoll(endpoints.legacyLocationRequestPoll)
                return@get
            }
            when (status) {
                204, 404 -> return@get
                !in 200..299 -> {
                    Log.w(TAG, "poll HTTP $status")
                    if (status == 404) tryLegacyPoll(endpoints.legacyLocationRequestPoll)
                    return@get
                }
            }
            handlePollBody(body.orEmpty(), status)
        }
    }

    private fun tryLegacyPoll(url: String) {
        http.get(url) { status, body, _ ->
            if (status in 200..299 && !body.isNullOrBlank()) {
                handlePollBody(body, status)
            }
        }
    }

    private fun handlePollBody(body: String, pollStatus: Int) {
        val command = PollCommand.parse(body) ?: run {
            config.recordPoll(pollStatus, null)
            return
        }
        config.recordPoll(pollStatus, command.type)
        when (command) {
            is PollCommand.LocationRequest -> {
                fetchAndSendLocationNow(command.effectiveRequestId())
                command.id?.let { http.ackCommand(it) }
            }
            is PollCommand.HealthCheck -> {
                reportSender.sendFullReport(isServiceRunning(), command.id)
            }
            is PollCommand.ConfigUpdate -> applyRemoteConfig(command)
            is PollCommand.AppUpdate -> applyAppUpdate(command)
        }
    }

    private fun applyRemoteConfig(command: PollCommand.ConfigUpdate) {
        val wasPaused = config.trackingPaused
        val changed = config.applyRemoteConfig(command.payload)
        config.syncLegacyPrefs()
        if (wasPaused != config.trackingPaused) updateForegroundNotification()
        restartLocationUpdates()
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.post(pollRunnable)
        if (changed) {
            config.recordLocationPost(200, "config_update", null)
            flushOfflineQueue()
            Log.i(TAG, "remote config applied, flushing offline queue")
        }
        reportSender.sendFullReport(isServiceRunning(), command.id) { ok ->
            if (ok) command.id?.let { http.ackCommand(it) }
        }
    }

    private fun applyAppUpdate(command: PollCommand.AppUpdate) {
        command.id?.let { http.ackCommand(it, "accepted") }
        appUpdateManager.handlePollCommand(command.payload, command.id)
        reportSender.sendFullReport(isServiceRunning(), command.id)
    }

    private fun fetchAndSendLocationNow(requestId: String?) {
        if (!isFetchingOnDemand.compareAndSet(false, true)) return
        val releaseLock = { isFetchingOnDemand.set(false) }
        val cancelToken = CancellationTokenSource()
        try {
            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                cancelToken.token
            ).addOnSuccessListener { location ->
                if (location != null) {
                    sendLocation(location, requestId, "on_demand")
                    releaseLock()
                } else {
                    releaseLock()
                    Log.w(TAG, "on_demand: no fresh location")
                }
            }.addOnFailureListener {
                releaseLock()
                Log.w(TAG, "on_demand: getCurrentLocation failed")
            }
        } catch (e: SecurityException) {
            releaseLock()
            Log.e(TAG, "location permission missing for on_demand")
        }
    }

    private fun maybeRequestFreshLocation() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFreshLocationAttemptMs < 60_000) return
        lastFreshLocationAttemptMs = now
        fetchAndSendLocationNow(null)
    }

    private fun sendLocation(
        location: Location,
        requestId: String? = null,
        source: String = "periodic",
    ): Boolean {
        val reject = LocationQuality.rejectReason(location, source, config.lastSentLocation())
        if (reject != null) {
            Log.i(TAG, "skip location source=$source reason=${reject.reason}")
            return false
        }

        val json = JSONObject().apply {
            put("user_id", config.userId)
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("source", source)
            if (location.hasAccuracy()) put("accuracy", location.accuracy.toDouble())
            put("timestamp", location.time)
            requestId?.let { put("request_id", it) }
        }
        postLocationPayload(json.toString(), source, location.accuracy) { success ->
            if (success) {
                config.recordAcceptedLocation(location.latitude, location.longitude)
            }
        }
        return true
    }

    private fun postLocationPayload(
        payload: String,
        source: String,
        accuracy: Float?,
        onDone: ((success: Boolean) -> Unit)? = null,
    ) {
        if (config.apiKey.isEmpty()) {
            onDone?.invoke(false)
            return
        }
        val url = config.endpoints().locationPost
        http.postJson(url, JSONObject(payload)) { status, body, error ->
            val skipped = try {
                body?.let { JSONObject(it).optBoolean("skipped", false) } ?: false
            } catch (_: Exception) {
                false
            }
            val ok = status in 200..299 && !skipped
            Log.i(TAG, "location post HTTP $status source=$source url=$url skipped=$skipped")
            if (ok) {
                config.recordLocationPost(status, source, accuracy)
            } else {
                config.recordLocationPost(status, source, accuracy, error ?: "HTTP $status")
                // Повторная отправка из очереди — не дублируем payload в offline_queue
                if (source != "offline_retry" && (status == 0 || status >= 500)) {
                    if (isOfflinePayloadAcceptable(payload)) {
                        config.enqueueOffline(payload)
                    } else {
                        Log.i(TAG, "skip offline enqueue: low quality payload")
                    }
                }
            }
            onDone?.invoke(ok)
            if (ok) flushOfflineQueue()
        }
    }

    private fun flushOfflineQueue() {
        if (!isFlushingOffline.compareAndSet(false, true)) return
        val next = config.offlineQueue().firstOrNull()
        if (next == null) {
            isFlushingOffline.set(false)
            return
        }
        if (!isOfflinePayloadAcceptable(next)) {
            config.dequeueOffline()
            isFlushingOffline.set(false)
            flushOfflineQueue()
            return
        }
        postLocationPayload(next, "offline_retry", null) { success ->
            if (success) config.dequeueOffline()
            isFlushingOffline.set(false)
            if (success) {
                flushOfflineQueue()
            } else {
                // Не останавливаем разгрузку очереди после одной ошибки
                pollHandler.postDelayed({ flushOfflineQueue() }, 5_000)
            }
        }
    }

    private fun isOfflinePayloadAcceptable(payload: String): Boolean {
        return try {
            val json = JSONObject(payload)
            val source = json.optString("source", "periodic")
            val loc = Location(source).apply {
                latitude = json.getDouble("latitude")
                longitude = json.getDouble("longitude")
                time = json.optLong("timestamp", System.currentTimeMillis())
                if (json.has("accuracy")) {
                    accuracy = json.getDouble("accuracy").toFloat()
                }
            }
            LocationQuality.rejectReason(loc, source, config.lastSentLocation()) == null
        } catch (_: Exception) {
            false
        }
    }

    private fun startForegroundServiceWithNotification() {
        ensureNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
    }

    private fun updateForegroundNotification() {
        ensureNotificationChannel()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildForegroundNotification())
    }

    private fun ensureNotificationChannel() {
        val ch = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.notification_channel_service_desc)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun buildForegroundNotification() =
        NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_service_title))
            .setContentText(getString(R.string.notification_service_text))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()

    override fun onTaskRemoved(rootIntent: Intent?) {
        config.serviceActive = true
        config.syncLegacyPrefs()
        val restart = Intent(applicationContext, LocationService::class.java)
        val pi = PendingIntent.getService(
            this, 1, restart,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.set(
            AlarmManager.ELAPSED_REALTIME,
            SystemClock.elapsedRealtime() + 1000,
            pi
        )
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopRequestPolling()
        stopLocationUpdates()
        config.serviceActive = false
        config.syncLegacyPrefs()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "LocationService"
        private const val NOTIFICATION_CHANNEL_ID = "svc_bg_v2"
        private const val NOTIFICATION_ID = 1
        const val ACTION_RELOAD_CONFIG = "com.example.lctr_app.RELOAD_CONFIG"
        const val EXTRA_FORCE_HEALTH_REPORT = "force_health_report"
        const val EXTRA_USER_ID = "user_id"
        const val EXTRA_API_KEY = "api_key"

        fun reloadConfigIntent(context: Context): Intent =
            Intent(context, LocationService::class.java).apply {
                action = ACTION_RELOAD_CONFIG
            }
    }
}
