package com.hai.manager.router

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class RouterHttpClient(private val baseUrl: String) {
    suspend fun get(path: String, headers: Map<String, String> = emptyMap()): RouterHttpResponse = withContext(Dispatchers.IO) {
        request("GET", path, null, null, headers)
    }

    suspend fun postForm(
        path: String,
        fields: Map<String, String>,
        headers: Map<String, String> = emptyMap()
    ): RouterHttpResponse = withContext(Dispatchers.IO) {
        val body = fields.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, Charsets.UTF_8.name())}=${URLEncoder.encode(value, Charsets.UTF_8.name())}"
        }
        request("POST", path, body, "application/x-www-form-urlencoded; charset=UTF-8", headers)
    }

    suspend fun postXml(
        path: String,
        xml: String,
        headers: Map<String, String> = emptyMap()
    ): RouterHttpResponse = withContext(Dispatchers.IO) {
        request("POST", path, xml, "application/xml; charset=UTF-8", headers)
    }

    private fun request(
        method: String,
        path: String,
        body: String?,
        contentType: String?,
        headers: Map<String, String>
    ): RouterHttpResponse {
        val url = resolve(path)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 3500
        connection.readTimeout = 3500
        connection.instanceFollowRedirects = true
        connection.requestMethod = method
        connection.setRequestProperty("User-Agent", "HAI-MANAGER/0.4")
        connection.setRequestProperty("Accept", "application/json, application/xml, text/xml, text/plain, */*")
        connection.setRequestProperty("Referer", baseUrl.trimEnd('/') + "/")
        connection.setRequestProperty("X-Requested-With", "XMLHttpRequest")
        CookieManager.getInstance().getCookie(url.toString())
            ?.takeIf { it.isNotBlank() }
            ?.let { connection.setRequestProperty("Cookie", it) }
        headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }

        if (body != null) {
            connection.doOutput = true
            if (contentType != null) connection.setRequestProperty("Content-Type", contentType)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }

        return try {
            val code = connection.responseCode
            connection.headerFields.forEach { (name, values) ->
                if (name != null && name.equals("Set-Cookie", ignoreCase = true)) {
                    values.orEmpty().forEach { cookie -> CookieManager.getInstance().setCookie(url.toString(), cookie) }
                }
            }
            CookieManager.getInstance().flush()
            val stream = if (code in 200..399) connection.inputStream else connection.errorStream
            val responseBody = stream?.bufferedReader()?.use { reader ->
                buildString {
                    var total = 0
                    while (total < 300_000) {
                        val line = reader.readLine() ?: break
                        append(line).append('\n')
                        total += line.length
                    }
                }
            }.orEmpty()
            val responseHeaders = connection.headerFields
                .filterKeys { it != null }
                .mapValues { (_, values) -> values.orEmpty().joinToString(";") }
            RouterHttpResponse(code, responseBody, responseHeaders)
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
            ?: return RouterInspection(snapshot, RouterAccessStatus.FAILED, message = "لا يوجد عنوان إدارة صالح لهذا الراوتر")

        val adapter = when (snapshot.brand) {
            RouterBrand.ZTE -> ZteOperationalAdapter
            RouterBrand.HUAWEI -> HuaweiOperationalAdapter
            RouterBrand.NETGEAR -> NetgearOperationalAdapter
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
                RouterInspection(snapshot, RouterAccessStatus.FAILED, message = "تعذر قراءة بيانات الراوتر من واجهة الإدارة")
            }
    }
}

object ZteOperationalAdapter : OperationalRouterAdapter {
    override val brand = RouterBrand.ZTE

    private val commands = listOf(
        "DeviceName", "model_name", "product_name", "SerialNumber", "serial_number", "imei",
        "wa_inner_version", "cr_version", "RD", "hardware_version", "web_version",
        "network_type", "network_type_ex", "network_provider", "net_select", "current_network_mode",
        "lte_rsrp", "lte_rsrq", "lte_snr", "lte_rssi", "Z5g_rsrp", "Z5g_rsrq", "Z5g_SINR",
        "nr5g_rsrp", "nr5g_rsrq", "nr5g_snr", "lte_ca_pcell_band", "lte_ca_scell_band",
        "lte_multi_ca_scell_info", "nr5g_band", "nr5g_action_band", "wan_active_band",
        "wan_active_channel", "nr5g_action_channel", "cell_id", "pci", "lte_pci", "wan_ipaddr"
    ).joinToString(",")

