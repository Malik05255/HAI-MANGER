package com.hai.manager.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZteLockDiagnosticsInterpreterTest {
    @Test
    fun waitNckStateForcesLockedDiagnosis() {
        val result = ZteLockDiagnosticsInterpreter.interpret(
            ZteLockRawSnapshot(
                modemMainState = "modem_imsi_waitnck",
                networkLockStatus = "unknown",
                networkUnlockRemainCount = "5"
            )
        )

        assertEquals(CarrierLockState.LOCKED, result.state)
        assertTrue(result.waitingForNck)
        assertEquals("5", result.attemptsRemaining)
        assertTrue(result.diagnostic.contains("انتظار NCK"))
    }

    @Test
    fun unlockNckTimeIsNotPromotedToAttempts() {
        val result = ZteLockDiagnosticsInterpreter.interpret(
            ZteLockRawSnapshot(
                networkLockStatus = "1",
                unlockNckTime = "0"
            )
        )

        assertEquals(CarrierLockState.LOCKED, result.state)
        assertNull(result.attemptsRemaining)
        assertEquals("0", result.nckRelatedValue)
        assertFalse(result.diagnostic.contains("عداد المحاولات الصريح = 0"))
    }

    @Test
    fun explicitRemainingCountZeroIsExhaustedEvidence() {
        val result = ZteLockDiagnosticsInterpreter.interpret(
            ZteLockRawSnapshot(
                networkLockStatus = "1",
                networkUnlockRemainCount = "0",
                unlockNckTime = "10"
            )
        )

        assertEquals(CarrierLockState.LOCKED, result.state)
        assertEquals("0", result.attemptsRemaining)
        assertEquals("10", result.nckRelatedValue)
        assertTrue(result.diagnostic.contains("لا ترسل أي كود"))
    }

    @Test
    fun unlockedStatusRemainsUnlocked() {
        val result = ZteLockDiagnosticsInterpreter.interpret(
            ZteLockRawSnapshot(networkLockStatus = "0")
        )

        assertEquals(CarrierLockState.UNLOCKED, result.state)
        assertFalse(result.waitingForNck)
    }

    @Test
    fun lockedHplmnIsEvidenceButNotAutomaticLockProof() {
        val result = ZteLockDiagnosticsInterpreter.interpret(
            ZteLockRawSnapshot(lockedHplmns = "42001,42003")
        )

        assertEquals(CarrierLockState.UNKNOWN, result.state)
        assertEquals("42001,42003", result.lockedHplmns)
        assertTrue(result.diagnostic.contains("غير محسومة"))
    }
}
