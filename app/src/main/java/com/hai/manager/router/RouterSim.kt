package com.hai.manager.router

import kotlinx.coroutines.delay
import org.json.JSONObject
import java.security.MessageDigest

enum class SimRequiredAction { NONE, PIN, PUK, UNKNOWN }

data class SimProbe(
    val brand: RouterBrand,
    val security: RouterSecurityInfo,
    val requiredAction: SimRequiredAction,
    val canEnterPin: Boolean,
    val canEnterPuk: Boolean,
    val message: String
)

class RouterSimService {
    suspend fun inspect(baseUrl: String): SimProbe? {
        val client = RouterHttpClient(baseUrl)
        inspectZte(client)?.let { return it }
        inspectHuawei(client)?.let { return it }
        return null
    }

    suspend fun enterPin(baseUrl: String, probe: SimProbe, pin: String): RouterActionResult {
        val cleanPin = pin.trim()
        if (!probe.canEnterPin || probe.requiredAction != SimRequiredAction.PIN) {
            return RouterActionResult(false, "الراوتر لا يطلب PIN حاليًا")
        }
        if (!cleanPin.matches(Regex("\\d{4,8}"))) {
            return RouterActionResult(false, "PIN يجب أن يتكون من 4 إلى 8 أرقام")
        }
        if (probe.security.pinAttemptsRemaining?.toIntOrNull() == 0) {
            return RouterActionResult(false, "لا توجد محاولات PIN متبقية. لا تحاول مرة أخرى قبل الحصول على PUK من المشغل")
        }

        val client = RouterHttpClient(baseUrl)
        val accepted = when (probe.brand) {
            RouterBrand.ZTE -> enterZtePin(client, cleanPin)
            RouterBrand.HUAWEI -> enterHuaweiPin(client, cleanPin)
            else -> false
        }
        if (!accepted) return RouterActionResult(false, "لم يقبل الراوتر رمز PIN")

        delay(700)
        val after = when (probe.brand) {
            RouterBrand.ZTE -> inspectZte(client)
            RouterBrand.HUAWEI -> inspectHuawei(client)
            else -> null
        }
        return when (after?.requiredAction) {
            SimRequiredAction.NONE -> RouterActionResult(true, "تم قبول PIN وأصبحت SIM جاهزة")
            SimRequiredAction.PUK -> RouterActionResult(false, "تم رفض PIN وأصبحت SIM تطلب PUK. توقف عن المحاولة واستخدم PUK الصحيح من المشغل")
            SimRequiredAction.PIN -> RouterActionResult(false, "PIN غير صحيح أو لم تتغير حالة SIM")
            else -> RouterActionResult(true, "قبل الراوتر PIN، وتعذر تأكيد الحالة النهائية مباشرة")
        }
    }

    suspend fun enterPuk(baseUrl: String, probe: SimProbe, puk: String, newPin: String): RouterActionResult {
        val cleanPuk = puk.trim()
        val cleanPin = newPin.trim()
        if (!probe.canEnterPuk || probe.requiredAction != SimRequiredAction.PUK) {
            return RouterActionResult(false, "الراوتر لا يطلب PUK حاليًا")
        }
        if (!cleanPuk.matches(Regex("\\d{8}"))) {
            return RouterActionResult(false, "PUK يجب أن يتكون من 8 أرقام")
        }
        if (!cleanPin.matches(Regex("\\d{4,8}"))) {
            return RouterActionResult(false, "PIN الجديد يجب أن يتكون من 4 إلى 8 أرقام")
        }
        if (probe.security.pukAttemptsRemaining?.toIntOrNull() == 0) {
            return RouterActionResult(false, "لا توجد محاولات PUK متبقية. تواصل مع المشغل ولا ترسل رموزًا إضافية")
        }

        val client = RouterHttpClient(baseUrl)
        val accepted = when (probe.brand) {
            RouterBrand.ZTE -> enterZtePuk(client, cleanPuk, cleanPin)
            RouterBrand.HUAWEI -> enterHuaweiPuk(client, cleanPuk, cleanPin)
            else -> false
        }
        if (!accepted) return RouterActionResult(false, "لم يقبل الراوتر رمز PUK")

        delay(900)
        val after = when (probe.brand) {
            RouterBrand.ZTE -> inspectZte(client)
            RouterBrand.HUAWEI -> inspectHuawei(client)
            else -> null
        }
        return when (after?.requiredAction) {
            SimRequiredAction.NONE -> RouterActionResult(true, "تم قبول PUK وتعيين PIN الجديد وأصبحت SIM جاهزة")
            SimRequiredAction.PUK -> RouterActionResult(false, "PUK غير صحيح أو لم تتغير حالة SIM. لا تكرر المحاولة بدون التأكد من الرمز")
            else -> RouterActionResult(true, "قبل الراوتر PUK، وتعذر تأكيد الحالة النهائية مباشرة")
        }
    }

