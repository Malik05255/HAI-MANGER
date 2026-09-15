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
 * Small curated router-only TAC seed list.
 *
 * The engine intentionally does not bundle a huge phone TAC database. Only router/CPE TACs that
 * have been cross-checked are added here. The list can grow independently from unlock algorithms.
 */
object TacResolver {
    private val devices = listOf(
        TacDevice(
            tac = "86946504",
            brand = UnlockBrand.HUAWEI,
            model = "B310s-22",
            generation = UnlockGeneration.FOUR_G_HILINK,
            profile = "HUAWEI-BALONG-4G-V4-AWARE",
            evidence = "Open TAC catalog / FCCID.io"
        ),
        TacDevice(
            tac = "86367104",
            brand = UnlockBrand.ZTE,
            model = "MC801A",
            generation = UnlockGeneration.FIVE_G,
            profile = "ZTE-5G-GOFORM-RUNTIME",
            evidence = "FCC MC801A test-device IMEI allocation"
        ),
        TacDevice(
            tac = "86694906",
            brand = UnlockBrand.ZTE,
            model = "MC888",
            generation = UnlockGeneration.FIVE_G,
            profile = "ZTE-5G-GOFORM-RUNTIME",
            evidence = "Open TAC catalog / FCCID.io"
        ),
        TacDevice(
            tac = "86156906",
            brand = UnlockBrand.ZTE,
            model = "MC888A Ultra",
            generation = UnlockGeneration.FIVE_G,
            profile = "ZTE-5G-GOFORM-RUNTIME",
            evidence = "Open TAC catalog / FCCID.io"
        ),
        TacDevice(
            tac = "86608608",
            brand = UnlockBrand.ZTE,
            model = "MC889 Ultra",
            generation = UnlockGeneration.FIVE_G,
            profile = "ZTE-5G-GOFORM-RUNTIME",
            evidence = "Open TAC catalog / FCCID.io"
        )
    ).associateBy { it.tac }

    fun resolve(imei: String): TacDevice? {
        val digits = imei.filter(Char::isDigit)
        if (digits.length < 8) return null
        return devices[digits.take(8)]
    }
}
