package com.hai.manager.router

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
    get() = RouterFirmwareProfiles.resolve(
        brand = snapshot.brand,
        model = device?.model ?: snapshot.model,
        firmware = device?.firmwareVersion,
        capabilities = capabilities
    )

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

private fun String.isKnownZte5gProfileFamily(): Boolean =
    contains("MC801", true) || contains("MC888", true) || contains("MC889", true) || contains("MC7010", true)

private fun String.isKnownZte4gProfileFamily(): Boolean =
    contains("MF286", true) || contains("MF289", true) || contains("MF297", true)

private fun String.isKnownHuaweiProfileFamily(): Boolean = listOf(
    "H158", "H155", "H138", "H122", "H112", "B818", "B715", "B628", "B535", "B525"
).any { contains(it, true) }
