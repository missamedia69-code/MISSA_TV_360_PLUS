package com.lagradost.cloudstream3.ui.settings.extensions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissaAdminLockTest {
    @Test
    fun `only the administrator code unlocks the extension settings`() {
        assertTrue(MissaAdminLock.check(" 5148 "))
        assertFalse(MissaAdminLock.check("0000"))
        assertFalse(MissaAdminLock.check("1234"))
        assertFalse(MissaAdminLock.check(""))
    }
}
