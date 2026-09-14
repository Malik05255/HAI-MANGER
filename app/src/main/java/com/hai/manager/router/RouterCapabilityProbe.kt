package com.hai.manager.router

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.math.BigInteger
import java.security.MessageDigest

enum class CapabilityProbeStatus(val displayName: String) {
    AVAILABLE("متاح"),
    AUTH_REQUIRED("يتطلب تسجيل الدخول"),
    NOT_EXPOSED("غير ظاهر"),
    FAILED("فشل الفحص")
}

data class CapabilityProbeItem(
    val id: String,
    val label: String,
    val status: CapabilityProbeStatus,
    val detail: String? = null
)

data class BandSelectionDiagnostics(
    val source: String,
    val networkBandRaw: String? = null,
    val lteBandMaskRaw: String? = null,
    val nrBandMaskRaw: String? = null,
    val decodedLteBands: List<Int> = emptyList(),
    val decodedNrBands: List<Int> = emptyList(),
    val advertisedLteBands: List<Int> = emptyList(),
    val note: String? = null
)

data class RouterCapabilityReport(
    val firmwareFingerprint: String,
    val items: List<CapabilityProbeItem>,
    val bandSelection: BandSelectionDiagnostics? = null,
    val networkLockReadable: Boolean = false,
    val nckEntryVerified: Boolean = false,
    val summary: String
)

class RouterCapabilityProbeService {
    suspend fun enrich(inspection: RouterInspection): RouterInspection {
        if (inspection.accessStatus != RouterAccessStatus.AVAILABLE) return inspection
        val baseUrl = inspection.snapshot.managementUrl ?: return inspection
        val client = RouterHttpClient(baseUrl)
        val report = when (inspection.snapshot.brand) {
            RouterBrand.ZTE -> probeZte(client, inspection)
            RouterBrand.HUAWEI -> probeHuawei(client, inspection)
            else -> null
        }
        return if (report == null) inspection else inspection.copy(probeReport = report)
    }

    private suspend fun probeZte(client: RouterHttpClient, inspection: RouterInspection): RouterCapabilityReport {
        val response = runCatching {
            client.get(
                "/goform/goform_get_cmd_process?isTest=false&cmd=" +
                    "wa_inner_version,cr_version,RD,BearerPreference,current_network_mode," +
                    "nr5g_band_mask,nr5g_band,nr5g_action_band,network_lock_status,network_lock," +
                    "network_unlock_remain_count,unlock_nck_time,wifiEnabled,SSID1&multi_data=1"
            )
        }.getOrNull()
        val json = response?.body?.let { runCatching { JSONObject(it) }.getOrNull() }
        val authRequired = response?.code == 401 || response?.code == 403 ||
            response?.body.orEmpty().contains("login", ignoreCase = true)

        fun value(vararg keys: String): String? {
            val source = json ?: return null
            return keys.firstNotNullOfOrNull { key ->
                source.optString(key, "").trim().takeIf(::meaningful)
            }
        }

        fun item(id: String, label: String, present: Boolean, detail: String? = null): CapabilityProbeItem {
            val status = when {
                authRequired -> CapabilityProbeStatus.AUTH_REQUIRED
                response == null -> CapabilityProbeStatus.FAILED
                present -> CapabilityProbeStatus.AVAILABLE
                else -> CapabilityProbeStatus.NOT_EXPOSED
            }
            return CapabilityProbeItem(id, label, status, detail)
        }

        val actionSeedReady = listOf(value("wa_inner_version"), value("cr_version"), value("RD")).all { it != null }
        val networkMode = value("BearerPreference", "current_network_mode")
        val nrRaw = value("nr5g_band_mask", "nr5g_band", "nr5g_action_band")
        val nrList = nrRaw?.let(::parseSimpleBandList).orEmpty()
        val lockState = value("network_lock_status", "network_lock")
        val lockAttempts = value("network_unlock_remain_count", "unlock_nck_time")
        val wifiVisible = value("wifiEnabled") != null || value("SSID1") != null

        val items = listOf(
            item("zte_action_seed", "مفتاح أوامر ZTE", actionSeedReady, if (actionSeedReady) "wa/cr/RD متاحة" else null),
            item("network_mode_read", "قراءة وضع الشبكة", networkMode != null, networkMode),
            item("nr_band_state", "قراءة حالة Band Lock", nrRaw != null, nrRaw),
            item("network_lock_read", "قراءة Network Lock", lockState != null || lockAttempts != null, listOfNotNull(lockState, lockAttempts?.let { "محاولات: $it" }).joinToString(" • ").ifBlank { null }),
            item("wifi_read", "قراءة Wi-Fi", wifiVisible),
            CapabilityProbeItem(
                id = "nck_write",
                label = "إدخال NCK",
                status = CapabilityProbeStatus.NOT_EXPOSED,
                detail = "لا يوجد endpoint كتابة موثق في Profile الحالي"
            )
        )

        return RouterCapabilityReport(
            firmwareFingerprint = fingerprint(inspection),
            items = items,
            bandSelection = BandSelectionDiagnostics(
                source = "ZTE goform",
                nrBandMaskRaw = nrRaw,
                decodedNrBands = nrList,
                note = if (nrRaw != null && nrList.isEmpty()) "القيمة موجودة لكن ترميزها غير قابل للتفسير الآمن؛ لا تستخدم للكتابة." else null
            ),
            networkLockReadable = lockState != null || lockAttempts != null,
            nckEntryVerified = false,
            summary = summary(items)
        )
    }

