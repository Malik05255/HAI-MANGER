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
    val requiredProbes: Set<String> = emptySet(),
    val actionProbes: Map<RouterWriteOperation, Set<String>> = emptyMap(),
    val notes: String
)

data class ProfileProbeReadiness(
    val ready: Boolean,
    val required: Set<String>,
    val missing: Set<String>,
    val message: String
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
        )
        val resolved = if (live == null) baseline else mergeConservatively(baseline, live)
        return applyProbeRestrictions(resolved, probeReport)
    }

val RouterInspection.profileProbeReadiness: ProfileProbeReadiness
    get() {
        val profile = firmwareProfileInfo
        val required = profile.requiredProbes
        if (required.isEmpty()) {
            return ProfileProbeReadiness(true, emptySet(), emptySet(), "لا يتطلب Profile الحالي probes إضافية")
        }
        val report = probeReport
            ?: return ProfileProbeReadiness(false, required, required, "يجب إكمال Capability Probe قبل السماح بالعمليات الحساسة")
        val available = report.items
            .filter { it.status == CapabilityProbeStatus.AVAILABLE }
            .mapTo(mutableSetOf()) { it.id }
        val missing = required - available
        return if (missing.isEmpty()) {
            ProfileProbeReadiness(true, required, emptySet(), "جميع probes المطلوبة لهذا Profile ناجحة")
        } else {
            ProfileProbeReadiness(false, required, missing, "Profile غير جاهز للكتابة؛ probes الناقصة: ${missing.joinToString(", ")}")
        }
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
                    requiredProbes = item.stringList("requiredProbes").toSet(),
                    actionProbes = item.actionProbeMap(),
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
                requiredProbes = setOf("zte_action_seed", "network_mode_read", "nr_band_state"),
                actionProbes = zte5gActionProbes(),
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
                requiredProbes = setOf("zte_action_seed", "network_mode_read", "nr_band_state"),
                actionProbes = zte5gActionProbes(),
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
                requiredProbes = setOf("network_mode_read", "network_lock_read"),
                actionProbes = mapOf(
                    RouterWriteOperation.REBOOT to setOf("zte_action_seed"),
                    RouterWriteOperation.NETWORK_MODE to setOf("zte_action_seed", "network_mode_read")
                ),
                notes = "قراءة الشبكة وSIM والقفل متاحة عندما يعرضها WebUI. Band Lock وNCK لا يكتبان بدون Profile موثق."
            )
        }

        if (brand == RouterBrand.HUAWEI && normalizedModel.isKnownHuaweiBalongV4Family()) {
            return FirmwareProfileInfo(
                profileId = "HUAWEI-BALONG-4G-V4-AWARE",
                verification = FirmwareVerification.RUNTIME_PROBED,
                model = normalizedModel,
                firmware = normalizedFirmware.ifBlank { "غير معروف" },
                bandLock = ProfileActionSupport.READ_ONLY,
                nckEntry = ProfileActionSupport.READ_ONLY,
                requiredProbes = setOf("huawei_session_token", "sim_security"),
                actionProbes = mapOf(
                    RouterWriteOperation.REBOOT to setOf("huawei_session_token"),
                    RouterWriteOperation.NETWORK_MODE to setOf("huawei_session_token", "network_mode_read")
                ),
                notes = "B310/B311/B315 قد تستخدم قفل V4 يعتمد على Firmware/AT/hash. لا تُطبق أكواد V1/V2/V201 عليها تلقائيًا؛ يتم تشخيص القفل والـFirmware أولًا."
            )
        }

        if (brand == RouterBrand.HUAWEI && normalizedModel.isKnownHuaweiLegacyBalongFamily()) {
            return FirmwareProfileInfo(
                profileId = "HUAWEI-LEGACY-BALONG-READ",
                verification = FirmwareVerification.READ_ONLY,
                model = normalizedModel,
                firmware = normalizedFirmware.ifBlank { "غير معروف" },
                bandLock = ProfileActionSupport.READ_ONLY,
                nckEntry = ProfileActionSupport.READ_ONLY,
                requiredProbes = setOf("sim_security"),
                actionProbes = emptyMap(),
                notes = "عائلة Balong قديمة؛ بعض الـFirmware غير متوافق مع HiLink الحديث، لذلك يبقى المسار قراءة وتشخيص فقط حتى المطابقة الدقيقة."
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
                requiredProbes = setOf("huawei_session_token", "network_mode_read", "band_selection_read", "sim_security"),
                actionProbes = mapOf(
                    RouterWriteOperation.REBOOT to setOf("huawei_session_token"),
                    RouterWriteOperation.NETWORK_MODE to setOf("huawei_session_token", "network_mode_read", "band_selection_read")
                ),
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
            requiredProbes = emptySet(),
            actionProbes = emptyMap(),
            notes = "لم يطابق الجهاز Profile كتابة موثق؛ HAI MANAGER يبقي العمليات الحساسة معطلة."
        )
    }
}

