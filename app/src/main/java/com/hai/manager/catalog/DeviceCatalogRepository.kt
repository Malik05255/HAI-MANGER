package com.hai.manager.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val CATALOG_URL = "https://raw.githubusercontent.com/Malik05255/HAI-MANGER/main/device-catalog.json"

data class CatalogStatus(
    val version: Int,
    val deviceCount: Int,
    val updatedAt: String
)

class DeviceCatalogRepository(context: Context) {
    private val preferences = context.getSharedPreferences("device_catalog", Context.MODE_PRIVATE)

    fun status(): CatalogStatus = CatalogStatus(
        version = preferences.getInt("version", 0),
        deviceCount = preferences.getInt("deviceCount", 0),
        updatedAt = preferences.getString("updatedAt", "لم يتم التحديث بعد").orEmpty()
    )

    fun cachedJson(): String? = preferences.getString("raw", null)

    suspend fun sync(): CatalogStatus? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(CATALOG_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Cache-Control", "no-cache")
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val raw = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(raw)
                val version = json.optInt("catalogVersion")
                val updatedAt = json.optString("updatedAt")
                val count = json.optJSONArray("devices")?.length() ?: 0
                preferences.edit()
                    .putString("raw", raw)
                    .putInt("version", version)
                    .putInt("deviceCount", count)
                    .putString("updatedAt", updatedAt)
                    .apply()
                CatalogStatus(version, count, updatedAt)
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}
