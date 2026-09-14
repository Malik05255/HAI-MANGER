package com.hai.manager.catalog

import android.content.Context
import com.hai.manager.router.LiveFirmwareProfileCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val CATALOG_URL = "https://raw.githubusercontent.com/Malik05255/HAI-MANGER/main/device-catalog.json"
private const val FIRMWARE_SOURCES_URL = "https://raw.githubusercontent.com/Malik05255/HAI-MANGER/main/firmware-sources.json"
private const val BUNDLED_CATALOG_ASSET = "device-catalog.json"
private const val BUNDLED_FIRMWARE_SOURCES_ASSET = "firmware-sources.json"

data class CatalogStatus(
    val version: Int,
    val deviceCount: Int,
    val updatedAt: String
)

class DeviceCatalogRepository(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("device_catalog", Context.MODE_PRIVATE)

    init {
        seedBundledCatalog()
        seedBundledFirmwareSources()
        primeCache()
    }

    fun status(): CatalogStatus = CatalogStatus(
        version = preferences.getInt("version", 0),
        deviceCount = preferences.getInt("deviceCount", 0),
        updatedAt = preferences.getString("updatedAt", "لم يتم التحديث بعد").orEmpty()
    )

    fun cachedJson(): String? = preferences.getString("raw", null) ?: bundledCatalogJson()

    /**
     * Returns the normal device catalog plus the separately maintained firmware-discovery index.
     * Firmware findings are informational only; they never grant install permission by themselves.
     */
    fun firmwareJson(): String? {
        val catalogRaw = cachedJson() ?: return null
        val sourcesRaw = preferences.getString("firmwareSourcesRaw", null) ?: bundledFirmwareSourcesJson()
        return runCatching {
            val catalog = JSONObject(catalogRaw)
            if (!sourcesRaw.isNullOrBlank()) {
                val sources = JSONObject(sourcesRaw)
                sources.optJSONArray("firmwareFindings")?.let { catalog.put("firmwareFindings", it) }
                catalog.put("firmwareSourceVersion", sources.optInt("sourceVersion", 0))
            }
            catalog.toString()
        }.getOrElse { catalogRaw }
    }

    fun primeCache() {
        LiveFirmwareProfileCache.update(cachedJson())
    }

    suspend fun sync(): CatalogStatus? = withContext(Dispatchers.IO) {
        // Source discovery is independent from the capability catalog. If one endpoint fails,
        // the other still works and the bundled fallback remains available.
        downloadJson(FIRMWARE_SOURCES_URL)?.let { persistFirmwareSources(it) }

        val remote = downloadJson(CATALOG_URL)
        if (!remote.isNullOrBlank()) {
            persistCatalog(remote)?.let { return@withContext it }
        }

        // لا نوقف أدوات الراوتر إذا تعذر الإنترنت/GitHub أثناء الاتصال بالراوتر.
        // نستخدم آخر نسخة ناجحة، وإن لم توجد فنعتمد النسخة المضمّنة داخل APK.
        val fallback = cachedJson()
        if (!fallback.isNullOrBlank()) {
            LiveFirmwareProfileCache.update(fallback)
            return@withContext parseStatus(fallback)
        }

        null
    }

    private fun seedBundledCatalog() {
        val bundled = bundledCatalogJson() ?: return
        val bundledStatus = parseStatus(bundled) ?: return
        val storedVersion = preferences.getInt("version", 0)
        val hasStoredRaw = !preferences.getString("raw", null).isNullOrBlank()

        if (!hasStoredRaw || storedVersion < bundledStatus.version) {
            persistCatalog(bundled)
        }
    }

    private fun seedBundledFirmwareSources() {
        if (!preferences.getString("firmwareSourcesRaw", null).isNullOrBlank()) return
        bundledFirmwareSourcesJson()?.let { persistFirmwareSources(it) }
    }

    private fun bundledCatalogJson(): String? = readAsset(BUNDLED_CATALOG_ASSET)

    private fun bundledFirmwareSourcesJson(): String? = readAsset(BUNDLED_FIRMWARE_SOURCES_ASSET)

    private fun readAsset(name: String): String? = runCatching {
        appContext.assets.open(name).bufferedReader().use { it.readText() }
    }.getOrNull()

    private fun downloadJson(url: String): String? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 6000
        connection.instanceFollowRedirects = true
        connection.useCaches = false
        connection.setRequestProperty("Cache-Control", "no-cache, no-store")
        connection.setRequestProperty("Pragma", "no-cache")
        connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun persistCatalog(raw: String): CatalogStatus? {
        val status = parseStatus(raw) ?: return null
        preferences.edit()
            .putString("raw", raw)
            .putInt("version", status.version)
            .putInt("deviceCount", status.deviceCount)
            .putString("updatedAt", status.updatedAt)
            .apply()
        LiveFirmwareProfileCache.update(raw)
        return status
    }

    private fun persistFirmwareSources(raw: String): Boolean {
        val valid = runCatching {
            val json = JSONObject(raw)
            json.optInt("sourceVersion") > 0 && json.optJSONArray("firmwareFindings") != null
        }.getOrDefault(false)
        if (!valid) return false
        preferences.edit().putString("firmwareSourcesRaw", raw).apply()
        return true
    }

    private fun parseStatus(raw: String): CatalogStatus? = runCatching {
        val json = JSONObject(raw)
        val version = json.optInt("catalogVersion")
        val updatedAt = json.optString("updatedAt")
        val count = json.optJSONArray("devices")?.length() ?: 0
        val profiles = json.optJSONArray("firmwareProfiles")
        if (version <= 0 || profiles == null) return@runCatching null
        CatalogStatus(version, count, updatedAt)
    }.getOrNull()
}
