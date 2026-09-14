package com.hai.manager.router

import android.util.Base64
import android.webkit.CookieManager
import org.json.JSONObject
import java.security.MessageDigest

internal enum class NativeRouterAuthBrand(val displayName: String) {
    HUAWEI("Huawei"),
    ZTE("ZTE"),
    UNKNOWN("غير معروف")
}

internal data class NativeRouterAuthResult(
    val success: Boolean,
    val brand: NativeRouterAuthBrand,
    val message: String
)

/**
 * تسجيل دخول Native إلى واجهات Huawei HiLink وZTE WebUI.
 * لا تُحفظ بيانات الاعتماد؛ CookieManager يحتفظ بجلسة الإدارة التي يصدرها الراوتر فقط.
 */
internal class RouterAuthService(private val baseUrl: String) {
    private val client = RouterHttpClient(baseUrl)

    suspend fun detectBrand(): NativeRouterAuthBrand {
        val huawei = runCatching { client.get("/api/webserver/SesTokInfo") }.getOrNull()
        if (huawei?.body?.contains("TokInfo", ignoreCase = true) == true ||
            huawei?.body?.contains("SesInfo", ignoreCase = true) == true
        ) return NativeRouterAuthBrand.HUAWEI

        val zte = runCatching {
            client.get("/goform/goform_get_cmd_process?isTest=false&cmd=LD&multi_data=1")
        }.getOrNull()
        val zteJson = zte?.body?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (zteJson?.has("LD") == true || zte?.body?.contains("goform", ignoreCase = true) == true) {
            return NativeRouterAuthBrand.ZTE
        }
        return NativeRouterAuthBrand.UNKNOWN
    }

    suspend fun login(username: String, password: String): NativeRouterAuthResult {
        if (password.isBlank()) {
            return NativeRouterAuthResult(false, NativeRouterAuthBrand.UNKNOWN, "أدخل كلمة مرور إدارة الراوتر")
        }
        return when (val brand = detectBrand()) {
            NativeRouterAuthBrand.HUAWEI -> loginHuawei(username.ifBlank { "admin" }, password)
            NativeRouterAuthBrand.ZTE -> loginZte(password)
            NativeRouterAuthBrand.UNKNOWN -> NativeRouterAuthResult(
                false,
                brand,
                "لم أتعرف على واجهة تسجيل دخول Huawei أو ZTE لهذا Firmware بعد"
            )
        }
    }

    private suspend fun loginHuawei(username: String, password: String): NativeRouterAuthResult {
        val security = runCatching { client.get("/api/webserver/SesTokInfo") }.getOrNull()
            ?: return fail(NativeRouterAuthBrand.HUAWEI, "تعذر بدء جلسة Huawei")

        val session = xmlValue(security.body, "SesInfo")
        val token = xmlValue(security.body, "TokInfo")
        if (session.isNullOrBlank() || token.isNullOrBlank()) {
            return fail(NativeRouterAuthBrand.HUAWEI, "لم يعطِ الراوتر Session/Token صالحًا لتسجيل الدخول")
        }

        CookieManager.getInstance().setCookie(baseUrl, session)
        CookieManager.getInstance().flush()

        val state = runCatching { client.get("/api/user/state-login") }.getOrNull()
        val passwordType = state?.body?.let { xmlValue(it, "password_type", "PasswordType") }
            ?.toIntOrNull()
            ?: 4

        val encodedPassword = when (passwordType) {
            3 -> base64(password.toByteArray(Charsets.UTF_8))
            4 -> {
                val firstHex = sha256Hex(password).lowercase()
                val firstBase64 = base64(firstHex.toByteArray(Charsets.UTF_8))
                val secondHex = sha256Hex(username + firstBase64 + token).lowercase()
                base64(secondHex.toByteArray(Charsets.UTF_8))
            }
            else -> return fail(
                NativeRouterAuthBrand.HUAWEI,
                "طريقة تسجيل الدخول password_type=$passwordType غير موثقة لهذا Firmware"
            )
        }

        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<request><Username>${xmlEscape(username)}</Username>" +
            "<Password>${xmlEscape(encodedPassword)}</Password>" +
            "<password_type>$passwordType</password_type></request>"

        val response = runCatching {
            client.postXml(
                "/api/user/login",
                xml,
                headers = mapOf("__RequestVerificationToken" to token)
            )
        }.getOrNull() ?: return fail(NativeRouterAuthBrand.HUAWEI, "تعذر الاتصال بخدمة تسجيل الدخول في Huawei")

        if (response.body.contains("<response>OK</response>", ignoreCase = true) ||
            response.body.trim().equals("OK", ignoreCase = true)
        ) {
            CookieManager.getInstance().flush()
            return NativeRouterAuthResult(true, NativeRouterAuthBrand.HUAWEI, "تم تسجيل الدخول إلى Huawei من داخل HAI MANAGER")
        }

        val errorCode = Regex("<code>([^<]+)</code>", RegexOption.IGNORE_CASE)
            .find(response.body)?.groupValues?.getOrNull(1)?.trim()
        val message = when (errorCode) {
            "108006", "108007", "125002" -> "اسم المستخدم أو كلمة المرور غير صحيحة"
            "108008" -> "عدد محاولات تسجيل الدخول مرتفع؛ انتظر قليلًا ثم أعد المحاولة"
            else -> "رفض Huawei تسجيل الدخول${errorCode?.let { " (رمز $it)" }.orEmpty()}"
        }
        return fail(NativeRouterAuthBrand.HUAWEI, message)
    }

