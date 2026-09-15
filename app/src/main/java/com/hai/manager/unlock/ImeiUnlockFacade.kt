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
        val report = ImeiUnlockEngine.analyze(imei, resolvedBrand, resolvedModel)
        if (tac == null) return report
        return report.copy(
            sourceNotes = listOf(
                "TAC ${tac.tac} → ${tac.brand.displayName} ${tac.model} (${tac.evidence}).",
                "Profile: ${tac.profile}."
            ) + report.sourceNotes
        )
    }
}
