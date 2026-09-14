package com.hai.manager.router

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
        val response = client.postForm(
            "/goform/goform_set_cmd_process",
            mapOf(
                "isTest" to "false",
                "goformId" to "WAN_PERFORM_NR5G_BAND_LOCK",
                "nr5g_band_mask" to bands.distinct().sorted().joinToString(","),
                "AD" to ad
            )
        )
        return zteResult(response, "تم تطبيق قفل نطاقات 5G", "لم يقبل الراوتر قفل النطاقات")
    }

    private suspend fun rebootZte(client: RouterHttpClient): RouterActionResult {
        val ad = zteAd(client) ?: return RouterActionResult(false, "تسجيل الدخول مطلوب أو لم يمكن إنشاء مفتاح الأمر")
        val response = client.postForm(
            "/goform/goform_set_cmd_process",
            mapOf("isTest" to "false", "goformId" to "REBOOT_DEVICE", "AD" to ad)
        )
        return zteResult(response, "تم إرسال أمر إعادة التشغيل", "لم يقبل الراوتر أمر إعادة التشغيل")
    }

    private suspend fun setZteNetworkMode(client: RouterHttpClient, mode: NetworkMode): RouterActionResult {
        val ad = zteAd(client) ?: return RouterActionResult(false, "تسجيل الدخول مطلوب أو لم يمكن إنشاء مفتاح الأمر")
        val value = when (mode) {
            NetworkMode.AUTO -> "NETWORK_auto"
            NetworkMode.LTE_ONLY -> "Only_LTE"
            NetworkMode.NR_LTE -> "4G_AND_5G"
            NetworkMode.NR_ONLY -> "Only_5G"
        }
        val response = client.postForm(
            "/goform/goform_set_cmd_process",
            mapOf(
                "isTest" to "false",
                "goformId" to "SET_BEARER_PREFERENCE",
                "BearerPreference" to value,
                "AD" to ad
            )
        )
        return zteResult(response, "تم تغيير وضع الشبكة إلى ${mode.displayName}", "لم يقبل الراوتر وضع الشبكة المطلوب")
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

    private fun zteResult(response: RouterHttpResponse, okMessage: String, failMessage: String): RouterActionResult {
        val result = runCatching { JSONObject(response.body).optString("result") }.getOrNull().orEmpty()
        val success = response.successful && (result.equals("success", true) || result == "0")
        return RouterActionResult(success, if (success) okMessage else failMessage)
    }

    private suspend fun rebootHuawei(client: RouterHttpClient): RouterActionResult {
        val auth = huaweiAuth(client) ?: return RouterActionResult(false, "تسجيل الدخول مطلوب")
        val response = client.postXml(
            "/api/device/control",
            "<request><Control>1</Control></request>",
            auth
        )
        val success = response.successful && response.body.contains("<response>OK</response>", true)
        return RouterActionResult(success, if (success) "تم إرسال أمر إعادة التشغيل" else "لم يقبل Huawei أمر إعادة التشغيل")
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
        val success = response.successful && response.body.contains("<response>OK</response>", true)
        return RouterActionResult(success, if (success) "تم تغيير وضع الشبكة إلى ${mode.displayName}" else "لم يقبل Huawei وضع الشبكة المطلوب")
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
