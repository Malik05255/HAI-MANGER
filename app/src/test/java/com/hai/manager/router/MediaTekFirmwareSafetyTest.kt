package com.hai.manager.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaTekFirmwareSafetyTest {
    @Test
    fun mc8512DoesNotInheritQualcommZteWriteProfile() {
        val profile = RouterFirmwareProfiles.resolve(
            brand = RouterBrand.ZTE,
            model = "MC8512",
            firmware = "unknown",
            capabilities = setOf(RouterCapability.DEVICE_INFO)
        )

        assertEquals("UNMATCHED", profile.profileId)
        assertFalse(profile.bandLock.canWrite)
        assertFalse(profile.nckEntry.canWrite)
    }
}