private fun zte5gActionProbes(): Map<RouterWriteOperation, Set<String>> = mapOf(
    RouterWriteOperation.REBOOT to setOf("zte_action_seed"),
    RouterWriteOperation.NETWORK_MODE to setOf("zte_action_seed", "network_mode_read"),
    RouterWriteOperation.BAND_LOCK to setOf("zte_action_seed", "network_mode_read", "nr_band_state")
)

private fun applyProbeRestrictions(
    profile: FirmwareProfileInfo,
    report: RouterCapabilityReport?
): FirmwareProfileInfo {
    if (!profile.bandLock.canWrite) return profile
    val required = profile.actionProbes[RouterWriteOperation.BAND_LOCK].orEmpty().ifEmpty { profile.requiredProbes }
    if (required.isEmpty()) return profile
    val available = report?.items
        ?.filter { it.status == CapabilityProbeStatus.AVAILABLE }
        ?.mapTo(mutableSetOf()) { it.id }
        .orEmpty()
    val missing = required - available
    if (missing.isEmpty()) return profile
    return profile.copy(
        bandLock = ProfileActionSupport.READ_ONLY,
        notes = "${profile.notes} الكتابة محجوبة حاليًا حتى تنجح probes المطلوبة: ${missing.joinToString(", ")}."
    )
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
    requiredProbes = baseline.requiredProbes + live.requiredProbes,
    actionProbes = mergeActionProbes(baseline.actionProbes, live.actionProbes),
    notes = "${live.notes} لا تستطيع قاعدة البيانات البعيدة رفع صلاحية الكتابة فوق الحد الموجود في APK الموقع."
)

private fun mergeActionProbes(
    baseline: Map<RouterWriteOperation, Set<String>>,
    live: Map<RouterWriteOperation, Set<String>>
): Map<RouterWriteOperation, Set<String>> = buildMap {
    RouterWriteOperation.entries.forEach { operation ->
        val merged = baseline[operation].orEmpty() + live[operation].orEmpty()
        if (merged.isNotEmpty()) put(operation, merged)
    }
}

private fun JSONObject.actionProbeMap(): Map<RouterWriteOperation, Set<String>> {
    val source = optJSONObject("actionProbes") ?: return emptyMap()
    return buildMap {
        listOf(
            RouterWriteOperation.REBOOT to "reboot",
            RouterWriteOperation.NETWORK_MODE to "networkMode",
            RouterWriteOperation.BAND_LOCK to "bandLock",
            RouterWriteOperation.NCK_ENTRY to "nck"
        ).forEach { (operation, key) ->
            val values = source.stringList(key).toSet()
            if (values.isNotEmpty()) put(operation, values)
        }
    }
}

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

private fun String.isKnownZte5gProfileFamily(): Boolean = listOf(
    "MC801", "MC888", "MC889", "MC7010", "MU5001"
).any { contains(it, true) }

private fun String.isKnownZte4gProfileFamily(): Boolean = listOf(
    "MF253", "MF283", "MF286", "MF289", "MF297"
).any { contains(it, true) }

private fun String.isKnownHuaweiBalongV4Family(): Boolean = listOf(
    "B310", "B311", "B315"
).any { contains(it, true) }

private fun String.isKnownHuaweiLegacyBalongFamily(): Boolean = listOf(
    "B593"
).any { contains(it, true) }

private fun String.isKnownHuaweiProfileFamily(): Boolean = listOf(
    "H158", "H155", "H138", "H122", "H112",
    "B818", "B716", "B715", "B628", "B618", "B612", "B535", "B528", "B525",
    "E5186", "E5577", "E5576"
).any { contains(it, true) }
