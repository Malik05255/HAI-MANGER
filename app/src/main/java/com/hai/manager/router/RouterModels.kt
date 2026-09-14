package com.hai.manager.router

enum class RouterBrand(val displayName: String) {
    ZTE("ZTE"), HUAWEI("Huawei"), NOKIA("Nokia"), NETGEAR("Netgear"),
    TP_LINK("TP-Link"), ZYXEL("Zyxel"), D_LINK("D-Link"), UNKNOWN("غير معروف")
}

data class RouterSnapshot(
    val connected: Boolean,
    val gateway: String? = null,
    val managementUrl: String? = null,
    val brand: RouterBrand = RouterBrand.UNKNOWN,
    val model: String? = null,
    val pageTitle: String? = null,
    val confidence: Int = 0,
    val message: String = ""
)

enum class RouterCapability(val displayName: String) {
    DEVICE_INFO("معلومات الجهاز"), CELLULAR_SIGNAL("إشارة الشبكة"), NETWORK_STATUS("حالة الشبكة"),
    SESSION_COOKIES("جلسة إدارة"), CA_DETAILS("تجميع الترددات CA"), NETWORK_MODE("أوضاع الشبكة"),
    BAND_LOCK("قفل النطاقات"), REBOOT("إعادة التشغيل"), WIFI_SETTINGS("إعدادات Wi-Fi"),
    FIRMWARE_INFO("معلومات النظام"), SIM_SECURITY("حالة SIM وقفل الشبكة")
}

enum class RouterAccessStatus { AVAILABLE, AUTH_REQUIRED, UNSUPPORTED, FAILED }

enum class NetworkMode(val displayName: String) {
    AUTO("تلقائي"), LTE_ONLY("4G فقط"), NR_LTE("5G / 4G"), NR_ONLY("5G فقط")
}

data class RouterDeviceInfo(
    val manufacturer: String? = null,
    val model: String? = null,
    val serialNumber: String? = null,
    val imei: String? = null,
    val firmwareVersion: String? = null,
    val hardwareVersion: String? = null,
    val webUiVersion: String? = null,
    val wanIp: String? = null
)

data class RouterSecurityInfo(
    val simState: String? = null,
    val pinState: String? = null,
    val pinAttemptsRemaining: String? = null,
    val pukAttemptsRemaining: String? = null,
    val networkLockState: String? = null,
    val unlockAttemptsRemaining: String? = null,
    val iccid: String? = null,
    val imsi: String? = null
) {
    val hasData: Boolean
        get() = listOf(simState, pinState, pinAttemptsRemaining, pukAttemptsRemaining, networkLockState, unlockAttemptsRemaining, iccid, imsi)
            .any { !it.isNullOrBlank() }
}

data class CellularSignal(
    val networkType: String? = null,
    val networkPreference: String? = null,
    val operatorName: String? = null,
    val rsrp: String? = null,
    val rsrq: String? = null,
    val sinr: String? = null,
    val rssi: String? = null,
    val bands: List<String> = emptyList(),
    val primaryBand: String? = null,
    val secondaryBands: List<String> = emptyList(),
    val nrBand: String? = null,
    val carrierAggregation: Boolean = false,
    val cellId: String? = null,
    val pci: String? = null,
    val earfcn: String? = null,
    val nrarfcn: String? = null
) {
    val hasData: Boolean
        get() = listOf(networkType, networkPreference, operatorName, rsrp, rsrq, sinr, rssi, cellId, pci, earfcn, nrarfcn)
            .any { !it.isNullOrBlank() } || bands.isNotEmpty()
}

data class RouterWifiInfo(
    val enabled: Boolean? = null,
    val ssid: String? = null,
    val hidden: Boolean? = null,
    val channel: String? = null,
    val mode: String? = null,
    val securityMode: String? = null,
    val canToggle: Boolean = false,
    val canRename: Boolean = false
) {
    val hasData: Boolean get() = enabled != null || !ssid.isNullOrBlank() || !channel.isNullOrBlank() || !mode.isNullOrBlank()
}

data class RouterInspection(
    val snapshot: RouterSnapshot,
    val accessStatus: RouterAccessStatus,
    val device: RouterDeviceInfo? = null,
    val signal: CellularSignal? = null,
    val security: RouterSecurityInfo? = null,
    val wifi: RouterWifiInfo? = null,
    val capabilities: Set<RouterCapability> = emptySet(),
    val supportedNetworkModes: Set<NetworkMode> = emptySet(),
    val probeReport: RouterCapabilityReport? = null,
    val message: String = ""
)

data class RouterActionResult(val success: Boolean, val message: String)

interface RouterAdapter {
    val brand: RouterBrand
    fun confidence(page: String, headers: Map<String, String>): Int
}

object ZteRouterAdapter : RouterAdapter {
    override val brand = RouterBrand.ZTE
    override fun confidence(page: String, headers: Map<String, String>): Int {
        val text = (page + headers.values.joinToString(" ")).lowercase()
        return when {
            "zte" in text -> 100
            listOf("mc888", "mc801", "mc889", "mc7010", "mf286", "mf289", "mf297").any { it in text } -> 95
            "goform" in text -> 70
            else -> 0
        }
    }
}

object HuaweiRouterAdapter : RouterAdapter {
    override val brand = RouterBrand.HUAWEI
    override fun confidence(page: String, headers: Map<String, String>): Int {
        val text = (page + headers.values.joinToString(" ")).lowercase()
        return when {
            "huawei" in text -> 100
            "hilink" in text -> 90
            "api/device/information" in text -> 80
            listOf("h155", "h158", "h122", "h112", "b818", "b535", "b525").any { it in text } -> 85
            else -> 0
        }
    }
}

/** المرحلة الأولى من HAI MANAGER تركز على Huawei وZTE فقط. */
object RouterAdapters {
    val all: List<RouterAdapter> = listOf(ZteRouterAdapter, HuaweiRouterAdapter)
}
