package com.example.lctr_app.corporate

import android.content.Context
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.lctr_app.device.DeviceConfigStore
import java.util.concurrent.TimeUnit

/** Резервный цикл WorkManager (≥15 мин) — переживает убийство foreground-сервиса. */
class LocatorSyncWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {

    override fun doWork(): Result {
        val config = DeviceConfigStore(applicationContext)
        config.loadFromLegacyPrefsIfNeeded()
        if (config.userId == -1 || config.apiKey.isEmpty() || config.trackingPaused) {
            return Result.success()
        }
        Log.i(TAG, "WorkManager sync — wake tracking")
        DeviceOwnerManager.wakeTracking(applicationContext, forceHealthReport = true)
        return Result.success()
    }

    companion object {
        private const val TAG = "LocatorSyncWorker"
        private const val UNIQUE_NAME = "locator_sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<LocatorSyncWorker>(15, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
