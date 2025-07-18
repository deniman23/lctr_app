package com.example.lctr_app

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
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class LocationService : Service() {
    companion object {
        // Серверный адрес для отправки данных
        const val SERVER_URL = "http://178.172.138.123:8080/api/location"
    }

    // Интервал отправки данных: 15 минут = 15 * 60 * 1000 мс
    private val updateInterval: Long = 15 * 60 * 1000

    // Handler для планирования Runnable
    private val handler = Handler(Looper.getMainLooper())

    // Клиент для получения геолокации с использованием FusedLocationProvider
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    // Данные, переданные из MainActivity (user_id и api_key)
    private var userId: Int = -1
    private var apiKey: String = ""

    // HTTP-клиент для отправки запросов
    private val httpClient = OkHttpClient()

    // Runnable для периодической отправки геоданных
    private val locationRunnable: Runnable = object : Runnable {
        override fun run() {
            Log.d("LocationService", "Запуск Runnable для отправки геоданных")
            sendLocationData()
            // Планируем следующий запуск через заданный интервал
            handler.postDelayed(this, updateInterval)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startForegroundServiceWithNotification()
    }

    /**
     * В onStartCommand получаем переданные через Intent параметры,
     * а затем запускаем периодическую отправку геоданных.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            userId = it.getIntExtra("user_id", -1)
            apiKey = it.getStringExtra("api_key") ?: ""
        }
        // Запускаем Runnable сразу
        handler.post(locationRunnable)
        return START_STICKY
    }

    /**
     * Создание foreground-сервиса с уведомлением.
     * При нажатии на уведомление открывается MainActivity.
     */
    private fun startForegroundServiceWithNotification() {
        val channelId = "location_service_channel"
        val channelName = "Location Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, channelName, NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_IMMUTABLE
            else 0
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Отслеживание местоположения")
            .setContentText("Служба отправки геолокации работает")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .build()

        startForeground(1, notification)
    }

    /**
     * Метод для отправки данных о местоположении.
     * 1. Если доступна последняя известная локация, отправляем её.
     * 2. Если lastLocation вернул null, запрашиваем одно обновление локации.
     */
    private fun sendLocationData() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                if (location != null && userId != -1 && apiKey.isNotEmpty()) {
                    Log.d("LocationService", "Получена последняя известная локация")
                    sendData(location)
                } else {
                    Log.w("LocationService", "Нет последних геоданных. Пробуем запросить актуальную локацию...")
                    requestCurrentLocation()
                }
            }.addOnFailureListener { e ->
                Log.e("LocationService", "Ошибка получения lastLocation: ${e.message}")
                requestCurrentLocation() // Пробуем запросить обновленную локацию
            }
        } catch (e: SecurityException) {
            // Если нет разрешения на доступ к локации
            Log.e("LocationService", "Необходимы разрешения на определение местоположения: ${e.message}")
        }
    }

    /**
     * Метод для отправки HTTP-запроса с данными локации.
     */
    private fun sendData(location: Location) {
        val json = JSONObject().apply {
            put("user_id", userId)
            put("latitude", location.latitude)
            put("longitude", location.longitude)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = json.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(SERVER_URL)
            .addHeader("X-API-Key", apiKey)
            .post(requestBody)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("LocationService", "Ошибка отправки данных: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                Log.d("LocationService", "Данные отправлены, код ответа: ${response.code}")
                response.close()
            }
        })
    }

    /**
     * Если lastLocation недоступен, запрашиваем одно обновление текущей локации.
     */
    private fun requestCurrentLocation() {
        try {
            val locationRequest = LocationRequest.create().apply {
                // Немедленный запрос
                interval = 0
                fastestInterval = 0
                priority = LocationRequest.PRIORITY_HIGH_ACCURACY
                numUpdates = 1
            }

            val locationCallback = object : LocationCallback() {
                override fun onLocationResult(locationResult: LocationResult) {
                    val newLocation = locationResult.lastLocation
                    if (newLocation != null && userId != -1 && apiKey.isNotEmpty()) {
                        Log.d("LocationService", "Получена новая локация по запросу")
                        sendData(newLocation)
                    } else {
                        Log.w("LocationService", "Запрошенная локация недоступна или отсутствуют параметры")
                    }
                    // Удаляем обновления после получения одной локации
                    fusedLocationClient.removeLocationUpdates(this)
                }
            }

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e("LocationService", "Ошибка запроса обновления локации: ${e.message}")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(locationRunnable)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}