    override suspend fun inspect(client: RouterHttpClient, snapshot: RouterSnapshot): RouterInspection {
        val response = client.get("/goform/goform_get_cmd_process?isTest=false&cmd=$commands&multi_data=1")
        if (response.code == 401 || response.code == 403) return authRequired(snapshot)
        val json = runCatching { JSONObject(response.body) }.getOrNull()
            ?: return if (looksLikeLogin(response.body)) authRequired(snapshot) else failed(snapshot)

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

        val primaryBand = json.firstString("lte_ca_pcell_band", "wan_active_band")
        val secondaryBands = parseZteSecondaryBands(json.firstString("lte_multi_ca_scell_info"), json.firstString("lte_ca_scell_band"))
        val nrBand = json.firstString("nr5g_band", "nr5g_action_band")
        val bands = bandValues(primaryBand, secondaryBands.joinToString(","), nrBand)
        val signal = CellularSignal(
            networkType = json.firstString("network_type_ex", "network_type"),
            networkPreference = json.firstString("net_select", "current_network_mode"),
            operatorName = json.firstString("network_provider"),
            rsrp = json.firstString("Z5g_rsrp", "nr5g_rsrp", "lte_rsrp").cleanMetric(),
            rsrq = json.firstString("Z5g_rsrq", "nr5g_rsrq", "lte_rsrq").cleanMetric(),
            sinr = json.firstString("Z5g_SINR", "nr5g_snr", "lte_snr").cleanMetric(),
            rssi = json.firstString("lte_rssi").cleanMetric(),
            bands = bands,
            primaryBand = primaryBand,
            secondaryBands = secondaryBands,
            nrBand = nrBand,
            carrierAggregation = secondaryBands.isNotEmpty() || bands.size > 1,
            cellId = json.firstString("cell_id"),
            pci = json.firstString("pci", "lte_pci"),
            earfcn = json.firstString("wan_active_channel"),
            nrarfcn = json.firstString("nr5g_action_channel")
        )

        val hasData = listOf(device.model, device.serialNumber, device.imei, device.firmwareVersion, signal.networkType, signal.rsrp)
            .any { !it.isNullOrBlank() }
        if (!hasData && json.optString("result").equals("failure", ignoreCase = true)) return authRequired(snapshot)
        if (!hasData) return failed(snapshot)

        val capabilities = mutableSetOf(
            RouterCapability.DEVICE_INFO,
            RouterCapability.CELLULAR_SIGNAL,
            RouterCapability.NETWORK_STATUS,
            RouterCapability.SESSION_COOKIES,
            RouterCapability.FIRMWARE_INFO,
            RouterCapability.CA_DETAILS
        )
        val model = device.model.orEmpty()
        val hasActionSeed = json.firstString("wa_inner_version") != null && json.firstString("cr_version") != null && json.firstString("RD") != null
        if (hasActionSeed && model.isKnownZte5g()) {
            capabilities += RouterCapability.REBOOT
            capabilities += RouterCapability.NETWORK_MODE
        }
        if (model.contains("MC801A", ignoreCase = true) && device.firmwareVersion.orEmpty().contains("BD_UKH3GMC801AV1.0.0B15", ignoreCase = true)) {
            capabilities += RouterCapability.BAND_LOCK
        }

        return RouterInspection(
            snapshot = snapshot,
            accessStatus = RouterAccessStatus.AVAILABLE,
            device = device,
            signal = signal.takeIf { it.hasData },
            capabilities = capabilities,
            supportedNetworkModes = if (RouterCapability.NETWORK_MODE in capabilities) {
                setOf(NetworkMode.AUTO, NetworkMode.LTE_ONLY, NetworkMode.NR_LTE, NetworkMode.NR_ONLY)
            } else emptySet(),
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

        val modeResponse = runCatching { client.get("/api/net/net-mode") }.getOrNull()
        val modeListResponse = runCatching { client.get("/api/net/net-mode-list") }.getOrNull()
        val device = RouterDeviceInfo(
            manufacturer = "Huawei",
            model = xmlValue(infoResponse.body, "DeviceName", "ProductFamily", "Classify") ?: snapshot.model,
            serialNumber = xmlValue(infoResponse.body, "SerialNumber"),
            imei = xmlValue(infoResponse.body, "Imei", "IMEI"),
            firmwareVersion = xmlValue(infoResponse.body, "SoftwareVersion"),
            hardwareVersion = xmlValue(infoResponse.body, "HardwareVersion"),
            webUiVersion = xmlValue(infoResponse.body, "WebUIVersion"),
            wanIp = xmlValue(infoResponse.body, "WanIPAddress", "wan_ip_address")
        )
        val band = xmlValue(signalResponse.body, "band", "lteband")
        val signal = CellularSignal(
            networkType = xmlValue(signalResponse.body, "mode", "workmode"),
            networkPreference = modeResponse?.body?.let { xmlValue(it, "NetworkMode") },
            rsrp = xmlValue(signalResponse.body, "rsrp").cleanMetric(),
            rsrq = xmlValue(signalResponse.body, "rsrq").cleanMetric(),
            sinr = xmlValue(signalResponse.body, "sinr").cleanMetric(),
            rssi = xmlValue(signalResponse.body, "rssi").cleanMetric(),
            bands = bandValues(band),
            primaryBand = band,
            carrierAggregation = xmlValue(signalResponse.body, "dlbandwidth", "ulbandwidth", "sc").isNullOrBlank().not(),
            cellId = xmlValue(signalResponse.body, "cell_id", "cellid"),
            pci = xmlValue(signalResponse.body, "pci"),
            earfcn = xmlValue(signalResponse.body, "earfcn"),
            nrarfcn = xmlValue(signalResponse.body, "nrarfcn")
        )

        val hasData = listOf(device.model, device.serialNumber, device.imei, device.firmwareVersion, signal.rsrp)
            .any { !it.isNullOrBlank() }
        if (!hasData) {
            return RouterInspection(
                snapshot = snapshot,
                accessStatus = RouterAccessStatus.FAILED,
                device = RouterDeviceInfo(manufacturer = "Huawei", model = snapshot.model),
                message = "تم العثور على Huawei لكن إصدار HiLink/WebUI الحالي لم يعطِ بيانات عبر واجهات القراءة المعروفة"
            )
        }

        val capabilities = mutableSetOf(
            RouterCapability.DEVICE_INFO,
            RouterCapability.CELLULAR_SIGNAL,
            RouterCapability.NETWORK_STATUS,
            RouterCapability.SESSION_COOKIES,
            RouterCapability.FIRMWARE_INFO,
            RouterCapability.CA_DETAILS
        )
        val tokenProbe = runCatching { client.get("/api/webserver/SesTokInfo") }.getOrNull()
        if (tokenProbe?.successful == true && xmlValue(tokenProbe.body, "TokInfo") != null) {
            capabilities += RouterCapability.REBOOT
        }
        if (modeResponse?.successful == true && modeListResponse?.successful == true && xmlValue(modeResponse.body, "NetworkMode") != null) {
            capabilities += RouterCapability.NETWORK_MODE
        }

        val supportedModes = mutableSetOf<NetworkMode>()
        if (RouterCapability.NETWORK_MODE in capabilities) {
            supportedModes += NetworkMode.AUTO
            supportedModes += NetworkMode.LTE_ONLY
            val modeList = modeListResponse?.body.orEmpty().lowercase()
            if ("5g" in modeList || "nr" in modeList) {
                supportedModes += NetworkMode.NR_LTE
            }
        }

        return RouterInspection(
            snapshot = snapshot,
            accessStatus = RouterAccessStatus.AVAILABLE,
            device = device,
            signal = signal.takeIf { it.hasData },
            capabilities = capabilities,
            supportedNetworkModes = supportedModes,
            message = "تمت قراءة بيانات Huawei مباشرة من الراوتر"
        )
    }
}

object NetgearOperationalAdapter : OperationalRouterAdapter {
    override val brand = RouterBrand.NETGEAR

