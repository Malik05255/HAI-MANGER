package com.hai.manager.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTekPlatformProfilesTest {
    @Test
    fun t750ReferenceCpesResolveToMt6890() {
        listOf("ZLT-X28", "NR5103", "NR5103E", "FWA505").forEach { model ->
            val profile = PlatformResolver.resolve(model)
            assertNotNull(model, profile)
            assertEquals("MEDIATEK-T750-MT6890", profile?.family)
            assertEquals(PlatformConfidence.VERIFIED, profile?.confidence)
        }
    }

    @Test
    fun genericT830ResolvesSeparatelyFromT750() {
        val profile = PlatformResolver.resolve("MediaTek T830")
        assertNotNull(profile)
        assertEquals("MEDIATEK-T830-M80", profile?.family)
        assertTrue(profile?.name?.contains("M80") == true)
    }

    @Test
    fun mc8512RemainsVariantDependent() {
        val profile = PlatformResolver.resolve("MC8512")
        assertNotNull(profile)
        assertEquals("ZTE-MC8512-PLATFORM-VARIANT", profile?.family)
        assertEquals(PlatformConfidence.VARIANT_DEPENDENT, profile?.confidence)
        assertTrue(profile?.imeiNckDerivation?.contains("لا توجد") == true)
    }

    @Test
    fun mc8512CannotFallBackToLegacyZteCalculator() {
        val report = ImeiUnlockFacade.analyze(
            rawImei = "868757025499999",
            brandHint = UnlockBrand.ZTE,
            modelHint = "MC8512"
        )
        assertEquals(UnlockGeneration.FIVE_G, report.generation)
        assertTrue(report.codes.isEmpty())
        assertTrue(report.family.contains("MC8512"))
    }

    @Test
    fun mediatekDoesNotClaimImeiOnlyNck() {
        val t750 = PlatformResolver.resolve("ZLT-X28")!!
        val t830 = PlatformResolver.resolve("MediaTek T830")!!
        assertTrue(t750.imeiNckDerivation.contains("لا توجد"))
        assertTrue(t830.imeiNckDerivation.contains("لا توجد"))
    }
}
