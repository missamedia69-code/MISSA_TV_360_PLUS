package com.lagradost.cloudstream3.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissaLoadControlTest {

    @Test
    fun closeRebuffersAskForMoreAdvance() {
        assertEquals(2_000, MissaLoadControl.rebufferStepMs(1))
        assertEquals(5_000, MissaLoadControl.rebufferStepMs(2))
        assertEquals(10_000, MissaLoadControl.rebufferStepMs(3))
        assertEquals(10_000, MissaLoadControl.rebufferStepMs(9))
        // Valeur hors bornes : jamais d'exception, la première marche.
        assertEquals(2_000, MissaLoadControl.rebufferStepMs(0))
    }

    @Test
    fun retriesAreMorePatientThanStandard() {
        assertTrue(MissaLoadErrorPolicy.MIN_RETRIES > 3)
        assertTrue(MissaLoadErrorPolicy.MIN_RETRIES_LIVE > 6)
    }
}
