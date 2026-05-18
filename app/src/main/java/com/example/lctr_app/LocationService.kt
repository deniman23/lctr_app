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
                result.lastLocation?.let { sendLocation(it, source = "periodic") }
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
    }

    private fun rebuildLocationRequest() {
        val intervalMs = config.locationIntervalSeconds * 1000
        locationRequest = LocationRequest.create().apply {
            interval = intervalMs
            fastestInterval = intervalMs / 2
            priority = Priority.PRIORITY_HIGH_ACCURACY
        }
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
        val changed = config.applyRemoteConfig(command.payload)
        config.syncLegacyPrefs()
        restartLocationUpdates()
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.post(pollRunnable)
        reportSender.sendFullReport(isServiceRunning(), command.id) { ok ->
            if (ok) command.id?.let { http.ackCommand(it) }
        }
        if (changed) {
            Log.i(TAG, "remote config applied")
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
                    fusedLocationClient.lastLocation
                        .addOnSuccessListener { last ->
                            last?.let { sendLocation(it, requestId, "on_demand") }
                        }
                        .addOnCompleteListener { releaseLock() }
                }
            }.addOnFailureListener {
                fusedLocationClient.lastLocation
                    .addOnSuccessListener { last ->
                        last?.let { sendLocation(it, requestId, "on_demand") }
                    }
                    .addOnCompleteListener { releaseLock() }
            }
        } catch (e: SecurityException) {
            releaseLock()
            Log.e(TAG, "location permission missing for on_demand")
        }
    }

    private fun sendLocation(
        location: Location,
        requestId: String? = null,
        source: String = "periodic",
    ) {
        val json = JSONObject().apply {
            put("user_id", config.userId)
            put("latitude", location.latitude)
            put("longitude", location.longitude)
            put("source", source)
            if (location.hasAccuracy()) put("accuracy", location.accuracy.toDouble())
            put("timestamp", location.time)
            requestId?.let { put("request_id", it) }
        }
        postLocationPayload(json.toString(), source, location.accuracy)
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
        http.postJson(url, JSONObject(payload)) { status, _, error ->
            val ok = status in 200..299
            if (ok) {
                config.recordLocationPost(status, source, accuracy)
            } else {
                config.recordLocationPost(status, source, accuracy, error ?: "HTTP $status")
                if (status == 0 || status >= 500) config.enqueueOffline(payload)
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
        postLocationPayload(next, "offline_retry", null) { success ->
            if (success) config.dequeueOffline()
            isFlushingOffline.set(false)
            if (success) flushOfflineQueue()
        }
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "location_service_channel"
        val ch = NotificationChannel(
            channelId,
            "Location Service",
            NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Отслеживание местоположения")
            .setContentText(
                if (config.trackingPaused) "Трекинг приостановлен сервером"
                else "Служба активна"
            )
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .build()
        startForeground(1, notif)
    }

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
