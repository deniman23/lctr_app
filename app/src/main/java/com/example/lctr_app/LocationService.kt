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
import com.google.android.gms.location.*
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import androidx.core.content.edit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class LocationService : Service() {
    companion object {
        const val SERVER_URL = "http://192.168.100.136:8080/api/location"
    }

    private val updateInterval: Long = 15 * 60 * 1000
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var userId: Int = -1
    private var apiKey: String = ""
    private val httpClient = OkHttpClient()

    private val locationRunnable = object : Runnable {
        override fun run() {
            Log.d("LocationService", "Запуск Runnable для отправки геоданных")
            sendLocationData()
            handler.postDelayed(this, updateInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startForegroundServiceWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Разбор Intent: либо из Activity, либо при рестарте после onTaskRemoved
        if (intent?.hasExtra("user_id") == true) {
            userId = intent.getIntExtra("user_id", -1)
            apiKey = intent.getStringExtra("api_key") ?: ""
        } else {
            // рестартились без Intent — грузим из prefs
            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            userId = prefs.getInt("user_id", -1)
            apiKey = prefs.getString("api_key", "") ?: ""
        }

        // Сохраняем в prefs, что сервис жив и параметры
        PreferenceManager.getDefaultSharedPreferences(this).edit {
            putBoolean("location_service_active", true)
                .putInt("user_id", userId)
                .putString("api_key", apiKey)
        }

        if (userId == -1 || apiKey.isEmpty()) {
            showToast("Неверные параметры: user_id или api_key не установлены")
            Log.e("LocationService", "Параметры не установлены: user_id=$userId, apiKey=$apiKey")
        }

        handler.post(locationRunnable)
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "location_service_channel"
        val ch = NotificationChannel(
            channelId, "Location Service", NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_ONE_SHOT or
                    PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Отслеживание местоположения")
            .setContentText("Служба отправки геолокации работает")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .build()
        startForeground(1, notif)
    }

    private fun sendLocationData() {
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { loc ->
                    if (loc != null && userId != -1 && apiKey.isNotEmpty()) {
                        sendData(loc)
                    } else {
                        requestCurrentLocation()
                    }
                }
                .addOnFailureListener {
                    requestCurrentLocation()
                }
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

    private fun requestCurrentLocation() {
        try {
            val lr = LocationRequest.create().apply {
                interval = 0; fastestInterval = 0
                priority = Priority.PRIORITY_HIGH_ACCURACY
                numUpdates = 1
            }
            fusedLocationClient.requestLocationUpdates(
                lr,
                object : LocationCallback() {
                    override fun onLocationResult(res: LocationResult) {
                        res.lastLocation?.let { sendData(it) }
                        fusedLocationClient.removeLocationUpdates(this)
                    }
                },
                Looper.getMainLooper()
            )
        } catch (_: SecurityException) { }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // сервис будет жить дальше
        PreferenceManager.getDefaultSharedPreferences(this).edit {
            putBoolean("location_service_active", true)
        }

        // рестартим через AlarmManager
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
        handler.removeCallbacks(locationRunnable)
        // помечаем в prefs, что сервис остановлен
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