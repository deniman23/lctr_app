package com.example.lctr_app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.lctr_app.BuildConfig
import com.example.lctr_app.corporate.CorporateSetupHelper
import com.example.lctr_app.corporate.CorporateSetupStatus
import com.example.lctr_app.corporate.DeviceOwnerManager
import com.example.lctr_app.corporate.LocationServiceStarter
import com.example.lctr_app.device.DeviceConfigStore
import com.example.lctr_app.security.AppLockManager
import com.example.lctr_app.ui.theme.Lctr_appTheme
import com.example.lctr_app.ui.theme.SystemAccent
import com.example.lctr_app.ui.theme.SystemGrayDark
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private lateinit var deviceConfig: DeviceConfigStore

    private val userIdState = mutableStateOf("")
    private val apiKeyState = mutableStateOf("")
    private val isServiceRunning = mutableStateOf(false)
    private val issuesState = mutableStateOf<List<String>>(emptyList())
    private val setupStatusState = mutableStateOf<CorporateSetupStatus?>(null)

    private val isUnlocked = mutableStateOf(false)
    private var deferLock = false

    companion object {
        const val EXTRA_CLEAR_DEVICE_OWNER = "clear_device_owner"
    }

    private val scanLauncher = registerForActivityResult(ScanContract()) { res ->
        deferLock = false
        res?.let {
            try {
                val j = JSONObject(it)
                val uid = j.getInt("user_id")
                val key = j.getString("api_key")
                userIdState.value = uid.toString()
                apiKeyState.value = key
                deviceConfig.saveCredentials(uid, key)
                j.optString("api_base_url").ifBlank { null }?.let { deviceConfig.apiBaseUrl = it }
                startOrReloadService(forceHealthReport = true)
                refreshUi()
                Toast.makeText(this, "QR применён, проверка настройки…", Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
                Toast.makeText(this, "Неверный QR", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val locationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
            val fine = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
            val coarse = perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            refreshUi()
            if (fine || coarse) {
                requestBackgroundLocationIfNeeded()
            } else {
                Toast.makeText(this, "Нужна геолокация", Toast.LENGTH_LONG).show()
            }
        }

    private val backgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshUi()
            if (!granted) {
                Toast.makeText(
                    this,
                    "Выберите «Разрешить всегда» в настройках приложения",
                    Toast.LENGTH_LONG,
                ).show()
                CorporateSetupHelper.openLocationPermissionSettings(this)
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            refreshUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Lctr_app)
        super.onCreate(savedInstanceState)
        deviceConfig = DeviceConfigStore(this)
        deviceConfig.loadFromLegacyPrefsIfNeeded()

        if (deviceConfig.userId != -1) userIdState.value = deviceConfig.userId.toString()
        if (deviceConfig.apiKey.isNotEmpty()) apiKeyState.value = deviceConfig.apiKey
        isServiceRunning.value = deviceConfig.serviceActive

        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_CLEAR_DEVICE_OWNER, false)) {
            DeviceOwnerManager.clearDeviceOwnerForProvisioning(this)
            finish()
            return
        }

        if (DeviceOwnerManager.isDeviceOwner(this)) {
            DeviceOwnerManager.applyDeviceOwnerPolicies(
                this,
                restartLocationService = false,
            )
        }

        refreshUi()
        isUnlocked.value = AppLockManager.isUnlocked()

        setContent {
            Lctr_appTheme {
                val setup by setupStatusState
                val unlocked by isUnlocked
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(stringResource(R.string.app_header_title)) },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = SystemGrayDark,
                                titleContentColor = Color.White,
                            ),
                        )
                    },
                ) { inner ->
                    if (!unlocked) {
                        LockScreen(
                            onUnlock = { pin ->
                                if (AppLockManager.verifyPin(this, pin)) {
                                    isUnlocked.value = true
                                    true
                                } else {
                                    false
                                }
                            },
                            modifier = Modifier.padding(inner),
                        )
                    } else {
                        MainScreen(
                            userIdState = userIdState,
                            apiKeyState = apiKeyState,
                            isSending = isServiceRunning.value,
                            issues = issuesState.value,
                            setup = setup,
                            adbCommand = DeviceOwnerManager.provisioningAdbCommand(packageName),
                            isHiddenFromLauncher = DeviceOwnerManager.isHiddenFromLauncher(this),
                            onToggleClick = { toggleLocationService() },
                            onScanClick = {
                                deferLock = true
                                scanLauncher.launch(null)
                            },
                            onInstallUpdatesClick = { CorporateSetupHelper.openInstallUpdatesSettings(this) },
                            onBatteryClick = { CorporateSetupHelper.openBatteryOptimizationSettings(this) },
                            onLocationClick = { requestAllLocationPermissions() },
                            onNotificationsClick = { requestNotificationPermission() },
                            onOpenAppSettingsClick = { CorporateSetupHelper.openAppSettings(this) },
                            onHideFromLauncherClick = {
                                DeviceOwnerManager.hideAppFromLauncher(this)
                                Toast.makeText(this, "Приложение скрыто из списка", Toast.LENGTH_SHORT).show()
                            },
                            onShowInLauncherClick = {
                                if (DeviceOwnerManager.showAppInLauncher(this)) {
                                    Toast.makeText(this, "Приложение снова в списке", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onChangePinClick = { current, newPin ->
                                AppLockManager.changePin(this, current, newPin)
                            },
                            onLockClick = {
                                AppLockManager.lock()
                                isUnlocked.value = false
                            },
                            modifier = Modifier.padding(inner),
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLockManager.touchSession()
        isUnlocked.value = AppLockManager.isUnlocked()
        isServiceRunning.value = deviceConfig.serviceActive ||
            com.example.lctr_app.corporate.ServiceRunningHelper.isLocationServiceRunning(this)
        if (deviceConfig.userId != -1 && deviceConfig.apiKey.isNotEmpty() &&
            (deviceConfig.serviceActive || DeviceOwnerManager.isDeviceOwner(this))
        ) {
            LocationServiceStarter.startIfConfigured(this, forceHealthReport = false)
            isServiceRunning.value = true
        }
        refreshUi()
    }

    private fun refreshUi() {
        setupStatusState.value = CorporateSetupHelper.evaluate(this)
        issuesState.value = com.example.lctr_app.device.DeviceDiagnostics.detectIssues(
            this,
            deviceConfig,
            deviceConfig.serviceActive,
        )
    }

    private fun requestAllLocationPermissions() {
        locationPermissionLauncher.launch(CorporateSetupHelper.requiredRuntimePermissions())
    }

    private fun requestBackgroundLocationIfNeeded() {
        val bg = CorporateSetupHelper.backgroundLocationPermission() ?: run {
            tryStartTrackingAfterSetup()
            return
        }
        if (ContextCompat.checkSelfPermission(this, bg) == PackageManager.PERMISSION_GRANTED) {
            tryStartTrackingAfterSetup()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            backgroundLocationLauncher.launch(bg)
        }
    }

    private fun requestNotificationPermission() {
        if (DeviceOwnerManager.isDeviceOwner(this)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun tryStartTrackingAfterSetup() {
        if (userIdState.value.isNotBlank() && apiKeyState.value.isNotBlank() &&
            CorporateSetupHelper.evaluate(this).locationAlways && !isServiceRunning.value
        ) {
            // не автостартуем без явного действия
        }
    }

    private fun toggleLocationService() {
        if (!isServiceRunning.value) {
            val uid = userIdState.value.toIntOrNull() ?: run {
                Toast.makeText(this, "Некорректный userId", Toast.LENGTH_SHORT).show()
                return
            }
            val key = apiKeyState.value
            if (key.isBlank()) {
                Toast.makeText(this, "Укажите API Key", Toast.LENGTH_SHORT).show()
                return
            }
            val setup = CorporateSetupHelper.evaluate(this)
            if (!setup.locationAlways) {
                Toast.makeText(this, "Сначала настройте геолокацию «Всегда»", Toast.LENGTH_LONG).show()
                requestAllLocationPermissions()
                return
            }
            if (!setup.canInstallUpdates && !setup.isDeviceOwner) {
                Toast.makeText(
                    this,
                    "Разрешите установку обновлений (кнопка ниже)",
                    Toast.LENGTH_LONG,
                ).show()
                CorporateSetupHelper.openInstallUpdatesSettings(this)
                return
            }
            deviceConfig.saveCredentials(uid, key)
            if (hasLocationPermissions()) {
                startOrReloadService()
                isServiceRunning.value = true
                deviceConfig.serviceActive = true
                Toast.makeText(this, "Отправка локации включена", Toast.LENGTH_SHORT).show()
            } else {
                requestAllLocationPermissions()
            }
        } else {
            stopService(Intent(this, LocationService::class.java))
            isServiceRunning.value = false
            deviceConfig.serviceActive = false
            deviceConfig.syncLegacyPrefs()
            Toast.makeText(this, "Отправка локации отключена", Toast.LENGTH_SHORT).show()
        }
        refreshUi()
    }

    private fun startOrReloadService(forceHealthReport: Boolean = false) {
        val intent = Intent(this, LocationService::class.java).apply {
            putExtra(LocationService.EXTRA_USER_ID, deviceConfig.userId)
            putExtra(LocationService.EXTRA_API_KEY, deviceConfig.apiKey)
            if (forceHealthReport) putExtra(LocationService.EXTRA_FORCE_HEALTH_REPORT, true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun hasLocationPermissions(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val fgOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.FOREGROUND_SERVICE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        } else true
        return (fine || coarse) && fgOk
    }

    override fun onPause() {
        super.onPause()
        if (!deferLock) {
            AppLockManager.lock()
            isUnlocked.value = false
        }
    }

    @Composable
    fun LockScreen(
        onUnlock: (String) -> Boolean,
        modifier: Modifier = Modifier,
    ) {
        var pin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf(false) }

        Column(
            modifier
                .padding(24.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(stringResource(R.string.app_header_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Введите код доступа",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray,
            )
            Spacer(Modifier.height(16.dp))
            TextField(
                value = pin,
                onValueChange = {
                    pin = it.filter { ch -> ch.isDigit() }.take(8)
                    error = false
                },
                label = { Text("Код") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            if (error) {
                Spacer(Modifier.height(8.dp))
                Text("Неверный код", color = Color(0xFFD32F2F))
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    error = !onUnlock(pin)
                    if (!error) pin = ""
                },
                enabled = pin.length >= 4,
            ) {
                Text("Войти")
            }
        }
    }

    @Composable
    fun MainScreen(
        userIdState: MutableState<String>,
        apiKeyState: MutableState<String>,
        isSending: Boolean,
        issues: List<String>,
        setup: CorporateSetupStatus?,
        adbCommand: String,
        isHiddenFromLauncher: Boolean,
        onToggleClick: () -> Unit,
        onScanClick: () -> Unit,
        onInstallUpdatesClick: () -> Unit,
        onBatteryClick: () -> Unit,
        onLocationClick: () -> Unit,
        onNotificationsClick: () -> Unit,
        onOpenAppSettingsClick: () -> Unit,
        onHideFromLauncherClick: () -> Unit,
        onShowInLauncherClick: () -> Unit,
        onChangePinClick: (String, String) -> Boolean,
        onLockClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        var showChangePin by remember { mutableStateOf(false) }
        var currentPin by remember { mutableStateOf("") }
        var newPin by remember { mutableStateOf("") }
        var pinChangeError by remember { mutableStateOf(false) }
        Column(
            modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SystemAccent.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        text = "${stringResource(R.string.app_update_banner)} v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.titleSmall,
                        color = SystemGrayDark,
                    )
                    Text(
                        text = "Серая тема · код доступа 3107",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.DarkGray,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Версия приложения ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Сборка ${BuildConfig.VERSION_CODE}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Статус отправки: ${if (isSending) "Включена" else "Отключена"}",
                color = if (isSending) Color(0xFF388E3C) else Color(0xFFD32F2F),
            )

            setup?.let { s ->
                Spacer(Modifier.height(12.dp))
                Text("Настройка устройства", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                SetupRow(
                    "Корпоративный режим (Device Owner)",
                    s.isDeviceOwner,
                    if (!s.isDeviceOwner) "Назначается через ADB в мастерской" else null,
                )
                if (!s.isDeviceOwner) {
                    Text(
                        text = adbCommand,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                SetupActionRow("Установка обновлений (APK)", s.canInstallUpdates, onInstallUpdatesClick)
                SetupActionRow("Батарея без ограничений", s.batteryUnrestricted, onBatteryClick)
                SetupActionRow("Уведомления", s.notificationsGranted, onNotificationsClick)
                if (s.isDeviceOwner) {
                    Text(
                        "При Device Owner уведомления приложения отключены политикой (POST_NOTIFICATIONS = DENIED)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
                SetupActionRow("Геолокация «Всегда»", s.locationAlways, onLocationClick)
                if (!s.locationAlways) {
                    TextButton(onClick = onOpenAppSettingsClick) {
                        Text("Открыть настройки приложения")
                    }
                }
                if (s.allReady) {
                    Text(
                        "Готово к работе",
                        color = Color(0xFF388E3C),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (issues.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Проблемы: ${issues.joinToString(", ")}",
                    color = Color(0xFFF57C00),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(onClick = onScanClick) { Text("Сканировать QR") }
            Spacer(Modifier.height(16.dp))
            TextField(
                value = userIdState.value,
                onValueChange = { userIdState.value = it },
                label = { Text("User ID") },
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = apiKeyState.value,
                onValueChange = { apiKeyState.value = it },
                label = { Text("API Key") },
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onToggleClick) {
                Text(if (isSending) "Остановить отправку" else "Отправить данные")
            }

            Spacer(Modifier.height(16.dp))
            Text("Доступ техника", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (setup?.isDeviceOwner == true) {
                if (isHiddenFromLauncher) {
                    TextButton(onClick = onShowInLauncherClick) {
                        Text("Показать в списке приложений")
                    }
                } else {
                    TextButton(onClick = onHideFromLauncherClick) {
                        Text("Скрыть из списка приложений")
                    }
                }
            }
            TextButton(onClick = { showChangePin = !showChangePin }) {
                Text(if (showChangePin) "Отмена смены кода" else "Сменить код доступа")
            }
            if (showChangePin) {
                TextField(
                    value = currentPin,
                    onValueChange = { currentPin = it.filter { ch -> ch.isDigit() }.take(8) },
                    label = { Text("Текущий код") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = newPin,
                    onValueChange = { newPin = it.filter { ch -> ch.isDigit() }.take(8) },
                    label = { Text("Новый код (мин. 4 цифры)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                if (pinChangeError) {
                    Text("Не удалось сменить код", color = Color(0xFFD32F2F))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        pinChangeError = !onChangePinClick(currentPin, newPin)
                        if (!pinChangeError) {
                            currentPin = ""
                            newPin = ""
                            showChangePin = false
                            Toast.makeText(this@MainActivity, "Код изменён", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = currentPin.length >= 4 && newPin.length >= 4,
                ) {
                    Text("Сохранить новый код")
                }
            }
            TextButton(onClick = onLockClick) { Text("Заблокировать") }
        }
    }

    @Composable
    private fun SetupRow(label: String, ok: Boolean, hint: String? = null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium)
                hint?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
            }
            Text(if (ok) "OK" else "—", color = if (ok) Color(0xFF388E3C) else Color(0xFFD32F2F))
        }
        Spacer(Modifier.height(6.dp))
    }

    @Composable
    private fun SetupActionRow(label: String, ok: Boolean, onConfigure: () -> Unit) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (ok) {
                Text("OK", color = Color(0xFF388E3C))
            } else {
                TextButton(onClick = onConfigure) { Text("Разрешить") }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}
