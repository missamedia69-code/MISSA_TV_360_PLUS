package com.lagradost.cloudstream3.ui.universe

import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertEquals
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
    fun `incomplete or null plugin types are normalized from section and provider metadata`() {
        assertTrue(
            MediaUniverseClassifier.matches(
                universeId = "films",
                type = null,
                title = "Le Comte de Monte-Cristo",
                sectionName = "Films Box-Office"
            )
        )
        assertTrue(
            MediaUniverseClassifier.matches(
                universeId = "series",
                type = TvType.Others,
                title = "Les Anneaux S02E04",
                sectionName = "Nouveautés"
            )
        )
        assertTrue(
            MediaUniverseClassifier.matches(
                universeId = "live_tv",
                type = null,
                title = "Canal Info HD",
                providerSupportedTypes = setOf(TvType.Live)
            )
        )
    }

    @Test
    fun `short films use duration or explicit short-film metadata and remain cross-cutting with films`() {
        assertTrue(MediaUniverseClassifier.matches("shorts", TvType.Movie, "A story", durationMinutes = 18))
        assertTrue(MediaUniverseClassifier.matches("shorts", TvType.Movie, "A short film"))
        assertTrue(MediaUniverseClassifier.matches("shorts", TvType.Movie, "A story", tags = listOf("court-métrage")))
        assertFalse(MediaUniverseClassifier.matches("shorts", TvType.Movie, "A feature film", durationMinutes = 110))
        assertFalse(MediaUniverseClassifier.matches("shorts", TvType.TvSeries, "Short Film episode", durationMinutes = 12))

        val crossCutting = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.Movie,
            title = "L'Oiseau d'Or",
            tags = listOf("Animation", "Famille"),
            durationMinutes = 14
        )
        assertTrue(crossCutting.contains("films"))
        assertTrue(crossCutting.contains("shorts"))
        assertTrue(crossCutting.contains("kids"))
    }

    @Test
    fun `kids universe needs family metadata or rating and strictly blocks mature tags`() {
        assertTrue(MediaUniverseClassifier.matches("kids", TvType.Cartoon, "The adventure", tags = listOf("family")))
        assertTrue(MediaUniverseClassifier.matches("kids", TvType.Movie, "The adventure", contentRating = "G"))
        assertTrue(MediaUniverseClassifier.matches("kids", TvType.TvSeries, "Kids science"))
        assertFalse(MediaUniverseClassifier.matches("kids", TvType.Movie, "Crime drama"))
        assertFalse(MediaUniverseClassifier.matches("kids", TvType.NSFW, "Kids only"))
        assertFalse(
            MediaUniverseClassifier.matches(
                universeId = "kids",
                type = TvType.Cartoon,
                title = "Nightmare Family",
                tags = listOf("Horror", "18+")
            )
        )
        assertFalse(
            MediaUniverseClassifier.matches(
                universeId = "kids",
                type = TvType.Movie,
                title = "Kids in Danger",
                contentRating = "TV-MA"
            )
        )
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
        assertTrue(MediaUniverseClassifier.matches("sports", TvType.Live, "Canal+ Sport Ligue 1"))
        assertFalse(MediaUniverseClassifier.matches("sports", TvType.Movie, "Football documentary"))
    }

    @Test
    fun `radio does not absorb ordinary music or podcasts and recognizes Cameroon and African stations`() {
        assertTrue(MediaUniverseClassifier.matches("radio", TvType.Live, "BBC Radio 1"))
        assertTrue(MediaUniverseClassifier.matches("radio", TvType.Audio, "Radio Afrique FM"))
        assertTrue(MediaUniverseClassifier.matches("radio", TvType.Audio, "CRTV Littoral Douala"))
        assertTrue(MediaUniverseClassifier.matches("radio", TvType.Audio, "Radio Balafon 90.3 FM"))
        assertFalse(MediaUniverseClassifier.matches("radio", TvType.Music, "A song by an artist"))
        assertFalse(MediaUniverseClassifier.matches("radio", TvType.Podcast, "Radio interview podcast"))
        assertTrue(MediaUniverseClassifier.matches("podcasts", TvType.Podcast, "Science interview"))
    }

    @Test
    fun `title deduplication merges duplicate releases across plugins`() {
        val keyA = MediaUniverseClassifier.normalizeTitleKey("Dune : Deuxième Partie (2024) [VF] 1080p", "films")
        val keyB = MediaUniverseClassifier.normalizeTitleKey("Dune - Deuxieme Partie VOSTFR HD", "films")
        assertEquals(keyA, keyB)
    }

    @Test
    fun `metadata lookup prioritizes clearly labelled shorts and kids titles`() {
        assertTrue(
            MediaUniverseClassifier.detailPriority("shorts", "A short film") >
                MediaUniverseClassifier.detailPriority("shorts", "The story")
        )
        assertTrue(
            MediaUniverseClassifier.detailPriority("kids", "Kids science") >
                MediaUniverseClassifier.detailPriority("kids", "Crime drama")
        )
    }

    @Test
    fun `adult and custom media are not routed into public universes`() {
        assertFalse(MediaUniverseClassifier.matches("home", TvType.NSFW, "Adult item"))
        assertFalse(MediaUniverseClassifier.matches("films", TvType.CustomMedia, "Custom player item"))
        assertFalse(MediaUniverseClassifier.shouldLoadDetails("kids", TvType.NSFW, "Kids"))
    }

    @Test
    fun `categories return stable language-independent enum keys sorted by editorial rank`() {
        val filmCats = MediaUniverseClassifier.categorizeForUniverse(
            universeId = "films",
            title = "Mission Impossible",
            sectionName = "Nouveautés Box-Office",
            tags = listOf("Action", "Thriller"),
            effectiveType = TvType.Movie
        )
        assertEquals(
            listOf(MissaCategory.FILMS_FEATURED, MissaCategory.GENRE_ACTION, MissaCategory.GENRE_THRILLER),
            filmCats
        )
        assertTrue(filmCats.zipWithNext().all { (a, b) -> a.editorialRank <= b.editorialRank })

        val radioCats = MediaUniverseClassifier.categorizeForUniverse(
            universeId = "radio",
            title = "Radio Balafon Douala",
            sectionName = "Cameroun Makossa & Info",
            effectiveType = TvType.Audio
        )
        assertEquals(MissaCategory.RADIO_AFRICA, radioCats.first())
        assertTrue(radioCats.contains(MissaCategory.RADIO_ALL))
    }

    @Test
    fun `english and french provider sections map to identical MissaCategory keys`() {
        val enSeries = MediaUniverseClassifier.categorizeForUniverse(
            universeId = "series",
            title = "Arcane",
            sectionName = "Trending Drama Series",
            tags = listOf("Animation", "Sci-Fi"),
            effectiveType = TvType.TvSeries
        )
        val frSeries = MediaUniverseClassifier.categorizeForUniverse(
            universeId = "series",
            title = "Arcane",
            sectionName = "Séries Tendances Drame",
            tags = listOf("Animation", "Science-Fiction"),
            effectiveType = TvType.TvSeries
        )
        assertEquals(enSeries, frSeries)
    }

    @Test
    fun `retro game player maps each supported console extension to its official Libretro core`() {
        assertEquals("sameboy_libretro_android.so", RetroGamePlayerActivity.resolveCoreSpec("gb")?.coreFileName)
        assertEquals("sameboy_libretro_android.so", RetroGamePlayerActivity.resolveCoreSpec("GBC")?.coreFileName)
        assertEquals("fceumm_libretro_android.so", RetroGamePlayerActivity.resolveCoreSpec("nes")?.coreFileName)
        assertEquals("snes9x_libretro_android.so", RetroGamePlayerActivity.resolveCoreSpec("sfc")?.coreFileName)
        assertEquals("snes9x_libretro_android.so", RetroGamePlayerActivity.resolveCoreSpec("smc")?.coreFileName)
        assertEquals("mgba_libretro_android.so", RetroGamePlayerActivity.resolveCoreSpec("gba")?.coreFileName)
        assertEquals("genesis_plus_gx_libretro_android.so", RetroGamePlayerActivity.resolveCoreSpec("md")?.coreFileName)
        assertEquals("genesis_plus_gx_libretro_android.so", RetroGamePlayerActivity.resolveCoreSpec("sms")?.coreFileName)
        assertEquals(null, RetroGamePlayerActivity.resolveCoreSpec("iso"))
    }

    @Test
    fun `navigation universes cover all 9 content universes plus Home with family separators`() {
        assertEquals(9, MissaUniverses.HUB_SHORTCUTS.size)
        assertEquals(10, MissaUniverses.NAVIGATION_UNIVERSES.size)
        val groups = MissaUniverses.NAVIGATION_UNIVERSES.map { it.familyGroup }
        // [Home(0)] | [Films, Series, Shorts, Kids(1)] | [Live TV, Sports(2)] | [Podcasts, Radio(3)] | [Games(4)]
        assertEquals(listOf(0, 1, 1, 1, 1, 2, 2, 3, 3, 4), groups)
    }
}
