package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MissaSourceFallbackTest {
    @Test
    fun `titles match without accents, punctuation, case or year`() {
        assertEquals(MissaSourceFallback.normalize("Les Misérables (2019)"), MissaSourceFallback.normalize("les miserables"))
        assertEquals(MissaSourceFallback.normalize("Spider-Man: No Way Home"), MissaSourceFallback.normalize("Spider Man  No Way Home"))
        assertNotEquals(MissaSourceFallback.normalize("Dune"), MissaSourceFallback.normalize("Dune Part Two"))
    }

    @Test
    fun `families group movies, series and live`() {
        assertEquals(MissaSourceFallback.Family.MOVIE, MissaSourceFallback.family(TvType.AnimeMovie))
        assertEquals(MissaSourceFallback.Family.SERIES, MissaSourceFallback.family(TvType.AsianDrama))
        assertEquals(MissaSourceFallback.Family.LIVE, MissaSourceFallback.family(TvType.Live))
        assertEquals(MissaSourceFallback.Family.OTHER, MissaSourceFallback.family(null))
    }
}
