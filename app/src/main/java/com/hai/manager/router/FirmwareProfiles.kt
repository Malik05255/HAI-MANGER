package com.hai.manager.router

import org.json.JSONArray
import org.json.JSONObject

enum class FirmwareVerification(val displayName: String) {
    VERIFIED("موثق"),
    RUNTIME_PROBED("تحقق وقت التشغيل"),
    READ_ONLY("قراءة فقط"),
    UNKNOWN("غير معروف")
}

enum class ProfileActionSupport(val displayName: String, val canWrite: Boolean) {
    VERIFIED("موثق للكتابة", true),
    RUNTIME_PROBE("يتطلب فحصًا آمنًا قبل التنفيذ", true),
    READ_ONLY("قراءة فقط", false),
    UNAVAILABLE("غير متاح", false)
}

data class FirmwareProfileInfo(
    val profileId: String,
    val verification: FirmwareVerification,
    val model: String,
    val firmware: String,
    val bandLock: ProfileActionSupport,
    val nckEntry: ProfileActionSupport,
    val notes: String
)

val RouterInspection.firmwareProfileInfo: FirmwareProfileInfo
    get() {
        val model = device?.model ?: snapshot.model
        val firmware = device?.firmwareVersion
        val baseline = RouterFirmwareProfiles.resolve(
            brand = snapshot.brand,
            model = model,
            firmware = firmware,
            capabilities = capabilities
        )
        val live = LiveFirmwareProfileCache.resolve(
            brand = snapshot.brand,
            model = model,
            firmware = firmware
        ) ?: return baseline
        return mergeConservatively(baseline, live)
    }

/**
 * Cache للـFirmware profiles القادمة من device-catalog.json.
 * قاعدة الأجهزة البعيدة لا تستطيع توسيع صلاحيات الكتابة فوق الحد الموجود في APK الموقع؛
 * يمكنها فقط تحسين المطابقة أو تضييق/تعطيل عملية بسرعة عند اكتشاف Firmware غير آمن.
 */
object LiveFirmwareProfileCache {
    @Volatile
    private var rawCatalog: String? = null

    fun update(raw: String?) {
        if (raw.isNullOrBlank()) return
        val valid = runCatching {
            val root = JSONObject(raw)
            root.optInt("catalogVersion") > 0 && root.optJSONArray("firmwareProfiles") != null
        }.getOrDefault(false)
        if (valid) rawCatalog = raw
    }

    fun resolve(brand: RouterBrand, model: String?, firmware: String?): FirmwareProfileInfo? {
        val raw = rawCatalog ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val version = root.optInt("catalogVersion")
            val profiles = root.optJSONArray("firmwareProfiles") ?: return@runCatching null
            val normalizedModel = model.orEmpty().trim()
            val normalizedFirmware = firmware.orEmpty().trim()

            var best: FirmwareProfileInfo? = null
            var bestScore = Int.MIN_VALUE
            for (index in 0 until profiles.length()) {
                val item = profiles.optJSONObject(index) ?: continue
                if (!brandMatches(item.optString("brand"), brand)) continue

                val modelPatterns = buildList {
                    item.optString("model").trim().takeIf { it.isNotBlank() }?.let(::add)
                    addAll(item.stringList("families"))
                    addAll(item.stringList("modelContains"))
                }.distinct()
                if (modelPatterns.isNotEmpty() && modelPatterns.none { normalizedModel.contains(it, ignoreCase = true) }) continue

                val firmwarePatterns = buildList {
                    item.optString("firmwareContains").trim().takeIf { it.isNotBlank() }?.let(::add)
                    addAll(item.stringList("firmwarePatterns"))
                }.distinct()
                if (firmwarePatterns.isNotEmpty() && firmwarePatterns.none { normalizedFirmware.contains(it, ignoreCase = true) }) continue

                val score = (if (firmwarePatterns.isNotEmpty()) 10_000 else 0) +
                    (modelPatterns.maxOfOrNull { it.length } ?: 0) * 10 +
                    if (item.optString("model").isNotBlank()) 500 else 0
                if (score < bestScore) continue

                val profileId = item.optString("id").ifBlank { "LIVE-$index" }
                val notes = item.optString("notes").ifBlank { "مطابقة من قاعدة Firmware الحية." }
                best = FirmwareProfileInfo(
                    profileId = profileId,
                    verification = item.optString("verification").toFirmwareVerification(),
                    model = normalizedModel.ifBlank { "غير معروف" },
                    firmware = normalizedFirmware.ifBlank { "غير معروف" },
                    bandLock = item.optString("bandLock").toActionSupport(),
                    nckEntry = item.optString("nck").toActionSupport(),
                    notes = "قاعدة الأجهزة الحية v$version: $notes"
                )
                bestScore = score
            }
            best
        }.getOrNull()
    }
}

