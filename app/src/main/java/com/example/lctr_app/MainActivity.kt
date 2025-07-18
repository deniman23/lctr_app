package com.example.lctr_app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.lctr_app.ui.theme.Lctr_appTheme
import org.json.JSONObject

class MainActivity : ComponentActivity() {

    // Переменные состояния для хранения userId и apiKey
    private val userIdState = mutableStateOf("")
    private val apiKeyState = mutableStateOf("")

    // Новый лончер для сканирования QR-кода через ScanContract
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result?.let { scannedData ->
            try {
                // Парсинг JSON из отсканированного результата
                val json = JSONObject(scannedData)
                val scannedUserId = json.getInt("user_id").toString()
                val scannedApiKey = json.getString("api_key")
                updateTextFields(scannedUserId, scannedApiKey)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Lctr_appTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        userIdState = userIdState,
                        apiKeyState = apiKeyState,
                        modifier = Modifier.padding(innerPadding),
                        onSendDataClick = {
                            startLocationService(userIdState.value, apiKeyState.value)
                        },
                        onScanClick = {
                            scanLauncher.launch(null)
                        }
                    )
                }
            }
        }
    }

    /**
     * Функция обновления значений userId и apiKey.
     */
    private fun updateTextFields(scannedUserId: String, scannedApiKey: String) {
        userIdState.value = scannedUserId
        apiKeyState.value = scannedApiKey
    }

    fun startLocationService(userId: String, apiKey: String) {
        val serviceIntent = Intent(this, LocationService::class.java).apply {
            putExtra("user_id", userId)
            putExtra("api_key", apiKey)
        }
        startService(serviceIntent)
    }

    /**
     * Composable, который отображает интерфейс.
     */
    @Composable
    fun MainScreen(
        userIdState: MutableState<String>,
        apiKeyState: MutableState<String>,
        modifier: Modifier = Modifier,
        onSendDataClick: () -> Unit,
        onScanClick: () -> Unit
    ) {
        Column(modifier = modifier.padding(16.dp)) {
            Button(onClick = onScanClick) {
                Text("Сканировать QR")
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextField(
                value = userIdState.value,
                onValueChange = { userIdState.value = it },
                label = { Text("User ID") }
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextField(
                value = apiKeyState.value,
                onValueChange = { apiKeyState.value = it },
                label = { Text("API Key") }
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onSendDataClick) {
                Text("Отправить данные")
            }
        }
    }
}