    private suspend fun inspectZte(client: RouterHttpClient): SimProbe? {
        val fields = listOf(
            "modem_main_state", "sim_state", "pin_status", "pinnumber", "puknumber",
            "network_lock", "network_lock_status", "network_unlock_remain_count", "unlock_nck_time",
            "iccid", "imsi"
        ).joinToString(",")
        val response = client.get("/goform/goform_get_cmd_process?isTest=false&cmd=$fields&multi_data=1")
        if (response.code == 401 || response.code == 403) return null
        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return null
        val state = firstZte(json, "modem_main_state", "sim_state")
        val pinState = firstZte(json, "pin_status")
        val pinAttempts = firstZte(json, "pinnumber")
        val pukAttempts = firstZte(json, "puknumber")
        val lockState = firstZte(json, "network_lock_status", "network_lock")
        val unlockAttempts = firstZte(json, "network_unlock_remain_count", "unlock_nck_time")
        val iccid = firstZte(json, "iccid")
        val imsi = firstZte(json, "imsi")
        if (listOf(state, pinState, pinAttempts, pukAttempts, lockState, unlockAttempts, iccid, imsi).all { it.isNullOrBlank() }) return null

        val required = zteRequiredAction(state, pinState)
        val security = RouterSecurityInfo(
            simState = state,
            pinState = pinState,
            pinAttemptsRemaining = pinAttempts,
            pukAttemptsRemaining = pukAttempts,
            networkLockState = lockState,
            unlockAttemptsRemaining = unlockAttempts,
            iccid = iccid,
            imsi = imsi
        )
        return SimProbe(
            brand = RouterBrand.ZTE,
            security = security,
            requiredAction = required,
            canEnterPin = required == SimRequiredAction.PIN && pinAttempts?.toIntOrNull() != 0,
            canEnterPuk = required == SimRequiredAction.PUK && pukAttempts?.toIntOrNull() != 0,
            message = when (required) {
                SimRequiredAction.PIN -> "SIM تطلب PIN"
                SimRequiredAction.PUK -> "SIM تطلب PUK — انتبه لعدد المحاولات المتبقية"
                SimRequiredAction.NONE -> "SIM لا تحتاج PIN/PUK حاليًا"
                SimRequiredAction.UNKNOWN -> "تمت قراءة SIM لكن حالة PIN/PUK غير واضحة لهذا Firmware"
            }
        )
    }

    private suspend fun inspectHuawei(client: RouterHttpClient): SimProbe? {
        val pinResponse = client.get("/api/pin/status")
        if (!pinResponse.successful || !pinResponse.body.contains("<response", true)) return null
        val simlockResponse = runCatching { client.get("/api/pin/simlock") }.getOrNull()
        val state = xmlValue(pinResponse.body, "SimState")
        val pinState = xmlValue(pinResponse.body, "PinOptState", "SimPinState")
        val pinAttempts = xmlValue(pinResponse.body, "SimPinTimes", "PinTimes")
        val pukAttempts = xmlValue(pinResponse.body, "SimPukTimes", "PukTimes")
        val lockState = simlockResponse?.body?.let { xmlValue(it, "SimlockStatus", "SimLock", "NetworkLock") }
        val unlockAttempts = simlockResponse?.body?.let { xmlValue(it, "SimlockRemainTimes", "NetworkUnlockRemainCount", "NetworkLockRemainTimes") }
        val required = huaweiRequiredAction(state)
        val security = RouterSecurityInfo(
            simState = state,
            pinState = pinState,
            pinAttemptsRemaining = pinAttempts,
            pukAttemptsRemaining = pukAttempts,
            networkLockState = lockState,
            unlockAttemptsRemaining = unlockAttempts
        )
        return SimProbe(
            brand = RouterBrand.HUAWEI,
            security = security,
            requiredAction = required,
            canEnterPin = required == SimRequiredAction.PIN && pinAttempts?.toIntOrNull() != 0,
            canEnterPuk = required == SimRequiredAction.PUK && pukAttempts?.toIntOrNull() != 0,
            message = when (required) {
                SimRequiredAction.PIN -> "SIM تطلب PIN"
                SimRequiredAction.PUK -> "SIM تطلب PUK — انتبه لعدد المحاولات المتبقية"
                SimRequiredAction.NONE -> "SIM لا تحتاج PIN/PUK حاليًا"
                SimRequiredAction.UNKNOWN -> "تمت قراءة SIM لكن حالة PIN/PUK غير واضحة لهذا Firmware"
            }
        )
    }

