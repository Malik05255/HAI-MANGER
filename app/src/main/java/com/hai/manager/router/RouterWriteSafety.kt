package com.hai.manager.router

enum class RouterWriteOperation(val displayName: String) {
    REBOOT("إعادة التشغيل"),
    NETWORK_MODE("تغيير وضع الشبكة"),
    BAND_LOCK("قفل النطاقات"),
    NCK_ENTRY("إدخال NCK")
}

object RouterWriteSafety {
    fun validate(inspection: RouterInspection, operation: RouterWriteOperation): RouterActionResult? {
        if (inspection.accessStatus != RouterAccessStatus.AVAILABLE) {
            return RouterActionResult(false, "الراوتر غير جاهز لتنفيذ ${operation.displayName}; أعد الفحص بعد تسجيل الدخول")
        }

        val report = inspection.probeReport
            ?: return RouterActionResult(false, "يجب إكمال Capability Probe قبل تنفيذ ${operation.displayName}")

        val required = requiredProbeIds(inspection, operation)
        if (required.isEmpty()) return null

        val byId = report.items.associateBy { it.id }
        val missing = required.filter { id -> byId[id]?.status != CapabilityProbeStatus.AVAILABLE }
        if (missing.isEmpty()) return null

        val details = missing.joinToString("، ") { id ->
            val item = byId[id]
            when {
                item == null -> id
                item.status == CapabilityProbeStatus.AUTH_REQUIRED -> "${item.label}: تسجيل الدخول مطلوب"
                item.status == CapabilityProbeStatus.FAILED -> "${item.label}: فشل الفحص"
                else -> "${item.label}: غير مثبت"
            }
        }
        return RouterActionResult(
            false,
            "تم حظر ${operation.displayName} لأن شروط الأمان لم تكتمل: $details"
        )
    }

    private fun requiredProbeIds(
        inspection: RouterInspection,
        operation: RouterWriteOperation
    ): Set<String> {
        val profileSpecific = inspection.firmwareProfileInfo.actionProbes[operation].orEmpty()
        if (profileSpecific.isNotEmpty()) return profileSpecific

        return when (operation) {
            RouterWriteOperation.REBOOT -> when (inspection.snapshot.brand) {
                RouterBrand.ZTE -> setOf("zte_action_seed")
                RouterBrand.HUAWEI -> setOf("huawei_session_token")
                else -> emptySet()
            }

            RouterWriteOperation.NETWORK_MODE -> when (inspection.snapshot.brand) {
                RouterBrand.ZTE -> setOf("zte_action_seed", "network_mode_read")
                RouterBrand.HUAWEI -> setOf("huawei_session_token", "network_mode_read", "band_selection_read")
                else -> emptySet()
            }

            RouterWriteOperation.BAND_LOCK -> when (inspection.snapshot.brand) {
                RouterBrand.ZTE -> setOf("zte_action_seed", "network_mode_read", "nr_band_state")
                RouterBrand.HUAWEI -> setOf("huawei_session_token", "network_mode_read", "band_selection_read")
                else -> emptySet()
            }

            RouterWriteOperation.NCK_ENTRY -> when (inspection.snapshot.brand) {
                RouterBrand.ZTE, RouterBrand.HUAWEI -> setOf("network_lock_read", "nck_write")
                else -> emptySet()
            }
        }
    }
}
