package com.hai.manager.router

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.security.MessageDigest

class RouterActionService {
    suspend fun reboot(inspection: RouterInspection): RouterActionResult {
        if (RouterCapability.REBOOT !in inspection.capabilities) {
            return RouterActionResult(false, "إعادة التشغيل غير موثقة لهذا الجهاز أو Firmware")
        }
        val baseUrl = inspection.snapshot.managementUrl
            ?: return RouterActionResult(false, "عنوان إدارة الراوتر غير متوفر")
        val client = RouterHttpClient(baseUrl)
        return when (inspection.snapshot.brand) {
            RouterBrand.ZTE -> rebootZte(client)
            RouterBrand.HUAWEI -> rebootHuawei(client)
            else -> RouterActionResult(false, "هذا الأمر غير مدعوم لهذا النوع")
        }
    }

    suspend fun setNetworkMode(inspection: RouterInspection, mode: NetworkMode): RouterActionResult {
        if (RouterCapability.NETWORK_MODE !in inspection.capabilities || mode !in inspection.supportedNetworkModes) {
            return RouterActionResult(false, "وضع الشبكة هذا غير موثق لهذا الراوتر")
        }
        val baseUrl = inspection.snapshot.managementUrl
            ?: return RouterActionResult(false, "عنوان إدارة الراوتر غير متوفر")
        val client = RouterHttpClient(baseUrl)
        return when (inspection.snapshot.brand) {
            RouterBrand.ZTE -> setZteNetworkMode(client, mode)
            RouterBrand.HUAWEI -> setHuaweiNetworkMode(client, mode)
            else -> RouterActionResult(false, "تغيير وضع الشبكة غير مدعوم لهذا النوع")
        }
    }

    suspend fun setZteNrBands(inspection: RouterInspection, bands: List<Int>): RouterActionResult {
        if (RouterCapability.BAND_LOCK !in inspection.capabilities) {
            return RouterActionResult(false, "قفل النطاقات غير موثق لهذا Firmware")
        }
        if (bands.isEmpty() || bands.any { it !in 1..261 }) {
            return RouterActionResult(false, "أدخل نطاقات NR صحيحة")
        }
        val baseUrl = inspection.snapshot.managementUrl
            ?: return RouterActionResult(false, "عنوان إدارة الراوتر غير متوفر")
        val client = RouterHttpClient(baseUrl)
        val ad = zteAd(client) ?: return RouterActionResult(false, "تسجيل الدخول مطلوب أو لم يمكن إنشاء مفتاح الأمر")
        val requested = bands.distinct().sorted()
        val response = client.postForm(
            "/goform/goform_set_cmd_process",
            mapOf(
                "isTest" to "false",
                "goformId" to "WAN_PERFORM_NR5G_BAND_LOCK",
                "nr5g_band_mask" to requested.joinToString(","),
                "AD" to ad
            )
        )
        val accepted = zteAccepted(response)
        if (!accepted) return RouterActionResult(false, "لم يقبل الراوتر قفل النطاقات")

        delay(700)
        val verified = readZteNrBandMask(client)?.let { actual ->
            val actualBands = actual.split(',', '+', ' ', ';').mapNotNull { it.trim().toIntOrNull() }.distinct().sorted()
            actualBands == requested
        }
        return when (verified) {
            true -> RouterActionResult(true, "تم تطبيق قفل نطاقات 5G والتحقق منه")
            false -> RouterActionResult(false, "قبل الراوتر الأمر لكن القيمة المقروءة بعد التنفيذ لا تطابق النطاقات المطلوبة")
            null -> RouterActionResult(true, "قبل الراوتر قفل نطاقات 5G، لكن هذا Firmware لا يعرض قيمة القفل للتحقق المباشر")
        }
    }

    private suspend fun rebootZte(client: RouterHttpClient): RouterActionResult {
        val ad = zteAd(client) ?: return RouterActionResult(false, "تسجيل الدخول مطلوب أو لم يمكن إنشاء مفتاح الأمر")
        val response = client.postForm(
            "/goform/goform_set_cmd_process",
            mapOf("isTest" to "false", "goformId" to "REBOOT_DEVICE", "AD" to ad)
        )
        return if (zteAccepted(response)) {
            RouterActionResult(true, "تم قبول أمر إعادة التشغيل؛ سينقطع الاتصال بالراوتر مؤقتًا")
        } else {
            RouterActionResult(false, "لم يقبل الراوتر أمر إعادة التشغيل")
        }
    }

    private suspend fun setZteNetworkMode(client: RouterHttpClient, mode: NetworkMode): RouterActionResult {
        val ad = zteAd(client) ?: return RouterActionResult(false, "تسجيل الدخول مطلوب أو لم يمكن إنشاء مفتاح الأمر")
        val value = zteModeValue(mode)
        val response = client.postForm(
            "/goform/goform_set_cmd_process",
            mapOf(
                "isTest" to "false",
                "goformId" to "SET_BEARER_PREFERENCE",
                "BearerPreference" to value,
                "AD" to ad
            )
        )
        if (!zteAccepted(response)) return RouterActionResult(false, "لم يقبل الراوتر وضع الشبكة المطلوب")

        delay(650)
        val actual = readZteBearerPreference(client)
        return if (actual == null) {
            RouterActionResult(true, "قبل الراوتر وضع ${mode.displayName}، وتعذر قراءة القيمة بعد التنفيذ")
        } else if (zteModeMatches(actual, mode)) {
            RouterActionResult(true, "تم تغيير وضع الشبكة إلى ${mode.displayName} والتحقق منه")
        } else {
            RouterActionResult(false, "قبل الراوتر الأمر لكن بقي الوضع المقروء: $actual")
        }
    }

