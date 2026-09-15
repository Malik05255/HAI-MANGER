package com.hai.manager.router

/**
 * Pure interpreter for read-only ZTE WebUI network-lock fields.
 *
 * Important: unlock_nck_time is preserved as an NCK-related raw value. It is NOT promoted to
 * "attempts remaining" because that semantic is firmware-dependent. Only
 * network_unlock_remain_count is treated as an explicit remaining-attempt counter.
 *
 * Some carrier MC801A builds (including STC B15) hide the explicit lock fields. For those builds
 * HAI can still prove the state safely when a SIM from another operator is inserted:
 * - modem_imsi_waitnck => locked
 * - foreign SIM + successful network registration => unlocked
 * With the carrier's own SIM, the state remains intentionally unguessed and the UI asks for a
 * one-time foreign-SIM check.
 */
data class ZteLockRawSnapshot(
    val networkLockStatus: String? = null,
    val networkLock: String? = null,
    val lockStatus: String? = null,
    val networkUnlockRemainCount: String? = null,
    val unlockNckTime: String? = null,
    val lockedHplmns: String? = null,
    val modemMainState: String? = null,
    val networkProvider: String? = null,
    val imei: String? = null,
    val innerVersion: String? = null,
    val webVersion: String? = null,
    val firmwareVersion: String? = null,
    val simHomePlmn: String? = null,
    val servingPlmn: String? = null,
    val pppStatus: String? = null,
    val networkType: String? = null
)

data class ZteLockInterpretation(
    val state: CarrierLockState,
    val rawState: String?,
    val attemptsRemaining: String?,
    val waitingForNck: Boolean,
    val lockedHplmns: String?,
    val modemMainState: String?,
    val nckRelatedValue: String?,
    val needsForeignSimTest: Boolean,
    val diagnostic: String
)

object ZteLockDiagnosticsInterpreter {
    private const val STC_SAUDI_PLMN = "42001"

    fun interpret(raw: ZteLockRawSnapshot): ZteLockInterpretation {
        val lockRaw = firstMeaningful(raw.networkLockStatus, raw.networkLock, raw.lockStatus)
        val modemState = meaningful(raw.modemMainState)
        val waitingForNck = modemState?.contains("waitnck", ignoreCase = true) == true ||
            modemState?.equals("modem_imsi_waitnck", ignoreCase = true) == true

        val explicitState = if (waitingForNck) CarrierLockState.LOCKED else decodeLockState(lockRaw)
        val attempts = meaningful(raw.networkUnlockRemainCount)
        val hplmns = meaningful(raw.lockedHplmns)
        val nckRaw = meaningful(raw.unlockNckTime)
        val brandedPlmn = brandedHomePlmn(raw.firmwareVersion ?: raw.innerVersion)
        val simHomePlmn = meaningful(raw.simHomePlmn)?.filter(Char::isDigit)?.takeIf { it.length >= 5 }?.take(5)
        val foreignSim = brandedPlmn != null && simHomePlmn != null && simHomePlmn != brandedPlmn
        val registered = hasLiveNetwork(raw.pppStatus, raw.networkType, raw.servingPlmn)
        val inferredUnlocked = explicitState == CarrierLockState.UNKNOWN && foreignSim && registered && !waitingForNck
        val finalState = if (inferredUnlocked) CarrierLockState.UNLOCKED else explicitState

        val currentLooksLikeBrandedOperator = when (brandedPlmn) {
            STC_SAUDI_PLMN -> raw.networkProvider.orEmpty().contains("stc", ignoreCase = true)
            else -> false
        }
        val needsForeignSimTest = finalState == CarrierLockState.UNKNOWN && brandedPlmn != null &&
            (simHomePlmn == null || simHomePlmn == brandedPlmn || currentLooksLikeBrandedOperator)

        val diagnostic = when {
            waitingForNck -> "المودم في حالة انتظار NCK؛ هذا دليل قوي على وجود Network/SIM personalization lock."
            finalState == CarrierLockState.UNLOCKED && inferredUnlocked ->
                "تم قبول شريحة من مشغل آخر واتصل المودم بالشبكة؛ الراوتر غير مقفل على المشغل الأصلي."
            finalState == CarrierLockState.LOCKED && attempts?.toIntOrNull() == 0 ->
                "القفل ظاهر وعداد المحاولات الصريح = 0؛ لا ترسل أي كود إضافي."
            finalState == CarrierLockState.LOCKED && attempts != null ->
                "القفل ظاهر، والواجهة تعرض $attempts محاولة متبقية."
            finalState == CarrierLockState.LOCKED ->
                "القفل ظاهر، لكن الواجهة لم تعرض عداد محاولات صريحًا."
            finalState == CarrierLockState.UNLOCKED ->
                "الواجهة تعلن أن Network/SIM lock غير مفعّل."
            needsForeignSimTest ->
                "هذا Firmware يخفي حالة القفل والشريحة الحالية من نفس المشغل؛ يلزم شريحة من مشغل آخر للتأكد بدون تخمين."
            hplmns != null ->
                "حالة القفل غير محسومة، لكن الواجهة كشفت locked_hplmns؛ يحتاج ذلك مطابقة مع Firmware/SKU."
            else -> "لم تكشف الواجهة حالة قفل حاسمة من الحقول المقروءة."
        }

        return ZteLockInterpretation(
            state = finalState,
            rawState = lockRaw,
            attemptsRemaining = attempts,
            waitingForNck = waitingForNck,
            lockedHplmns = hplmns,
            modemMainState = modemState,
            nckRelatedValue = nckRaw,
            needsForeignSimTest = needsForeignSimTest,
            diagnostic = diagnostic
        )
    }

    private fun brandedHomePlmn(firmware: String?): String? {
        val value = firmware.orEmpty().uppercase()
        return when {
            "SASTC" in value -> STC_SAUDI_PLMN
            else -> null
        }
    }

    private fun hasLiveNetwork(pppStatus: String?, networkType: String?, servingPlmn: String?): Boolean {
        val ppp = meaningful(pppStatus)?.lowercase()
        if (ppp == "ppp_connected" || ppp == "connected") return true

        val type = meaningful(networkType)?.lowercase()
        val serviceType = type != null && listOf(
            "no_service", "no service", "limited_service", "limited service", "searching"
        ).none { marker -> marker in type }
        val serving = meaningful(servingPlmn)?.filter(Char::isDigit)?.length?.let { it >= 5 } == true
        return serviceType && serving
    }

    private fun decodeLockState(raw: String?): CarrierLockState {
        val value = raw?.trim()?.lowercase() ?: return CarrierLockState.UNKNOWN
        return when (value) {
            "0", "off", "disabled", "disable", "unlocked", "unlock", "false", "open" -> CarrierLockState.UNLOCKED
            "1", "on", "enabled", "enable", "locked", "lock", "true" -> CarrierLockState.LOCKED
            else -> CarrierLockState.UNKNOWN
        }
    }

    private fun firstMeaningful(vararg values: String?): String? = values.firstNotNullOfOrNull(::meaningful)

    private fun meaningful(value: String?): String? = value
        ?.trim()
        ?.takeIf {
            it.isNotBlank() && it != "--" && it != "-" &&
                !it.equals("null", true) && !it.equals("undefined", true)
        }
}
