package com.hai.manager.unlock

/** Resolves a router model from TAC before selecting an unlock strategy. */
object ImeiUnlockFacade {
    fun analyze(
        rawImei: String,
        brandHint: UnlockBrand = UnlockBrand.AUTO,
        modelHint: String? = null
    ): ImeiUnlockReport {
        val imei = rawImei.filter(Char::isDigit).take(15)
        val tac = TacResolver.resolve(imei)

        if (tac != null && brandHint != UnlockBrand.AUTO && brandHint != tac.brand) {
            return ImeiUnlockEngine.analyze(imei, tac.brand, tac.model).copy(
                brand = tac.brand,
                generation = tac.generation,
                family = "تعارض في تعريف الجهاز",
                codes = emptyList(),
                warning = "TAC ${tac.tac} يطابق ${tac.brand.displayName} ${tac.model} لكن الشركة المختارة مختلفة. تم منع توليد أي كود حتى تصحيح الاختيار.",
                sourceNotes = listOf(
                    "TAC ${tac.tac} → ${tac.brand.displayName} ${tac.model} (${tac.evidence}).",
                    "Profile: ${tac.profile}."
                )
            )
        }

        val resolvedBrand = when {
            tac != null -> tac.brand
            brandHint != UnlockBrand.AUTO -> brandHint
            else -> UnlockBrand.AUTO
        }
        val resolvedModel = tac?.model ?: modelHint?.takeIf { it.isNotBlank() }
        var report = ImeiUnlockEngine.analyze(imei, resolvedBrand, resolvedModel)

        // A known router TAC is stronger evidence than a generic brand calculator. The TAC profile
        // can only restrict generation; it never upgrades an unverified algorithm to "verified".
        if (tac != null) {
            report = applyTacProfile(report, tac)
        }

        if (resolvedBrand == UnlockBrand.HUAWEI && resolvedModel.isNullOrBlank() && report.codes.isNotEmpty()) {
            report = report.copy(
                codes = report.codes.map { candidate ->
                    candidate.copy(
                        confidence = UnlockConfidence.FAMILY_ONLY,
                        note = "${candidate.note} لم يتم التعرف على الموديل من TAC، لذلك هذا مرشح لعائلة Huawei القديمة وليس ضمانًا لهذا الجهاز."
                    )
                },
                warning = "الموديل غير معروف من TAC. لا تستخدم أكواد Legacy على B-series أو 5G قبل التأكد من الموديل/الـFirmware."
            )
        }

        val normalizedModel = resolvedModel.orEmpty().uppercase()
        if (resolvedBrand == UnlockBrand.HUAWEI && listOf("B310", "B311", "B315").any(normalizedModel::startsWith)) {
            report = report.copy(
                family = "Huawei B310/B311/B315 — Balong 4G / V4-aware",
                codes = emptyList(),
                warning = "هذه العائلة قد تستخدم قفل Huawei V4 يعتمد على الـFirmware وAT/hash. لا يعرض HAI أكواد V1/V2/V201 ككود فك نهائي لها. عند الاتصال بالراوتر تُستخدم حالة SIM Lock والـFirmware لتحديد المسار الصحيح."
            )
        }

        // MediaTek CPE profiles are modern modem/NVRAM platforms. They must never fall through to
        // the legacy ZTE ZX297520V3 calculator just because the IMEI is valid and the TAC is absent.
        val platform = PlatformResolver.resolve(resolvedModel)
        val isMediaTek = platform?.family?.startsWith("MEDIATEK-") == true ||
            platform?.family == "ZTE-MC8512-PLATFORM-VARIANT"
        if (isMediaTek) {
            report = report.copy(
                generation = UnlockGeneration.FIVE_G,
                family = if (platform?.family == "ZTE-MC8512-PLATFORM-VARIANT") {
                    "ZTE MC8512 — platform must be fingerprinted"
                } else {
                    platform?.name ?: "MediaTek 5G CPE"
                },
                codes = emptyList(),
                warning = "منصة MediaTek حديثة/محتملة. لا يستخدم HAI خوارزمية ZTE القديمة ولا يولد NCK من IMEI. يجب إثبات الـHardware/Firmware ثم استخدام تشخيص SIMLOCK/NVRAM الخاص بالمنصة."
            )
        }

        if (tac == null) return report
        return report.copy(
            sourceNotes = listOf(
                "TAC ${tac.tac} → ${tac.brand.displayName} ${tac.model} (${tac.evidence}).",
                "Profile: ${tac.profile}."
            ) + report.sourceNotes
        )
    }

    private fun applyTacProfile(report: ImeiUnlockReport, tac: TacDevice): ImeiUnlockReport = when (tac.profile) {
        "HUAWEI-BALONG-4G-V4-AWARE" -> report.copy(
            brand = UnlockBrand.HUAWEI,
            generation = UnlockGeneration.FOUR_G_HILINK,
            family = "Huawei ${tac.model} — Balong 4G / V4-aware",
            codes = emptyList(),
            warning = "TAC معروف لهذه العائلة. يتم منع أكواد Legacy التلقائية لأن نسخًا من ${tac.model} تستخدم قفل V4/firmware-dependent. يلزم تحديد الـFirmware ومسار AT/HiLink قبل أي NCK."
        )

        "HUAWEI-HILINK-RUNTIME" -> report.copy(
            brand = UnlockBrand.HUAWEI,
            generation = tac.generation,
            family = "Huawei ${tac.model} — HiLink/Balong",
            codes = emptyList(),
            warning = if (tac.generation == UnlockGeneration.FIVE_G) {
                "تم التعرف على ${tac.model} من TAC. لا توجد خوارزمية IMEI→NCK موثقة لهذه المنصة؛ يدعم HAI التعرف والتشخيص فقط."
            } else {
                "تم التعرف على ${tac.model} من TAC. لا يتم تعميم V1/V2/V201 على هذا الـCPE دون توثيق جيل القفل والـFirmware."
            }
        )

        "ZTE-4G-GOFORM-READ" -> report.copy(
            brand = UnlockBrand.ZTE,
            generation = UnlockGeneration.FOUR_G_HILINK,
            family = "ZTE ${tac.model} — 4G goform",
            codes = emptyList(),
            warning = "تم التعرف على ${tac.model} من TAC. هذه العائلة لا تُعامل كـZX297520V3؛ يستخدم HAI goform/firmware diagnostics ولا يولد NCK تجريبيًا."
        )

        "ZTE-5G-GOFORM-RUNTIME" -> report.copy(
            brand = UnlockBrand.ZTE,
            generation = UnlockGeneration.FIVE_G,
            family = "ZTE ${tac.model} — Qualcomm 5G / goform",
            codes = emptyList(),
            warning = "تم التعرف على ${tac.model} من TAC. IMEI-only NCK غير مثبت لهذه المنصة؛ يستخدم HAI حالة SIM Lock وعداد NCK والـFirmware لتحديد المسار."
        )

        else -> report
    }
}
