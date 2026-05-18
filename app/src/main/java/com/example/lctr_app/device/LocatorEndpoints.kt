package com.example.lctr_app.device

import com.example.lctr_app.BuildConfig

/**
 * API paths for locator backend (locator_go contract).
 * Runtime [apiBaseUrl] may override BuildConfig default after remote config_update.
 */
data class LocatorEndpoints(
    val apiBaseUrl: String,
) {
    val locationPost: String get() = "$apiBaseUrl/api/location"
    val devicePoll: String get() = "$apiBaseUrl/api/device/poll"
    val deviceReport: String get() = "$apiBaseUrl/api/device/report"
    val deviceCommandAck: String get() = "$apiBaseUrl/api/device/command/ack"
    val usersMe: String get() = "$apiBaseUrl/api/users/me"

    /** Legacy poll URL kept for backward compatibility during server rollout. */
    val legacyLocationRequestPoll: String get() = "$apiBaseUrl/api/location/request"

    companion object {
        fun defaultApiBaseFromBuildConfig(): String {
            val locationUrl = BuildConfig.LOCATOR_API_URL
            return locationUrl.replace(Regex("/api/.*$"), "")
        }

        fun fromBuildConfig(apiBaseOverride: String? = null): LocatorEndpoints {
            val configuredBase = BuildConfig.LOCATOR_API_BASE
            val base = apiBaseOverride?.trimEnd('/')
                ?: configuredBase.ifBlank { defaultApiBaseFromBuildConfig() }
            return LocatorEndpoints(base)
        }
    }
}
