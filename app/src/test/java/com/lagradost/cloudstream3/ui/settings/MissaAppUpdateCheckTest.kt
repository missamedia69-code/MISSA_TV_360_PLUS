package com.lagradost.cloudstream3.ui.settings

import com.lagradost.cloudstream4.viewmodels.isNewerVersion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissaAppUpdateCheckTest {
    @Test
    fun newerVersionIsOffered() {
        assertTrue(isNewerVersion("v4.9.1", "4.9.0"))
        assertTrue(isNewerVersion("4.10.0", "4.9.9"))
        assertTrue(isNewerVersion("v5.0", "4.9.1"))
        assertTrue(isNewerVersion("v4.9.0.1", "4.9.0"))
    }

    @Test
    fun sameOrOlderVersionIsNeverOffered() {
        assertFalse(isNewerVersion("v4.9.0", "4.9.0"))
        assertFalse(isNewerVersion("v4.9.0", "4.9.1"))
        assertFalse(isNewerVersion("v4.9", "4.9.0"))
        assertFalse(isNewerVersion("MISSA-TV-360-PLUS", "4.9.0"))
        assertFalse(isNewerVersion("", "4.9.0"))
    }

    @Test
    fun checkIsThrottled() {
        val hour = 60L * 60 * 1000
        val now = 100L * 24 * hour
        assertTrue(MissaAppUpdateCheck.isDue(now, 0L, metered = false))
        assertFalse(MissaAppUpdateCheck.isDue(now, now - 5 * hour, metered = false))
        assertTrue(MissaAppUpdateCheck.isDue(now, now - 6 * hour, metered = false))
        assertFalse(MissaAppUpdateCheck.isDue(now, now - 20 * hour, metered = true))
        assertTrue(MissaAppUpdateCheck.isDue(now, now - 24 * hour, metered = true))
        // Horloge reculée : on vérifie plutôt que de rester muet.
        assertTrue(MissaAppUpdateCheck.isDue(now, now + hour, metered = true))
    }
}
