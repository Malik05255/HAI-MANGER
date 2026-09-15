package com.hai.manager.unlock

/**
 * Router/CPE TAC data only. Keep identification data separate from unlock logic so the catalog can
 * grow without changing strategy selection code.
 */
internal object TacCatalogData {
    private fun tacEntries(
        brand: UnlockBrand,
        model: String,
        generation: UnlockGeneration,
        profile: String,
        evidence: String,
        vararg tacs: String
    ): List<TacDevice> = tacs.map { tac ->
        TacDevice(
            tac = tac,
            brand = brand,
            model = model,
            generation = generation,
            profile = profile,
            evidence = evidence
        )
    }

    val entries: List<TacDevice> = buildList {
        // Huawei B-series / Balong 4G.
        addAll(
            tacEntries(
                UnlockBrand.HUAWEI,
                "B310s",
                UnlockGeneration.FOUR_G_HILINK,
                "HUAWEI-BALONG-4G-V4-AWARE",
                "Vodafone supported modem TAC list",
                "86846503", "86360503", "86778702", "86382402", "86872502", "86606002",
                "86978702", "86394203", "86686202", "86009102", "86705802", "86803100"
            )
        )
        addAll(
            tacEntries(
                UnlockBrand.HUAWEI,
                "B310s-22",
                UnlockGeneration.FOUR_G_HILINK,
                "HUAWEI-BALONG-4G-V4-AWARE",
                "FCCID.io / MoazEb TAC database",
                "86946504"
            )
        )
        addAll(
            tacEntries(
                UnlockBrand.HUAWEI,
                "B311",
                UnlockGeneration.FOUR_G_HILINK,
                "HUAWEI-BALONG-4G-V4-AWARE",
                "Vodafone supported modem TAC list",
                "86702403"
            )
        )
        addAll(
            tacEntries(
                UnlockBrand.HUAWEI,
                "B311-221",
                UnlockGeneration.FOUR_G_HILINK,
                "HUAWEI-BALONG-4G-V4-AWARE",
                "Vodafone supported modem TAC list",
                "86954704", "86918004"
            )
        )
        addAll(
            tacEntries(
                UnlockBrand.HUAWEI,
                "B315s-22",
                UnlockGeneration.FOUR_G_HILINK,
                "HUAWEI-BALONG-4G-V4-AWARE",
                "Vodafone supported modem TAC list",
                "86616902", "86262103"
            )
        )

        // Huawei fixed/mobile LTE families that must not fall through to generic Legacy calculators.
        addAll(
            tacEntries(
                UnlockBrand.HUAWEI,
                "E5180",
                UnlockGeneration.FOUR_G_HILINK,
                "HUAWEI-HILINK-RUNTIME",
                "Vodafone supported modem TAC list",
                "86491902"
            )
        )
        addAll(
            tacEntries(
                UnlockBrand.HUAWEI,
                "E5577",
                UnlockGeneration.FOUR_G_HILINK,
                "HUAWEI-HILINK-RUNTIME",
                "FCCID.io / MoazEb TAC database",
                "00440172"
            )
        )

        // Huawei 5G CPE.
        addAll(
            tacEntries(
                UnlockBrand.HUAWEI,
                "H122-373",
                UnlockGeneration.FIVE_G,
                "HUAWEI-HILINK-RUNTIME",
                "Vodafone supported modem TAC list",
                "86688704"
            )
        )

        // ZTE 4G CPE families. These are goform/firmware-profile devices, not ZX297520V3 by default.
        addAll(
            tacEntries(
                UnlockBrand.ZTE,
                "MF286C",
                UnlockGeneration.FOUR_G_HILINK,
                "ZTE-4G-GOFORM-READ",
                "FCCID.io / MoazEb TAC database",
                "86097804"
            )
        )
        addAll(
            tacEntries(
                UnlockBrand.ZTE,
                "MF289F",
                UnlockGeneration.FOUR_G_HILINK,
                "ZTE-4G-GOFORM-READ",
                "FCC RF test IMEI allocation",
                "86478105"
            )
        )
        addAll(
            tacEntries(
                UnlockBrand.ZTE,
                "MF289F Pro",
                UnlockGeneration.FOUR_G_HILINK,
                "ZTE-4G-GOFORM-READ",
                "FCCID.io / MoazEb TAC database",
                "86477806"
            )
        )
        addAll(
            tacEntries(
                UnlockBrand.ZTE,
                "MF297D",
                UnlockGeneration.FOUR_G_HILINK,
                "ZTE-4G-GOFORM-READ",
                "Swappa TAC index",
                "86395505"
            )
        )
        addAll(
            tacEntries(
                UnlockBrand.ZTE,
                "MF297D2",
                UnlockGeneration.FOUR_G_HILINK,
                "ZTE-4G-GOFORM-READ",
                "FCCID.io / MoazEb TAC database",
                "86851407"
            )
        )

        // ZTE 5G CPE.
        addAll(
            tacEntries(
                UnlockBrand.ZTE,
                "MC801A",
                UnlockGeneration.FIVE_G,
                "ZTE-5G-GOFORM-RUNTIME",
                "FCC MC801A test-device IMEI allocation",
                "86367104"
            )
        )
        addAll(
            tacEntries(
                UnlockBrand.ZTE,
                "MC888",
                UnlockGeneration.FIVE_G,
                "ZTE-5G-GOFORM-RUNTIME",
                "FCCID.io / MoazEb TAC database",
                "86694906"
            )
        )
        addAll(
            tacEntries(
                UnlockBrand.ZTE,
                "MC888A Ultra",
                UnlockGeneration.FIVE_G,
                "ZTE-5G-GOFORM-RUNTIME",
                "FCCID.io / MoazEb TAC database",
                "86156906"
            )
        )
        addAll(
            tacEntries(
                UnlockBrand.ZTE,
                "MC889 Ultra",
                UnlockGeneration.FIVE_G,
                "ZTE-5G-GOFORM-RUNTIME",
                "FCCID.io / MoazEb TAC database",
                "86608608"
            )
        )
    }
}