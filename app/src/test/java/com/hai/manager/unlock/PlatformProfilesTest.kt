package com.hai.manager.unlock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlatformProfilesTest {
    @Test
    fun zteQualcommGenerationsResolveSeparately() {
        assertEquals("QUALCOMM-SDX55", PlatformResolver.resolve("MC801A")?.family)
        assertEquals("QUALCOMM-SDX55", PlatformResolver.resolve("MC7010")?.family)
        assertEquals("QUALCOMM-SDX62", PlatformResolver.resolve("MC888")?.family)
        assertEquals("QUALCOMM-SDX62", PlatformResolver.resolve("MU5120")?.family)
        assertEquals("QUALCOMM-SDX65", PlatformResolver.resolve("MC888A Ultra")?.family)
        assertEquals("QUALCOMM-SDX75", PlatformResolver.resolve("MU5250")?.family)
    }

    @Test
    fun ambiguousZteSkusRemainVariantDependent() {
        val ultra = PlatformResolver.resolve("MC888 Ultra")
        assertNotNull(ultra)
        assertEquals("QUALCOMM-SDX62-OR-SDX65", ultra?.family)
        assertEquals(PlatformConfidence.VARIANT_DEPENDENT, ultra?.confidence)

        val outdoor = PlatformResolver.resolve("MC889 Ultra")
        assertNotNull(outdoor)
        assertEquals("QUALCOMM-SDX62-OR-SDX65", outdoor?.family)
    }

    @Test
    fun huaweiBalongGenerationsResolveByModelFamily() {
        assertEquals("HUAWEI-BALONG-V7R1", PlatformResolver.resolve("E5180")?.family)
        assertEquals("HUAWEI-BALONG-V7R11", PlatformResolver.resolve("B310s-22")?.family)
        assertEquals("HUAWEI-BALONG-V7R22", PlatformResolver.resolve("B525s-23a")?.family)
        assertEquals("HUAWEI-BALONG-V7R5", PlatformResolver.resolve("B618s-22d")?.family)
        assertEquals("HUAWEI-BALONG-V7R65", PlatformResolver.resolve("B818-263")?.family)
        assertEquals("HUAWEI-BALONG-5000", PlatformResolver.resolve("H122-373")?.family)
    }
}
