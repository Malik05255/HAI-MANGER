package com.hai.manager.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FirmwareProfilesTest {
    @Test
    fun b310UsesV4AwareBalongProfile() {
        val profile = RouterFirmwareProfiles.resolve(
            brand = RouterBrand.HUAWEI,
            model = "B310s-22",
            firmware = "21.333.01.01.00",
            capabilities = setOf(RouterCapability.DEVICE_INFO)
        )
        assertEquals("HUAWEI-BALONG-4G-V4-AWARE", profile.profileId)
        assertEquals(ProfileActionSupport.READ_ONLY, profile.nckEntry)
        assertFalse(profile.nckEntry.canWrite)
    }

    @Test
    fun b315UsesV4AwareBalongProfile() {
        val profile = RouterFirmwareProfiles.resolve(
            brand = RouterBrand.HUAWEI,
            model = "B315s-22",
            firmware = "21.333.01.03.1096",
            capabilities = setOf(RouterCapability.DEVICE_INFO)
        )
        assertEquals("HUAWEI-BALONG-4G-V4-AWARE", profile.profileId)
    }

    @Test
    fun mc801aUsesModernZte5gProfile() {
        val profile = RouterFirmwareProfiles.resolve(
            brand = RouterBrand.ZTE,
            model = "MC801A",
            firmware = "BD_SASTCMC801AV1.0.0B07",
            capabilities = setOf(RouterCapability.DEVICE_INFO)
        )
        assertEquals("ZTE-5G-GOFORM-RUNTIME", profile.profileId)
        assertEquals(ProfileActionSupport.READ_ONLY, profile.nckEntry)
    }

    @Test
    fun mf253UsesZte4gProfile() {
        val profile = RouterFirmwareProfiles.resolve(
            brand = RouterBrand.ZTE,
            model = "MF253V",
            firmware = "unknown",
            capabilities = setOf(RouterCapability.DEVICE_INFO)
        )
        assertEquals("ZTE-4G-GOFORM-READ", profile.profileId)
    }
}
