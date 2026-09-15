package com.hai.manager.router

import org.json.JSONObject

/**
 * Executes only an already-verified NCK entry path.
 * It never calculates a code, resets counters, edits NV/EFS, or bypasses firmware security.
 */
class RouterNetworkUnlockService {
    suspend fun unlock(inspection: RouterInspection, nck: String): RouterActionResult {
        if (!nck.matches(Regex("[0-9]{6,32}"))) {
            return RouterActionResult(false, "رقم الفك غير صالح")
        }

        val profile = inspection.firmwareProfileInfo
        if (!profile.nckEntry.canWrite) {
            return RouterActionResult(false, "الفك التلقائي غير متاح لهذا الراوتر")
        }
        if (inspection.probeReport?.nckEntryVerified != true) {
            return RouterActionResult(false, "لم يتم التحقق من مسار إدخال رقم الفك لهذا النظام")
        }
        RouterWriteSafety.validate(inspection, RouterWriteOperation.NCK_ENTRY)?.let { return it }

        val baseUrl = inspection.snapshot.managementUrl
            ?: return RouterActionResult(false, "تعذر الوصول إلى الراوتر")
        val client = RouterHttpClient(baseUrl)

        return when (inspection.snapshot.brand) {
            RouterBrand.ZTE -> unlockZte(client, nck)
            else -> RouterActionResult(false, "الفك التلقائي غير متاح لهذا الراوتر")
        }
    }

    private suspend fun unlockZte(client: RouterHttpClient, nck: String): RouterActionResult {
        val preflight = runCatching {
            client.get(
                "/goform/goform_get_cmd_process?isTest=false&cmd=" +
                    "wa_inner_version,cr_version,RD,network_lock_status,network_lock," +
                    "network_unlock_remain_count&multi_data=1"
            )
        }.getOrNull() ?: return RouterActionResult(false, "تعذر فحص جلسة الراوتر قبل الفك")

        val json = runCatching { JSONObject(preflight.body) }.getOrNull()
            ?: return RouterActionResult(false, "سجّل الدخول إلى الراوتر أولًا")

        fun value(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            json.optString(key, "").trim().takeIf(::meaningful)
        }

        val rawLock = value("network_lock_status", "network_lock")
        val attempts = value("network_unlock_remain_count")?.toIntOrNull()
        if (rawLock != null && isUnlocked(rawLock)) {
            return RouterActionResult(true, "الراوتر مفتوح أصلًا")
        }
        if (attempts == 0) {
            return RouterActionResult(false, "محاولات رقم الفك منتهية؛ لن يرسل HAI أي رقم إضافي")
        }
        if (rawLock == null && attempts == null) {
            return RouterActionResult(false, "جلسة الإدارة غير جاهزة؛ سجّل الدخول ثم أعد التشخيص")
        }

        val ad = ZteNckRuntime.computeAd(
            waInnerVersion = value("wa_inner_version"),
            crVersion = value("cr_version"),
            rd = value("RD")
        ) ?: return RouterActionResult(false, "تعذر إنشاء مفتاح أمر آمن لهذا Firmware")

        val response = runCatching {
            client.postForm(
                "/goform/goform_set_cmd_process",
                mapOf(
                    "isTest" to "false",
                    "goformId" to "UNLOCK_NETWORK",
                    "unlock_network_code" to nck,
                    "AD" to ad
                )
            )
        }.getOrNull() ?: return RouterActionResult(false, "انقطع الاتصال أثناء إرسال رقم الفك")

        val result = runCatching { JSONObject(response.body).optString("result") }.getOrNull().orEmpty()
        val accepted = response.successful && (result.equals("success", true) || result == "0")
        return RouterActionResult(
            accepted,
            if (accepted) "تم إرسال رقم الفك؛ يجري الآن التحقق من حالة القفل" else "رفض الراوتر رقم الفك"
        )
    }

    private fun isUnlocked(raw: String): Boolean = when (raw.trim().lowercase()) {
        "0", "off", "disabled", "disable", "unlocked", "unlock", "false", "open" -> true
        else -> false
    }

    private fun meaningful(value: String): Boolean =
        value.isNotBlank() && value != "--" && value != "-" &&
            !value.equals("null", true) && !value.equals("undefined", true)
}
