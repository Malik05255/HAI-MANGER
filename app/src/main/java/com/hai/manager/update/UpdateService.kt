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
import java.net.HttpURLConnection
import java.net.URL
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
                sha256 = json.optString("sha256"),
                channel = json.optString("channel", "beta"),
                publishedAt = json.optString("publishedAt", Instant.now().toString())
            ).also {
                require(it.versionCode > 0 && it.versionName.isNotBlank()) { "ملف التحديث غير صالح" }
            }
        } finally {
            connection.disconnect()
        }
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
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        val pending = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (update.signatureStable) {
            "الإصدار ${update.versionName} جاهز للتنزيل والتثبيت"
        } else {
            "الإصدار ${update.versionName} متوفر — قناة اختبار"
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
}

object ApkUpdateInstaller {
    fun downloadAndInstall(context: Context, update: AppUpdate) {
        if (!update.downloadable) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return
        }
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val fileName = "HAI-MANAGER-${update.versionName}.apk"
        val request = DownloadManager.Request(Uri.parse(update.apkUrl))
            .setTitle("HAI MANAGER ${update.versionName}")
            .setDescription("تنزيل التحديث")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
        val downloadId = manager.enqueue(request)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) != downloadId) return
                val uri = manager.getUriForDownloadedFile(downloadId) ?: return
                receiverContext.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
                runCatching { receiverContext.unregisterReceiver(this) }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }
}
