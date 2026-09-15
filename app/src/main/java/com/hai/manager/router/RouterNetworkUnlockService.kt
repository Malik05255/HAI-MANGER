package com.hai.manager.router

import org.json.JSONObject
import java.security.MessageDigest

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
        val ad = zteAd(client) ?: return RouterActionResult(false, "سجّل الدخول إلى الراوتر أولًا")
        val response = client.postForm(
            "/goform/goform_set_cmd_process",
            mapOf(
                "isTest" to "false",
                "goformId" to "UNLOCK_NETWORK",
                "unlock_network_code" to nck,
                "AD" to ad
            )
        )
        val result = runCatching { JSONObject(response.body).optString("result") }.getOrNull().orEmpty()
        val accepted = response.successful && (result.equals("success", true) || result == "0")
        return RouterActionResult(
            accepted,
            if (accepted) "تم إرسال رقم الفك" else "لم يقبل الراوتر رقم الفك"
        )
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

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
