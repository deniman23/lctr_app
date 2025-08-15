package com.example.lctr_app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.lctr_app.ui.theme.Lctr_appTheme
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    private val userIdState      = mutableStateOf("")
    private val apiKeyState      = mutableStateOf("")
    private val isServiceRunning = mutableStateOf(false)

    private val scanLauncher = registerForActivityResult(ScanContract()) { res ->
        res?.let {
            try {
                val j = JSONObject(it)
                userIdState.value = j.getInt("user_id").toString()
                apiKeyState.value = j.getString("api_key")
            } catch (_: Exception) {}
        }
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val fine   = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (fine || coarse) {
                toggleLocationService()
            } else {
                Toast.makeText(this, "Нужны разрешения для локации", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Восстанавливаем состояние из SharedPreferences
        val prefs   = PreferenceManager.getDefaultSharedPreferences(this)
        val active  = prefs.getBoolean("location_service_active", false)
        val savedId = prefs.getInt("user_id", -1)
        val savedKey= prefs.getString("api_key", "") ?: ""
        if (active) {
            isServiceRunning.value = true
            if (savedId != -1) userIdState.value = savedId.toString()
            apiKeyState.value = savedKey
        }

        setContent {
            Lctr_appTheme {
                Scaffold { inner ->
                    MainScreen(
                        userIdState   = userIdState,
                        apiKeyState   = apiKeyState,
                        isSending     = isServiceRunning.value,
                        onToggleClick = { toggleLocationService() },
                        onScanClick   = { scanLauncher.launch(null) },
                        modifier      = Modifier.padding(inner)
                    )
                }
            }
        }
    }

    private fun toggleLocationService() {
        if (!isServiceRunning.value) {
            val uid = userIdState.value.toIntOrNull() ?: run {
                Toast.makeText(this, "Некорректный userId", Toast.LENGTH_SHORT).show()
                return
            }
            val key = apiKeyState.value
            if (hasLocationPermissions()) {
                val intent = Intent(this, LocationService::class.java).apply {
                    putExtra("user_id", uid)
                    putExtra("api_key", key)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(intent)
                else
                    startService(intent)

                isServiceRunning.value = true
                saveServiceState(true)
                Toast.makeText(this, "Отправка локации включена", Toast.LENGTH_SHORT).show()
            } else {
                requestLocationPermissions()
            }
        } else {
            stopService(Intent(this, LocationService::class.java))
            isServiceRunning.value = false
            saveServiceState(false)
            Toast.makeText(this, "Отправка локации отключена", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveServiceState(active: Boolean) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        prefs.edit().apply {
            putBoolean("location_service_active", active)
            if (active) {
                putInt("user_id", userIdState.value.toInt())
                putString("api_key", apiKeyState.value)
            }
            apply()
        }
    }

    private fun hasLocationPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val fgOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.FOREGROUND_SERVICE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        return (fine || coarse) && fgOk
    }

    private fun requestLocationPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            perms.add(Manifest.permission.FOREGROUND_SERVICE_LOCATION)
        locationPermissionLauncher.launch(perms.toTypedArray())
    }

    @Composable
    fun MainScreen(
        userIdState: MutableState<String>,
        apiKeyState: MutableState<String>,
        isSending: Boolean,
        onToggleClick: () -> Unit,
        onScanClick: () -> Unit,
        modifier: Modifier = Modifier
    ) {
        Column(modifier.padding(16.dp)) {
            Text(
                text  = "Статус отправки: ${if (isSending) "Включена" else "Отключена"}",
                color = if (isSending) Color(0xFF388E3C) else Color(0xFFD32F2F)
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onScanClick) { Text("Сканировать QR") }
            Spacer(Modifier.height(16.dp))
            TextField(
                value = userIdState.value,
                onValueChange = { userIdState.value = it },
                label = { Text("User ID") }
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = apiKeyState.value,
                onValueChange = { apiKeyState.value = it },
                label = { Text("API Key") }
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onToggleClick) {
                Text(if (isSending) "Остановить отправку" else "Отправить данные")
            }
        }
    }
}