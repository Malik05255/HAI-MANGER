package com.hai.manager.router

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
            val rootProbe = runCatching { probe(candidate) }.getOrNull() ?: continue
            val passiveWinner = RouterAdapters.all
                .map { adapter -> adapter to adapter.confidence(rootProbe.body, rootProbe.headers) }
                .maxByOrNull { it.second }

            val passiveBrand = passiveWinner?.takeIf { it.second > 0 }?.first?.brand ?: RouterBrand.UNKNOWN
            val passiveConfidence = passiveWinner?.second ?: 0
            val active = if (passiveConfidence < 90) activeFingerprint(candidate) else null
            val brand = active?.brand ?: passiveBrand
            val confidence = active?.confidence ?: passiveConfidence
            val evidence = buildString {
                append(rootProbe.body)
                active?.evidence?.let { append('\n').append(it) }
            }

            return@withContext RouterSnapshot(
                connected = true,
                gateway = gateway,
                managementUrl = candidate,
                brand = brand,
                model = detectModel(evidence),
                pageTitle = detectTitle(rootProbe.body),
                confidence = confidence,
                message = when {
                    active != null -> "تم التعرف على راوتر ${brand.displayName} عبر واجهة الإدارة"
                    brand == RouterBrand.UNKNOWN -> "تم العثور على الراوتر؛ دعم المرحلة الحالية مخصص لـ Huawei وZTE"
                    else -> "تم التعرف على راوتر ${brand.displayName}"
                }
            )
        }

        RouterSnapshot(
            connected = true,
            gateway = gateway,
            message = "تم العثور على الراوتر، لكن واجهة الإدارة لم تستجب عبر HTTP/HTTPS"
        )
    }

    private suspend fun activeFingerprint(baseUrl: String): ActiveFingerprint? = coroutineScope {
        val base = baseUrl.trimEnd('/')
        val zteDeferred = async {
            runCatching {
                probe(
                    "$base/goform/goform_get_cmd_process?isTest=false&cmd=DeviceName,model_name,product_name,wa_inner_version&multi_data=1",
                    timeoutMs = 1600
                )
            }.getOrNull()
        }
        val huaweiDeferred = async {
            runCatching { probe("$base/api/device/information", timeoutMs = 1600) }.getOrNull()
        }

        val zte = zteDeferred.await()
        val huawei = huaweiDeferred.await()

        when {
            zte?.looksLikeZteApi() == true -> ActiveFingerprint(RouterBrand.ZTE, 98, zte.body)
            huawei?.looksLikeHuaweiApi() == true -> ActiveFingerprint(RouterBrand.HUAWEI, 98, huawei.body)
            else -> null
        }
    }

    private fun probe(url: String, timeoutMs: Int = 2200): ProbeResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "HAI-MANAGER/0.5")
        connection.setRequestProperty("Accept", "application/json, application/xml, text/html, */*")
        return try {
            val code = connection.responseCode
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { reader ->
                buildString {
                    var total = 0
                    while (total < 160_000) {
                        val line = reader.readLine() ?: break
                        append(line).append('\n')
                        total += line.length
                    }
                }
            }.orEmpty()
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapValues { (_, values) -> values.orEmpty().joinToString(";") }
            ProbeResult(code, body, headers)
        } finally {
            connection.disconnect()
        }
    }

    private fun ProbeResult.looksLikeZteApi(): Boolean {
        val text = body.lowercase()
        if (code == 404) return false
        return text.trimStart().startsWith("{") && (
            "\"devicename\"" in text ||
                "\"model_name\"" in text ||
                "\"product_name\"" in text ||
                "\"wa_inner_version\"" in text ||
                "\"result\":\"failure\"" in text.replace(" ", "")
            )
    }

    private fun ProbeResult.looksLikeHuaweiApi(): Boolean {
        val text = body.lowercase()
        if (code == 404) return false
        val responseShape = "<response" in text && (
            "<devicename>" in text ||
                "<productfamily>" in text ||
                "<serialnumber>" in text ||
                "<imei>" in text ||
                "<softwareversion>" in text
            )
        val knownAuthError = "<error>" in text && (
            "125002" in text || "125003" in text || "100003" in text
            )
        return responseShape || knownAuthError
    }

    private fun detectTitle(body: String): String? =
        Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(body)?.groupValues?.getOrNull(1)?.replace(Regex("\\s+"), " ")?.trim()?.take(80)

    private fun detectModel(body: String): String? {
        val models = listOf(
            "MC888 Ultra", "MC888 Pro", "MC888D", "MC888", "MC801A", "MC889A", "MC889", "MC7010CA", "MC7010",
            "MF297D", "MF289F", "MF286D", "MF286R", "MF286", "MF293N",
            "H158-381", "H155-381", "H138-380", "H122-373", "H112-370", "H312-371",
            "B818", "B715", "B628", "B612", "B535", "B525", "B315", "B310", "B593"
        )
        return models.firstOrNull { body.contains(it, ignoreCase = true) }
    }

    private data class ProbeResult(
        val code: Int,
        val body: String,
        val headers: Map<String, String>
    )

    private data class ActiveFingerprint(
        val brand: RouterBrand,
        val confidence: Int,
        val evidence: String
    )
}
