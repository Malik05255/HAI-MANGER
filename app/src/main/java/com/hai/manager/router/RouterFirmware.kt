package com.hai.manager.router

import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Online firmware updates through the router's own update service.
 * No third-party firmware is downloaded or flashed here.
 */
data class RouterFirmwareStatus(
    val currentVersion: String? = null,
    val availableVersion: String? = null,
    val componentSize: String? = null,
    val updateAvailable: Boolean = false,
    val canInstall: Boolean = false,
    val progressPercent: Int? = null,
    val state: String = "جاهز",
    val source: String = "خادم الشركة"
)

class RouterFirmwareService {
    suspend fun check(inspection: RouterInspection): RouterFirmwareStatus {
        val baseUrl = inspection.snapshot.managementUrl
            ?: return RouterFirmwareStatus(currentVersion = inspection.device?.firmwareVersion, state = "تعذر الوصول للراوتر")
        val client = RouterHttpClient(baseUrl)
        return when (inspection.snapshot.brand) {
            RouterBrand.HUAWEI -> checkHuawei(client, inspection)
            RouterBrand.ZTE -> checkZte(client, inspection)
            else -> RouterFirmwareStatus(currentVersion = inspection.device?.firmwareVersion, state = "غير مدعوم")
        }
    }

    suspend fun install(inspection: RouterInspection, status: RouterFirmwareStatus): RouterActionResult {
        if (!status.updateAvailable) return RouterActionResult(false, "لا يوجد تحديث متاح")
        val baseUrl = inspection.snapshot.managementUrl
            ?: return RouterActionResult(false, "تعذر الوصول للراوتر")
        return when (inspection.snapshot.brand) {
            RouterBrand.HUAWEI -> installHuawei(RouterHttpClient(baseUrl), status)
            RouterBrand.ZTE -> RouterActionResult(
                false,
                "تم رصد تحديث ZTE، لكن بدء OTA غير مفعّل لهذا Firmware حتى يتم توثيق أمر التحديث الخاص به"
            )
            else -> RouterActionResult(false, "تحديث Firmware غير مدعوم لهذا النوع")
        }
    }

    private suspend fun checkHuawei(client: RouterHttpClient, inspection: RouterInspection): RouterFirmwareStatus {
        val auth = huaweiAuth(client)
            ?: return RouterFirmwareStatus(
                currentVersion = inspection.device?.firmwareVersion,
                state = "تسجيل الدخول مطلوب"
            )

        val check = runCatching {
            client.postXml("/api/online-update/check-new-version", "", auth)
        }.getOrNull()

        if (check == null || check.code == 404) {
            return RouterFirmwareStatus(
                currentVersion = inspection.device?.firmwareVersion,
                state = "التحديث عبر الإنترنت غير متاح على هذا Firmware"
            )
        }

        delay(900)
        val list = runCatching { client.get("/api/online-update/url-list") }.getOrNull()
        val statusResponse = runCatching { client.get("/api/online-update/status") }.getOrNull()
        val versions = list?.body?.let(::xmlValues).orEmpty()
        val sizes = list?.body?.let { xmlValues(it, "ComponentSize") }.orEmpty()
        val current = inspection.device?.firmwareVersion
        val available = versions.firstOrNull { it.isNotBlank() && !it.equals(current, ignoreCase = true) }
            ?: versions.firstOrNull { it.isNotBlank() }
        val progress = statusResponse?.body?.let { xmlValue(it, "DownloadProgress") }?.toIntOrNull()
        val componentState = statusResponse?.body?.let { xmlValue(it, "CurrentComponentStatus") }

        val updateFound = !available.isNullOrBlank() && !available.equals(current, ignoreCase = true)
        val state = when {
            progress != null && progress in 1..99 -> "جارٍ تنزيل التحديث"
            componentState == "100" -> "اكتمل التحديث"
            updateFound -> "تحديث متاح"
            check.successful -> "أحدث إصدار"
            else -> "تعذر التحقق من التحديث"
        }

        return RouterFirmwareStatus(
            currentVersion = current,
            availableVersion = available?.takeIf { updateFound },
            componentSize = sizes.firstOrNull()?.takeIf { it.isNotBlank() },
            updateAvailable = updateFound,
            canInstall = updateFound && list?.successful == true,
            progressPercent = progress,
            state = state,
            source = "Huawei OTA"
        )
    }

