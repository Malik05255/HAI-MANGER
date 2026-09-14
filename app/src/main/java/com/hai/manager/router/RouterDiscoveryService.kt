package com.hai.manager.router

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL

class RouterDiscoveryService(private val context: Context) {

    suspend fun discover(): RouterSnapshot = withContext(Dispatchers.IO) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
            ?: return@withContext RouterSnapshot(false, message = "لا يوجد اتصال شبكة نشط")
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true &&
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) != true
        ) {
            return@withContext RouterSnapshot(false, message = "اتصل بشبكة Wi-Fi الخاصة بالراوتر أولًا")
        }

        val properties = connectivityManager.getLinkProperties(network)
        val gateway = properties?.routes
            ?.firstOrNull { route -> route.isDefaultRoute && route.gateway is Inet4Address }
            ?.gateway?.hostAddress
            ?: properties?.routes?.firstOrNull { it.gateway is Inet4Address }?.gateway?.hostAddress
            ?: return@withContext RouterSnapshot(false, message = "تعذر تحديد عنوان الراوتر")

        val probes = listOf("http://$gateway", "https://$gateway")
        for (candidate in probes) {
            val probe = runCatching { probe(candidate) }.getOrNull() ?: continue
            val winner = RouterAdapters.all
                .map { adapter -> adapter to adapter.confidence(probe.body, probe.headers) }
                .maxByOrNull { it.second }
            val brand = winner?.takeIf { it.second > 0 }?.first?.brand ?: RouterBrand.UNKNOWN
            val confidence = winner?.second ?: 0
            return@withContext RouterSnapshot(
                connected = true,
                gateway = gateway,
                managementUrl = candidate,
                brand = brand,
                model = detectModel(probe.body),
                pageTitle = detectTitle(probe.body),
                confidence = confidence,
                message = if (brand == RouterBrand.UNKNOWN) "تم العثور على الراوتر وسيتم تحسين التعرف عليه" else "تم التعرف على الراوتر"
            )
        }

        RouterSnapshot(
            connected = true,
            gateway = gateway,
            message = "تم العثور على الراوتر، لكن واجهة الإدارة لم تستجب عبر HTTP/HTTPS"
        )
    }

    private fun probe(url: String): ProbeResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 2200
        connection.readTimeout = 2200
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "HAI-MANAGER/0.1")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { reader ->
                buildString {
                    var total = 0
                    while (total < 96_000) {
                        val line = reader.readLine() ?: break
                        append(line).append('\n')
                        total += line.length
                    }
                }
            }.orEmpty()
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapValues { (_, values) -> values.orEmpty().joinToString(";") }
            ProbeResult(body, headers)
        } finally {
            connection.disconnect()
        }
    }

    private fun detectTitle(body: String): String? =
        Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(body)?.groupValues?.getOrNull(1)?.replace(Regex("\\s+"), " ")?.trim()?.take(80)

    private fun detectModel(body: String): String? {
        val models = listOf(
            "MC888", "MC888 Pro", "MC801A", "MC889",
            "H155-381", "H158-381", "B818", "B535", "B525",
            "FastMile", "Nighthawk M6", "Nighthawk M5"
        )
        return models.firstOrNull { body.contains(it, ignoreCase = true) }
    }

    private data class ProbeResult(
        val body: String,
        val headers: Map<String, String>
    )
}
