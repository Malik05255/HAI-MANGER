package com.hai.manager.router

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnlockErrorReportTest {
    @Test
    fun reportKeepsUsefulFailureDataAndExcludesSensitiveIdentifiers() {
        val inspection = RouterInspection(
            snapshot = RouterSnapshot(
                connected = true,
                brand = RouterBrand.ZTE,
                model = "MC801A"
            ),
            accessStatus = RouterAccessStatus.AVAILABLE,
            device = RouterDeviceInfo(
                model = "MC801A",
                serialNumber = "SECRET-SERIAL",
                imei = "123456789012345",
                firmwareVersion = "BD_SASTCMC801AV1.0.0B15",
                hardwareVersion = "HW1"
            ),
            security = RouterSecurityInfo(
                imsi = "420011234567890",
                iccid = "8996612345678901234"
            ),
            wifi = RouterWifiInfo(ssid = "PRIVATE-WIFI")
        )

        val report = UnlockErrorReport.build(
            appVersion = "0.32.0",
            phase = "RESULT",
            router = inspection.snapshot,
            inspection = inspection,
            lock = null,
            message = "diagnostic failed imei=123456789012345"
        )

        assertTrue(report.contains("MC801A"))
        assertTrue(report.contains("BD_SASTCMC801AV1.0.0B15"))
        assertTrue(report.contains("0.32.0"))
        assertFalse(report.contains("123456789012345"))
        assertFalse(report.contains("420011234567890"))
        assertFalse(report.contains("8996612345678901234"))
        assertFalse(report.contains("SECRET-SERIAL"))
        assertFalse(report.contains("PRIVATE-WIFI"))
    }
}
