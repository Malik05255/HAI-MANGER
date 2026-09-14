package com.hai.manager.router

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class RouterHttpClient(private val baseUrl: String) {
    private val cookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)

    suspend fun get(path: String): RouterHttpResponse = withContext(Dispatchers.IO) {
        request(path)
    }

    private fun request(path: String): RouterHttpResponse {
        val url = resolve(path)
        val uri = URI(url.toString())
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "HAI-MANAGER/0.2")
        connection.setRequestProperty("Accept", "application/json, application/xml, text/xml, text/plain, */*")

        cookieManager.get(uri, emptyMap())["Cookie"]
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { connection.setRequestProperty("Cookie", it) }

        return try {
            val code = connection.responseCode
            val headerMap = connection.headerFields
                .filterKeys { it != null }
                .mapKeys { (key, _) -> key!! }
                .mapValues { (_, values) -> values.orEmpty() }
            runCatching { cookieManager.put(uri, headerMap) }

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
            RouterHttpResponse(code, body, headerMap.mapValues { it.value.joinToString(";") })
        } finally {
            connection.disconnect()
        }
    }

    private fun resolve(path: String): URL {
        val normalizedBase = if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/"
        return URL(URL(normalizedBase), path.removePrefix("/"))
    }
}

data class RouterHttpResponse(
    val code: Int,
    val body: String,
    val headers: Map<String, String>
) {
    val successful: Boolean get() = code in 200..299
}

interface OperationalRouterAdapter {
    val brand: RouterBrand
    suspend fun inspect(client: RouterHttpClient, snapshot: RouterSnapshot): RouterInspection
}

class RouterInspectorService {
    suspend fun inspect(snapshot: RouterSnapshot): RouterInspection {
        val baseUrl = snapshot.managementUrl
            ?: return RouterInspection(
                snapshot = snapshot,
                accessStatus = RouterAccessStatus.FAILED,
                message = "لا يوجد عنوان إدارة صالح لهذا الراوتر"
            )

        val adapter = when (snapshot.brand) {
            RouterBrand.ZTE -> ZteOperationalAdapter
            RouterBrand.HUAWEI -> HuaweiOperationalAdapter
            else -> null
        } ?: return RouterInspection(
            snapshot = snapshot,
            accessStatus = RouterAccessStatus.UNSUPPORTED,
            device = RouterDeviceInfo(
                manufacturer = snapshot.brand.displayName.takeIf { snapshot.brand != RouterBrand.UNKNOWN },
                model = snapshot.model
            ),
            message = "تم التعرف على الراوتر، لكن القراءة المباشرة لهذا النوع لم تُضف بعد"
        )

        return runCatching { adapter.inspect(RouterHttpClient(baseUrl), snapshot) }
            .getOrElse {
                RouterInspection(
                    snapshot = snapshot,
                    accessStatus = RouterAccessStatus.FAILED,
                    message = "تعذر قراءة بيانات الراوتر من واجهة الإدارة"
                )
            }
    }
}

object ZteOperationalAdapter : OperationalRouterAdapter {
    override val brand = RouterBrand.ZTE

    private val commands = listOf(
        "DeviceName",
        "model_name",
        "product_name",
        "SerialNumber",
        "serial_number",
        "imei",
        "wa_inner_version",
        "hardware_version",
        "web_version",
        "network_type",
        "network_type_ex",
        "network_provider",
        "lte_rsrp",
        "lte_rsrq",
        "lte_snr",
        "lte_rssi",
        "Z5g_rsrp",
        "Z5g_rsrq",
        "Z5g_SINR",
        "nr5g_rsrp",
        "nr5g_rsrq",
        "nr5g_snr",
        "lte_ca_pcell_band",
        "lte_ca_scell_band",
        "nr5g_band",
        "cell_id",
        "pci",
        "wan_ipaddr"
    ).joinToString(",")

