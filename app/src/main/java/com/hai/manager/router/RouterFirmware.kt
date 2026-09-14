package com.hai.manager.router

import kotlinx.coroutines.delay
import org.json.JSONObject

/** Firmware sources exposed to the user. */
enum class FirmwareSearchSource(val displayName: String) {
    OFFICIAL("التحديث الرسمي"),
    COMPANIES("تحديثات الشركات")
}

data class FirmwareCandidate(
    val currentVersion: String? = null,
    val version: String,
    val size: String? = null,
    val source: FirmwareSearchSource,
    val sourceLabel: String,
    val summaryArabic: String,
    val installable: Boolean,
    val installMode: String,
    val brand: String,
    val model: String? = null,
    val hardware: String? = null,
    val requiredCurrentFirmware: String? = null,
    val downloadUrl: String? = null,
    val sha256: String? = null
)

data class FirmwareSearchResult(
    val candidate: FirmwareCandidate? = null,
    val message: String
)

data class FirmwareCompatibility(
    val compatible: Boolean,
    val message: String
)

class RouterFirmwareService {
    suspend fun search(
        inspection: RouterInspection,
        source: FirmwareSearchSource,
        catalogJson: String?,
        onProgress: suspend (Int, String) -> Unit
    ): FirmwareSearchResult {
        onProgress(0, "بدء البحث")
        onProgress(8, "قراءة بيانات الراوتر")
        inspection.device ?: return FirmwareSearchResult(message = "تعذر قراءة بيانات الراوتر")
        delay(120)

        onProgress(18, "مطابقة الموديل والـFirmware")
        delay(120)

        return when (source) {
            FirmwareSearchSource.OFFICIAL -> {
                onProgress(32, "الاتصال بخادم الشركة")
                val result = when (inspection.snapshot.brand) {
                    RouterBrand.HUAWEI -> searchHuaweiOfficial(inspection, onProgress)
                    RouterBrand.ZTE -> searchZteOfficial(inspection, onProgress)
                    else -> FirmwareSearchResult(message = "هذا النوع غير مدعوم")
                }
                onProgress(100, if (result.candidate != null) "تم العثور على تحديث" else "اكتمل البحث")
                result
            }

            FirmwareSearchSource.COMPANIES -> {
                onProgress(32, "تحديث قاعدة التوافق")
                delay(150)
                onProgress(55, "البحث في تحديثات الشركات")
                val result = searchCompanyCatalog(inspection, catalogJson)
                onProgress(78, "التحقق من التوافق")
                delay(120)
                onProgress(100, if (result.candidate != null) "تم العثور على تحديث" else "اكتمل البحث")
                result
            }
        }
    }

    suspend fun verifyCompatibility(
        inspection: RouterInspection,
        candidate: FirmwareCandidate,
        onProgress: suspend (Int, String) -> Unit
    ): FirmwareCompatibility {
        onProgress(3, "قراءة هوية الراوتر")
        val device = inspection.device
            ?: return FirmwareCompatibility(false, "تعذر قراءة هوية الراوتر")

        val actualCurrent = device.firmwareVersion.orEmpty()
        if (candidate.version.isNotBlank() && candidate.version.equals(actualCurrent, ignoreCase = true)) {
            return FirmwareCompatibility(false, "هذا الإصدار مثبت على الراوتر بالفعل")
        }

        onProgress(10, "التحقق من الشركة")
        if (!candidate.brand.equals(inspection.snapshot.brand.displayName, true)) {
            return FirmwareCompatibility(false, "التحديث لا يطابق شركة الراوتر")
        }

        onProgress(18, "التحقق من الموديل")
        candidate.model?.takeIf { it.isNotBlank() }?.let { expected ->
            val actual = device.model.orEmpty()
            if (!actual.contains(expected, true) && !expected.contains(actual, true)) {
                return FirmwareCompatibility(false, "التحديث لا يطابق موديل الراوتر")
            }
        }

        onProgress(26, "التحقق من Hardware")
        candidate.hardware?.takeIf { it.isNotBlank() }?.let { expected ->
            val actual = device.hardwareVersion.orEmpty()
            if (actual.isBlank() || !actual.contains(expected, true)) {
                return FirmwareCompatibility(false, "إصدار Hardware غير مطابق")
            }
        }

        onProgress(34, "التحقق من الإصدار الحالي")
        candidate.requiredCurrentFirmware?.takeIf { it.isNotBlank() }?.let { expected ->
            if (actualCurrent.isBlank() || !actualCurrent.contains(expected, true)) {
                return FirmwareCompatibility(false, "الإصدار الحالي ليس ضمن المسار المسموح لهذا التحديث")
            }
        }

        onProgress(40, "التحقق من مصدر التحديث")
        if (candidate.source == FirmwareSearchSource.COMPANIES) {
            val directPackage = candidate.installMode.equals("direct_package", true)
            if (directPackage && (candidate.downloadUrl.isNullOrBlank() || candidate.sha256.isNullOrBlank())) {
                return FirmwareCompatibility(false, "حزمة الشركة لا تحتوي تحقق SHA-256 كامل")
            }
        }

        if (!candidate.installable) {
            return FirmwareCompatibility(false, "التحديث موجود لكن طريقة التثبيت غير موثقة لهذا Firmware")
        }

        return FirmwareCompatibility(true, "متوافق")
    }

