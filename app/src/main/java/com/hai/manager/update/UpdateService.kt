package com.hai.manager.update

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hai.manager.BuildConfig
import com.hai.manager.MainActivity
import com.hai.manager.catalog.DeviceCatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit

private const val RELEASE_FEED_URL = "https://github.com/Malik05255/HAI-MANGER/releases/download/beta/update-feed.json"
private const val LEGACY_FEED_URL = "https://raw.githubusercontent.com/Malik05255/HAI-MANGER/main/update-feed.json"
private const val UPDATE_CHANNEL = "hai_manager_updates"

data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String,
    val mandatory: Boolean,
    val signatureStable: Boolean,
    val signingCertSha256: String,
    val sha256: String,
    val channel: String,
    val publishedAt: String
) {
    val available: Boolean get() = versionCode > BuildConfig.VERSION_CODE
    val downloadable: Boolean get() = apkUrl.isNotBlank()
}

sealed interface UpdateCheckResult {
    data class Success(val update: AppUpdate) : UpdateCheckResult
    data class Failure(val message: String) : UpdateCheckResult
}

class UpdateRepository {
    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        val urls = listOf(RELEASE_FEED_URL, LEGACY_FEED_URL)
        var lastError = "تعذر الوصول إلى خادم التحديث"
        for (base in urls) {
            val result = runCatching { fetch(base) }
            if (result.isSuccess) return@withContext UpdateCheckResult.Success(result.getOrThrow())
            lastError = result.exceptionOrNull()?.message ?: lastError
        }
        UpdateCheckResult.Failure(lastError)
    }

    private fun fetch(base: String): AppUpdate {
        val separator = if ('?' in base) '&' else '?'
        val url = URL("$base${separator}t=${System.currentTimeMillis()}")
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 6500
        connection.readTimeout = 6500
        connection.useCaches = false
        connection.setRequestProperty("Cache-Control", "no-cache, no-store")
        connection.setRequestProperty("Pragma", "no-cache")
        return try {
            if (connection.responseCode !in 200..299) error("فشل التحقق: HTTP ${connection.responseCode}")
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            AppUpdate(
                versionCode = json.optInt("versionCode"),
                versionName = json.optString("versionName"),
                apkUrl = json.optString("apkUrl"),
                notes = json.optString("notes"),
                mandatory = json.optBoolean("mandatory", false),
                signatureStable = json.optBoolean("signatureStable", false),
                signingCertSha256 = json.optString("signingCertSha256").normalizeHex(),
                sha256 = json.optString("sha256").normalizeHex(),
                channel = json.optString("channel", "beta"),
                publishedAt = json.optString("publishedAt", Instant.now().toString())
            ).also {
                require(it.versionCode > 0 && it.versionName.isNotBlank()) { "ملف التحديث غير صالح" }
                if (it.signatureStable) require(it.signingCertSha256.length == 64) { "بصمة توقيع التحديث غير صالحة" }
            }
        } finally {
            connection.disconnect()
        }
    }
}

object UpdateCompatibility {
    fun currentSigningCertSha256(context: Context): String? =
        packageSigningCertSha256(context.packageManager, context.packageName)

    fun canReplaceInstalled(context: Context, update: AppUpdate): Boolean {
        if (!update.signatureStable || update.signingCertSha256.isBlank()) return false
        val current = currentSigningCertSha256(context) ?: return false
        return current.equals(update.signingCertSha256, ignoreCase = true)
    }

    fun archiveSigningCertSha256(context: Context, file: File): String? {
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        return packageInfo?.let(::signingCertSha256)
    }

    private fun packageSigningCertSha256(pm: PackageManager, packageName: String): String? {
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        return signingCertSha256(info)
    }

    private fun signingCertSha256(info: PackageInfo): String? {
        val bytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray()
        } ?: return null
        return sha256(bytes)
    }
}

object UpdateScheduler {
    fun schedule(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "hai-manager-update-check",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}

class UpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        DeviceCatalogRepository(applicationContext).sync()
        when (val result = UpdateRepository().check()) {
            is UpdateCheckResult.Success -> if (result.update.available) UpdateNotifications.show(applicationContext, result.update)
            is UpdateCheckResult.Failure -> Unit
        }
        return Result.success()
    }
}