object RouterFirmwareProfiles {
    fun resolve(
        brand: RouterBrand,
        model: String?,
        firmware: String?,
        capabilities: Set<RouterCapability>
    ): FirmwareProfileInfo {
        val normalizedModel = model.orEmpty().trim()
        val normalizedFirmware = firmware.orEmpty().trim()

        if (brand == RouterBrand.ZTE &&
            normalizedModel.contains("MC801A", ignoreCase = true) &&
            normalizedFirmware.contains("BD_UKH3GMC801AV1.0.0B15", ignoreCase = true)
        ) {
            return FirmwareProfileInfo(
                profileId = "ZTE-MC801A-B15",
                verification = FirmwareVerification.VERIFIED,
                model = normalizedModel.ifBlank { "MC801A" },
                firmware = normalizedFirmware,
                bandLock = ProfileActionSupport.VERIFIED,
                nckEntry = ProfileActionSupport.READ_ONLY,
                notes = "قفل NR موثق لهذا Firmware. حالة Network Lock ومحاولات NCK تُقرأ فقط؛ إدخال NCK غير مفعّل دون endpoint رسمي موثق."
            )
        }

        if (brand == RouterBrand.ZTE && normalizedModel.isKnownZte5gProfileFamily()) {
            return FirmwareProfileInfo(
                profileId = "ZTE-5G-GOFORM-RUNTIME",
                verification = FirmwareVerification.RUNTIME_PROBED,
                model = normalizedModel,
                firmware = normalizedFirmware.ifBlank { "غير معروف" },
                bandLock = ProfileActionSupport.RUNTIME_PROBE,
                nckEntry = ProfileActionSupport.READ_ONLY,
                notes = "يمكن اختبار قفل NR بفحص no-op للقيمة الحالية أولًا. إذا كان ترميز band mask غير واضح أو لم ينجح التحقق، يمنع التطبيق الكتابة."
            )
        }

        if (brand == RouterBrand.ZTE && normalizedModel.isKnownZte4gProfileFamily()) {
            return FirmwareProfileInfo(
                profileId = "ZTE-4G-GOFORM-READ",
                verification = FirmwareVerification.RUNTIME_PROBED,
                model = normalizedModel,
                firmware = normalizedFirmware.ifBlank { "غير معروف" },
                bandLock = ProfileActionSupport.READ_ONLY,
                nckEntry = ProfileActionSupport.READ_ONLY,
                notes = "قراءة الشبكة وSIM والقفل متاحة عندما يعرضها WebUI. Band Lock وNCK لا يكتبان بدون Profile موثق."
            )
        }

        if (brand == RouterBrand.HUAWEI && normalizedModel.isKnownHuaweiProfileFamily()) {
            return FirmwareProfileInfo(
                profileId = "HUAWEI-HILINK-RUNTIME",
                verification = FirmwareVerification.RUNTIME_PROBED,
                model = normalizedModel,
                firmware = normalizedFirmware.ifBlank { "غير معروف" },
                bandLock = ProfileActionSupport.READ_ONLY,
                nckEntry = ProfileActionSupport.READ_ONLY,
                notes = "HiLink/WebUI يُستخدم للقراءة والأوامر المثبتة فقط. Band Lock وNCK يبقيان قراءة فقط حتى توثيق ترميز الـAPI لهذا Firmware."
            )
        }

        return FirmwareProfileInfo(
            profileId = "UNMATCHED",
            verification = if (RouterCapability.DEVICE_INFO in capabilities) FirmwareVerification.READ_ONLY else FirmwareVerification.UNKNOWN,
            model = normalizedModel.ifBlank { "غير معروف" },
            firmware = normalizedFirmware.ifBlank { "غير معروف" },
            bandLock = ProfileActionSupport.UNAVAILABLE,
            nckEntry = ProfileActionSupport.UNAVAILABLE,
            notes = "لم يطابق الجهاز Profile كتابة موثق؛ HAI MANAGER يبقي العمليات الحساسة معطلة."
        )
    }
}

