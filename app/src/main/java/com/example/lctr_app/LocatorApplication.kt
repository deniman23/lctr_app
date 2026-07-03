package com.example.lctr_app

import android.app.Application
import com.example.lctr_app.corporate.DeviceOwnerManager
import com.example.lctr_app.corporate.LocatorPollReceiver
import com.example.lctr_app.corporate.LocatorSyncWorker
import com.example.lctr_app.corporate.LocationServiceWatchdog
import com.example.lctr_app.device.DeviceConfigStore

class LocatorApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val config = DeviceConfigStore(this)
        config.loadFromLegacyPrefsIfNeeded()
        if (config.userId == -1 || config.apiKey.isEmpty()) return

        LocatorPollReceiver.scheduleNext(this)
        LocationServiceWatchdog.scheduleNext(this)
        LocatorSyncWorker.schedule(this)
        if (DeviceOwnerManager.isDeviceOwner(this)) {
            DeviceOwnerManager.applyDeviceOwnerPolicies(this, restartLocationService = true)
        }
    }
}