    private suspend fun probeHuawei(client: RouterHttpClient, inspection: RouterInspection): RouterCapabilityReport = coroutineScope {
        val modeDeferred = async { safeGet(client, "/api/net/net-mode") }
        val listDeferred = async { safeGet(client, "/api/net/net-mode-list") }
        val tokenDeferred = async { safeGet(client, "/api/webserver/SesTokInfo") }
        val pinDeferred = async { safeGet(client, "/api/pin/status") }
        val wifiDeferred = async { safeGet(client, "/api/wlan/basic-settings") }

        val mode = modeDeferred.await()
        val modeList = listDeferred.await()
        val token = tokenDeferred.await()
        val pin = pinDeferred.await()
        val wifi = wifiDeferred.await()

        fun status(response: RouterHttpResponse?, present: Boolean): CapabilityProbeStatus = when {
            response == null -> CapabilityProbeStatus.FAILED
            huaweiAuthRequired(response) -> CapabilityProbeStatus.AUTH_REQUIRED
            present -> CapabilityProbeStatus.AVAILABLE
            else -> CapabilityProbeStatus.NOT_EXPOSED
        }

        val networkMode = mode?.body?.let { xmlValue(it, "NetworkMode") }
        val networkBand = mode?.body?.let { xmlValue(it, "NetworkBand") }
        val lteMask = mode?.body?.let { xmlValue(it, "LTEBand") }
        val nrMask = mode?.body?.let { xmlValue(it, "NR5GBand", "NRBand") }
        val lteBands = decodeHuaweiLteMask(lteMask)
        val advertisedBands = modeList?.body?.let(::extractHuaweiAdvertisedLteBands).orEmpty()
        val tokenValue = token?.body?.let { xmlValue(it, "TokInfo") }
        val pinState = pin?.body?.let { xmlValue(it, "SimState", "PinOptState", "SimPinState") }
        val ssid = wifi?.body?.let { xmlValue(it, "SSID", "WifiSsid") }
        val wifiEnabled = wifi?.body?.let { xmlValue(it, "WifiEnable", "WiFiEnable") }
        val lockReadable = inspection.security?.let {
            !it.networkLockState.isNullOrBlank() || !it.unlockAttemptsRemaining.isNullOrBlank()
        } == true

        val items = listOf(
            CapabilityProbeItem("huawei_session_token", "جلسة HiLink", status(token, tokenValue != null), tokenValue?.let { "Token متاح" }),
            CapabilityProbeItem("network_mode_read", "قراءة وضع الشبكة", status(mode, networkMode != null), networkMode),
            CapabilityProbeItem("band_selection_read", "قراءة Band selection", status(mode, lteMask != null || nrMask != null || networkBand != null), listOfNotNull(lteMask?.let { "LTE=$it" }, nrMask?.let { "NR=$it" }).joinToString(" • ").ifBlank { null }),
            CapabilityProbeItem("mode_list", "قائمة النطاقات المعلنة", status(modeList, advertisedBands.isNotEmpty()), advertisedBands.takeIf { it.isNotEmpty() }?.joinToString(", ") { "B$it" }),
            CapabilityProbeItem("sim_security", "حالة SIM/PIN", status(pin, pinState != null), pinState),
            CapabilityProbeItem("wifi_read", "قراءة Wi-Fi", status(wifi, ssid != null || wifiEnabled != null), listOfNotNull(ssid, wifiEnabled?.let { "enabled=$it" }).joinToString(" • ").ifBlank { null }),
            CapabilityProbeItem("network_lock_read", "قراءة Network Lock", if (lockReadable) CapabilityProbeStatus.AVAILABLE else CapabilityProbeStatus.NOT_EXPOSED),
            CapabilityProbeItem("nck_write", "إدخال NCK", CapabilityProbeStatus.NOT_EXPOSED, "لا يوجد endpoint كتابة موثق في Profile الحالي")
        )

        RouterCapabilityReport(
            firmwareFingerprint = fingerprint(inspection),
            items = items,
            bandSelection = BandSelectionDiagnostics(
                source = "Huawei HiLink",
                networkBandRaw = networkBand,
                lteBandMaskRaw = lteMask,
                nrBandMaskRaw = nrMask,
                decodedLteBands = lteBands,
                advertisedLteBands = advertisedBands,
                note = "فك LTEBand تشخيصي فقط؛ لا يمنح صلاحية كتابة Band Lock."
            ),
            networkLockReadable = lockReadable,
            nckEntryVerified = false,
            summary = summary(items)
        )
    }