    override suspend fun inspect(client: RouterHttpClient, snapshot: RouterSnapshot): RouterInspection {
        val response = client.get("/goform/goform_get_cmd_process?isTest=false&cmd=$commands&multi_data=1")
        if (response.code == 401 || response.code == 403) return authRequired(snapshot)

        val json = runCatching { JSONObject(response.body) }.getOrNull()
        if (json == null) {
            return if (looksLikeLogin(response.body)) authRequired(snapshot) else failed(snapshot)
        }

        val device = RouterDeviceInfo(
            manufacturer = "ZTE",
            model = json.firstString("DeviceName", "model_name", "product_name") ?: snapshot.model,
            serialNumber = json.firstString("SerialNumber", "serial_number"),
            imei = json.firstString("imei"),
            firmwareVersion = json.firstString("wa_inner_version"),
            hardwareVersion = json.firstString("hardware_version"),
            webUiVersion = json.firstString("web_version"),
            wanIp = json.firstString("wan_ipaddr")
        )

        val signal = CellularSignal(
            networkType = json.firstString("network_type_ex", "network_type"),
            operatorName = json.firstString("network_provider"),
            rsrp = json.firstString("Z5g_rsrp", "nr5g_rsrp", "lte_rsrp").cleanMetric(),
            rsrq = json.firstString("Z5g_rsrq", "nr5g_rsrq", "lte_rsrq").cleanMetric(),
            sinr = json.firstString("Z5g_SINR", "nr5g_snr", "lte_snr").cleanMetric(),
            rssi = json.firstString("lte_rssi").cleanMetric(),
            bands = bandValues(
                json.firstString("lte_ca_pcell_band"),
                json.firstString("lte_ca_scell_band"),
                json.firstString("nr5g_band")
            ),
            cellId = json.firstString("cell_id"),
            pci = json.firstString("pci")
        )

        val hasData = listOf(
            device.model,
            device.serialNumber,
            device.imei,
            device.firmwareVersion,
            device.hardwareVersion,
            signal.networkType,
            signal.rsrp,
            signal.sinr
        ).any { !it.isNullOrBlank() }

        if (!hasData && json.optString("result").equals("failure", ignoreCase = true)) {
            return authRequired(snapshot)
        }
        if (!hasData) return failed(snapshot)

        return RouterInspection(
            snapshot = snapshot,
            accessStatus = RouterAccessStatus.AVAILABLE,
            device = device,
            signal = signal.takeIf { it.hasData },
            capabilities = setOf(
                RouterCapability.DEVICE_INFO,
                RouterCapability.CELLULAR_SIGNAL,
                RouterCapability.NETWORK_STATUS,
                RouterCapability.SESSION_COOKIES,
                RouterCapability.FIRMWARE_INFO
            ),
            message = "تمت قراءة بيانات ZTE مباشرة من الراوتر"
        )
    }

    private fun authRequired(snapshot: RouterSnapshot) = RouterInspection(
        snapshot = snapshot,
        accessStatus = RouterAccessStatus.AUTH_REQUIRED,
        device = RouterDeviceInfo(manufacturer = "ZTE", model = snapshot.model),
        capabilities = setOf(RouterCapability.SESSION_COOKIES),
        message = "الراوتر موجود، لكن واجهة ZTE تطلب تسجيل الدخول قبل قراءة التفاصيل"
    )

    private fun failed(snapshot: RouterSnapshot) = RouterInspection(
        snapshot = snapshot,
        accessStatus = RouterAccessStatus.FAILED,
        device = RouterDeviceInfo(manufacturer = "ZTE", model = snapshot.model),
        message = "تم العثور على ZTE لكن إصدار WebUI الحالي لم يعطِ بيانات عبر واجهة القراءة المعروفة"
    )
}

object HuaweiOperationalAdapter : OperationalRouterAdapter {
    override val brand = RouterBrand.HUAWEI