    override suspend fun inspect(client: RouterHttpClient, snapshot: RouterSnapshot): RouterInspection {
        val response = client.get("/model.json")
        if (response.code == 401 || response.code == 403 || looksLikeLogin(response.body)) {
            return RouterInspection(
                snapshot = snapshot,
                accessStatus = RouterAccessStatus.AUTH_REQUIRED,
                device = RouterDeviceInfo(manufacturer = "NETGEAR", model = snapshot.model),
                capabilities = setOf(RouterCapability.SESSION_COOKIES),
                message = "سجّل الدخول إلى Netgear WebUI ثم أعد الفحص لقراءة model.json"
            )
        }

        val json = runCatching { JSONObject(response.body) }.getOrNull()
            ?: return RouterInspection(
                snapshot = snapshot,
                accessStatus = RouterAccessStatus.FAILED,
                device = RouterDeviceInfo(manufacturer = "NETGEAR", model = snapshot.model),
                message = "تم العثور على Netgear لكن model.json لم يُقرأ بصيغة JSON"
            )

        val model = json.pathString("general.deviceName", "device.deviceName", "deviceName") ?: snapshot.model
        val firmware = json.pathString(
            "general.FWversion", "general.fwVersion", "device.FWversion", "FWversion", "fwVersion"
        )
        val device = RouterDeviceInfo(
            manufacturer = json.pathString("general.companyName", "companyName") ?: "NETGEAR",
            model = model,
            serialNumber = json.pathString("general.serialNumber", "device.serialNumber", "serialNumber"),
            imei = json.pathString("wwan.imei", "general.imei", "imei"),
            firmwareVersion = firmware,
            hardwareVersion = json.pathString("general.hardwareVersion", "device.hardwareVersion", "hardwareVersion"),
            webUiVersion = json.pathString("general.apiVersion", "apiVersion"),
            wanIp = json.pathString("wwan.IP", "wwan.ip", "wwan.ipv4Addr")
        )

        val lteRsrp = json.pathString("wwan.signalStrength.rsrp").cleanNetgearMetric()
        val lteRsrq = json.pathString("wwan.signalStrength.rsrq").cleanNetgearMetric()
        val lteSinr = json.pathString("wwan.signalStrength.sinr").cleanNetgearMetric()
        val nrRsrp = json.pathString("wwan.signalStrength.nr5gRsrp").cleanNetgearMetric()
        val nrRsrq = json.pathString("wwan.signalStrength.nr5gRsrq").cleanNetgearMetric()
        val nrSinr = json.pathString("wwan.signalStrength.nr5gSinr").cleanNetgearMetric()
        val primaryBand = json.pathString("wwanadv.curBand")
        val caBands = extractNetgearBands(json)
        val sccCount = json.pathString("wwan.ca.SCCcount")?.toIntOrNull() ?: 0

        val signal = CellularSignal(
            networkType = json.pathString("wwan.currentPSserviceType", "wwan.connectionText", "wwan.connection"),
            networkPreference = json.currentNetgearBandRegion(),
            operatorName = json.pathString("wwan.networkName", "wwan.operatorName"),
            rsrp = nrRsrp ?: lteRsrp,
            rsrq = nrRsrq ?: lteRsrq,
            sinr = nrSinr ?: lteSinr,
            rssi = json.pathString("wwan.signalStrength.rssi").cleanNetgearMetric(),
            bands = (listOfNotNull(primaryBand) + caBands).distinct(),
            primaryBand = primaryBand,
            secondaryBands = caBands.filterNot { it.equals(primaryBand, true) },
            nrBand = caBands.firstOrNull { it.contains("NR", true) || it.startsWith("N", true) },
            carrierAggregation = sccCount > 0 || caBands.size > 1,
            cellId = json.pathString("wwanadv.cellId"),
            pci = json.pathString("wwanadv.primScode", "wwan.pci"),
            earfcn = json.pathString("wwanadv.chanId"),
            nrarfcn = json.pathString("wwan.nr5gChanId", "wwanadv.nr5gChanId")
        )

        val hasData = listOf(device.model, device.firmwareVersion, signal.networkType, signal.rsrp, signal.primaryBand)
            .any { !it.isNullOrBlank() }
        if (!hasData) {
            return RouterInspection(
                snapshot = snapshot,
                accessStatus = RouterAccessStatus.FAILED,
                device = device,
                message = "Netgear استجاب، لكن model.json الحالي لا يحتوي حقول التشخيص المعروفة"
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
                RouterCapability.FIRMWARE_INFO,
                RouterCapability.CA_DETAILS
            ),
            message = "تمت قراءة تشخيص Netgear Nighthawk من model.json — وضع القراءة فقط"
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

private fun JSONObject.pathString(vararg paths: String): String? {
    for (path in paths) {
        var current: Any? = this
        for (part in path.split('.')) {
            current = (current as? JSONObject)?.opt(part)
            if (current == null || current == JSONObject.NULL) break
        }
        val value = when (current) {
            is String -> current.trim()
            is Number, is Boolean -> current.toString()
            else -> null
        }
        if (value?.isMeaningful() == true) return value
    }
    return null
}

private fun JSONObject.currentNetgearBandRegion(): String? {
    val regions = optJSONObject("wwan")?.optJSONArray("bandRegion") ?: optJSONArray("bandRegion") ?: return null
    for (i in 0 until regions.length()) {
        val entry = regions.optJSONObject(i) ?: continue
        if (entry.optBoolean("current", false)) {
            return entry.optString("name").takeIf { it.isMeaningful() }
        }
    }
    return null
}

private fun extractNetgearBands(root: JSONObject): List<String> {
    val wwan = root.optJSONObject("wwan") ?: return emptyList()
    val values = mutableListOf<String>()
    fun collect(array: JSONArray?) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            listOf("band", "lteBand", "bandName", "freqBand", "name").forEach { key ->
                item.optString(key).trim().takeIf { it.isMeaningful() }?.let { values += it }
            }
        }
    }
    collect(wwan.optJSONObject("ca")?.optJSONArray("SCClist"))
    collect(wwan.optJSONArray("lteBandInfo"))
    collect(wwan.optJSONArray("nr5gBandInfo"))
    return values.distinct()
}

private fun String?.cleanNetgearMetric(): String? {
    val value = this?.trim()?.takeIf { it.isMeaningful() } ?: return null
    val numeric = Regex("-?\\d+(?:\\.\\d+)?").find(value)?.value?.toDoubleOrNull()
    if (numeric != null && numeric <= -300) return null
    return value
}

private fun String?.cleanMetric(): String? = this?.trim()?.takeIf { it.isMeaningful() }

private fun String.isMeaningful(): Boolean =
    isNotBlank() && !equals("null", ignoreCase = true) && !equals("undefined", ignoreCase = true) && this != "--" && this != "-"

private fun bandValues(vararg raw: String?): List<String> = raw
    .filterNotNull()
    .flatMap { value -> value.split(',', ';', ' ') }
    .map { it.trim() }
    .filter { it.isMeaningful() && it != "0" }
    .distinct()

private fun parseZteSecondaryBands(multiCa: String?, simple: String?): List<String> {
    val parsed = multiCa.orEmpty().trimEnd(';').split(';').mapNotNull { item ->
        val parts = item.split(',')
        parts.getOrNull(3)?.takeIf { it.isMeaningful() }?.let { "B$it" }
    }
    return if (parsed.isNotEmpty()) parsed.distinct() else bandValues(simple)
}

internal fun xmlValue(xml: String, vararg tags: String): String? {
    for (tag in tags) {
        val regex = Regex("<$tag(?:\\s[^>]*)?>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val value = regex.find(xml)?.groupValues?.getOrNull(1)
            ?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.trim()
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

private fun String.isKnownZte5g(): Boolean =
    contains("MC801A", true) || contains("MC888", true) || contains("MC889", true)