    private suspend fun installHuawei(
        client: RouterHttpClient,
        status: RouterFirmwareStatus
    ): RouterActionResult {
        if (!status.canInstall) return RouterActionResult(false, "التحديث غير جاهز للتثبيت")
        val auth = huaweiAuth(client) ?: return RouterActionResult(false, "تسجيل الدخول مطلوب")
        val response = client.postXml(
            "/api/online-update/ack-newversion",
            "<request><userAckNewVersion>1</userAckNewVersion></request>",
            auth
        )
        val accepted = response.successful && response.body.contains("<response>OK</response>", ignoreCase = true)
        return if (accepted) {
            RouterActionResult(true, "بدأ تحديث الراوتر. لا تفصل الكهرباء حتى يكتمل ويعيد التشغيل")
        } else {
            RouterActionResult(false, "لم يقبل الراوتر بدء التحديث")
        }
    }

    private suspend fun checkZte(client: RouterHttpClient, inspection: RouterInspection): RouterFirmwareStatus {
        val response = runCatching {
            client.get(
                "/goform/goform_get_cmd_process?isTest=false&cmd=" +
                    "new_version_state,current_upgrade_state,is_mandatory,upgrade_result," +
                    "update_version,new_version,wa_inner_version&multi_data=1"
            )
        }.getOrNull()
            ?: return RouterFirmwareStatus(
                currentVersion = inspection.device?.firmwareVersion,
                state = "تعذر فحص التحديث"
            )

        val json = runCatching { JSONObject(response.body) }.getOrNull()
            ?: return RouterFirmwareStatus(
                currentVersion = inspection.device?.firmwareVersion,
                state = "واجهة التحديث غير متاحة"
            )

        val current = inspection.device?.firmwareVersion ?: json.optString("wa_inner_version").takeIf { it.isNotBlank() }
        val newState = json.optString("new_version_state").trim()
        val upgradeState = json.optString("current_upgrade_state").trim()
        val result = json.optString("upgrade_result").trim()
        val version = sequenceOf("update_version", "new_version")
            .map { json.optString(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.equals("null", true) }

        val available = newState.isNotBlank() && newState !in setOf("0", "false", "no", "none")
        val busy = upgradeState.isNotBlank() && upgradeState !in setOf("0", "idle", "none")
        val state = when {
            busy -> "التحديث قيد التنفيذ"
            available -> "تحديث متاح"
            result.equals("error", true) -> "تعذر الوصول إلى خادم تحديث ZTE"
            else -> "أحدث إصدار أو لا يوجد تحديث معلن"
        }

        return RouterFirmwareStatus(
            currentVersion = current,
            availableVersion = version,
            updateAvailable = available,
            canInstall = false,
            state = state,
            source = "ZTE OTA"
        )
    }

    private suspend fun huaweiAuth(client: RouterHttpClient): Map<String, String>? {
        val response = runCatching { client.get("/api/webserver/SesTokInfo") }.getOrNull() ?: return null
        val token = xmlValue(response.body, "TokInfo") ?: return null
        val session = xmlValue(response.body, "SesInfo")
        return buildMap {
            put("__RequestVerificationToken", token)
            if (!session.isNullOrBlank()) put("Cookie", session)
        }
    }

    private fun xmlValues(xml: String, tag: String = "Version"): List<String> =
        Regex("<$tag>(.*?)</$tag>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(xml)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .toList()
}
