package com.hai.manager.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Mc801aDiagnosticReportTest {
    @Test
    fun readyWhenLiveWebUiAndLockProbesAreVerified() {
        val report = Mc801aDiagnosticReporter.build(
            inspection = inspection(nckVerified = true),
            lockSummary = RouterCarrierLockSummary(
                state = CarrierLockState.LOCKED,
                attemptsRemaining = "5",
                source = "test",
                waitingForNck = true
            )
        )

        assertTrue(report.applicable)
        assertEquals(Mc801aNckReadiness.READY_FOR_NCK, report.readiness)
        assertEquals(CapabilityProbeStatus.AVAILABLE, report.nckWriteStatus)
    }

    @Test
    fun exhaustedCounterAlwaysBlocksReadiness() {
        val report = Mc801aDiagnosticReporter.build(
            inspection = inspection(nckVerified = true),
            lockSummary = RouterCarrierLockSummary(
                state = CarrierLockState.LOCKED,
                attemptsRemaining = "0",
                source = "test"
            )
        )

        assertEquals(Mc801aNckReadiness.ATTEMPTS_EXHAUSTED, report.readiness)
    }

    @Test
    fun shareTextExcludesSensitiveRouterIdentifiers() {
        val report = Mc801aDiagnosticReporter.build(
            inspection = inspection(nckVerified = false).copy(
                device = RouterDeviceInfo(
                    model = "MC801A",
                    serialNumber = "SERIAL-SECRET",
                    imei = "868000000000001",
                    firmwareVersion = "BD_TEST_MC801A_B01",
                    hardwareVersion = "HW1",
                    webUiVersion = "WEB1"
                ),
                security = RouterSecurityInfo(
                    networkLockState = "1",
                    unlockAttemptsRemaining = "4",
                    iccid = "ICCID-SECRET",
                    imsi = "IMSI-SECRET"
                )
            ),
            lockSummary = RouterCarrierLockSummary(
                state = CarrierLockState.LOCKED,
                attemptsRemaining = "4",
                source = "test"
            )
        )

        val text = report.shareText()
        assertFalse(text.contains("868000000000001"))
        assertFalse(text.contains("SERIAL-SECRET"))
        assertFalse(text.contains("ICCID-SECRET"))
        assertFalse(text.contains("IMSI-SECRET"))
        assertTrue(text.contains("Privacy:"))
    }

    private fun inspection(nckVerified: Boolean): RouterInspection {
        val nckStatus = if (nckVerified) CapabilityProbeStatus.AVAILABLE else CapabilityProbeStatus.NOT_EXPOSED
        return RouterInspection(
            snapshot = RouterSnapshot(
                connected = true,
                managementUrl = "http://192.168.0.1",
                brand = RouterBrand.ZTE,
                model = "MC801A"
            ),
            accessStatus = RouterAccessStatus.AVAILABLE,
            device = RouterDeviceInfo(
                model = "MC801A",
                firmwareVersion = "BD_TEST_MC801A_B01",
                hardwareVersion = "HW1",
                webUiVersion = "WEB1"
            ),
            security = RouterSecurityInfo(networkLockState = "1", unlockAttemptsRemaining = "5"),
            capabilities = setOf(RouterCapability.DEVICE_INFO, RouterCapability.SIM_SECURITY),
            probeReport = RouterCapabilityReport(
                firmwareFingerprint = "A1B2C3D4",
                items = listOf(
                    CapabilityProbeItem("zte_action_seed", "seed", CapabilityProbeStatus.AVAILABLE, "AD قابل للاشتقاق"),
                    CapabilityProbeItem("network_lock_read", "lock", CapabilityProbeStatus.AVAILABLE, "1 • محاولات: 5"),
                    CapabilityProbeItem(
                        "nck_write",
                        "NCK",
                        nckStatus,
                        if (nckVerified) "WebUI يعلن UNLOCK_NETWORK عبر /js/service.js" else "لم يجد HAI نموذج UNLOCK_NETWORK"
                    )
                ),
                networkLockReadable = true,
                nckEntryVerified = nckVerified,
                summary = "test"
            )
        )
    }
}