object UpdateNotifications {
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(UPDATE_CHANNEL, "تحديثات HAI MANAGER", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "تنبيهات الإصدارات الجديدة وقاعدة الأجهزة"
                }
            )
        }
    }

    fun show(context: Context, update: AppUpdate) {
        createChannel(context)
        if (!canNotify(context)) return
        val pending = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when {
            !update.signatureStable -> "الإصدار ${update.versionName} موجود، لكنه غير موقع بمفتاح التحديث الثابت"
            !UpdateCompatibility.canReplaceInstalled(context, update) -> "الإصدار ${update.versionName} يحتاج إعادة تثبيت انتقالية مرة واحدة"
            else -> "الإصدار ${update.versionName} جاهز للتنزيل والتثبيت فوق النسخة الحالية"
        }
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("تحديث جديد لـ HAI MANAGER")
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(2001, notification)
    }

    fun showInstallError(context: Context, message: String) {
        createChannel(context)
        if (!canNotify(context)) return
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("تعذر تثبيت تحديث HAI MANAGER")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(2002, notification)
    }

    private fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
}

object ApkUpdateInstaller {
    fun downloadAndInstall(context: Context, update: AppUpdate) {
        if (!update.downloadable) return
        if (!update.signatureStable || update.signingCertSha256.isBlank()) {
            UpdateNotifications.showInstallError(context, "هذا الإصدار ليس ضمن قناة التوقيع الثابت، لذلك لن يحاول التطبيق استبدال النسخة الحالية.")
            return
        }
        if (!UpdateCompatibility.canReplaceInstalled(context, update)) {
            UpdateNotifications.showInstallError(
                context,
                "مفتاح توقيع النسخة الحالية مختلف. يلزم حذف النسخة القديمة مرة واحدة وتثبيت أول إصدار موقع بمفتاح HAI MANAGER الثابت."
            )
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }

        val downloadsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val fileName = "HAI-MANAGER-${update.versionName}.apk"
        val file = File(downloadsDir, fileName)
        if (file.exists()) file.delete()

        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle("HAI MANAGER ${update.versionName}")
            .setDescription("تنزيل التحديث الموقع")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val downloadId = manager.enqueue(request)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                try {
                    if (!downloadSucceeded(manager, downloadId) || !file.exists()) {
                        UpdateNotifications.showInstallError(receiverContext, "فشل تنزيل ملف التحديث.")
                        return
                    }
                    if (update.sha256.isNotBlank()) {
                        val actualSha = sha256(file.readBytes())
                        if (!actualSha.equals(update.sha256, ignoreCase = true)) {
                            file.delete()
                            UpdateNotifications.showInstallError(receiverContext, "فشل التحقق من سلامة ملف التحديث SHA-256.")
                            return
                        }
                    }
                    val archiveCert = UpdateCompatibility.archiveSigningCertSha256(receiverContext, file)
                    if (!archiveCert.equals(update.signingCertSha256, ignoreCase = true)) {
                        file.delete()
                        UpdateNotifications.showInstallError(receiverContext, "توقيع APK لا يطابق مفتاح HAI MANAGER المعتمد.")
                        return
                    }
                    val uri = manager.getUriForDownloadedFile(downloadId)
                    if (uri == null) {
                        UpdateNotifications.showInstallError(receiverContext, "تعذر فتح ملف التحديث بعد التنزيل.")
                        return
                    }
                    receiverContext.startActivity(
                        Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                } finally {
                    runCatching { receiverContext.unregisterReceiver(this) }
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun downloadSucceeded(manager: DownloadManager, downloadId: Long): Boolean {
        val cursor = manager.query(DownloadManager.Query().setFilterById(downloadId)) ?: return false
        cursor.use {
            if (!it.moveToFirst()) return false
            val column = it.getColumnIndex(DownloadManager.COLUMN_STATUS)
            return column >= 0 && it.getInt(column) == DownloadManager.STATUS_SUCCESSFUL
        }
    }
}

private fun String.normalizeHex(): String = replace(":", "").trim().uppercase()

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { "%02X".format(it) }
