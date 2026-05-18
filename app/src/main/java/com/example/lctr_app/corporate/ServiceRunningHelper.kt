package com.example.lctr_app.corporate

import android.app.ActivityManager
import android.content.Context
import com.example.lctr_app.LocationService

object ServiceRunningHelper {
    fun isLocationServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == LocationService::class.java.name
        }
    }
}