    private suspend fun enterZtePin(client: RouterHttpClient, pin: String): Boolean {
        val fields = mutableMapOf(
            "goformId" to "ENTER_PIN",
            "isTest" to "false",
            "PinNumber" to pin,
            "pin_save_flag" to "0"
        )
        zteAd(client)?.let { fields["AD"] = it }
        return zteAccepted(client.postForm("/goform/goform_set_cmd_process", fields))
    }

    private suspend fun enterZtePuk(client: RouterHttpClient, puk: String, newPin: String): Boolean {
        val fields = mutableMapOf(
            "goformId" to "ENTER_PUK",
            "isTest" to "false",
            "PUKNumber" to puk,
            "PinNumber" to newPin
        )
        zteAd(client)?.let { fields["AD"] = it }
        return zteAccepted(client.postForm("/goform/goform_set_cmd_process", fields))
    }

    private suspend fun enterHuaweiPin(client: RouterHttpClient, pin: String): Boolean {
        val auth = huaweiAuth(client) ?: return false
        val xml = "<request><OperateType>0</OperateType><CurrentPin>$pin</CurrentPin><NewPin></NewPin><PukCode></PukCode></request>"
        val response = client.postXml("/api/pin/operate", xml, auth)
        return response.successful && response.body.contains("<response>OK</response>", true)
    }

    private suspend fun enterHuaweiPuk(client: RouterHttpClient, puk: String, newPin: String): Boolean {
        val auth = huaweiAuth(client) ?: return false
        val xml = "<request><OperateType>4</OperateType><CurrentPin>$newPin</CurrentPin><NewPin>$newPin</NewPin><PukCode>$puk</PukCode></request>"
        val response = client.postXml("/api/pin/operate", xml, auth)
        return response.successful && response.body.contains("<response>OK</response>", true)
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

    private suspend fun zteAd(client: RouterHttpClient): String? {
        val response = client.get("/goform/goform_get_cmd_process?isTest=false&cmd=wa_inner_version,cr_version,RD&multi_data=1")
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

    private fun firstZte(json: JSONObject, vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        json.optString(key).trim().takeIf { it.isNotBlank() && it != "--" && !it.equals("null", true) }
    }

    private fun zteRequiredAction(state: String?, pinState: String?): SimRequiredAction {
        val text = listOfNotNull(state, pinState).joinToString(" ").lowercase()
        return when {
            "waitpuk" in text || "puk_required" in text || "puk required" in text -> SimRequiredAction.PUK
            "waitpin" in text || "pin_required" in text || "pin required" in text -> SimRequiredAction.PIN
            "init_complete" in text || "ready" in text || "pin_disabled" in text -> SimRequiredAction.NONE
            text.isBlank() -> SimRequiredAction.UNKNOWN
            else -> SimRequiredAction.UNKNOWN
        }
    }

    private fun huaweiRequiredAction(state: String?): SimRequiredAction = when (state?.trim()) {
        "257", "258" -> SimRequiredAction.NONE
        "259", "260" -> SimRequiredAction.PIN
        "261" -> SimRequiredAction.PUK
        else -> SimRequiredAction.UNKNOWN
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