    suspend fun execute(
        inspection: RouterInspection,
        candidate: FirmwareCandidate,
        onProgress: suspend (Int, String) -> Unit
    ): RouterActionResult {
        val compatibility = verifyCompatibility(inspection, candidate, onProgress)
        if (!compatibility.compatible) return RouterActionResult(false, compatibility.message)

        onProgress(45, "تجهيز التحديث")
        return when {
            candidate.installMode.equals("huawei_ota", true) -> executeHuaweiOta(inspection, candidate, onProgress)
            else -> RouterActionResult(false, "طريقة التثبيت غير مفعلة لهذا التحديث")
        }
    }

    private suspend fun searchHuaweiOfficial(
        inspection: RouterInspection,
        onProgress: suspend (Int, String) -> Unit
    ): FirmwareSearchResult {
        val baseUrl = inspection.snapshot.managementUrl
            ?: return FirmwareSearchResult(message = "تعذر الوصول للراوتر")
        val client = RouterHttpClient(baseUrl)
        val auth = huaweiAuth(client)
            ?: return FirmwareSearchResult(message = "سجّل الدخول للراوتر أولًا")

        onProgress(45, "طلب أحدث إصدار من Huawei")
        val check = runCatching {
            client.postXml("/api/online-update/check-new-version", "", auth)
        }.getOrNull()

        if (check == null || check.code == 404) {
            return FirmwareSearchResult(message = "خدمة التحديث الرسمي غير متاحة على هذا Firmware")
        }

        onProgress(60, "انتظار نتيجة الخادم")
        delay(900)
        val list = runCatching { client.get("/api/online-update/url-list") }.getOrNull()
        val components = list?.body?.let(::parseHuaweiComponents).orEmpty()
        val current = inspection.device?.firmwareVersion
        val firmwareComponent = components.firstOrNull { component ->
            val name = component.name.lowercase()
            "firmware" in name || "software" in name || "modem" in name || "system" in name
        } ?: components.firstOrNull()

        onProgress(82, "مقارنة الإصدارات")
        val available = firmwareComponent?.version?.takeIf {
            it.isNotBlank() && !it.equals(current, ignoreCase = true)
        } ?: return FirmwareSearchResult(message = "الراوتر على أحدث إصدار رسمي")

        return FirmwareSearchResult(
            candidate = FirmwareCandidate(
                currentVersion = current,
                version = available,
                size = firmwareComponent.size,
                source = FirmwareSearchSource.OFFICIAL,
                sourceLabel = "Huawei OTA",
                summaryArabic = "تحديث رسمي من Huawei مطابق للراوتر. سيتحقق التطبيق من الموديل وHardware والإصدار مرة أخرى قبل التنفيذ.",
                installable = list?.successful == true,
                installMode = "huawei_ota",
                brand = "Huawei",
                model = inspection.device?.model,
                hardware = inspection.device?.hardwareVersion
            ),
            message = "تم العثور على تحديث رسمي"
        )
    }

