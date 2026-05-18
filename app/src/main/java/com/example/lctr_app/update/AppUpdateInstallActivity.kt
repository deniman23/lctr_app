package com.example.lctr_app.update

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.lctr_app.device.AppUpdateManager
import com.example.lctr_app.device.DeviceConfigStore

/**
 * Прозрачный экран: открывает системный установщик APK и сразу закрывается.
 * Сотруднику нужен один тап «Установить» в системном диалоге (ограничение Android).
 */
class AppUpdateInstallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val started = AppUpdateManager(this, DeviceConfigStore(this)).promptInstallIfReady()
        if (!started) finish()
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) finish()
    }
}
