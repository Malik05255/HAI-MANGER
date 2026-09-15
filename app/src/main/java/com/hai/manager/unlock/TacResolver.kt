package com.hai.manager.unlock

data class TacDevice(
    val tac: String,
    val brand: UnlockBrand,
    val model: String,
    val generation: UnlockGeneration,
    val profile: String,
    val evidence: String
)

/**
 * Resolves router/CPE TAC before selecting an unlock strategy.
 *
 * A TAC identifies the device family only. It never proves that a specific NCK algorithm applies.
 */
object TacResolver {
    private val devices: Map<String, TacDevice>

    init {
        val duplicates = TacCatalogData.entries
            .groupBy(TacDevice::tac)
            .filterValues { it.size > 1 }
        check(duplicates.isEmpty()) { "Duplicate TAC entries: ${duplicates.keys.joinToString()}" }
        devices = TacCatalogData.entries.associateBy(TacDevice::tac)
    }

    fun resolve(imei: String): TacDevice? {
        val digits = imei.filter(Char::isDigit)
        if (digits.length < 8) return null
        return devices[digits.take(8)]
    }

    internal fun knownTacCount(): Int = devices.size
}