    private suspend fun searchZteOfficial(
        inspection: RouterInspection,
        onProgress: suspend (Int, String) -> Unit
    ): FirmwareSearchResult {
        val baseUrl = inspection.snapshot.managementUrl
            ?: return FirmwareSearchResult(message = "تعذر الوصول للراوتر")
        val client = RouterHttpClient(baseUrl)
        onProgress(48, "طلب حالة OTA من ZTE")
        val response = runCatching {
            client.get(
                "/goform/goform_get_cmd_process?isTest=false&cmd=" +
                    "new_version_state,current_upgrade_state,is_mandatory,upgrade_result," +
                    "update_version,new_version,wa_inner_version&multi_data=1"
            )
        }.getOrNull() ?: return FirmwareSearchResult(message = "تعذر فحص تحديث ZTE")

        val json = runCatching { JSONObject(response.body) }.getOrNull()
            ?: return FirmwareSearchResult(message = "واجهة تحديث ZTE غير متاحة لهذا Firmware")

        onProgress(72, "قراءة الإصدار المتاح")
        val current = inspection.device?.firmwareVersion ?: json.optString("wa_inner_version").takeIf { it.isNotBlank() }
        val version = sequenceOf("update_version", "new_version")
            .map { json.optString(it).trim() }
            .firstOrNull { it.isNotBlank() && !it.equals("null", true) && !it.equals(current, true) }
        val newState = json.optString("new_version_state").trim().lowercase()
        val explicitAvailable = newState in setOf("1", "true", "available", "new_version", "newversion", "has_new_version")
        val available = version != null || explicitAvailable
        if (!available) return FirmwareSearchResult(message = "لا يوجد تحديث رسمي معلن من ZTE")

        return FirmwareSearchResult(
            candidate = FirmwareCandidate(
                currentVersion = current,
                version = version ?: "إصدار جديد",
                source = FirmwareSearchSource.OFFICIAL,
                sourceLabel = "ZTE OTA",
                summaryArabic = "تحديث رسمي رصده الراوتر من خدمة ZTE. التثبيت المباشر محجوب حتى يكون أمر OTA موثقًا لهذا Firmware.",
                installable = false,
                installMode = "zte_ota_unverified",
                brand = "ZTE",
                model = inspection.device?.model,
                hardware = inspection.device?.hardwareVersion
            ),
            message = "تم العثور على تحديث رسمي"
        )
    }

    private fun searchCompanyCatalog(
        inspection: RouterInspection,
        catalogJson: String?
    ): FirmwareSearchResult {
        if (catalogJson.isNullOrBlank()) return FirmwareSearchResult(message = "تعذر تحميل قاعدة تحديثات الشركات")
        val root = runCatching { JSONObject(catalogJson) }.getOrNull()
            ?: return FirmwareSearchResult(message = "قاعدة تحديثات الشركات غير صالحة")
        val updates = root.optJSONArray("firmwareUpdates")
            ?: return FirmwareSearchResult(message = "لا توجد تحديثات شركات موثقة لهذا الراوتر حاليًا")

        val brand = inspection.snapshot.brand.displayName
        val model = inspection.device?.model.orEmpty()
        val hardware = inspection.device?.hardwareVersion.orEmpty()
        val current = inspection.device?.firmwareVersion.orEmpty()

        for (index in 0 until updates.length()) {
            val item = updates.optJSONObject(index) ?: continue
            if (!item.optString("brand").equals(brand, true)) continue
            val modelMatch = item.optString("modelContains")
            if (modelMatch.isNotBlank() && !model.contains(modelMatch, true)) continue
            val hardwareMatch = item.optString("hardwareContains")
            if (hardwareMatch.isNotBlank() && !hardware.contains(hardwareMatch, true)) continue
            val currentMatch = item.optString("currentFirmwareContains")
            if (currentMatch.isNotBlank() && !current.contains(currentMatch, true)) continue

            val version = item.optString("version").trim()
            if (version.isBlank() || version.equals(current, true)) continue
            val url = item.optString("url").takeIf { it.startsWith("https://") }
            val sha256 = item.optString("sha256").takeIf { it.matches(Regex("[A-Fa-f0-9]{64}")) }
            val installMode = item.optString("installMode", "direct_package")
            val verified = item.optBoolean("verified", false)
            val installable = verified && when (installMode.lowercase()) {
                "huawei_ota" -> true
                "direct_package" -> false // Requires a signed model-specific flashing adapter before enabling.
                else -> false
            }

            return FirmwareSearchResult(
                candidate = FirmwareCandidate(
                    currentVersion = current,
                    version = version,
                    size = item.optString("size").takeIf { it.isNotBlank() },
                    source = FirmwareSearchSource.COMPANIES,
                    sourceLabel = item.optString("company", "تحديث شركة"),
                    summaryArabic = item.optString("summaryArabic").takeIf { it.isNotBlank() }
                        ?: "تحديث شركة موثق في قاعدة HAI MANAGER ومتوافق مع بيانات هذا الراوتر.",
                    installable = installable,
                    installMode = installMode,
                    brand = brand,
                    model = modelMatch.takeIf { it.isNotBlank() },
                    hardware = hardwareMatch.takeIf { it.isNotBlank() },
                    requiredCurrentFirmware = currentMatch.takeIf { it.isNotBlank() },
                    downloadUrl = url,
                    sha256 = sha256
                ),
                message = "تم العثور على تحديث شركة"
            )
        }

        return FirmwareSearchResult(message = "لا يوجد تحديث شركات موثق ومطابق لهذا الراوتر")
    }

