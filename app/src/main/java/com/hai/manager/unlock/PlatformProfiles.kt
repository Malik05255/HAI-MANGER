package com.hai.manager.unlock

enum class PlatformConfidence(val displayName: String) {
    VERIFIED("موثق"),
    COMMUNITY("موثق مجتمعيًا"),
    VARIANT_DEPENDENT("يعتمد على النسخة")
}

data class ModemPlatformProfile(
    val name: String,
    val family: String,
    val confidence: PlatformConfidence,
    val models: String,
    val accessLayers: List<String>,
    val researchProjects: List<String>,
    val note: String
)

/**
 * Platform-first resolver for router research.
 *
 * This does not unlock a device. It identifies the modem/chipset family so HAI can select the
 * correct diagnostic/research path and avoid applying algorithms from an unrelated generation.
 */
object PlatformResolver {
    private val sdx55 = ModemPlatformProfile(
        name = "Qualcomm SDX55 / Snapdragon X55",
        family = "QUALCOMM-SDX55",
        confidence = PlatformConfidence.VERIFIED,
        models = "ZTE MC801A / MC7010 / MU5001",
        accessLayers = listOf("Web API/goform", "AT/QMI/DIAG", "EDL/Sahara/Firehose", "NV/EFS"),
        researchProjects = listOf(
            "nicjac/python-zte-mc801a",
            "stich86/ZTE-MC7010",
            "bkerler/edl",
            "iamromulan/qfenix",
            "quectel-official/QLog"
        ),
        note = "أقرب منصة مباشرة لـMC801A. ابدأ بواجهات Web/diagnostics للقراءة قبل أي مسار EDL أو كتابة NV."
    )

    private val sdx62 = ModemPlatformProfile(
        name = "Qualcomm SDX62 / Snapdragon X62",
        family = "QUALCOMM-SDX62",
        confidence = PlatformConfidence.VERIFIED,
        models = "ZTE MC888 / MC888D / MC888 Pro / MU5120",
        accessLayers = listOf("Web API/goform", "AT/QMI/MBIM", "MHI/PCIe", "DIAG", "EDL when a signed loader is available"),
        researchProjects = listOf(
            "iamromulan/quectel-rgmii-toolkit",
            "dr-dolomite/QManager-RM520N",
            "snowzach/quectool",
            "bkerler/Loaders#82",
            "iamromulan/qfenix",
            "quectel-official/QLog"
        ),
        note = "MC888 القياسي موثق على X62. الأجهزة الآمنة Secure Boot قد تحتاج loader موقّع، لذلك لا يفترض HAI توفر EDL write."
    )

    private val sdx65 = ModemPlatformProfile(
        name = "Qualcomm SDX65 / Snapdragon X65",
        family = "QUALCOMM-SDX65",
        confidence = PlatformConfidence.COMMUNITY,
        models = "ZTE MC888A / MC888A Ultra وبعض SKU من MC888 Ultra/MC889",
        accessLayers = listOf("Web API/goform", "AT/QMI/MBIM", "MHI/PCIe", "DIAG", "signed-loader EDL where available"),
        researchProjects = listOf(
            "bkerler/Loaders#82",
            "iamromulan/qfenix",
            "quectel-official/QLog",
            "bkerler/edl"
        ),
        note = "توجد اختلافات بين SKU والأسواق. لا تُثبت X65 من اسم MC888/MC889 وحده؛ استخدم Hardware/Firmware evidence."
    )

    private val sdx75 = ModemPlatformProfile(
        name = "Qualcomm SDX75 / Snapdragon X75 (SDXPINN)",
        family = "QUALCOMM-SDX75",
        confidence = PlatformConfidence.VERIFIED,
        models = "ZTE U60 Pro / MU5250",
        accessLayers = listOf("OpenWrt/ubus", "device REST services", "AT/QMI/DIAG", "MHI/PCIe", "EDL/Firehose research"),
        researchProjects = listOf(
            "jesther-ai/open-u60-pro",
            "amenekowo/mu5250_tweaking",
            "iamromulan/quectel-rgmii-toolkit (SDXPINN)",
            "iamromulan/qfenix",
            "qualcomm/qdlrs"
        ),
        note = "الجيل الأحدث. open-u60-pro يعطي مرجعًا مباشرًا لبنية ZTE/ZWRT وواجهات الجهاز الحديثة."
    )

