package com.lagradost.cloudstream3.ui.universe

import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUniverseClassifierTest {
    @Test
    fun `movies and series are routed by media type rather than provider`() {
        assertTrue(MediaUniverseClassifier.matches("films", TvType.Movie, "Film A"))
        assertTrue(MediaUniverseClassifier.matches("films", TvType.AnimeMovie, "Film B"))
        assertFalse(MediaUniverseClassifier.matches("films", TvType.TvSeries, "Series A"))
        assertTrue(MediaUniverseClassifier.matches("series", TvType.TvSeries, "Series A"))
        assertTrue(MediaUniverseClassifier.matches("series", TvType.Anime, "Anime A"))
        assertFalse(MediaUniverseClassifier.matches("series", TvType.Movie, "Film A"))
    }

    @Test
    fun `short films use duration or explicit short-film metadata`() {
        assertTrue(MediaUniverseClassifier.matches("shorts", TvType.Movie, "A story", durationMinutes = 18))
        assertTrue(MediaUniverseClassifier.matches("shorts", TvType.Movie, "A short film"))
        assertTrue(MediaUniverseClassifier.matches("shorts", TvType.Movie, "A story", tags = listOf("court-métrage")))
        assertFalse(MediaUniverseClassifier.matches("shorts", TvType.Movie, "A feature film", durationMinutes = 110))
        assertFalse(MediaUniverseClassifier.matches("shorts", TvType.TvSeries, "Short Film episode", durationMinutes = 12))
    }

    @Test
    fun `kids universe needs family metadata or a child-friendly rating`() {
        assertTrue(MediaUniverseClassifier.matches("kids", TvType.Cartoon, "The adventure", tags = listOf("family")))
        assertTrue(MediaUniverseClassifier.matches("kids", TvType.Movie, "The adventure", contentRating = "G"))
        assertTrue(MediaUniverseClassifier.matches("kids", TvType.TvSeries, "Kids science"))
        assertFalse(MediaUniverseClassifier.matches("kids", TvType.Movie, "Crime drama"))
        assertFalse(MediaUniverseClassifier.matches("kids", TvType.NSFW, "Kids only"))
    }

    @Test
    fun `live television excludes sports and radio titles`() {
        assertTrue(MediaUniverseClassifier.matches("live_tv", TvType.Live, "News 24"))
        assertTrue(MediaUniverseClassifier.matches("live_tv", TvType.Live, "Transport News"))
        assertTrue(MediaUniverseClassifier.matches("live_tv", TvType.Live, "Radiology Live"))
        assertFalse(MediaUniverseClassifier.matches("live_tv", TvType.Live, "Live football match"))
        assertFalse(MediaUniverseClassifier.matches("live_tv", TvType.Live, "BBC Radio 1"))
        assertTrue(MediaUniverseClassifier.matches("sports", TvType.Live, "Live football match"))
        assertTrue(MediaUniverseClassifier.matches("sports", TvType.Live, "ESPN"))
        assertFalse(MediaUniverseClassifier.matches("sports", TvType.Movie, "Football documentary"))
    }

    @Test
    fun `radio does not absorb ordinary music or podcasts`() {
        assertTrue(MediaUniverseClassifier.matches("radio", TvType.Live, "BBC Radio 1"))
        assertTrue(MediaUniverseClassifier.matches("radio", TvType.Audio, "Radio Afrique FM"))
        assertFalse(MediaUniverseClassifier.matches("radio", TvType.Music, "A song by an artist"))
        assertFalse(MediaUniverseClassifier.matches("radio", TvType.Podcast, "Radio interview podcast"))
        assertTrue(MediaUniverseClassifier.matches("podcasts", TvType.Podcast, "Science interview"))
    }

    @Test
    fun `metadata lookup prioritizes clearly labelled shorts and kids titles`() {
        assertTrue(MediaUniverseClassifier.detailPriority("shorts", "A short film") >
            MediaUniverseClassifier.detailPriority("shorts", "The story"))
        assertTrue(MediaUniverseClassifier.detailPriority("kids", "Kids science") >
            MediaUniverseClassifier.detailPriority("kids", "Crime drama"))
    }

    @Test
    fun `adult and custom media are not routed into public universes`() {
        assertFalse(MediaUniverseClassifier.matches("home", TvType.NSFW, "Adult item"))
        assertFalse(MediaUniverseClassifier.matches("films", TvType.CustomMedia, "Custom player item"))
        assertFalse(MediaUniverseClassifier.shouldLoadDetails("kids", TvType.NSFW, "Kids"))
    }
}
