package com.hai.manager.router

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

enum class CarrierLockState(val displayName: String) {
    UNLOCKED("غير مقفل على مشغل"),
    LOCKED("مقفل على مشغل"),
    UNKNOWN("حالة القفل غير معروفة")
}

data class RouterCarrierLockSummary(
    val state: CarrierLockState,
    val currentOperator: String? = null,
    val lockedOperator: String? = null,
    val attemptsRemaining: String? = null,
    val rawState: String? = null,
    val source: String,
    val note: String? = null
)

class RouterCarrierLockProbeService {
    suspend fun probe(inspection: RouterInspection): RouterCarrierLockSummary? {
        val baseUrl = inspection.snapshot.managementUrl ?: return null
        if (inspection.snapshot.brand != RouterBrand.HUAWEI && inspection.snapshot.brand != RouterBrand.ZTE) return null
        val client = RouterHttpClient(baseUrl)
        return when (inspection.snapshot.brand) {
            RouterBrand.HUAWEI -> probeHuawei(client, inspection)
            RouterBrand.ZTE -> probeZte(client, inspection)
            else -> null
        }
    }

    private suspend fun probeHuawei(
        client: RouterHttpClient,
        inspection: RouterInspection
    ): RouterCarrierLockSummary = coroutineScope {
        val simLockDeferred = async { runCatching { client.get("/api/pin/simlock") }.getOrNull() }
        val convergedDeferred = async { runCatching { client.get("/api/monitoring/converged-status") }.getOrNull() }
        val plmnDeferred = async { runCatching { client.get("/api/net/current-plmn") }.getOrNull() }

        val simLock = simLockDeferred.await()
        val converged = convergedDeferred.await()
        val plmn = plmnDeferred.await()

        val raw = simLock?.body?.let { xmlValue(it, "SimLockEnable", "pSimLockEnable") }
            ?: converged?.body?.let { xmlValue(it, "SimLockEnable", "simlockStatus") }
            ?: inspection.security?.networkLockState
        val attempts = simLock?.body?.let { xmlValue(it, "SimLockRemainTimes", "pSimLockRemainTimes") }
            ?: inspection.security?.unlockAttemptsRemaining
        val currentOperator = plmn?.body?.let { xmlValue(it, "FullName", "ShortName", "Numeric") }
            ?: inspection.signal?.operatorName

        RouterCarrierLockSummary(
            state = decodeLockState(raw),
            currentOperator = currentOperator,
            lockedOperator = null,
            attemptsRemaining = attempts,
            rawState = raw,
            source = "Huawei HiLink /api/pin/simlock",
            note = "HiLink يعلن حالة SIM lock وعدد المحاولات، لكنه لا يعلن دائمًا اسم المشغل الذي خُصص له القفل."
        )
    }

    private suspend fun probeZte(
        client: RouterHttpClient,
        inspection: RouterInspection
    ): RouterCarrierLockSummary {
        val response = runCatching {
            client.get(
                "/goform/goform_get_cmd_process?isTest=false&cmd=" +
                    "network_lock_status,network_lock,network_unlock_remain_count,unlock_nck_time,network_provider&multi_data=1"
            )
        }.getOrNull()
        val json = response?.body?.let { runCatching { JSONObject(it) }.getOrNull() }

        fun value(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            json?.optString(key, "")?.trim()?.takeIf(::meaningfulLockValue)
        }

        val raw = value("network_lock_status", "network_lock") ?: inspection.security?.networkLockState
        val attempts = value("network_unlock_remain_count", "unlock_nck_time")
            ?: inspection.security?.unlockAttemptsRemaining
        val operator = value("network_provider") ?: inspection.signal?.operatorName

        return RouterCarrierLockSummary(
            state = decodeLockState(raw),
            currentOperator = operator,
            lockedOperator = null,
            attemptsRemaining = attempts,
            rawState = raw,
            source = "ZTE WebUI network lock diagnostics",
            note = "اسم الشبكة الحالية لا يعني بالضرورة أنها الشبكة التي قُفل عليها الراوتر؛ لا يعرض التطبيق اسم المشغل المقيد عليه إلا إذا كشفه WebUI صراحة."
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

    private fun meaningfulLockValue(value: String): Boolean =
        value.isNotBlank() && value != "--" && value != "-" &&
            !value.equals("null", true) && !value.equals("undefined", true)
}
