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
        val resolvedBrand = when {
            brandHint != UnlockBrand.AUTO -> brandHint
            tac != null -> tac.brand
            else -> UnlockBrand.AUTO
        }
        val resolvedModel = modelHint?.takeIf { it.isNotBlank() } ?: tac?.model
        var report = ImeiUnlockEngine.analyze(imei, resolvedBrand, resolvedModel)

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

        if (tac == null) return report
        return report.copy(
            sourceNotes = listOf(
                "TAC ${tac.tac} → ${tac.brand.displayName} ${tac.model} (${tac.evidence}).",
                "Profile: ${tac.profile}."
            ) + report.sourceNotes
        )
    }
}
