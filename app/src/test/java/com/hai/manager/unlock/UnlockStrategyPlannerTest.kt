package com.hai.manager.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockStrategyPlannerTest {
    @Test
    fun mc801aUsesQualcommDiagnosticsBeforeResearch() {
        val report = ImeiUnlockEngine.analyze("868757025499999", UnlockBrand.ZTE, "MC801A")
        val platform = PlatformResolver.resolve("MC801A")
        val plan = UnlockStrategyPlanner.plan(report, platform, "MC801A")

        assertTrue(plan.title.contains("Qualcomm"))
        assertEquals("Web API / goform", plan.steps.first().title)
        assertEquals(UnlockPathStatus.DIAGNOSTICS, plan.steps.first().status)
        assertEquals(UnlockPathStatus.UNAVAILABLE, plan.steps.last().status)
    }

    @Test
    fun h122UsesBalong5000Plan() {
        val report = ImeiUnlockEngine.analyze("868757025499999", UnlockBrand.HUAWEI, "H122-373")
        val platform = PlatformResolver.resolve("H122-373")
        val plan = UnlockStrategyPlanner.plan(report, platform, "H122-373")

        assertTrue(plan.title.contains("Balong 5000"))
        assertTrue(plan.steps.any { it.title.contains("Emergency USB") && it.status == UnlockPathStatus.RESEARCH })
        assertEquals(UnlockPathStatus.UNAVAILABLE, plan.steps.last().status)
    }

    @Test
    fun b310RemainsV4AwareAndDoesNotPromoteLegacyCode() {
        val report = ImeiUnlockEngine.analyze("868757025499999", UnlockBrand.HUAWEI, "B310s-22")
        val platform = PlatformResolver.resolve("B310s-22")
        val plan = UnlockStrategyPlanner.plan(report, platform, "B310s-22")

        assertTrue(plan.summary.contains("V4-aware"))
        assertTrue(plan.steps.none { it.status == UnlockPathStatus.READY })
    }

    @Test
    fun verifiedLegacyHuaweiStartsWithOfflineNck() {
        val report = ImeiUnlockEngine.analyze("868757025499999", UnlockBrand.HUAWEI, "E173")
        val plan = UnlockStrategyPlanner.plan(report, null, "E173")

        assertEquals(UnlockPathStatus.READY, plan.steps.first().status)
        assertEquals("IMEI → NCK", plan.steps.first().title)
    }
}
