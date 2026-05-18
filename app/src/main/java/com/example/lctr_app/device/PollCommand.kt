package com.example.lctr_app.device

import org.json.JSONObject

sealed class PollCommand {
  abstract val id: String?
  abstract val type: String

  data class LocationRequest(
    override val id: String?,
    val legacyRequestId: String? = null,
  ) : PollCommand() {
    override val type = TYPE
    fun effectiveRequestId(): String? = id ?: legacyRequestId

    companion object {
      const val TYPE = "location_request"
    }
  }

  data class HealthCheck(override val id: String?) : PollCommand() {
    override val type = TYPE

    companion object {
      const val TYPE = "health_check"
    }
  }

  data class ConfigUpdate(
    override val id: String?,
    val payload: JSONObject,
  ) : PollCommand() {
    override val type = TYPE

    companion object {
      const val TYPE = "config_update"
    }
  }

  data class AppUpdate(
    override val id: String?,
    val payload: JSONObject,
  ) : PollCommand() {
    override val type = TYPE

    companion object {
      const val TYPE = "app_update"
    }
  }

  companion object {
    fun parse(body: String): PollCommand? {
      if (body.isBlank()) return null
      val json = try {
        JSONObject(body)
      } catch (_: Exception) {
        return null
      }

      val command = json.optJSONObject("command")
      if (command != null) {
        val type = command.optString("type", "")
        val id = command.optString("id", "").ifBlank { null }
        val payload = command.optJSONObject("payload") ?: JSONObject()
        return when (type) {
          LocationRequest.TYPE -> LocationRequest(id)
          HealthCheck.TYPE -> HealthCheck(id)
          ConfigUpdate.TYPE -> ConfigUpdate(id, payload)
          AppUpdate.TYPE -> AppUpdate(id, payload)
          else -> null
        }
      }

      // Legacy: { "request_id": "...", "pending": true }
      if (json.has("request_id") || json.optBoolean("pending", false)) {
        if (json.optBoolean("pending", true).not() && !json.has("request_id")) return null
        val requestId = json.optString("request_id", "").ifBlank { null }
        return LocationRequest(id = requestId, legacyRequestId = requestId)
      }

      val type = json.optString("type", "")
      if (type.isNotEmpty()) {
        val id = json.optString("id", json.optString("command_id", "")).ifBlank { null }
        val payload = json.optJSONObject("payload") ?: JSONObject()
        return when (type) {
          LocationRequest.TYPE -> LocationRequest(id, json.optString("request_id", "").ifBlank { null })
          HealthCheck.TYPE -> HealthCheck(id)
          ConfigUpdate.TYPE -> ConfigUpdate(id, payload)
          AppUpdate.TYPE -> AppUpdate(id, payload)
          else -> null
        }
      }
      return null
    }
  }
}
