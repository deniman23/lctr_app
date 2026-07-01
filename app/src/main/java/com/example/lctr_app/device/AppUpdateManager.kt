package com.example.lctr_app.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.lctr_app.BuildConfig
import com.example.lctr_app.corporate.DeviceOwnerManager
import com.example.lctr_app.update.AppUpdateInstallActivity
import com.example.lctr_app.update.UpdateInstallReceiver
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class AppUpdateManager(
    private val context: Context,
    private val config: DeviceConfigStore,
) {
    private val downloadHttp = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()
    private val ackHttp = LocatorHttpClient(config)

    fun handlePollCommand(payload: JSONObject, commandId: String?) {
        val url = resolveUrl(
            payload.optString("url")
                .ifBlank { payload.optString("app_update_url") }
                .ifBlank { config.pendingAppUpdateUrl.orEmpty() }
        )
        if (url.isBlank()) {
            Log.w(TAG, "app_update: missing url")
            return
        }

        val versionCode = when {
            payload.has("version_code") && !payload.isNull("version_code") ->
                payload.getInt("version_code")
            else -> payload.optInt("version_code", 0)
        }
        val versionName = payload.optString("version")
            .ifBlank { payload.optString("app_update_version") }
            .ifBlank { null }

        if (versionCode > 0 && versionCode <= BuildConfig.VERSION_CODE) {
            config.appUpdateState = AppUpdateState.SKIPPED
            config.appUpdateLastError = "already_up_to_date"
            Log.i(TAG, "skip update: current=${BuildConfig.VERSION_CODE} target=$versionCode")
            return
        }

        config.pendingAppUpdateUrl = url
        config.pendingAppUpdateVersion = versionName
        config.pendingAppUpdateVersionCode = versionCode
        config.pendingAppUpdateSha256 = payload.optString("sha256").ifBlank { null }
        config.appUpdateWifiOnly = payload.optBoolean("wifi_only", false)
        config.appUpdateDeferQuietHours = payload.optBoolean("install_when_idle", true)
        config.appUpdateForceInstall = payload.optBoolean("force", false)
        config.appUpdateCommandId = commandId
        config.appUpdateState = AppUpdateState.IDLE
        config.appUpdateLastError = null

        if (config.appUpdateWifiOnly && !isOnWifi()) {
            config.appUpdateState = AppUpdateState.IDLE
            config.appUpdateLastError = "waiting_for_wifi"
            Log.i(TAG, "update deferred: wifi_only")
            return
        }

        startDownloadService(commandId)
    }

    fun resumePendingWork() {
        when (config.appUpdateState) {
            AppUpdateState.DOWNLOADED, AppUpdateState.FAILED -> {
                if (apkFileOrNull() != null) maybePromptInstall()
            }
            AppUpdateState.IDLE -> {
                if (!config.pendingAppUpdateUrl.isNullOrBlank() &&
                    config.appUpdateLastError == "waiting_for_wifi" &&
                    isOnWifi()
                ) {
                    startDownloadService(config.appUpdateCommandId)
                }
            }
        }
    }

    fun startDownloadService(commandId: String?) {
        if (config.appUpdateState == AppUpdateState.DOWNLOADING) return
        config.appUpdateState = AppUpdateState.DOWNLOADING
        com.example.lctr_app.update.AppUpdateDownloadService.start(context, commandId)
    }

    fun downloadPendingUpdate(onProgress: (Int) -> Unit): Boolean {
        val url = config.pendingAppUpdateUrl ?: return fail("no_url")
        val targetFile = apkTargetFile()
        targetFile.parentFile?.mkdirs()
        if (targetFile.exists()) targetFile.delete()

        val request = Request.Builder().url(url).get().build()
        return try {
            downloadHttp.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return fail("HTTP ${response.code}")
                val body = response.body ?: return fail("empty_body")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        var downloaded = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                onProgress(((downloaded * 100) / total).toInt())
                            }
                        }
                    }
                }
                if (!verifySha256(targetFile, config.pendingAppUpdateSha256)) {
                    targetFile.delete()
                    return fail("sha256_mismatch")
                }
                if (!verifySameSigner(targetFile)) {
                    targetFile.delete()
                    return fail("signature_mismatch")
                }
                config.appUpdateDownloadedPath = targetFile.absolutePath
                config.appUpdateDownloadProgress = 100
                true
            }
        } catch (e: Exception) {
            targetFile.delete()
            fail(e.message ?: "download_error")
            false
        }
    }

    fun onDownloadFinished(commandId: String?, http: LocatorHttpClient? = null) {
        config.appUpdateState = AppUpdateState.DOWNLOADED
        config.appUpdateLastError = null
        Log.i(TAG, "APK downloaded: ${config.pendingAppUpdateVersion}")
        commandId?.let { http?.ackCommand(it, "downloaded") }
        maybePromptInstall(commandId)
    }

    fun onDownloadFailed(commandId: String?, reason: String, http: LocatorHttpClient? = ackHttp) {
        config.appUpdateState = AppUpdateState.FAILED
        config.appUpdateLastError = reason
        Log.e(TAG, "APK download failed: $reason")
        commandId?.let { http?.ackCommand(it, "download_failed", reason) }
    }

    fun maybePromptInstall(commandId: String? = config.appUpdateCommandId) {
        if (config.appUpdateState != AppUpdateState.DOWNLOADED &&
            config.appUpdateState != AppUpdateState.FAILED
        ) return
        val apk = apkFileOrNull() ?: return

        if (beginSilentInstall(apk, commandId)) return

        if (config.appUpdateDeferQuietHours && !config.appUpdateForceInstall && isQuietHours()) {
            showUpdateReadyNotification(apk, highPriority = false)
            return
        }

        if (config.appUpdateForceInstall && promptInstallIfReady(commandId)) return

        showUpdateReadyNotification(apk, highPriority = config.appUpdateForceInstall)
    }

    fun promptInstallIfReady(commandId: String? = config.appUpdateCommandId): Boolean {
        val apk = apkFileOrNull() ?: return false

        if (beginSilentInstall(apk, commandId)) return true

        if (!canInstallPackages()) {
            config.appUpdateLastError = "install_permission_required"
            openUnknownSourcesSettingsFallback()
            return false
        }

        config.appUpdateState = AppUpdateState.INSTALLING
        commandId?.let { ackHttp.ackCommand(it, "installing") }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(installIntent)
            return true
        } catch (e: Exception) {
            config.appUpdateState = AppUpdateState.DOWNLOADED
            config.appUpdateLastError = e.message ?: "install_intent_failed"
            Log.e(TAG, "install failed", e)
            return false
        }
    }

    private fun beginSilentInstall(apk: File, commandId: String?): Boolean {
        if (!DeviceOwnerManager.isDeviceOwner(context)) return false
        config.appUpdateState = AppUpdateState.INSTALLING
        if (!DeviceOwnerManager.installApkSilently(context, apk)) {
            config.appUpdateState = AppUpdateState.DOWNLOADED
            Log.w(TAG, "silent install failed, fallback to user prompt")
            return false
        }
        commandId?.let { ackHttp.ackCommand(it, "installing") }
        return true
    }

    private fun showUpdateReadyNotification(apk: File, highPriority: Boolean) {
        createUpdateChannel()
        val installPi = PendingIntent.getActivity(
            context,
            0,
            Intent(context, AppUpdateInstallActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val actionPi = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, UpdateInstallReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val version = config.pendingAppUpdateVersion ?: "новая"
        val builder = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Доступно обновление")
            .setContentText("Версия $version. Нажмите «Установить» — один раз.")
            .setContentIntent(installPi)
            .addAction(0, "Установить", actionPi)
            .setAutoCancel(true)
            .setPriority(
                if (highPriority) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT,
            )
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(UPDATE_NOTIF_ID, builder.build())
    }

    private fun createUpdateChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val ch = NotificationChannel(
            UPDATE_CHANNEL_ID,
            "Обновления",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Уведомления о готовых обновлениях приложения"
        }
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(ch)
    }

    private fun apkTargetFile(): File {
        val dir = context.getExternalFilesDir("updates") ?: File(context.cacheDir, "updates")
        val code = config.pendingAppUpdateVersionCode
        val name = if (code > 0) "update-v$code.apk" else "update-latest.apk"
        return File(dir, name)
    }

    private fun apkFileOrNull(): File? {
        val path = config.appUpdateDownloadedPath ?: return null
        val f = File(path)
        return f.takeIf { it.isFile && it.length() > 0 }
    }

    private fun resolveUrl(raw: String): String {
        if (raw.isBlank()) return ""
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val base = config.endpoints().apiBaseUrl.trimEnd('/')
        return base + if (raw.startsWith("/")) raw else "/$raw"
    }

    private fun verifySha256(file: File, expected: String?): Boolean {
        if (expected.isNullOrBlank()) return true
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var n: Int
            while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return hash.equals(expected.trim(), ignoreCase = true)
    }

    @Suppress("DEPRECATION")
    private fun verifySameSigner(apk: File): Boolean {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archiveInfo = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: return false
        archiveInfo.applicationInfo?.apply {
            sourceDir = apk.absolutePath
            publicSourceDir = apk.absolutePath
        }
        val currentInfo = pm.getPackageInfo(context.packageName, flags)
        val archiveCerts = archiveInfo.signingInfo?.apkContentsSigners
        val currentCerts = currentInfo.signingInfo?.apkContentsSigners
        if (archiveCerts.isNullOrEmpty() || currentCerts.isNullOrEmpty()) return true
        return archiveCerts[0].toCharsString() == currentCerts[0].toCharsString()
    }

    private fun canInstallPackages(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    private fun openUnknownSourcesSettingsFallback(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** Ночное окно 22:00–07:00 — не беспокоим установкой. */
    private fun isQuietHours(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= 22 || hour < 7
    }

    private fun fail(reason: String): Boolean {
        config.appUpdateLastError = reason
        return false
    }

    companion object {
        private const val TAG = "AppUpdateManager"
        private const val UPDATE_CHANNEL_ID = "app_update_ready"
        private const val UPDATE_NOTIF_ID = 43
    }
}
