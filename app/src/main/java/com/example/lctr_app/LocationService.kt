package com.example.lctr_app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.google.android.gms.location.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class LocationService : Service() {
    companion object {
        const val SERVER_URL = "http://178.172.173.183:8080/api/location"
    }

    // Интервал обновления 15 минут
    private val updateIntervalMs = 5 * 60 * 1000L

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    private var userId: Int = -1
    private var apiKey: String = ""
    private val httpClient = OkHttpClient()

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startForegroundServiceWithNotification()

        // Настраиваем запрос локации
        locationRequest = LocationRequest.create().apply {
            interval = updateIntervalMs
            fastestInterval = updateIntervalMs / 2
            priority = Priority.PRIORITY_HIGH_ACCURACY
        }

        // Обрабатываем приход локаций
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { sendData(it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Читаем параметры из Intent или prefs
        intent?.let {
            if (it.hasExtra("user_id")) {
                userId = it.getIntExtra("user_id", -1)
                apiKey = it.getStringExtra("api_key") ?: ""
            }
        }
        if (userId == -1 || apiKey.isEmpty()) {
            showToast("Неверные параметры: user_id=$userId, apiKey='$apiKey'")
            Log.e("LocationService", "Параметры не установлены: user_id=$userId, apiKey='$apiKey'")
        }

        // Сохраняем в prefs, что сервис активен и его параметры
        PreferenceManager.getDefaultSharedPreferences(this).edit {
            putBoolean("location_service_active", true)
            putInt("user_id", userId)
            putString("api_key", apiKey)
        }

        // Запускаем постоянные обновления локации
        startLocationUpdates()
        return START_STICKY
    }

    private fun startLocationUpdates() {
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            showToast("Необходимы разрешения для определения местоположения")
        }
    }

    private fun sendData(location: Location) {
        val json = JSONObject().apply {
            put("user_id", userId)
            put("latitude", location.latitude)
            put("longitude", location.longitude)
        }
        val body = json.toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder()
            .url(SERVER_URL)
            .addHeader("X-API-Key", apiKey)
            .post(body)
            .build()

        httpClient.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                showToast("Ошибка отправки данных: ${e.message}")
            }
            override fun onResponse(call: Call, resp: Response) {
                resp.close()
            }
        })
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
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Отслеживание местоположения")
            .setContentText("Служба отправки геолокации работает")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .build()
        startForeground(1, notif)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Чтобы сервис не убивался навсегда
        PreferenceManager.getDefaultSharedPreferences(this).edit {
            putBoolean("location_service_active", true)
        }
        // Рестартим через AlarmManager
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
        fusedLocationClient.removeLocationUpdates(locationCallback)
        PreferenceManager.getDefaultSharedPreferences(this).edit {
            putBoolean("location_service_active", false)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }
}