    private suspend fun safeGet(client: RouterHttpClient, path: String): RouterHttpResponse? =
        runCatching { client.get(path) }.getOrNull()

    private fun huaweiAuthRequired(response: RouterHttpResponse): Boolean {
        if (response.code == 401 || response.code == 403) return true
        val body = response.body.lowercase()
        return "125002" in body || "125003" in body || "100003" in body
    }

    private fun decodeHuaweiLteMask(raw: String?): List<Int> {
        val value = raw?.trim()?.removePrefix("0x")?.removePrefix("0X") ?: return emptyList()
        if (value.isBlank() || !value.matches(Regex("[0-9A-Fa-f]+"))) return emptyList()
        val mask = runCatching { BigInteger(value, 16) }.getOrNull() ?: return emptyList()
        if (mask.signum() < 0 || mask.bitLength() > 256) return emptyList()
        return buildList {
            for (bit in 0 until mask.bitLength()) {
                if (mask.testBit(bit)) add(bit + 1)
            }
        }
    }

    private fun extractHuaweiAdvertisedLteBands(xml: String): List<Int> {
        val regex = Regex("<LTEBand(?:\\s[^>]*)?>(.*?)</LTEBand>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return regex.findAll(xml)
            .flatMap { match -> decodeHuaweiLteMask(match.groupValues[1]).asSequence() }
            .distinct()
            .sorted()
            .toList()
    }

    private fun parseSimpleBandList(raw: String): List<Int> {
        val normalized = raw.trim().removePrefix("[").removeSuffix("]")
        if (!normalized.matches(Regex("[0-9]+(?:[,;+\\s]+[0-9]+)*"))) return emptyList()
        return normalized.split(',', '+', ';', ' ')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 1..261 }
            .distinct()
            .sorted()
    }

    private fun fingerprint(inspection: RouterInspection): String {
        val device = inspection.device
        val value = listOf(
            inspection.snapshot.brand.name,
            device?.model.orEmpty(),
            device?.firmwareVersion.orEmpty(),
            device?.hardwareVersion.orEmpty(),
            device?.webUiVersion.orEmpty()
        ).joinToString("|").uppercase()
        return MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02X".format(it) }
    }

    private fun summary(items: List<CapabilityProbeItem>): String {
        val available = items.count { it.status == CapabilityProbeStatus.AVAILABLE }
        val auth = items.count { it.status == CapabilityProbeStatus.AUTH_REQUIRED }
        val unavailable = items.size - available - auth
        return "$available متاح • $auth يحتاج دخول • $unavailable غير مثبت"
    }

    private fun meaningful(value: String): Boolean =
        value.isNotBlank() && value != "--" && value != "-" && !value.equals("null", true) && !value.equals("undefined", true)
}
