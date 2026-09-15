package com.hai.manager.router

/**
 * تقرير أخطاء قابل للمشاركة مع الدعم بدون نسخ معرفات الجهاز أو أسرار الجلسة.
 * الهدف منه تفسير سبب فشل التشخيص/الفك، وليس تسجيل بيانات المستخدم.
 */
object UnlockErrorReport {
    fun build(
        appVersion: String,
        phase: String,
        router: RouterSnapshot?,
        inspection: RouterInspection?,
        lock: RouterCarrierLockSummary?,
        message: String?
    ): String {
        val profile = inspection?.firmwareProfileInfo
        val report = inspection?.probeReport

        return buildString {
            appendLine("HAI MANAGER — error report")
            appendLine("app=$appVersion")
            appendLine("phase=$phase")
            appendLine("connected=${router?.connected ?: false}")
            appendLine("brand=${inspection?.snapshot?.brand?.displayName ?: router?.brand?.displayName ?: "unknown"}")
            appendLine("model=${inspection?.device?.model ?: router?.model ?: "unknown"}")
            appendLine("firmware=${inspection?.device?.firmwareVersion ?: "unknown"}")
            appendLine("hardware=${inspection?.device?.hardwareVersion ?: "unknown"}")
            appendLine("access=${inspection?.accessStatus?.name ?: "unknown"}")
            appendLine("networkType=${inspection?.signal?.networkType ?: "unknown"}")
            appendLine("lockState=${lock?.state?.name ?: "unknown"}")
            appendLine("waitingForNck=${lock?.waitingForNck ?: false}")
            appendLine("foreignSimTest=${lock?.requiresForeignSimTest ?: false}")
            appendLine("attempts=${lock?.attemptsRemaining ?: "unknown"}")
            appendLine("lockSource=${scrub(lock?.source) ?: "unknown"}")
            appendLine("lockDiagnostic=${scrub(lock?.diagnostic) ?: "none"}")
            appendLine("profile=${profile?.profileId ?: "unknown"}")
            appendLine("profileVerification=${profile?.verification?.name ?: "unknown"}")
            appendLine("nckSupport=${profile?.nckEntry?.name ?: "unknown"}")
            appendLine("nckTransportVerified=${report?.nckEntryVerified ?: false}")
            appendLine("networkLockReadable=${report?.networkLockReadable ?: false}")
            report?.items?.sortedBy { it.id }?.forEach { item ->
                appendLine("probe.${item.id}=${item.status.name}")
            }
            appendLine("inspectionMessage=${scrub(inspection?.message) ?: "none"}")
            appendLine("error=${scrub(message) ?: "none"}")
            appendLine("privacy=IMEI/Serial/IMSI/ICCID/SSID/NCK/AD/RD/tokens/cookies/passwords excluded")
        }.trim()
    }

    private fun scrub(value: String?): String? {
        var text = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        text = text.replace(
            Regex("(?i)(imei|imsi|iccid|serial|ssid|token|cookie|password|pin|puk|nck|\\bAD\\b|\\bRD\\b)\\s*[=:]\\s*[^\\s•,;]+"),
            "$1=<redacted>"
        )
        text = text.replace(Regex("\\b\\d{14,20}\\b"), "<redacted-id>")
        return text.take(500)
    }
}
