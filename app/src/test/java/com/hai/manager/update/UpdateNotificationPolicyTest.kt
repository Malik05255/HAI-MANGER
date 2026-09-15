package com.hai.manager.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNotificationPolicyTest {
    @Test
    fun notifiesOnlyForNewerUnnotifiedVersion() {
        assertTrue(UpdateNotificationPolicy.shouldNotify(40, 41, 0))
        assertFalse(UpdateNotificationPolicy.shouldNotify(40, 40, 0))
        assertFalse(UpdateNotificationPolicy.shouldNotify(40, 41, 41))
        assertTrue(UpdateNotificationPolicy.shouldNotify(40, 42, 41))
    }
}