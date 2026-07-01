package com.example.lctr_app.device

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class LocatorHttpClient(
  private val config: DeviceConfigStore,
) {
  private val client = OkHttpClient.Builder()
    .connectTimeout(20, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .build()

  fun apiKey(): String = config.apiKey

  fun get(
    url: String,
    onResult: (status: Int, body: String?, error: String?) -> Unit,
  ) {
    if (config.apiKey.isEmpty()) {
      onResult(0, null, "no_api_key")
      return
    }
    val req = Request.Builder()
      .url(url)
      .addHeader("X-API-Key", config.apiKey)
      .get()
      .build()
    client.newCall(req).enqueue(callback(onResult))
  }

  fun postJson(
    url: String,
    json: JSONObject,
    onResult: (status: Int, body: String?, error: String?) -> Unit = { _, _, _ -> },
  ) {
    if (config.apiKey.isEmpty()) {
      onResult(0, null, "no_api_key")
      return
    }
    val body = json.toString().toRequestBody(JSON_MEDIA)
    val req = Request.Builder()
      .url(url)
      .addHeader("X-API-Key", config.apiKey)
      .post(body)
      .build()
    client.newCall(req).enqueue(callback(onResult))
  }

  fun checkUsersMe(onDone: (() -> Unit)? = null) {
    val url = config.endpoints().usersMe
    get(url) { status, _, error ->
      if (error == null) config.recordMeCheck(status)
      onDone?.invoke()
    }
  }

  fun sendDeviceReport(
    report: JSONObject,
    onDone: ((success: Boolean) -> Unit)? = null,
  ) {
    postJson(config.endpoints().deviceReport, report) { status, _, error ->
      if (status in 200..299) {
        config.lastHealthReportAtMs = System.currentTimeMillis()
        onDone?.invoke(true)
      } else {
        Log.w(TAG, "device report failed: HTTP $status error=$error")
        onDone?.invoke(false)
      }
    }
  }

  fun ackCommand(commandId: String, status: String = "success", message: String? = null) {
    val body = JSONObject().apply {
      put("command_id", commandId)
      put("status", status)
      if (!message.isNullOrBlank()) put("message", message)
    }
    postJson(config.endpoints().deviceCommandAck, body) { httpStatus, _, _ ->
      Log.d(TAG, "command ack $commandId ($status) -> HTTP $httpStatus")
    }
  }

  private fun callback(
    onResult: (status: Int, body: String?, error: String?) -> Unit,
  ) = object : Callback {
    override fun onFailure(call: Call, e: IOException) {
      onResult(0, null, e.message)
    }

    override fun onResponse(call: Call, response: Response) {
      response.use {
        onResult(it.code, it.body?.string(), null)
      }
    }
  }

  companion object {
    private const val TAG = "LocatorHttp"
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
  }
}