    override suspend fun inspect(client: RouterHttpClient, snapshot: RouterSnapshot): RouterInspection {
        val infoResponse = client.get("/api/device/information")
        val signalResponse = client.get("/api/device/signal")

        if (requiresHuaweiAuth(infoResponse) && requiresHuaweiAuth(signalResponse)) {
            return RouterInspection(
                snapshot = snapshot,
                accessStatus = RouterAccessStatus.AUTH_REQUIRED,
                device = RouterDeviceInfo(manufacturer = "Huawei", model = snapshot.model),
                capabilities = setOf(RouterCapability.SESSION_COOKIES),
                message = "الراوتر موجود، لكن واجهة Huawei تطلب تسجيل الدخول قبل قراءة التفاصيل"
            )
        }

        val infoXml = infoResponse.body
        val signalXml = signalResponse.body
        val device = RouterDeviceInfo(
            manufacturer = "Huawei",
            model = xmlValue(infoXml, "DeviceName", "ProductFamily", "Classify") ?: snapshot.model,
            serialNumber = xmlValue(infoXml, "SerialNumber"),
            imei = xmlValue(infoXml, "Imei", "IMEI"),
            firmwareVersion = xmlValue(infoXml, "SoftwareVersion"),
            hardwareVersion = xmlValue(infoXml, "HardwareVersion"),
            webUiVersion = xmlValue(infoXml, "WebUIVersion"),
            wanIp = xmlValue(infoXml, "WanIPAddress", "wan_ip_address")
        )
        val signal = CellularSignal(
            networkType = xmlValue(signalXml, "mode", "workmode"),
            rsrp = xmlValue(signalXml, "rsrp").cleanMetric(),
            rsrq = xmlValue(signalXml, "rsrq").cleanMetric(),
            sinr = xmlValue(signalXml, "sinr").cleanMetric(),
            rssi = xmlValue(signalXml, "rssi").cleanMetric(),
            bands = bandValues(xmlValue(signalXml, "band")),
            cellId = xmlValue(signalXml, "cell_id", "cellid"),
            pci = xmlValue(signalXml, "pci")
        )

        val hasData = listOf(
            device.model,
            device.serialNumber,
            device.imei,
            device.firmwareVersion,
            signal.rsrp,
            signal.sinr
        ).any { !it.isNullOrBlank() }

        if (!hasData) {
            return RouterInspection(
                snapshot = snapshot,
                accessStatus = RouterAccessStatus.FAILED,
                device = RouterDeviceInfo(manufacturer = "Huawei", model = snapshot.model),
                message = "تم العثور على Huawei لكن إصدار HiLink/WebUI الحالي لم يعطِ بيانات عبر واجهات القراءة المعروفة"
            )
        }

        return RouterInspection(
            snapshot = snapshot,
            accessStatus = RouterAccessStatus.AVAILABLE,
            device = device,
            signal = signal.takeIf { it.hasData },
            capabilities = setOf(
                RouterCapability.DEVICE_INFO,
                RouterCapability.CELLULAR_SIGNAL,
                RouterCapability.NETWORK_STATUS,
                RouterCapability.SESSION_COOKIES,
                RouterCapability.FIRMWARE_INFO
            ),
            message = "تمت قراءة بيانات Huawei مباشرة من الراوتر"
        )
    }
}

private fun JSONObject.firstString(vararg keys: String): String? {
    for (key in keys) {
        val value = optString(key, "").trim()
        if (value.isMeaningful()) return value
    }
    return null
}

private fun String?.cleanMetric(): String? = this?.trim()?.takeIf { it.isMeaningful() }

private fun String.isMeaningful(): Boolean =
    isNotBlank() &&
        !equals("null", ignoreCase = true) &&
        !equals("undefined", ignoreCase = true) &&
        this != "--" &&
        this != "-"

private fun bandValues(vararg raw: String?): List<String> = raw
    .filterNotNull()
    .flatMap { value -> value.split(',', ';', ' ') }
    .map { it.trim() }
    .filter { it.isMeaningful() && it != "0" }
    .distinct()

private fun xmlValue(xml: String, vararg tags: String): String? {
    for (tag in tags) {
        val regex = Regex(
            "<$tag(?:\\s[^>]*)?>(.*?)</$tag>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val value = regex.find(xml)?.groupValues?.getOrNull(1)
            ?.replace("&amp;", "&")
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.trim()
        if (value?.isMeaningful() == true) return value
    }
    return null
}

private fun looksLikeLogin(body: String): Boolean {
    val text = body.lowercase()
    return "login" in text || "log in" in text || "password" in text
}

private fun requiresHuaweiAuth(response: RouterHttpResponse): Boolean {
    if (response.code == 401 || response.code == 403) return true
    val body = response.body.lowercase()
    return "125002" in body || "125003" in body || "100003" in body
}
