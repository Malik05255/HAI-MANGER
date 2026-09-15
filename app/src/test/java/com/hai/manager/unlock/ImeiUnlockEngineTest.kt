package com.hai.manager.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeiUnlockEngineTest {
    @Test
    fun huaweiReferenceVectorsMatch() {
        val a = HuaweiLegacyAlgorithms.calculateAll("868757025499999")
        assertEquals("48125080", a.v1)
        assertEquals("39842371", a.v2)
        assertEquals("46863554", a.v201)
        assertEquals("50702788", a.flash)

        val b = HuaweiLegacyAlgorithms.calculateAll("351753039107700")
        assertEquals("51940123", b.v1)
        assertEquals("91170346", b.v2)
        assertEquals("47709162", b.v201)
        assertEquals("61770125", b.flash)

        val c = HuaweiLegacyAlgorithms.calculateAll("868757028951552")
        assertEquals("56730142", c.v1)
        assertEquals("34196754", c.v2)
        assertEquals("25381795", c.v201)
        assertEquals("36527190", c.flash)
    }

    @Test
    fun modern5gDoesNotReceiveLegacyCode() {
        val zte = ImeiUnlockEngine.analyze("868757025499999", UnlockBrand.ZTE, "MC801A")
        assertTrue(zte.codes.isEmpty())
        assertEquals(UnlockGeneration.FIVE_G, zte.generation)

        val huawei = ImeiUnlockEngine.analyze("868757025499999", UnlockBrand.HUAWEI, "H155-381")
        assertTrue(huawei.codes.isEmpty())
        assertEquals(UnlockGeneration.FIVE_G, huawei.generation)
    }

    @Test
    fun b310TacSelectsHuaweiBalongProfileInsteadOfLegacyCodes() {
        val report = ImeiUnlockFacade.analyze("869465040000000")
        assertEquals(UnlockBrand.HUAWEI, report.brand)
        assertEquals(UnlockGeneration.FOUR_G_HILINK, report.generation)
        assertTrue(report.family.contains("Huawei"))
        assertTrue(report.codes.isEmpty())
        assertTrue(report.sourceNotes.any { it.contains("B310s-22") })
    }

    @Test
    fun mc888TacSelectsZte5gProfile() {
        val report = ImeiUnlockFacade.analyze("866949060000000")
        assertEquals(UnlockBrand.ZTE, report.brand)
        assertEquals(UnlockGeneration.FIVE_G, report.generation)
        assertTrue(report.codes.isEmpty())
        assertTrue(report.sourceNotes.any { it.contains("MC888") })
    }

    @Test
    fun zteZxFamilyCalculatorIsDeterministic() {
        val first = ZteZx297520v3Algorithm.calculate("123456789012345")
        val second = ZteZx297520v3Algorithm.calculate("123456789012345")
        assertEquals(first, second)
        assertEquals(8, first.length)
        assertTrue(first.all(Char::isDigit))
    }

    @Test
    fun imeiValidationWorks() {
        assertFalse(ImeiUnlockEngine.analyze("123", UnlockBrand.HUAWEI).formatValid)
        assertTrue(ImeiUnlockEngine.analyze("868757025499999", UnlockBrand.HUAWEI).formatValid)
    }
}
