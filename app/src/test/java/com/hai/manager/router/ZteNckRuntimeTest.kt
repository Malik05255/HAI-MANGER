package com.hai.manager.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZteNckRuntimeTest {
    @Test
    fun detectsExactWebUiUnlockFormEvidence() {
        val source = """
            function unlockNetwork(data) {
              return postData({goformId: \"UNLOCK_NETWORK\", unlock_network_code: data.unlock_network_code});
            }
        """.trimIndent()

        assertTrue(ZteNckRuntime.exposesUnlockNetwork(source))
        assertFalse(ZteNckRuntime.exposesUnlockNetwork("goformId: 'UNLOCK_NETWORK'"))
        assertFalse(ZteNckRuntime.exposesUnlockNetwork("unlock_network_code: code"))
    }

    @Test
    fun mc801aUsesMd5AdFormulaFromItsOwnFirmwareFamily() {
        val ad = ZteNckRuntime.computeAd(
            waInnerVersion = "BD_SASTCMC801AV1.0.0B07",
            crVersion = "CR_SASTCMC801AV1.0.0B07",
            rd = "1234567890"
        )

        assertEquals("306658ea0c87a03855fb855753cf1ef8", ad)
        assertEquals(ZteAdDigest.MD5, ZteNckRuntime.adDigestFor("BD_SASTCMC801AV1.0.0B07"))
    }

    @Test
    fun mc801aCanDeriveAdWhenCrVersionIsNotExposed() {
        val ad = ZteNckRuntime.computeAd(
            waInnerVersion = "BD_SASTCMC801AV1.0.0B07",
            crVersion = null,
            rd = "1234567890"
        )

        assertTrue(!ad.isNullOrBlank())
    }

    @Test
    fun unknownFamiliesNeverGetAnAdGuess() {
        assertNull(ZteNckRuntime.computeAd("UNKNOWN_FIRMWARE", "", "123"))
    }
}