    private suspend fun executeHuaweiOta(
        inspection: RouterInspection,
        candidate: FirmwareCandidate,
        onProgress: suspend (Int, String) -> Unit
    ): RouterActionResult {
        val baseUrl = inspection.snapshot.managementUrl
            ?: return RouterActionResult(false, "تعذر الوصول للراوتر")
        val client = RouterHttpClient(baseUrl)
        val auth = huaweiAuth(client) ?: return RouterActionResult(false, "سجّل الدخول للراوتر أولًا")

        onProgress(48, "إرسال أمر التحديث")
        val response = client.postXml(
            "/api/online-update/ack-newversion",
            "<request><userAckNewVersion>1</userAckNewVersion></request>",
            auth
        )
        val accepted = response.successful && response.body.contains("<response>OK</response>", ignoreCase = true)
        if (!accepted) return RouterActionResult(false, "الراوتر لم يقبل بدء التحديث")

        onProgress(52, "بدأ التحديث")
        var lastReported = 52
        var packageComplete = false
        for (attempt in 0 until 90) {
            delay(2000)
            val status = runCatching { client.get("/api/online-update/status") }.getOrNull()
            val raw = status?.body?.let { body -> xmlValue(body, "DownloadProgress") }?.toIntOrNull()
            val componentState = status?.body?.let { body -> xmlValue(body, "CurrentComponentStatus") }
            if (raw != null) {
                val mapped = (52 + (raw.coerceIn(0, 100) * 0.40)).toInt().coerceIn(52, 92)
                if (mapped > lastReported) {
                    lastReported = mapped
                    onProgress(mapped, if (raw < 100) "تنزيل النظام $raw%" else "تجهيز التثبيت")
                }
            }
            if (componentState == "100" || raw == 100) {
                packageComplete = true
                lastReported = maxOf(lastReported, 94)
                onProgress(lastReported, "إعادة تشغيل الراوتر")
                break
            }
        }

        if (!packageComplete && lastReported < 70) {
            return RouterActionResult(false, "بدأ الراوتر التحديث لكن لم تصل حالة تنزيل موثوقة. أعد الفحص قبل أي محاولة أخرى")
        }

        onProgress(maxOf(lastReported, 94), "بانتظار عودة الراوتر")
        for (attempt in 0 until 75) {
            delay(2000)
            val info = runCatching { client.get("/api/device/information") }.getOrNull()
            val version = info?.body?.let { body -> xmlValue(body, "SoftwareVersion") }
            if (!version.isNullOrBlank()) {
                val target = candidate.version
                val old = candidate.currentVersion
                if (version.equals(target, true) || (!old.isNullOrBlank() && !version.equals(old, true))) {
                    onProgress(100, "اكتمل التحديث")
                    return RouterActionResult(true, "تم تحديث نظام الراوتر إلى $version")
                }
            }
        }

        return RouterActionResult(
            true,
            "بدأ التحديث بنجاح، لكن لم يتمكن التطبيق من تأكيد الإصدار الجديد بعد إعادة التشغيل. افحص الحالة بعد عودة الراوتر."
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

    private data class HuaweiComponent(
        val name: String,
        val version: String,
        val size: String?
    )

    private fun parseHuaweiComponents(xml: String): List<HuaweiComponent> {
        return Regex("<Component>(.*?)</Component>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(xml)
            .mapNotNull { match ->
                val block = match.groupValues[1]
                val version = xmlValue(block, "Version")?.trim().orEmpty()
                if (version.isBlank()) return@mapNotNull null
                HuaweiComponent(
                    name = xmlValue(block, "ComponentName")?.trim().orEmpty(),
                    version = version,
                    size = xmlValue(block, "ComponentSize")?.trim()
                )
            }
            .toList()
    }
}
