package com.hai.manager.router

/**
 * تقرير قابل للمشاركة لتوثيق Firmware بدون تضمين معرفات الجهاز أو الشبكة الشخصية.
 * لا يضم IMEI/SN/IMSI/ICCID/WAN IP/SSID/Gateway أو أي token/cookie.
 */
object RouterDiagnosticsReport {
    fun build(inspection: RouterInspection): String {
        val device = inspection.device
        val profile = inspection.firmwareProfileInfo
        val readiness = inspection.profileProbeReadiness
        val report = inspection.probeReport
        val band = report?.bandSelection

        return buildString {
            appendLine("HAI MANAGER — Huawei/ZTE diagnostic")
            appendLine("privacy=sanitized")
            appendLine("brand=${inspection.snapshot.brand.displayName}")
            appendLine("model=${device?.model ?: inspection.snapshot.model ?: "unknown"}")
            appendLine("firmware=${device?.firmwareVersion ?: "unknown"}")
            appendLine("hardware=${device?.hardwareVersion ?: "unknown"}")
            appendLine("webui=${device?.webUiVersion ?: "unknown"}")
            appendLine("access=${inspection.accessStatus.name}")
            appendLine("profile=${profile.profileId}")
            appendLine("verification=${profile.verification.name}")
            appendLine("bandLock=${profile.bandLock.name}")
            appendLine("nck=${profile.nckEntry.name}")
            appendLine("writeReady=${readiness.ready}")
            if (readiness.required.isNotEmpty()) {
                appendLine("requiredProbes=${readiness.required.sorted().joinToString(",")}")
            }
            if (readiness.missing.isNotEmpty()) {
                appendLine("missingProbes=${readiness.missing.sorted().joinToString(",")}")
            }

            report?.let {
                appendLine("fingerprint=${it.firmwareFingerprint}")
                appendLine("probeSummary=${it.summary}")
                it.items.sortedBy { item -> item.id }.forEach { item ->
                    append("probe.${item.id}=${item.status.name}")
                    sanitizedProbeDetail(item)?.let { detail -> append(" [$detail]") }
                    appendLine()
                }
                appendLine("networkLockReadable=${it.networkLockReadable}")
                appendLine("nckEntryVerified=${it.nckEntryVerified}")
            }

            band?.let {
                appendLine("band.source=${it.source}")
                sanitizedRaw(it.networkBandRaw)?.let { value -> appendLine("band.networkBand=$value") }
                sanitizedRaw(it.lteBandMaskRaw)?.let { value -> appendLine("band.lteMask=$value") }
                sanitizedRaw(it.nrBandMaskRaw)?.let { value -> appendLine("band.nrMask=$value") }
                if (it.decodedLteBands.isNotEmpty()) appendLine("band.lteDecoded=${it.decodedLteBands.joinToString(",")}")
                if (it.decodedNrBands.isNotEmpty()) appendLine("band.nrDecoded=${it.decodedNrBands.joinToString(",")}")
                if (it.advertisedLteBands.isNotEmpty()) appendLine("band.lteAdvertised=${it.advertisedLteBands.joinToString(",")}")
            }

            appendLine("redacted=imei,serial,imsi,iccid,wan_ip,ssid,gateway,cell_id,pci,earfcn,nrarfcn,tokens,cookies")
        }.trim()
    }

    private fun sanitizedProbeDetail(item: CapabilityProbeItem): String? {
        val detail = item.detail?.trim()?.takeIf { it.isNotBlank() } ?: return null
        // لا نشارك أي نص قد يبدو كقيمة اعتماد/جلسة. حالة توفر الـToken تكفي للتشخيص.
        if (item.id == "huawei_session_token") return "token_available=${item.status == CapabilityProbeStatus.AVAILABLE}"
        if (item.id == "wifi_read") return null // قد يحتوي SSID.
        return detail
            .replace(Regex("(?i)(imei|imsi|iccid|serial|ssid|token|cookie|password|pin|puk)\\s*[=:]\\s*[^\\s•,]+"), "$1=<redacted>")
            .take(240)
    }

    private fun sanitizedRaw(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotBlank() && it.length <= 256 }
}
