package com.hai.manager.router

/**
 * Pure interpreter for the read-only ZTE WebUI network-lock fields.
 *
 * Important: unlock_nck_time is preserved as an NCK-related raw value. It is NOT promoted to
 * "attempts remaining" because that semantic is firmware-dependent. Only
 * network_unlock_remain_count is treated as an explicit remaining-attempt counter.
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
    val webVersion: String? = null
)

data class ZteLockInterpretation(
    val state: CarrierLockState,
    val rawState: String?,
    val attemptsRemaining: String?,
    val waitingForNck: Boolean,
    val lockedHplmns: String?,
    val modemMainState: String?,
    val nckRelatedValue: String?,
    val diagnostic: String
)

object ZteLockDiagnosticsInterpreter {
    fun interpret(raw: ZteLockRawSnapshot): ZteLockInterpretation {
        val lockRaw = firstMeaningful(raw.networkLockStatus, raw.networkLock, raw.lockStatus)
        val modemState = meaningful(raw.modemMainState)
        val waitingForNck = modemState?.contains("waitnck", ignoreCase = true) == true ||
            modemState?.equals("modem_imsi_waitnck", ignoreCase = true) == true

        val decoded = if (waitingForNck) CarrierLockState.LOCKED else decodeLockState(lockRaw)
        val attempts = meaningful(raw.networkUnlockRemainCount)
        val hplmns = meaningful(raw.lockedHplmns)
        val nckRaw = meaningful(raw.unlockNckTime)

        val diagnostic = when {
            waitingForNck -> "المودم في حالة انتظار NCK؛ هذا دليل قوي على وجود Network/SIM personalization lock."
            decoded == CarrierLockState.LOCKED && attempts?.toIntOrNull() == 0 ->
                "القفل ظاهر وعداد المحاولات الصريح = 0؛ لا ترسل أي كود إضافي."
            decoded == CarrierLockState.LOCKED && attempts != null ->
                "القفل ظاهر، والواجهة تعرض $attempts محاولة متبقية."
            decoded == CarrierLockState.LOCKED ->
                "القفل ظاهر، لكن الواجهة لم تعرض عداد محاولات صريحًا."
            decoded == CarrierLockState.UNLOCKED ->
                "الواجهة تعلن أن Network/SIM lock غير مفعّل."
            hplmns != null ->
                "حالة القفل غير محسومة، لكن الواجهة كشفت locked_hplmns؛ يحتاج ذلك مطابقة مع Firmware/SKU."
            else -> "لم تكشف الواجهة حالة قفل حاسمة من الحقول المقروءة."
        }

        return ZteLockInterpretation(
            state = decoded,
            rawState = lockRaw,
            attemptsRemaining = attempts,
            waitingForNck = waitingForNck,
            lockedHplmns = hplmns,
            modemMainState = modemState,
            nckRelatedValue = nckRaw,
            diagnostic = diagnostic
        )
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
