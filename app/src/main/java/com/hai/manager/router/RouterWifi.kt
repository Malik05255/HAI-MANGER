package com.hai.manager.router

import org.json.JSONObject

class RouterWifiService {
    data class WifiProbe(val brand: RouterBrand, val info: RouterWifiInfo)

    suspend fun inspect(baseUrl: String): WifiProbe? {
        val client = RouterHttpClient(baseUrl)
        inspectZte(client)?.let { return WifiProbe(RouterBrand.ZTE, it) }
        inspectHuawei(client)?.let { return WifiProbe(RouterBrand.HUAWEI, it) }
        return null
    }

    suspend fun setEnabled(baseUrl: String, probe: WifiProbe, enabled: Boolean): RouterActionResult {
        if (!probe.info.canToggle) return RouterActionResult(false, "تشغيل وإيقاف Wi-Fi غير موثق لهذا الراوتر")
        if (probe.brand != RouterBrand.ZTE) return RouterActionResult(false, "التبديل المباشر غير مفعّل لهذا النوع")
        val response = RouterHttpClient(baseUrl).postForm(
            "/goform/goform_set_cmd_process",
            mapOf(
                "goformId" to "SET_WIFI_INFO",
                "isTest" to "false",
                "m_ssid_enable" to "0",
                "wifiEnabled" to if (enabled) "1" else "0"
            )
        )
        val result = runCatching { JSONObject(response.body).optString("result") }.getOrNull().orEmpty()
        val ok = response.successful && (result.equals("success", true) || result == "0")
        return RouterActionResult(ok, if (ok) "تم ${if (enabled) "تشغيل" else "إيقاف"} Wi-Fi" else "لم يقبل الراوتر تغيير حالة Wi-Fi")
    }

    suspend fun rename(baseUrl: String, probe: WifiProbe, newSsid: String): RouterActionResult {
        val ssid = newSsid.trim()
        if (!probe.info.canRename) return RouterActionResult(false, "تغيير اسم Wi-Fi غير موثق لهذا الراوتر")
        if (ssid.isEmpty() || ssid.length > 32) return RouterActionResult(false, "اسم Wi-Fi يجب أن يكون من 1 إلى 32 حرفًا")
        if (probe.brand != RouterBrand.HUAWEI) return RouterActionResult(false, "تغيير الاسم الآمن غير مفعّل لهذا النوع")
        val client = RouterHttpClient(baseUrl)
        val tokenResponse = client.get("/api/webserver/SesTokInfo")
        val token = xmlValue(tokenResponse.body, "TokInfo") ?: return RouterActionResult(false, "تسجيل الدخول مطلوب")
        val session = xmlValue(tokenResponse.body, "SesInfo")
        val headers = buildMap {
            put("__RequestVerificationToken", token)
            if (!session.isNullOrBlank()) put("Cookie", session)
        }
        val hide = if (probe.info.hidden == true) "1" else "0"
        val xml = "<request><WifiSsid>${escapeXml(ssid)}</WifiSsid><WifiHide>$hide</WifiHide><WifiRestart>1</WifiRestart></request>"
        val response = client.postXml("/api/wlan/basic-settings", xml, headers)
        val ok = response.successful && response.body.contains("<response>OK</response>", true)
        return RouterActionResult(ok, if (ok) "تم تغيير اسم Wi-Fi إلى $ssid" else "لم يقبل Huawei تغيير اسم Wi-Fi")
    }

    private suspend fun inspectZte(client: RouterHttpClient): RouterWifiInfo? {
        val fields = listOf(
            "SSID1", "HideSSID", "RadioOff", "m_ssid_enable", "wifiEnabled",
            "AuthMode", "EncrypType", "wifi_channel", "wifi_mode"
        ).joinToString(",")
        val response = client.get("/goform/goform_get_cmd_process?isTest=false&cmd=$fields&multi_data=1")
        if (!response.successful) return null
        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return null
        val ssid = json.optString("SSID1").takeIf { it.isMeaningfulWifi() }
        val radioOff = json.optString("RadioOff").trim()
        val direct = json.optString("wifiEnabled").trim()
        val enabled = when {
            direct == "1" -> true
            direct == "0" -> false
            radioOff == "0" -> true
            radioOff == "1" -> false
            else -> null
        }
        if (ssid == null && enabled == null) return null
        return RouterWifiInfo(
            enabled = enabled,
            ssid = ssid,
            hidden = json.optString("HideSSID").trim().let { value -> when (value) { "1" -> true; "0" -> false; else -> null } },
            channel = json.optString("wifi_channel").takeIf { it.isMeaningfulWifi() },
            mode = json.optString("wifi_mode").takeIf { it.isMeaningfulWifi() },
            securityMode = json.optString("AuthMode").takeIf { it.isMeaningfulWifi() },
            canToggle = true,
            canRename = false
        )
    }

    private suspend fun inspectHuawei(client: RouterHttpClient): RouterWifiInfo? {
        val response = client.get("/api/wlan/basic-settings")
        if (!response.successful || !response.body.contains("<response", true)) return null
        val ssid = xmlValue(response.body, "WifiSsid")
        val enable = xmlValue(response.body, "WifiEnable")
        if (ssid == null && enable == null) return null
        return RouterWifiInfo(
            enabled = when (enable) { "1" -> true; "0" -> false; else -> null },
            ssid = ssid,
            hidden = when (xmlValue(response.body, "WifiHide")) { "1" -> true; "0" -> false; else -> null },
            channel = xmlValue(response.body, "WifiChannel"),
            mode = xmlValue(response.body, "WifiMode"),
            securityMode = runCatching { xmlValue(client.get("/api/wlan/security-settings").body, "WifiAuthmode") }.getOrNull(),
            canToggle = false,
            canRename = ssid != null
        )
    }

    private fun String.isMeaningfulWifi(): Boolean = isNotBlank() && !equals("null", true) && this != "--"
    private fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
