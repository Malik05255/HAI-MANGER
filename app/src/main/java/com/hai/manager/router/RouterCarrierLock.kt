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
    val note: String? = null,
    val modemState: String? = null,
    val waitingForNck: Boolean = false,
    val lockedHplmns: String? = null,
    val routerImei: String? = null,
    val innerVersion: String? = null,
    val webVersion: String? = null,
    val nckRelatedValue: String? = null,
    val diagnostic: String? = null
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
            note = "HiLink يعلن حالة SIM lock وعدد المحاولات، لكنه لا يعلن دائمًا اسم المشغل الذي خُصص له القفل.",
            diagnostic = when (decodeLockState(raw)) {
                CarrierLockState.LOCKED -> "واجهة Huawei تعلن أن SIM/Network lock مفعّل."
                CarrierLockState.UNLOCKED -> "واجهة Huawei تعلن أن SIM/Network lock غير مفعّل."
                CarrierLockState.UNKNOWN -> "لم تحسم واجهة Huawei حالة Network lock من الحقول المقروءة."
            }
        )
    }

    private suspend fun probeZte(
        client: RouterHttpClient,
        inspection: RouterInspection
    ): RouterCarrierLockSummary {
        val response = runCatching {
            client.get(
                "/goform/goform_get_cmd_process?isTest=false&cmd=" +
                    "network_lock_status,network_lock,lock_status,network_unlock_remain_count," +
                    "unlock_nck_time,locked_hplmns,modem_main_state,network_provider,imei," +
                    "wa_inner_version,web_version&multi_data=1"
            )
        }.getOrNull()
        val json = response?.body?.let { runCatching { JSONObject(it) }.getOrNull() }

        fun value(key: String): String? = json
            ?.optString(key, "")
            ?.trim()
            ?.takeIf(::meaningfulLockValue)

        val rawSnapshot = ZteLockRawSnapshot(
            networkLockStatus = value("network_lock_status"),
            networkLock = value("network_lock"),
            lockStatus = value("lock_status"),
            networkUnlockRemainCount = value("network_unlock_remain_count"),
            unlockNckTime = value("unlock_nck_time"),
            lockedHplmns = value("locked_hplmns"),
            modemMainState = value("modem_main_state"),
            networkProvider = value("network_provider"),
            imei = value("imei"),
            innerVersion = value("wa_inner_version"),
            webVersion = value("web_version")
        )
        val interpreted = ZteLockDiagnosticsInterpreter.interpret(rawSnapshot)
        val fallbackRaw = inspection.security?.networkLockState
        val state = if (interpreted.state == CarrierLockState.UNKNOWN && fallbackRaw != null) {
            decodeLockState(fallbackRaw)
        } else interpreted.state
        val operator = rawSnapshot.networkProvider ?: inspection.signal?.operatorName

        return RouterCarrierLockSummary(
            state = state,
            currentOperator = operator,
            lockedOperator = null,
            attemptsRemaining = interpreted.attemptsRemaining,
            rawState = interpreted.rawState ?: fallbackRaw,
            source = "ZTE WebUI goform read-only lock diagnostics",
            note = "unlock_nck_time يُعرض كقيمة NCK خام فقط؛ لا يعتبره HAI عدد محاولات متبقية ما لم يكشف الراوتر network_unlock_remain_count صراحة.",
            modemState = interpreted.modemMainState,
            waitingForNck = interpreted.waitingForNck,
            lockedHplmns = interpreted.lockedHplmns,
            routerImei = rawSnapshot.imei,
            innerVersion = rawSnapshot.innerVersion,
            webVersion = rawSnapshot.webVersion,
            nckRelatedValue = interpreted.nckRelatedValue,
            diagnostic = interpreted.diagnostic
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
