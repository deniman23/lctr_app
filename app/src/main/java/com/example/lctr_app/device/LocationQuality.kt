package com.example.lctr_app.device

import android.location.Location
import android.os.SystemClock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Фильтр перед отправкой координат на сервер.
 * Блокирует устаревший кэш GPS (метро/туннель) и явно неточные periodic-точки.
 */
object LocationQuality {

    const val MAX_PERIODIC_FIX_AGE_MS = 3 * 60 * 1000L
    const val MAX_ON_DEMAND_FIX_AGE_MS = 5 * 60 * 1000L
    const val MAX_PERIODIC_ACCURACY_M = 80f
    const val MAX_ON_DEMAND_ACCURACY_M = 150f
    const val MAX_LOCATION_REQUEST_AGE_MS = 90_000L
    const val STATIONARY_RADIUS_M = 25.0

    data class LastSent(
        val latitude: Double,
        val longitude: Double,
        val atMs: Long,
    )

    data class Reject(val reason: String)

    fun rejectReason(
        location: Location,
        source: String,
        lastSent: LastSent?,
    ): Reject? {
        if (location.isFromMockProvider) {
            return Reject("mock_location")
        }

        val fixAgeMs = fixAgeMs(location)
        val maxAge = if (source == "on_demand") MAX_ON_DEMAND_FIX_AGE_MS else MAX_PERIODIC_FIX_AGE_MS
        if (fixAgeMs > maxAge) {
            return Reject("stale_fix_${fixAgeMs}ms")
        }

        val maxAccuracy = if (source == "on_demand") MAX_ON_DEMAND_ACCURACY_M else MAX_PERIODIC_ACCURACY_M
        if (location.hasAccuracy() && location.accuracy > maxAccuracy) {
            return Reject("poor_accuracy_${location.accuracy.toInt()}m")
        }

        if (source == "periodic" && lastSent != null) {
            val dist = haversineM(
                lastSent.latitude, lastSent.longitude,
                location.latitude, location.longitude,
            )
            // Кэш «застрял» на старых координатах: позиция та же, но fix давно не обновлялся.
            if (dist < STATIONARY_RADIUS_M && fixAgeMs > MAX_LOCATION_REQUEST_AGE_MS) {
                return Reject("stale_stationary")
            }
            // Сеть отдаёт старую точку с плохой точностью без реального движения.
            if (dist < STATIONARY_RADIUS_M && location.hasAccuracy() && location.accuracy > 50f) {
                return Reject("stationary_poor_accuracy")
            }
        }

        return null
    }

    fun fixAgeMs(location: Location): Long {
        if (location.elapsedRealtimeNanos > 0L) {
            return ((SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L)
                .coerceAtLeast(0L)
        }
        return (System.currentTimeMillis() - location.time).coerceAtLeast(0L)
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
