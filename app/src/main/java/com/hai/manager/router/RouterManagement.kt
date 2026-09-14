package com.hai.manager.router

import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
        connection.setRequestProperty("User-Agent", "HAI-MANAGER/0.5")
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
            else -> null
        } ?: return RouterInspection(
            snapshot = snapshot,
            accessStatus = RouterAccessStatus.UNSUPPORTED,
            device = RouterDeviceInfo(
                manufacturer = snapshot.brand.displayName.takeIf { snapshot.brand != RouterBrand.UNKNOWN },
                model = snapshot.model
            ),
            message = "دعم المرحلة الحالية مخصص لراوترات Huawei وZTE"
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
        "wa_inner_version", "cr_version", "RD", "hardware_version", "web_version", "wan_ipaddr",
        "network_type", "network_type_ex", "network_provider", "net_select", "current_network_mode", "BearerPreference",
        "lte_rsrp", "lte_rsrq", "lte_snr", "lte_rssi", "Z5g_rsrp", "Z5g_rsrq", "Z5g_SINR",
        "nr5g_rsrp", "nr5g_rsrq", "nr5g_snr", "lte_ca_pcell_band", "lte_ca_scell_band",
        "lte_multi_ca_scell_info", "nr5g_band", "nr5g_action_band", "nr5g_band_mask", "wan_active_band",
        "wan_active_channel", "nr5g_action_channel", "cell_id", "pci", "lte_pci",
        "sim_state", "pin_status", "network_lock", "network_lock_status", "network_unlock_remain_count",
        "unlock_nck_time", "iccid", "imsi"
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

        val security = RouterSecurityInfo(
            simState = json.firstString("sim_state"),
            pinState = json.firstString("pin_status"),
            networkLockState = json.firstString("network_lock_status", "network_lock"),
            unlockAttemptsRemaining = json.firstString("network_unlock_remain_count", "unlock_nck_time"),
            iccid = json.firstString("iccid"),
            imsi = json.firstString("imsi")
        )

        val primaryBand = json.firstString("lte_ca_pcell_band", "wan_active_band")
        val secondaryBands = parseZteSecondaryBands(json.firstString("lte_multi_ca_scell_info"), json.firstString("lte_ca_scell_band"))
        val nrBand = json.firstString("nr5g_band", "nr5g_action_band")
        val bands = bandValues(primaryBand, secondaryBands.joinToString(","), nrBand)
        val signal = CellularSignal(
            networkType = json.firstString("network_type_ex", "network_type"),
            networkPreference = json.firstString("BearerPreference", "net_select", "current_network_mode"),
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
        if (security.hasData) capabilities += RouterCapability.SIM_SECURITY

        val model = device.model.orEmpty()
        val hasActionSeed = json.firstString("wa_inner_version") != null &&
            json.firstString("cr_version") != null && json.firstString("RD") != null
        if (hasActionSeed && model.isKnownZteManagedFamily()) {
            capabilities += RouterCapability.REBOOT
            capabilities += RouterCapability.NETWORK_MODE
        }
        if (model.contains("MC801A", ignoreCase = true) &&
            device.firmwareVersion.orEmpty().contains("BD_UKH3GMC801AV1.0.0B15", ignoreCase = true)
        ) {
            capabilities += RouterCapability.BAND_LOCK
        }

        return RouterInspection(
            snapshot = snapshot,
            accessStatus = RouterAccessStatus.AVAILABLE,
            device = device,
            signal = signal.takeIf { it.hasData },
            security = security.takeIf { it.hasData },
            capabilities = capabilities,
            supportedNetworkModes = if (RouterCapability.NETWORK_MODE in capabilities && model.isKnownZte5gFamily()) {
                setOf(NetworkMode.AUTO, NetworkMode.LTE_ONLY, NetworkMode.NR_LTE, NetworkMode.NR_ONLY)
            } else if (RouterCapability.NETWORK_MODE in capabilities) {
                setOf(NetworkMode.AUTO, NetworkMode.LTE_ONLY)
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
        val statusResponse = runCatching { client.get("/api/monitoring/status") }.getOrNull()
        val plmnResponse = runCatching { client.get("/api/net/current-plmn") }.getOrNull()
        val pinResponse = runCatching { client.get("/api/pin/status") }.getOrNull()

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

        val security = RouterSecurityInfo(
            simState = pinResponse?.body?.let { xmlValue(it, "SimState") }
                ?: statusResponse?.body?.let { xmlValue(it, "SimStatus") },
            pinState = pinResponse?.body?.let { xmlValue(it, "PinOptState", "SimPinState") },
            networkLockState = infoResponse.body.let { xmlValue(it, "SimLock", "SimlockStatus", "NetworkLock") },
            unlockAttemptsRemaining = pinResponse?.body?.let { xmlValue(it, "SimPinTimes", "PinTimes") },
            iccid = xmlValue(infoResponse.body, "Iccid", "ICCID"),
            imsi = xmlValue(infoResponse.body, "Imsi", "IMSI")
        )

        val band = xmlValue(signalResponse.body, "band", "lteband")
        val operator = plmnResponse?.body?.let { xmlValue(it, "FullName", "ShortName", "Numeric") }
        val networkType = xmlValue(signalResponse.body, "mode", "workmode")
            ?: statusResponse?.body?.let { xmlValue(it, "CurrentNetworkTypeEx", "CurrentNetworkType") }
        val signal = CellularSignal(
            networkType = networkType,
            networkPreference = modeResponse?.body?.let { xmlValue(it, "NetworkMode") },
            operatorName = operator,
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

        val hasData = listOf(device.model, device.serialNumber, device.imei, device.firmwareVersion, signal.rsrp, signal.networkType)
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
        if (security.hasData) capabilities += RouterCapability.SIM_SECURITY

        val tokenProbe = runCatching { client.get("/api/webserver/SesTokInfo") }.getOrNull()
        if (tokenProbe?.successful == true && xmlValue(tokenProbe.body, "TokInfo") != null) {
            capabilities += RouterCapability.REBOOT
        }
        if (modeResponse?.successful == true && modeListResponse?.successful == true &&
            xmlValue(modeResponse.body, "NetworkMode") != null
        ) {
            capabilities += RouterCapability.NETWORK_MODE
        }

        val supportedModes = mutableSetOf<NetworkMode>()
        if (RouterCapability.NETWORK_MODE in capabilities) {
            supportedModes += NetworkMode.AUTO
            supportedModes += NetworkMode.LTE_ONLY
        }

        return RouterInspection(
            snapshot = snapshot,
            accessStatus = RouterAccessStatus.AVAILABLE,
            device = device,
            signal = signal.takeIf { it.hasData },
            security = security.takeIf { it.hasData },
            capabilities = capabilities,
            supportedNetworkModes = supportedModes,
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

private fun String.isKnownZte5gFamily(): Boolean =
    contains("MC801", true) || contains("MC888", true) || contains("MC889", true) || contains("MC7010", true)

private fun String.isKnownZteManagedFamily(): Boolean =
    isKnownZte5gFamily() || contains("MF286", true) || contains("MF289", true) || contains("MF297", true)