private fun mergeConservatively(
    baseline: FirmwareProfileInfo,
    live: FirmwareProfileInfo
): FirmwareProfileInfo = FirmwareProfileInfo(
    profileId = live.profileId,
    verification = stricterVerification(baseline.verification, live.verification),
    model = live.model,
    firmware = live.firmware,
    bandLock = stricterAction(baseline.bandLock, live.bandLock),
    nckEntry = stricterAction(baseline.nckEntry, live.nckEntry),
    notes = "${live.notes} لا تستطيع قاعدة البيانات البعيدة رفع صلاحية الكتابة فوق الحد الموجود في APK الموقع."
)

private fun stricterVerification(a: FirmwareVerification, b: FirmwareVerification): FirmwareVerification {
    fun rank(value: FirmwareVerification): Int = when (value) {
        FirmwareVerification.UNKNOWN -> 0
        FirmwareVerification.READ_ONLY -> 1
        FirmwareVerification.RUNTIME_PROBED -> 2
        FirmwareVerification.VERIFIED -> 3
    }
    return if (rank(a) <= rank(b)) a else b
}

private fun stricterAction(a: ProfileActionSupport, b: ProfileActionSupport): ProfileActionSupport {
    fun rank(value: ProfileActionSupport): Int = when (value) {
        ProfileActionSupport.UNAVAILABLE -> 0
        ProfileActionSupport.READ_ONLY -> 1
        ProfileActionSupport.RUNTIME_PROBE -> 2
        ProfileActionSupport.VERIFIED -> 3
    }
    return if (rank(a) <= rank(b)) a else b
}

private fun brandMatches(raw: String, brand: RouterBrand): Boolean {
    val normalized = raw.trim().replace("-", "_").uppercase()
    return when (brand) {
        RouterBrand.ZTE -> normalized == "ZTE"
        RouterBrand.HUAWEI -> normalized == "HUAWEI"
        else -> false
    }
}

private fun String.toFirmwareVerification(): FirmwareVerification = when (trim().lowercase()) {
    "verified" -> FirmwareVerification.VERIFIED
    "runtime_probed", "runtime_probe" -> FirmwareVerification.RUNTIME_PROBED
    "read_only" -> FirmwareVerification.READ_ONLY
    else -> FirmwareVerification.UNKNOWN
}

private fun String.toActionSupport(): ProfileActionSupport = when (trim().lowercase()) {
    "verified", "verified_write" -> ProfileActionSupport.VERIFIED
    "runtime_probe", "runtime_preflight" -> ProfileActionSupport.RUNTIME_PROBE
    "read_only", "diagnostics_only" -> ProfileActionSupport.READ_ONLY
    else -> ProfileActionSupport.UNAVAILABLE
}

private fun JSONObject.stringList(key: String): List<String> {
    val array: JSONArray = optJSONArray(key) ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            array.optString(index).trim().takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun String.isKnownZte5gProfileFamily(): Boolean =
    contains("MC801", true) || contains("MC888", true) || contains("MC889", true) || contains("MC7010", true)

private fun String.isKnownZte4gProfileFamily(): Boolean =
    contains("MF286", true) || contains("MF289", true) || contains("MF297", true)

private fun String.isKnownHuaweiProfileFamily(): Boolean = listOf(
    "H158", "H155", "H138", "H122", "H112", "B818", "B715", "B628", "B535", "B525"
).any { contains(it, true) }
