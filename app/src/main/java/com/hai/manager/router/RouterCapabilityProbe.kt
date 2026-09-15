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
                    "lte_band_lock,lte_band_mask,lte_band,wan_active_band," +
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

        val wa = value("wa_inner_version")
        val cr = value("cr_version")
        val rd = value("RD")
        val actionSeedReady = wa != null && rd != null && ZteNckRuntime.computeAd(wa, cr, rd) != null
        val networkMode = value("BearerPreference", "current_network_mode")
        val lteRaw = value("lte_band_lock", "lte_band_mask", "lte_band", "wan_active_band")
        val lteList = lteRaw?.let(::parseSimpleBandList).orEmpty()
        val nrRaw = value("nr5g_band_mask", "nr5g_band", "nr5g_action_band")
        val nrList = nrRaw?.let(::parseSimpleBandList).orEmpty()
        val lockState = value("network_lock_status", "network_lock")
        val lockAttempts = value("network_unlock_remain_count")
        val wifiVisible = value("wifiEnabled") != null || value("SSID1") != null
        val lockReadable = lockState != null || lockAttempts != null
        val lockDetail = listOfNotNull(lockState, lockAttempts?.let { "محاولات: $it" })
            .joinToString(" • ")
            .takeIf { it.isNotBlank() }

        val model = inspection.device?.model ?: inspection.snapshot.model
        val nckEvidencePath = if (ZteNckRuntime.isMc801a(model)) {
            probeZteNckWebUi(client)
        } else {
            null
        }
        val nckEntryVerified = !authRequired && actionSeedReady && lockReadable && nckEvidencePath != null
        val nckItem = when {
            authRequired -> CapabilityProbeItem(
                "nck_write",
                "إدخال NCK",
                CapabilityProbeStatus.AUTH_REQUIRED,
                "سجّل الدخول أولًا"
            )
            nckEntryVerified -> CapabilityProbeItem(
                "nck_write",
                "إدخال NCK",
                CapabilityProbeStatus.AVAILABLE,
                "WebUI يعلن UNLOCK_NETWORK عبر $nckEvidencePath"
            )
            nckEvidencePath != null -> CapabilityProbeItem(
                "nck_write",
                "إدخال NCK",
                CapabilityProbeStatus.NOT_EXPOSED,
                "نموذج الفك موجود لكن بيانات الجلسة أو حالة القفل غير مكتملة"
            )
            else -> CapabilityProbeItem(
                "nck_write",
                "إدخال NCK",
                CapabilityProbeStatus.NOT_EXPOSED,
                "لم يجد HAI نموذج UNLOCK_NETWORK في WebUI لهذا Firmware"
            )
        }

        val items = listOf(
            item("zte_action_seed", "مفتاح أوامر ZTE", actionSeedReady, if (actionSeedReady) "AD قابل للاشتقاق" else null),
            item("network_mode_read", "قراءة وضع الشبكة", networkMode != null, networkMode),
            item("lte_band_state", "قراءة حالة LTE Band", lteRaw != null, lteRaw),
            item("nr_band_state", "قراءة حالة NR Band", nrRaw != null, nrRaw),
            item("network_lock_read", "قراءة Network Lock", lockReadable, lockDetail),
            item("wifi_read", "قراءة Wi-Fi", wifiVisible),
            nckItem
        )

        val ambiguousLte = lteRaw != null && lteList.isEmpty()
        val ambiguousNr = nrRaw != null && nrList.isEmpty()
        return RouterCapabilityReport(
            firmwareFingerprint = fingerprint(inspection),
            items = items,
            bandSelection = BandSelectionDiagnostics(
                source = "ZTE goform",
                lteBandMaskRaw = lteRaw,
                nrBandMaskRaw = nrRaw,
                decodedLteBands = lteList,
                decodedNrBands = nrList,
                note = when {
                    ambiguousLte || ambiguousNr -> "توجد قيمة Band غير قابلة للتفسير كقائمة نطاقات مباشرة؛ تبقى للـdiagnostics فقط ولا تستخدم للكتابة."
                    else -> null
                }
            ),
            networkLockReadable = lockReadable,
            nckEntryVerified = nckEntryVerified,
            summary = summary(items)
        )
    }

    private suspend fun probeZteNckWebUi(client: RouterHttpClient): String? {
        for (path in ZteNckRuntime.webUiCandidatePaths) {
            val response = runCatching { client.get(path) }.getOrNull() ?: continue
            if (!response.successful) continue
            if (ZteNckRuntime.exposesUnlockNetwork(response.body)) return path
        }
        return null
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
        val nrBands = nrMask?.let(::parseSimpleBandList).orEmpty()
        val advertisedBands = modeList?.body?.let(::extractHuaweiAdvertisedLteBands).orEmpty()
        val tokenValue = token?.body?.let { xmlValue(it, "TokInfo") }
        val pinState = pin?.body?.let { xmlValue(it, "SimState", "PinOptState", "SimPinState") }
        val ssid = wifi?.body?.let { xmlValue(it, "SSID", "WifiSsid") }
        val wifiEnabled = wifi?.body?.let { xmlValue(it, "WifiEnable", "WiFiEnable") }
        val lockReadable = inspection.security?.let {
            !it.networkLockState.isNullOrBlank() || !it.unlockAttemptsRemaining.isNullOrBlank()
        } == true
        val bandDetail = listOfNotNull(lteMask?.let { "LTE=$it" }, nrMask?.let { "NR=$it" })
            .joinToString(" • ")
            .takeIf { it.isNotBlank() }
        val wifiDetail = listOfNotNull(ssid, wifiEnabled?.let { "enabled=$it" })
            .joinToString(" • ")
            .takeIf { it.isNotBlank() }

        val items = listOf(
            CapabilityProbeItem("huawei_session_token", "جلسة HiLink", status(token, tokenValue != null), tokenValue?.let { "Token متاح" }),
            CapabilityProbeItem("network_mode_read", "قراءة وضع الشبكة", status(mode, networkMode != null), networkMode),
            CapabilityProbeItem("band_selection_read", "قراءة Band selection", status(mode, lteMask != null || nrMask != null || networkBand != null), bandDetail),
            CapabilityProbeItem("mode_list", "قائمة النطاقات المعلنة", status(modeList, advertisedBands.isNotEmpty()), advertisedBands.takeIf { it.isNotEmpty() }?.joinToString(", ") { "B$it" }),
            CapabilityProbeItem("sim_security", "حالة SIM/PIN", status(pin, pinState != null), pinState),
            CapabilityProbeItem("wifi_read", "قراءة Wi-Fi", status(wifi, ssid != null || wifiEnabled != null), wifiDetail),
            CapabilityProbeItem("network_lock_read", "قراءة Network Lock", if (lockReadable) CapabilityProbeStatus.AVAILABLE else CapabilityProbeStatus.NOT_EXPOSED),
            CapabilityProbeItem("nck_write", "إدخال NCK", CapabilityProbeStatus.NOT_EXPOSED, "لا يوجد endpoint كتابة موثق في Profile الحالي")
        )

        val nrAmbiguous = nrMask != null && nrBands.isEmpty()
        RouterCapabilityReport(
            firmwareFingerprint = fingerprint(inspection),
            items = items,
            bandSelection = BandSelectionDiagnostics(
                source = "Huawei HiLink",
                networkBandRaw = networkBand,
                lteBandMaskRaw = lteMask,
                nrBandMaskRaw = nrMask,
                decodedLteBands = lteBands,
                decodedNrBands = nrBands,
                advertisedLteBands = advertisedBands,
                note = if (nrAmbiguous) {
                    "LTEBand يُفك تشخيصيًا. NRBand موجود لكن ترميزه ليس قائمة واضحة، لذلك يبقى raw فقط ولا يمنح صلاحية كتابة."
                } else {
                    "فك LTEBand/NRBand تشخيصي فقط؛ لا يمنح صلاحية كتابة Band Lock."
                }
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
            .joinToString("") { byte -> "%02X".format(byte.toInt() and 0xFF) }
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
