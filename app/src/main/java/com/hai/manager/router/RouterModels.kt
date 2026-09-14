package com.hai.manager.router

enum class RouterBrand(val displayName: String) {
    ZTE("ZTE"),
    HUAWEI("Huawei"),
    NOKIA("Nokia"),
    NETGEAR("Netgear"),
    TP_LINK("TP-Link"),
    ZYXEL("Zyxel"),
    D_LINK("D-Link"),
    UNKNOWN("غير معروف")
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
            "mc888" in text || "mc801" in text -> 95
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
            "hilink" in text || "webui" in text -> 70
            else -> 0
        }
    }
}

object NokiaRouterAdapter : RouterAdapter {
    override val brand = RouterBrand.NOKIA
    override fun confidence(page: String, headers: Map<String, String>): Int =
        if ((page + headers.values.joinToString(" ")).contains("nokia", ignoreCase = true)) 100 else 0
}

object NetgearRouterAdapter : RouterAdapter {
    override val brand = RouterBrand.NETGEAR
    override fun confidence(page: String, headers: Map<String, String>): Int =
        if ((page + headers.values.joinToString(" ")).contains("netgear", ignoreCase = true)) 100 else 0
}

object TpLinkRouterAdapter : RouterAdapter {
    override val brand = RouterBrand.TP_LINK
    override fun confidence(page: String, headers: Map<String, String>): Int {
        val text = (page + headers.values.joinToString(" ")).lowercase()
        return if ("tp-link" in text || "tplink" in text) 100 else 0
    }
}

object RouterAdapters {
    val all: List<RouterAdapter> = listOf(
        ZteRouterAdapter,
        HuaweiRouterAdapter,
        NokiaRouterAdapter,
        NetgearRouterAdapter,
        TpLinkRouterAdapter
    )
}