    private fun balong(
        name: String,
        family: String,
        models: String,
        note: String
    ) = ModemPlatformProfile(
        name = name,
        family = family,
        confidence = PlatformConfidence.VERIFIED,
        models = models,
        accessLayers = listOf("HiLink/Web API", "AT", "Balong USB/fastboot", "NVRAM/firmware analysis"),
        researchProjects = listOf(
            "Huawei-LTE-routers-mods/README",
            "forth32/balongflash",
            "forth32/balong-usbdload",
            "forth32/balong-nvtool",
            "huawei-lte-api"
        ),
        note = note
    )

    private val balongV7R1 = balong(
        "Huawei Balong V7R1 / Hi6920",
        "HUAWEI-BALONG-V7R1",
        "E5172 / E5180 / B593s",
        "جيل Balong 4G أقدم؛ واجهات HiLink تختلف حسب Firmware."
    )
    private val balongV7R11 = balong(
        "Huawei Balong V7R11 / Hi6921",
        "HUAWEI-BALONG-V7R11",
        "B310 / B315s / E3372h / E8372h / E5573 / E5576",
        "هذه أهم عائلة لـB310/B315، وتشمل قفل V4 في بعض الـFirmware؛ لا تعتمد على Legacy NCK وحده."
    )
    private val balongV7R22 = balong(
        "Huawei Balong V7R22 / Hi6932",
        "HUAWEI-BALONG-V7R22",
        "B316 / B525 / B528 / B535 / E5785 / E5885",
        "عائلة 4G/4G+ واسعة؛ HiLink مفيد للتشخيص بينما NV/firmware يظل منفصلًا."
    )
    private val balongV7R5 = balong(
        "Huawei Balong V7R5 / Hi6950",
        "HUAWEI-BALONG-V7R5",
        "B612s / B618s / B715s",
        "جيل 4G+ أحدث من V7R22 ويجب فصله عن خوارزميات Huawei القديمة."
    )
    private val balongV7R65 = balong(
        "Huawei Balong V7R65 / Hi6965",
        "HUAWEI-BALONG-V7R65",
        "B625 / B818",
        "من أحدث عائلات Balong 4G؛ يعامل كمنصة مستقلة في التشخيص."
    )
    private val balong5000 = balong(
        "Huawei Balong 5000 / Hi9500",
        "HUAWEI-BALONG-5000",
        "H112 / H122 / E6878",
        "منصة Huawei 5G. لا تطبق عليها V1/V2/V201؛ استخدم HiLink/firmware/platform-specific research."
    )

    fun resolve(model: String?): ModemPlatformProfile? {
        val value = model.orEmpty().uppercase().replace("_", " ")
        if (value.isBlank()) return null

        return when {
            value.startsWith("MC801") || value.startsWith("MC7010") || value.startsWith("MU5001") -> sdx55

            value.startsWith("MC888A") -> sdx65
            value.startsWith("MC888 ULTRA") -> variantSdx62Or65(value)
            value.startsWith("MC889") -> variantSdx62Or65(value)
            value.startsWith("MC888") || value.startsWith("MU5120") -> sdx62

            value.startsWith("MU5250") || value.contains("U60 PRO") -> sdx75

            value.startsWith("B310") || value.startsWith("B315") || value.startsWith("E3372H") ||
                value.startsWith("E8372H") || value.startsWith("E5573") || value.startsWith("E5576") -> balongV7R11
            value.startsWith("B316") || value.startsWith("B525") || value.startsWith("B528") ||
                value.startsWith("B535") || value.startsWith("E5785") || value.startsWith("E5885") -> balongV7R22
            value.startsWith("B612") || value.startsWith("B618") || value.startsWith("B715") -> balongV7R5
            value.startsWith("B625") || value.startsWith("B818") -> balongV7R65
            value.startsWith("H112") || value.startsWith("H122") || value.startsWith("E6878") -> balong5000
            value.startsWith("E5172") || value.startsWith("E5180") || value.startsWith("B593") -> balongV7R1
            else -> null
        }
    }

    private fun variantSdx62Or65(model: String) = ModemPlatformProfile(
        name = "Qualcomm SDX62 / SDX65 — SKU dependent",
        family = "QUALCOMM-SDX62-OR-SDX65",
        confidence = PlatformConfidence.VARIANT_DEPENDENT,
        models = model,
        accessLayers = listOf("Web API/goform", "Hardware/Firmware fingerprint", "AT/QMI/DIAG"),
        researchProjects = listOf(
            "ZTE 4th Gen FWA platform documentation",
            "stich86 community MC888 hardware research",
            "bkerler/Loaders#82",
            "iamromulan/qfenix"
        ),
        note = "اسم المنتج وحده غير كافٍ لبعض Ultra/MC889 SKU. HAI يجب أن يثبت المنصة من HW/Firmware قبل اختيار مسار منخفض المستوى."
    )
}
