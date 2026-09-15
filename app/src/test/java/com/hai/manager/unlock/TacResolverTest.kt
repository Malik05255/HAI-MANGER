package com.hai.manager.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TacResolverTest {
    @Test
    fun catalogHasExpandedRouterCoverage() {
        assertTrue(TacResolver.knownTacCount() >= 30)
    }

    @Test
    fun huaweiBSeriesTacsResolveToBalongProfiles() {
        val cases = listOf(
            "868465030000000" to "B310",
            "869547040000000" to "B311",
            "866169020000000" to "B315"
        )

        cases.forEach { (imei, expectedModel) ->
            val result = ImeiUnlockFacade.analyze(imei)
            assertEquals(UnlockBrand.HUAWEI, result.brand)
            assertEquals(UnlockGeneration.FOUR_G_HILINK, result.generation)
            assertTrue(result.family.contains(expectedModel))
            assertTrue(result.codes.isEmpty())
        }
    }

    @Test
    fun huaweiKnownCpeTacsStayOnDeviceProfiles() {
        val e5180 = ImeiUnlockFacade.analyze("864919020000000")
        assertEquals(UnlockBrand.HUAWEI, e5180.brand)
        assertEquals(UnlockGeneration.FOUR_G_HILINK, e5180.generation)
        assertTrue(e5180.family.contains("E5180"))
        assertTrue(e5180.codes.isEmpty())

        val h122 = ImeiUnlockFacade.analyze("866887040000000")
        assertEquals(UnlockBrand.HUAWEI, h122.brand)
        assertEquals(UnlockGeneration.FIVE_G, h122.generation)
        assertTrue(h122.family.contains("H122-373"))
        assertTrue(h122.codes.isEmpty())
    }

    @Test
    fun zte4gTacsResolveToGoformProfiles() {
        val cases = listOf(
            "860978040000000" to "MF286C",
            "864781050000000" to "MF289F",
            "864778060000000" to "MF289F Pro",
            "863955050000000" to "MF297D",
            "868514070000000" to "MF297D2"
        )

        cases.forEach { (imei, expectedModel) ->
            val result = ImeiUnlockFacade.analyze(imei)
            assertEquals(UnlockBrand.ZTE, result.brand)
            assertEquals(UnlockGeneration.FOUR_G_HILINK, result.generation)
            assertTrue(result.family.contains(expectedModel))
            assertTrue(result.codes.isEmpty())
        }
    }

    @Test
    fun explicitBrandConflictIsRejected() {
        val result = ImeiUnlockFacade.analyze(
            rawImei = "866949060000000",
            brandHint = UnlockBrand.HUAWEI
        )
        assertEquals(UnlockBrand.ZTE, result.brand)
        assertTrue(result.family.contains("تعارض"))
        assertTrue(result.codes.isEmpty())
    }
}