    private fun zteModeValue(mode: NetworkMode): String = when (mode) {
        NetworkMode.AUTO -> "NETWORK_auto"
        NetworkMode.LTE_ONLY -> "Only_LTE"
        NetworkMode.NR_LTE -> "4G_AND_5G"
        NetworkMode.NR_ONLY -> "Only_5G"
    }

    private fun zteModeMatches(actual: String, mode: NetworkMode): Boolean {
        val normalized = actual.trim().lowercase().replace('-', '_').replace(' ', '_')
        val expected = zteModeValue(mode).lowercase()
        if (normalized == expected) return true
        return when (mode) {
            NetworkMode.AUTO -> normalized.contains("auto")
            NetworkMode.LTE_ONLY -> normalized.contains("only_lte") || normalized == "lte_only"
            NetworkMode.NR_LTE -> normalized.contains("4g_and_5g") || normalized.contains("lte_nr") || normalized.contains("nr_lte")
            NetworkMode.NR_ONLY -> normalized.contains("only_5g") || normalized == "nr_only" || normalized == "5g_only"
        }
    }

    private suspend fun readZteBearerPreference(client: RouterHttpClient): String? {
        val response = client.get("/goform/goform_get_cmd_process?isTest=false&cmd=BearerPreference,net_select,current_network_mode&multi_data=1")
        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return null
        return listOf("BearerPreference", "net_select", "current_network_mode")
            .firstNotNullOfOrNull { key -> json.optString(key).trim().takeIf { it.isNotBlank() && it != "--" && !it.equals("null", true) } }
    }

    private suspend fun readZteNrBandMask(client: RouterHttpClient): String? {
        val response = client.get("/goform/goform_get_cmd_process?isTest=false&cmd=nr5g_band_mask,nr5g_band,nr5g_action_band&multi_data=1")
        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return null
        return listOf("nr5g_band_mask", "nr5g_band", "nr5g_action_band")
            .firstNotNullOfOrNull { key -> json.optString(key).trim().takeIf { it.isNotBlank() && it != "--" && !it.equals("null", true) } }
    }

    private suspend fun zteAd(client: RouterHttpClient): String? {
        val response = client.get(
            "/goform/goform_get_cmd_process?isTest=false&cmd=wa_inner_version,cr_version,RD&multi_data=1"
        )
        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return null
        val wa = json.optString("wa_inner_version")
        val cr = json.optString("cr_version")
        val rd = json.optString("RD")
        if (wa.isBlank() || cr.isBlank() || rd.isBlank()) return null
        return md5(md5(wa + cr) + rd)
    }

    private fun zteAccepted(response: RouterHttpResponse): Boolean {
        val result = runCatching { JSONObject(response.body).optString("result") }.getOrNull().orEmpty()
        return response.successful && (result.equals("success", true) || result == "0")
    }

    private suspend fun rebootHuawei(client: RouterHttpClient): RouterActionResult {
        val auth = huaweiAuth(client) ?: return RouterActionResult(false, "تسجيل الدخول مطلوب")
        val response = client.postXml(
            "/api/device/control",
            "<request><Control>1</Control></request>",
            auth
        )
        val success = response.successful && response.body.contains("<response>OK</response>", true)
        return RouterActionResult(
            success,
            if (success) "تم قبول أمر إعادة التشغيل؛ سينقطع الاتصال بالراوتر مؤقتًا" else "لم يقبل Huawei أمر إعادة التشغيل"
        )
    }

    private suspend fun setHuaweiNetworkMode(client: RouterHttpClient, mode: NetworkMode): RouterActionResult {
        if (mode != NetworkMode.AUTO && mode != NetworkMode.LTE_ONLY) {
            return RouterActionResult(false, "وضع 5G لا يُرسل على Huawei إلا بعد توثيق بنية API الخاصة بالـFirmware")
        }
        val current = client.get("/api/net/net-mode")
        val networkBand = xmlValue(current.body, "NetworkBand") ?: return RouterActionResult(false, "تعذر قراءة NetworkBand الحالي")
        val lteBand = xmlValue(current.body, "LTEBand") ?: return RouterActionResult(false, "تعذر قراءة LTEBand الحالي")
        val auth = huaweiAuth(client) ?: return RouterActionResult(false, "تسجيل الدخول مطلوب")
        val networkMode = if (mode == NetworkMode.AUTO) "00" else "03"
        val xml = "<request><NetworkMode>$networkMode</NetworkMode><NetworkBand>$networkBand</NetworkBand><LTEBand>$lteBand</LTEBand></request>"
        val response = client.postXml("/api/net/net-mode", xml, auth)
        val accepted = response.successful && response.body.contains("<response>OK</response>", true)
        if (!accepted) return RouterActionResult(false, "لم يقبل Huawei وضع الشبكة المطلوب")

        delay(650)
        val verify = client.get("/api/net/net-mode")
        val actual = xmlValue(verify.body, "NetworkMode")
        return if (actual == null) {
            RouterActionResult(true, "قبل Huawei وضع ${mode.displayName}، وتعذر قراءة القيمة بعد التنفيذ")
        } else if (actual.equals(networkMode, true)) {
            RouterActionResult(true, "تم تغيير وضع الشبكة إلى ${mode.displayName} والتحقق منه")
        } else {
            RouterActionResult(false, "قبل Huawei الأمر لكن بقي NetworkMode=$actual")
        }
    }

    private suspend fun huaweiAuth(client: RouterHttpClient): Map<String, String>? {
        val response = client.get("/api/webserver/SesTokInfo")
        val token = xmlValue(response.body, "TokInfo") ?: return null
        val session = xmlValue(response.body, "SesInfo")
        return buildMap {
            put("__RequestVerificationToken", token)
            if (!session.isNullOrBlank()) put("Cookie", session)
        }
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
