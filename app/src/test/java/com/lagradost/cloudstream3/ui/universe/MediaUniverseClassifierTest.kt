package com.lagradost.cloudstream3.ui.universe

import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaUniverseClassifierTest {
    @Test
    fun `films and manga universe groups all movies, anime movies, anime series, OVAs and manga metadata`() {
        assertTrue(MediaUniverseClassifier.matches("films", TvType.Movie, "Film A"))
        assertTrue(MediaUniverseClassifier.matches("films", TvType.AnimeMovie, "Demon Slayer: Mugen Train"))
        assertTrue(MediaUniverseClassifier.matches("films", TvType.Anime, "Naruto Shippuden"))
        assertTrue(MediaUniverseClassifier.matches("films", TvType.OVA, "Hunter x Hunter OAV"))
        assertTrue(
            MediaUniverseClassifier.matches(
                universeId = "films",
                type = null,
                title = "One Piece Chapitre Spécial",
                sectionName = "Mangas & Shonen"
            )
        )
        assertTrue(
            MediaUniverseClassifier.matches(
                universeId = "films",
                type = TvType.Others,
                title = "Solo Leveling",
                tags = listOf("Manhwa", "Isekai")
            )
        )
        assertFalse(MediaUniverseClassifier.matches("films", TvType.TvSeries, "Series A"))
        assertTrue(MediaUniverseClassifier.matches("series", TvType.TvSeries, "Series A"))
        assertTrue(MediaUniverseClassifier.matches("series", TvType.Anime, "Anime A"))
        assertFalse(MediaUniverseClassifier.matches("series", TvType.Movie, "Film A"))

        val mangaCats = MediaUniverseClassifier.categorizeForUniverse(
            universeId = "films",
            title = "Jujutsu Kaisen 2026",
            sectionName = "Nouveautés Mangas & Animes",
            tags = listOf("Shonen", "Action"),
            effectiveType = TvType.Anime
        )
        assertEquals(
            listOf(MissaCategory.FILMS_FEATURED, MissaCategory.FILMS_MANGA, MissaCategory.GENRE_ACTION),
            mangaCats
        )
    }

    @Test
    fun `music universe groups tracks, albums, music clips, concerts and live music channels`() {
        assertTrue(MediaUniverseClassifier.matches("music", TvType.Music, "Locko - Ndutu"))
        assertTrue(
            MediaUniverseClassifier.matches(
                universeId = "music",
                type = TvType.Video,
                title = "Burna Boy - Official Music Video",
                sectionName = "Clips Officiels Afrobeats"
            )
        )
        assertTrue(
            MediaUniverseClassifier.matches(
                universeId = "music",
                type = null,
                title = "Compilation Makossa & Bikutsi Cameroun",
                sectionName = "Albums & Playlists"
            )
        )
        assertTrue(
            MediaUniverseClassifier.matches(
                universeId = "music",
                type = TvType.Live,
                title = "Trace Africa HD",
                sectionName = "Chaînes Musicales"
            )
        )
        // Pure music video clips belong to Music rather than Movies & Manga
        assertFalse(
            MediaUniverseClassifier.matches(
                universeId = "films",
                type = TvType.Video,
                title = "Burna Boy - Official Music Video",
                sectionName = "Clips Officiels Afrobeats"
            )
        )
        // Podcasts and talk radio do not leak into Music
        assertFalse(MediaUniverseClassifier.matches("music", TvType.Podcast, "Music business podcast"))
        assertFalse(MediaUniverseClassifier.matches("music", TvType.Audio, "BBC Radio 1"))
        assertFalse(MediaUniverseClassifier.matches("music", TvType.Movie, "Inception"))

        val musicCats = MediaUniverseClassifier.categorizeForUniverse(
            universeId = "music",
            title = "Concert Makossa & Afrobeats Live Douala",
            sectionName = "Clips & Concerts Cameroun",
            tags = listOf("Afropop"),
            effectiveType = TvType.Music
        )
        assertEquals(MissaCategory.MUSIC_AFRICA, musicCats.first())
        assertTrue(musicCats.contains(MissaCategory.MUSIC_CLIPS_LIVE))
        assertTrue(musicCats.contains(MissaCategory.MUSIC_ALL))
    }

    @Test
    fun `documentaries universe groups feature docs, docu-series, investigative reports and discovery channels`() {
        assertTrue(MediaUniverseClassifier.matches("documentaries", TvType.Documentary, "Notre Planète"))
        assertFalse(MediaUniverseClassifier.matches("films", TvType.Documentary, "Notre Planète"))
        assertTrue(
            MediaUniverseClassifier.matches(
                universeId = "documentaries",
                type = null,
                title = "Les Civilisations du Cameroun",
                sectionName = "Documentaires & Histoire"
            )
        )
        assertTrue(
            MediaUniverseClassifier.matches(
                universeId = "documentaries",
                type = TvType.TvSeries,
                title = "Formula 1: Drive to Survive",
                tags = listOf("Docu-série", "Sport")
            )
        )
        assertTrue(
            MediaUniverseClassifier.matches(
                universeId = "documentaries",
                type = TvType.Live,
                title = "National Geographic Wild HD",
                sectionName = "Chaînes Découverte"
            )
        )
        assertTrue(MediaUniverseClassifier.matches("documentaries", TvType.Movie, "Football documentary"))
        // Short documentaries cross-cut into both Documentaries and Shorts
        val shortDocUniverses = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.Documentary,
            title = "Faune Sauvage du Waza",
            durationMinutes = 24
        )
        assertTrue(shortDocUniverses.contains("documentaries"))
        assertTrue(shortDocUniverses.contains("shorts"))
        // Podcasts, talk radio and pure feature films do not leak into Documentaries
        assertFalse(MediaUniverseClassifier.matches("documentaries", TvType.Podcast, "Histoire en podcast"))
        assertFalse(MediaUniverseClassifier.matches("documentaries", TvType.Audio, "RFI Afrique"))
        assertFalse(MediaUniverseClassifier.matches("documentaries", TvType.Movie, "Inception"))

        val docCats = MediaUniverseClassifier.categorizeForUniverse(
            universeId = "documentaries",
            title = "Ushuaïa Nature : Faune Sauvage d'Afrique 2026",
            sectionName = "Nouveautés Documentaires",
            tags = listOf("Nature", "Afrique"),
            effectiveType = TvType.Documentary
        )
        assertEquals(
            listOf(
                MissaCategory.DOCS_FEATURED,
                MissaCategory.DOCS_NATURE,
                MissaCategory.DOCS_SOCIETY,
                MissaCategory.DOCS_ALL
            ),
            docCats
        )
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
    fun `navigation universes cover all 11 content universes plus Home with family separators`() {
        assertEquals(11, MissaUniverses.HUB_SHORTCUTS.size)
        assertEquals(12, MissaUniverses.NAVIGATION_UNIVERSES.size)
        val groups = MissaUniverses.NAVIGATION_UNIVERSES.map { it.familyGroup }
        // [Home(0)] | [Films & Manga, Series, Documentaries, Shorts, Kids(1)] | [Live TV, Sports(2)] | [Music, Podcasts, Radio(3)] | [Games(4)]
        assertEquals(listOf(0, 1, 1, 1, 1, 1, 2, 2, 3, 3, 3, 4), groups)
        assertEquals("documentaries", MissaUniverses.DOCUMENTARIES.id)
        assertEquals("music", MissaUniverses.MUSIC.id)
    }

    @Test
    fun `contextual filter bar is hidden on Home and displayed inside Films, Documentaries, Music and dedicated universes`() {
        assertFalse(MediaUniverseFragment.shouldShowContextualFilterBar("home"))
        assertFalse(MediaUniverseFragment.shouldShowContextualFilterBar("HOME"))
        assertTrue(MediaUniverseFragment.shouldShowContextualFilterBar("films"))
        assertTrue(MediaUniverseFragment.shouldShowContextualFilterBar("series"))
        assertTrue(MediaUniverseFragment.shouldShowContextualFilterBar("documentaries"))
        assertTrue(MediaUniverseFragment.shouldShowContextualFilterBar("shorts"))
        assertTrue(MediaUniverseFragment.shouldShowContextualFilterBar("kids"))
        assertTrue(MediaUniverseFragment.shouldShowContextualFilterBar("live_tv"))
        assertTrue(MediaUniverseFragment.shouldShowContextualFilterBar("sports"))
        assertTrue(MediaUniverseFragment.shouldShowContextualFilterBar("music"))
        assertTrue(MediaUniverseFragment.shouldShowContextualFilterBar("podcasts"))
        assertTrue(MediaUniverseFragment.shouldShowContextualFilterBar("radio"))
    }

    @Test
    fun `no-plugin setup card never appears on any page when no plugin is installed yet`() {
        // When activeProviderCount == 0, resolveEmptyReason returns null across pages (no "Aucune source vidéo configurée" card)
        val whenNoPlugins = MediaUniverseViewModel.resolveEmptyReason(
            effectiveLoading = false,
            hasVisibleRows = false,
            hasActiveFilter = false,
            isOffline = false,
            hasError = false,
            activeProviderCount = 0
        )
        assertEquals(null, whenNoPlugins)

        // Active user filter still cleanly reports EMPTY_SEARCH_OR_FILTER
        val whenFiltered = MediaUniverseViewModel.resolveEmptyReason(
            effectiveLoading = false,
            hasVisibleRows = false,
            hasActiveFilter = true,
            isOffline = false,
            hasError = false,
            activeProviderCount = 0
        )
        assertEquals(UniverseEmptyReason.EMPTY_SEARCH_OR_FILTER, whenFiltered)

        // When providers are installed and a specific universe has 0 items, returns EMPTY_UNIVERSE
        val whenProvidersInstalledEmpty = MediaUniverseViewModel.resolveEmptyReason(
            effectiveLoading = false,
            hasVisibleRows = false,
            hasActiveFilter = false,
            isOffline = false,
            hasError = false,
            activeProviderCount = 3
        )
        assertEquals(UniverseEmptyReason.EMPTY_UNIVERSE, whenProvidersInstalledEmpty)
    }

    @Test
    fun `debug and prerelease build variants do not override MISSA TV 360 launcher icons`() {
        val projectAppDir = java.io.File("src").takeIf { it.isDirectory }
            ?: java.io.File("app/src")
        for (variant in listOf("debug", "prerelease")) {
            val variantDir = java.io.File(projectAppDir, variant)
            if (variantDir.exists()) {
                val forbiddenIcons = variantDir.walkTopDown()
                    .filter { it.isFile && (it.name.contains("launcher") || it.parentFile?.name?.startsWith("mipmap") == true) }
                    .toList()
                assertTrue(
                    "Variant '$variant' must not contain legacy launcher overrides: $forbiddenIcons",
                    forbiddenIcons.isEmpty()
                )
            }
        }
    }
}
