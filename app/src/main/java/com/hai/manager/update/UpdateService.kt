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
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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
private const val UPDATE_CHANNEL = "hai_manager_updates_v2"
private const val PERIODIC_UPDATE_WORK = "hai-manager-update-check"
private const val IMMEDIATE_UPDATE_WORK = "hai-manager-update-check-now"
private const val UPDATE_PREFS = "hai_update_notifications"
private const val LAST_NOTIFIED_VERSION = "last_notified_version"

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
    /**
     * Android may expose more than one certificate when signing lineage/key rotation exists.
     * Never decide compatibility from only the first signer.
     */
    fun currentSigningCertSha256s(context: Context): Set<String> = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(
                context.packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        signingCertSha256s(info)
    }.getOrDefault(emptySet())

    fun currentSigningCertSha256(context: Context): String? = currentSigningCertSha256s(context).firstOrNull()

    fun canReplaceInstalled(context: Context, update: AppUpdate): Boolean {
        if (!update.signatureStable || update.signingCertSha256.isBlank()) return false
        val installed = currentSigningCertSha256s(context)
        return installed.any { it.equals(update.signingCertSha256, ignoreCase = true) }
    }

    fun archiveSigningCertSha256s(context: Context, file: File): Set<String> {
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(
                file.absolutePath,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        }
        return packageInfo?.let(::signingCertSha256s).orEmpty()
    }

    fun archiveSigningCertSha256(context: Context, file: File): String? =
        archiveSigningCertSha256s(context, file).firstOrNull()

    private fun signingCertSha256s(info: PackageInfo): Set<String> {
        val signatures = linkedSetOf<Signature>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo
            signingInfo?.apkContentsSigners?.let(signatures::addAll)
            signingInfo?.signingCertificateHistory?.let(signatures::addAll)
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.let(signatures::addAll)
        }
        return signatures.mapTo(linkedSetOf()) { sha256(it.toByteArray()) }
    }
}

internal object UpdateNotificationPolicy {
    fun shouldNotify(installedVersionCode: Int, candidateVersionCode: Int, lastNotifiedVersionCode: Int): Boolean =
        candidateVersionCode > installedVersionCode && candidateVersionCode > lastNotifiedVersionCode
}

object UpdateScheduler {
    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val periodic = PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            PERIODIC_UPDATE_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )
        checkNow(appContext)
    }

    fun checkNow(context: Context) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_UPDATE_WORK,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}

class UpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // Device catalog failure must never prevent checking the app update feed.
        runCatching { DeviceCatalogRepository(applicationContext).sync() }
        return when (val result = UpdateRepository().check()) {
            is UpdateCheckResult.Success -> {
                if (result.update.available) UpdateNotifications.show(applicationContext, result.update)
                Result.success()
            }
            is UpdateCheckResult.Failure -> Result.retry()
        }
    }
}

class UpdateRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            UpdateNotifications.createChannel(context)
            UpdateScheduler.schedule(context)
        }
    }
}

object UpdateNotifications {
    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(UPDATE_CHANNEL, "تحديثات HAI MANAGER", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "إشعار عند توفر إصدار جديد من HAI MANAGER"
                    enableVibration(true)
                    setShowBadge(true)
                }
            )
        }
    }

    fun notificationsEnabled(context: Context): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return manager.areNotificationsEnabled()
    }

    fun show(context: Context, update: AppUpdate) {
        createChannel(context)
        if (!notificationsEnabled(context)) return

        val prefs = context.getSharedPreferences(UPDATE_PREFS, Context.MODE_PRIVATE)
        val lastNotified = prefs.getInt(LAST_NOTIFIED_VERSION, 0)
        if (!UpdateNotificationPolicy.shouldNotify(BuildConfig.VERSION_CODE, update.versionCode, lastNotified)) return

        val pending = PendingIntent.getActivity(
            context,
            10,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = when {
            !update.signatureStable -> "الإصدار ${update.versionName} متوفر، لكنه غير جاهز للتثبيت الآمن بعد"
            else -> "الإصدار ${update.versionName} جاهز. اضغط لفتح HAI MANAGER والتحديث"
        }
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("يتوفر تحديث جديد لـ HAI MANAGER")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(2001, notification)
        prefs.edit().putInt(LAST_NOTIFIED_VERSION, update.versionCode).apply()
    }

    fun showInstallError(context: Context, message: String) {
        createChannel(context)
        if (!notificationsEnabled(context)) return
        val notification = NotificationCompat.Builder(context, UPDATE_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("تعذر تثبيت تحديث HAI MANAGER")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(2002, notification)
    }
}

object ApkUpdateInstaller {
    fun downloadAndInstall(context: Context, update: AppUpdate) {
        if (!update.downloadable) return
        if (!update.signatureStable || update.signingCertSha256.isBlank()) {
            UpdateNotifications.showInstallError(
                context,
                "هذا الإصدار ليس ضمن قناة التوقيع الثابت، لذلك لن يحاول التطبيق استبدال النسخة الحالية."
            )
            return
        }

        /*
         * Do not block an otherwise valid update just because PackageManager failed to
         * expose the installed certificate exactly as expected. Android's PackageInstaller
         * is the final authority and will reject a genuinely incompatible signer anyway.
         * We still cryptographically verify the downloaded APK against the permanent HAI cert.
         */
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

                    val archiveCerts = UpdateCompatibility.archiveSigningCertSha256s(receiverContext, file)
                    val expectedCert = update.signingCertSha256.normalizeHex()
                    if (archiveCerts.none { it.equals(expectedCert, ignoreCase = true) }) {
                        file.delete()
                        UpdateNotifications.showInstallError(
                            receiverContext,
                            "توقيع APK لا يطابق مفتاح HAI MANAGER المعتمد. تم إيقاف التثبيت."
                        )
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
