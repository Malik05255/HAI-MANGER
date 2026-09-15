package com.hai.manager.router

enum class Mc801aNckReadiness(val displayName: String) {
    NOT_APPLICABLE("ليس MC801A"),
    NEEDS_LOGIN("يتطلب تسجيل الدخول"),
    UNLOCKED("الراوتر مفتوح"),
    ATTEMPTS_EXHAUSTED("المحاولات منتهية"),
    PROBE_INCOMPLETE("التشخيص غير مكتمل"),
    ACTION_SEED_NOT_READY("مفتاح الأوامر غير جاهز"),
    INSERT_OTHER_SIM("ركّب شريحة من شركة أخرى ثم أعد التشخيص"),
    LOCK_STATE_NOT_READABLE("حالة القفل غير قابلة للقراءة"),
    WEBUI_PATH_NOT_FOUND("مسار NCK غير مثبت في WebUI"),
    READY_FOR_NCK("جاهز لإدخال NCK")
}

data class Mc801aDiagnosticReport(
    val applicable: Boolean,
    val model: String,
    val firmware: String,
    val hardware: String,
    val webUi: String,
    val firmwareFingerprint: String,
    val lockState: CarrierLockState,
    val attemptsRemaining: String?,
    val waitingForNck: Boolean,
    val actionSeedStatus: CapabilityProbeStatus?,
    val networkLockStatus: CapabilityProbeStatus?,
    val nckWriteStatus: CapabilityProbeStatus?,
    val nckEvidence: String?,
    val readiness: Mc801aNckReadiness
) {
    fun shareText(): String = buildString {
        appendLine("HAI MANAGER — MC801A diagnostic")
        appendLine("Model: $model")
        appendLine("Firmware: $firmware")
        appendLine("Hardware: $hardware")
        appendLine("WebUI: $webUi")
        appendLine("Firmware fingerprint: $firmwareFingerprint")
        appendLine("Network lock: ${lockState.name}")
        appendLine("Attempts remaining: ${attemptsRemaining ?: "unknown"}")
        appendLine("Waiting for NCK: $waitingForNck")
        appendLine("ZTE action seed: ${actionSeedStatus?.name ?: "UNKNOWN"}")
        appendLine("Network lock probe: ${networkLockStatus?.name ?: "UNKNOWN"}")
        appendLine("NCK write probe: ${nckWriteStatus?.name ?: "UNKNOWN"}")
        appendLine("NCK evidence: ${nckEvidence ?: "none"}")
        appendLine("Readiness: ${readiness.name}")
        append("Privacy: IMEI/Serial/ICCID/IMSI/SSID/AD/RD are intentionally excluded.")
    }
}

object Mc801aDiagnosticReporter {
    fun build(
        inspection: RouterInspection,
        lockSummary: RouterCarrierLockSummary?
    ): Mc801aDiagnosticReport {
        val model = inspection.device?.model ?: inspection.snapshot.model ?: "MC801A"
        val applicable = inspection.snapshot.brand == RouterBrand.ZTE &&
            model.contains("MC801A", ignoreCase = true)
        val report = inspection.probeReport
        val actionSeed = report.item("zte_action_seed")
        val networkLock = report.item("network_lock_read")
        val nckWrite = report.item("nck_write")
        val attempts = lockSummary?.attemptsRemaining ?: inspection.security?.unlockAttemptsRemaining
        val lockState = lockSummary?.state ?: decodeFallbackLock(inspection.security?.networkLockState)
        val waitingForNck = lockSummary?.waitingForNck == true

        val readiness = when {
            !applicable -> Mc801aNckReadiness.NOT_APPLICABLE
            inspection.accessStatus == RouterAccessStatus.AUTH_REQUIRED -> Mc801aNckReadiness.NEEDS_LOGIN
            lockState == CarrierLockState.UNLOCKED -> Mc801aNckReadiness.UNLOCKED
            attempts?.trim()?.toIntOrNull() == 0 -> Mc801aNckReadiness.ATTEMPTS_EXHAUSTED
            report == null -> Mc801aNckReadiness.PROBE_INCOMPLETE
            actionSeed?.status != CapabilityProbeStatus.AVAILABLE -> Mc801aNckReadiness.ACTION_SEED_NOT_READY
            !report.nckEntryVerified || nckWrite?.status != CapabilityProbeStatus.AVAILABLE ->
                Mc801aNckReadiness.WEBUI_PATH_NOT_FOUND
            lockState == CarrierLockState.UNKNOWN && !waitingForNck ->
                Mc801aNckReadiness.INSERT_OTHER_SIM
            lockState != CarrierLockState.LOCKED ->
                Mc801aNckReadiness.LOCK_STATE_NOT_READABLE
            else -> Mc801aNckReadiness.READY_FOR_NCK
        }

        return Mc801aDiagnosticReport(
            applicable = applicable,
            model = model,
            firmware = inspection.device?.firmwareVersion ?: lockSummary?.innerVersion ?: "غير معروف",
            hardware = inspection.device?.hardwareVersion ?: "غير معروف",
            webUi = inspection.device?.webUiVersion ?: lockSummary?.webVersion ?: "غير معروف",
            firmwareFingerprint = report?.firmwareFingerprint ?: "غير متوفر",
            lockState = lockState,
            attemptsRemaining = attempts,
            waitingForNck = waitingForNck,
            actionSeedStatus = actionSeed?.status,
            networkLockStatus = networkLock?.status,
            nckWriteStatus = nckWrite?.status,
            nckEvidence = nckWrite?.detail,
            readiness = readiness
        )
    }

    private fun RouterCapabilityReport?.item(id: String): CapabilityProbeItem? =
        this?.items?.firstOrNull { it.id == id }

    private fun decodeFallbackLock(raw: String?): CarrierLockState = when (raw?.trim()?.lowercase()) {
        "0", "off", "disabled", "disable", "unlocked", "unlock", "false", "open" -> CarrierLockState.UNLOCKED
        "1", "on", "enabled", "enable", "locked", "lock", "true" -> CarrierLockState.LOCKED
        else -> CarrierLockState.UNKNOWN
    }
}