    private suspend fun loginZte(password: String): NativeRouterAuthResult {
        val seedResponse = runCatching {
            client.get(
                "/goform/goform_get_cmd_process?isTest=false&cmd=" +
                    "wa_inner_version,cr_version,RD,LD&multi_data=1"
            )
        }.getOrNull() ?: return fail(NativeRouterAuthBrand.ZTE, "تعذر قراءة بيانات جلسة ZTE")

        val seed = runCatching { JSONObject(seedResponse.body) }.getOrNull()
            ?: return fail(NativeRouterAuthBrand.ZTE, "واجهة ZTE الحالية لم تُرجع بيانات Login مفهومة")

        fun value(key: String): String? = seed.optString(key, "").trim().takeIf { meaningful(it) }

        val ld = value("LD")
        val wa = value("wa_inner_version")
        val cr = value("cr_version")
        val rd = value("RD")

        val fields = linkedMapOf(
            "isTest" to "false",
            "goformId" to "LOGIN"
        )

        // أجهزة ZTE الحديثة التي تعلن LD تستخدم challenge-response؛ الأقدم تستخدم Base64 لكلمة المرور.
        fields["password"] = if (!ld.isNullOrBlank()) {
            val passwordHash = sha256Hex(password).uppercase()
            sha256Hex(passwordHash + ld).uppercase()
        } else {
            base64(password.toByteArray(Charsets.UTF_8))
        }

        if (!wa.isNullOrBlank() && !cr.isNullOrBlank() && !rd.isNullOrBlank()) {
            val inner = sha256Hex(wa + cr).uppercase()
            fields["AD"] = sha256Hex(inner + rd).uppercase()
        }

        val response = runCatching {
            client.postForm("/goform/goform_set_cmd_process", fields)
        }.getOrNull() ?: return fail(NativeRouterAuthBrand.ZTE, "تعذر الاتصال بخدمة تسجيل الدخول في ZTE")

        val json = runCatching { JSONObject(response.body) }.getOrNull()
        val result = json?.optString("result", "")?.trim().orEmpty()
        if (result == "0" || result == "4" || result.equals("success", ignoreCase = true)) {
            CookieManager.getInstance().flush()
            return NativeRouterAuthResult(true, NativeRouterAuthBrand.ZTE, "تم تسجيل الدخول إلى ZTE من داخل HAI MANAGER")
        }

        val message = when (result) {
            "1", "3" -> "كلمة مرور إدارة ZTE غير صحيحة"
            "2" -> "توجد جلسة إدارة أخرى على الراوتر؛ أغلقها ثم أعد المحاولة"
            "5" -> "تم إيقاف محاولات الدخول مؤقتًا من الراوتر"
            else -> "رفض ZTE تسجيل الدخول${result.takeIf { it.isNotBlank() }?.let { " (رمز $it)" }.orEmpty()}"
        }
        return fail(NativeRouterAuthBrand.ZTE, message)
    }

    private fun fail(brand: NativeRouterAuthBrand, message: String) =
        NativeRouterAuthResult(false, brand, message)

    private fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun base64(value: ByteArray): String = Base64.encodeToString(value, Base64.NO_WRAP)

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun meaningful(value: String): Boolean =
        value.isNotBlank() && value != "--" && value != "-" &&
            !value.equals("null", true) && !value.equals("undefined", true)
}
