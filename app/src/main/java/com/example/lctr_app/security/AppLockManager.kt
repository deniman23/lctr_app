package com.example.lctr_app.security

import android.content.Context
import com.example.lctr_app.BuildConfig
import com.example.lctr_app.device.DeviceConfigStore
import java.security.MessageDigest

object AppLockManager {

    private const val SESSION_MS = 15 * 60 * 1000L

    @Volatile
    private var unlockedUntilMs = 0L

    fun isUnlocked(): Boolean = System.currentTimeMillis() < unlockedUntilMs

    fun unlock() {
        unlockedUntilMs = System.currentTimeMillis() + SESSION_MS
    }

    fun lock() {
        unlockedUntilMs = 0L
    }

    fun touchSession() {
        if (isUnlocked()) unlock()
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        if (pin.length < 4) return false
        val store = DeviceConfigStore(context)
        val stored = store.adminPinHash
        if (stored.isEmpty()) {
            if (pin != BuildConfig.DEFAULT_ADMIN_PIN) return false
            store.adminPinHash = hashPin(pin)
            unlock()
            return true
        }
        if (hashPin(pin) != stored) return false
        unlock()
        return true
    }

    fun changePin(context: Context, currentPin: String, newPin: String): Boolean {
        if (newPin.length < 4) return false
        if (!verifyPin(context, currentPin)) return false
        DeviceConfigStore(context).adminPinHash = hashPin(newPin)
        unlock()
        return true
    }

    /** Установка PIN с сервера (config_update); сбрасывает сессию. */
    fun setPinFromRemote(context: Context, pin: String): Boolean {
        if (pin.length < 4 || !pin.all { it.isDigit() }) return false
        DeviceConfigStore(context).adminPinHash = hashPin(pin)
        lock()
        return true
    }

    fun isPinConfigured(context: Context): Boolean =
        DeviceConfigStore(context).adminPinHash.isNotEmpty()

    fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(pin.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
