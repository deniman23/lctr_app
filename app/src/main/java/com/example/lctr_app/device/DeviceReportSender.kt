package com.example.lctr_app.device

import android.content.Context
import android.util.Log

class DeviceReportSender(
  private val context: Context,
  private val config: DeviceConfigStore,
  private val http: LocatorHttpClient,
) {
  fun sendFullReport(
    serviceRunning: Boolean,
    commandId: String? = null,
    onDone: ((Boolean) -> Unit)? = null,
  ) {
    http.checkUsersMe {
      val report = DeviceDiagnostics.buildReport(context, config, serviceRunning, commandId)
      http.sendDeviceReport(report) { ok ->
        // app_update ack'ается отдельно (accepted → downloaded → success); не шлём completed здесь
        onDone?.invoke(ok)
      }
    }
  }

  fun sendPeriodicIfDue(serviceRunning: Boolean) {
    val dueAt = config.lastHealthReportAtMs + config.healthReportIntervalMs
    if (System.currentTimeMillis() < dueAt) return
    Log.d(TAG, "scheduled health report")
    sendFullReport(serviceRunning)
  }

  companion object {
    private const val TAG = "DeviceReportSender"
  }
}
