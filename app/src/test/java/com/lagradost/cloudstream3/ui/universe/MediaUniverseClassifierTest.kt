package com.lagradost.cloudstream3.ui.universe

import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod

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
        // Mangas/Animes/OVAs are grouped exclusively in Films & Mangas, never duplicated in Series
        assertFalse(MediaUniverseClassifier.matches("series", TvType.Anime, "Anime A"))
        assertFalse(MediaUniverseClassifier.matches("series", TvType.OVA, "Hunter x Hunter OAV"))
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
    fun `music universe strictly isolates on-demand music tracks, albums and mixes without radio, TV or video`() {
        assertTrue(MediaUniverseClassifier.matches("music", TvType.Music, "Locko - Ndutu"))
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
                type = TvType.Audio,
                title = "DJ Mix Amapiano & Afrobeats",
                sectionName = "Afro Tracks"
            )
        )

        // Radio stations (even music radios) belong strictly to Radio, never to Music
        assertFalse(MediaUniverseClassifier.matches("music", TvType.Audio, "BBC Radio 1"))
        assertFalse(MediaUniverseClassifier.matches("music", TvType.Music, "Radio Balafon 90.2 FM"))
        assertFalse(
            MediaUniverseClassifier.matches(
                universeId = "music",
                type = TvType.Audio,
                title = "Webradio Zouk & Makossa",
                sectionName = "Flux Icecast"
            )
        )
        assertTrue(MediaUniverseClassifier.matches("radio", TvType.Audio, "Radio Balafon 90.2 FM"))

        // Live TV music channels belong to Live TV, never to Music
        assertFalse(
            MediaUniverseClassifier.matches(
                universeId = "music",
                type = TvType.Live,
                title = "Trace Africa HD",
                sectionName = "Chaînes Musicales"
            )
        )
        assertTrue(
            MediaUniverseClassifier.matches(
                universeId = "live_tv",
                type = TvType.Live,
                title = "Trace Africa HD",
                sectionName = "Chaînes Musicales"
            )
        )

        // Videos, movies (even with musical words like "Soul" or "Rock"), series, and podcasts never leak into Music
        assertFalse(
            MediaUniverseClassifier.matches(
                universeId = "music",
                type = TvType.Video,
                title = "Burna Boy - Official Music Video",
                sectionName = "Clips Officiels Afrobeats"
            )
        )
        assertFalse(
            MediaUniverseClassifier.matches(
                universeId = "music",
                type = null,
                title = "Soul",
                sectionName = "Films Animation",
                providerSupportedTypes = setOf(TvType.Movie, TvType.TvSeries)
            )
        )
        assertFalse(MediaUniverseClassifier.matches("music", TvType.Podcast, "Music business podcast"))
        assertFalse(MediaUniverseClassifier.matches("music", TvType.Movie, "Inception"))
        assertFalse(MediaUniverseClassifier.matches("music", TvType.TvSeries, "Empire Saison 1"))

        val musicCats = MediaUniverseClassifier.categorizeForUniverse(
            universeId = "music",
            title = "DJ Mix Makossa & Amapiano Douala",
            sectionName = "Afrobeats & Club",
            tags = listOf("Audius"),
            isTrending = true,
            effectiveType = TvType.Music
        )
        assertEquals(MissaCategory.MUSIC_TRENDING, musicCats.first())
        assertTrue(musicCats.contains(MissaCategory.MUSIC_AFRICA))
        assertTrue(musicCats.contains(MissaCategory.MUSIC_DJ_MIXES))
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

    private data class FakeTestSearchResponse(
        override val name: String,
        override val url: String,
        override val apiName: String,
        override var type: TvType?,
        override var posterUrl: String? = "https://img.example/poster.jpg",
        override var posterHeaders: Map<String, String>? = null,
        override var id: Int? = url.hashCode(),
        override var quality: com.lagradost.cloudstream3.SearchQuality? = null,
        override var score: com.lagradost.cloudstream3.Score? = null
    ) : com.lagradost.cloudstream3.SearchResponse

    @Test
    fun `sloppy plugin types and multi-plugin conflicts never duplicate series into films`() {
        // 1. Sloppy plugin sets TvType.Movie on a series inside a Series section or /serie/ URL
        val seriesByUrl = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.Movie,
            title = "Stranger Things",
            sectionName = "Tendances",
            url = "https://plugin-a.example/serie/stranger-things"
        )
        assertEquals(setOf("series"), seriesByUrl)

        val seriesBySection = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.Movie,
            title = "Prison Break",
            sectionName = "Séries Populaires"
        )
        assertEquals(setOf("series"), seriesBySection)

        val seriesBySeasonTitle = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.Movie,
            title = "House of the Dragon S02",
            sectionName = "Nouveautés"
        )
        assertEquals(setOf("series"), seriesBySeasonTitle)

        // 2. Sloppy plugin sets TvType.TvSeries on a feature film inside Box-Office / /film/ URL
        val movieBySectionAndUrl = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.TvSeries,
            title = "Gladiator II",
            sectionName = "Films Box-Office",
            url = "https://plugin-b.example/film/gladiator-2"
        )
        assertEquals(setOf("films"), movieBySectionAndUrl)

        // 3. Star Wars Episode titles remain feature films
        val starWars = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.Movie,
            title = "Star Wars : Épisode 3 - La Revanche des Sith",
            sectionName = "Films Science-Fiction"
        )
        assertEquals(setOf("films"), starWars)

        // 4. Cross-plugin deduplication merges sloppy Movie + proven TvSeries into a single Series entry
        val merged = MediaUniverseViewModel.deduplicateHarvestedItems(
            listOf(
                RawHarvestedItem(
                    response = FakeTestSearchResponse(
                        name = "Breaking Bad [VF] 1080p",
                        url = "https://sloppy.example/watch/breaking-bad",
                        apiName = "SloppyPlugin",
                        type = TvType.Movie
                    ),
                    sectionName = "Tendances",
                    isHorizontalSection = false,
                    providerSupportedTypes = setOf(TvType.Movie, TvType.TvSeries),
                    isPreferredLanguage = true
                ),
                RawHarvestedItem(
                    response = FakeTestSearchResponse(
                        name = "Breaking Bad",
                        url = "https://clean.example/serie/breaking-bad",
                        apiName = "CleanSeriesPlugin",
                        type = TvType.TvSeries
                    ),
                    sectionName = "Séries TV",
                    isHorizontalSection = false,
                    providerSupportedTypes = setOf(TvType.Movie, TvType.TvSeries),
                    isPreferredLanguage = true
                )
            )
        )
        assertEquals(1, merged.size)
        assertEquals(2, merged.first().sources.size)
        assertEquals(TvType.TvSeries, merged.first().effectiveType)
        assertEquals(setOf("series"), merged.first().universes)
    }

    @Test
    fun `live sporting events and sports channels route exclusively to sports and never to live_tv or films`() {
        val uclMatch = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.Live,
            title = "21:00 | Real Madrid vs FC Barcelone",
            sectionName = "Événements en Direct"
        )
        assertEquals(setOf("sports"), uclMatch)

        val afconKickoff = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.Live,
            title = "20h45 Cameroun - Sénégal",
            sectionName = "Directs du jour"
        )
        assertEquals(setOf("sports"), afconKickoff)

        val untypedLiveEvent = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.Others,
            title = "Arsenal vs Chelsea HD",
            sectionName = "Live Events",
            url = "https://live.example/direct/match1.m3u8"
        )
        assertEquals(setOf("sports"), untypedLiveEvent)

        val equipeChannel = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.Live,
            title = "L'Équipe TV HD",
            sectionName = "Chaînes Françaises"
        )
        assertEquals(setOf("sports"), equipeChannel)

        // VOD action movies with "vs" stay in Films & Mangas, never Sports
        val movieVs = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.Movie,
            title = "Godzilla vs Kong",
            sectionName = "Films Action"
        )
        assertEquals(setOf("films"), movieVs)
    }

    @Test
    fun `children films enter kids via animation genres, Tous publics or PG ratings, and family franchises while blocking adult animation`() {
        // Non-manga animated feature film in an "Animation" section enters Kids
        assertTrue(
            MediaUniverseClassifier.matches(
                universeId = "kids",
                type = TvType.Movie,
                title = "Migration",
                sectionName = "Films d'Animation"
            )
        )
        // Popular kids franchises in generic Box-Office rows enter Kids even before detail tags load
        assertTrue(MediaUniverseClassifier.matches("kids", TvType.Movie, "Vaiana 2", sectionName = "Box-Office"))
        assertTrue(MediaUniverseClassifier.matches("kids", TvType.Movie, "Vice-Versa 2", sectionName = "Tendances"))
        assertTrue(MediaUniverseClassifier.matches("kids", TvType.Movie, "Kung Fu Panda 4", sectionName = "Nouveautés"))
        assertTrue(MediaUniverseClassifier.matches("kids", TvType.Movie, "Moi, Moche et Méchant 4"))

        // French "Tous publics" and international "PG" / "TV-PG" enter Kids, while "PG-13" does not
        assertTrue(MediaUniverseClassifier.matches("kids", TvType.Movie, "L'Odyssée Magique", contentRating = "Tous publics"))
        assertTrue(MediaUniverseClassifier.matches("kids", TvType.Movie, "Paddington au Pérou", contentRating = "PG"))
        assertFalse(MediaUniverseClassifier.matches("kids", TvType.Movie, "Blockbuster Action", contentRating = "PG-13"))

        // Adult Western animation and teen/seinen anime do not leak into Kids
        assertFalse(
            MediaUniverseClassifier.matches(
                universeId = "kids",
                type = TvType.TvSeries,
                title = "Rick and Morty",
                tags = listOf("Animation", "Comédie")
            )
        )
        assertFalse(
            MediaUniverseClassifier.matches(
                universeId = "kids",
                type = TvType.Anime,
                title = "Jujutsu Kaisen",
                tags = listOf("Animation", "Action", "Shonen")
            )
        )

        // French "Dessins animés" is kids/cartoon animation, NOT Japanese manga
        val frenchCartoon = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.Cartoon,
            title = "Pat'Patrouille",
            sectionName = "Dessins animés"
        )
        assertEquals(setOf("series", "kids"), frenchCartoon)

        // Documentaries tagged on Movie/TvSeries items do not duplicate into Films or Series
        val docSeries = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.TvSeries,
            title = "Formula 1: Drive to Survive",
            tags = listOf("Docu-série", "Sport")
        )
        assertEquals(setOf("documentaries"), docSeries)
    }

    @Test
    fun `free radio providers parse RadioBrowser Zeno SomaFM LautFM M3U PLS and Icecast cleanly and merge fallback mirrors`() {
        // 1. Instant curated bouquets cover MISSA Bouquet Direct, SomaFM, and Laut.fm with valid URLs and brand logos
        val curated = MissaRadioProviders.curatedInstantStations
        assertTrue(curated.size >= 30)
        assertTrue(curated.all { MissaRadioProviders.isValidStreamUrl(it.streamUrl) })
        assertTrue(curated.all { !it.logoUrl.isNullOrBlank() && !it.logoUrl!!.contains("infomaniak") })
        assertTrue(MissaUniverses.RADIO.isHorizontalCards)
        val fipJazz = curated.first { it.id == "fip_jazz" }
        assertEquals(MissaRadioProviders.PROVIDER_MISSA_DIRECT, fipJazz.providerName)
        assertTrue(fipJazz.fallbackUrls.any { it.endsWith(".aac") })
        assertEquals(MissaRadioProviders.PROVIDER_MISSA_DIRECT, fipJazz.toSearchResponse().apiName)
        val genericCdnPreset = RadioStationPreset(
            id = "test_cdn",
            name = "Community Radio",
            genre = "Afrique",
            country = "CM",
            streamUrl = "https://stream.zeno.fm/test-cm",
            logoUrl = null
        )
        assertEquals(null, genericCdnPreset.toSearchResponse().posterUrl)

        // 2. Radio Browser parser extracts resolved + raw fallback URL and attributes Zeno.FM streams automatically
        val rbJson = """
            [
              {
                "stationuuid": "11111111-2222-3333-4444-555555555555",
                "name": "Sweet FM Douala",
                "url": "https://stream.zeno.fm/sweetfm-cm",
                "url_resolved": "https://node-12.zeno.fm/sweetfm-cm",
                "homepage": "https://sweetfm.cm",
                "favicon": "",
                "tags": "cameroun,makossa,afrobeats,info",
                "countrycode": "cm"
              },
              {
                "stationuuid": "66666666-7777-8888-9999-000000000000",
                "name": "Couleur 3",
                "url": "https://stream.srg-ssr.ch/m/couleur3/mp3_128",
                "url_resolved": "https://stream.srg-ssr.ch/m/couleur3/mp3_128",
                "favicon": "https://cdn.rts.ch/couleur3.png",
                "tags": "pop,rock,francophonie",
                "countrycode": "CH"
              }
            ]
        """.trimIndent()
        val rbParsed = MissaRadioProviders.parseRadioBrowserArray(rbJson)
        assertEquals(2, rbParsed.size)
        assertEquals(MissaRadioProviders.PROVIDER_ZENO_AFRICA, rbParsed[0].providerName)
        assertEquals("CM", rbParsed[0].country)
        assertEquals(listOf("https://stream.zeno.fm/sweetfm-cm"), rbParsed[0].fallbackUrls)
        assertTrue(rbParsed[0].logoUrl?.contains("sweetfm.cm") == true)
        assertEquals(MissaRadioProviders.PROVIDER_RADIO_BROWSER, rbParsed[1].providerName)

        // 3. SomaFM JSON parser builds 512px artwork + 3 backup Icecast mirrors
        val somaJson = """
            {
              "channels": [
                {
                  "id": "groovesalad",
                  "title": "Groove Salad",
                  "description": "A nicely chilled plate of ambient/downtempo beats.",
                  "genre": "ambient|electronica",
                  "xlimage": "https://api.somafm.com/logos/512/groovesalad512.png"
                }
              ]
            }
        """.trimIndent()
        val somaParsed = MissaRadioProviders.parseSomaFmChannelsJson(somaJson)
        assertEquals(1, somaParsed.size)
        assertEquals(MissaRadioProviders.PROVIDER_SOMAFM, somaParsed[0].providerName)
        assertEquals(3, somaParsed[0].fallbackUrls.size)

        // 4. Laut.fm JSON parser extracts display_name, stream URL, and station logo
        val lautJson = """
            [
              {
                "name": "afrobeats",
                "display_name": "Laut.fm Afrobeats",
                "stream_url": "https://stream.laut.fm/afrobeats",
                "genres": ["Afrobeats", "Amapiano"],
                "images": { "station_120x120": "https://assets.laut.fm/afrobeats_120.jpg" }
              }
            ]
        """.trimIndent()
        val lautParsed = MissaRadioProviders.parseLautFmStationsJson(lautJson)
        assertEquals(1, lautParsed.size)
        assertEquals(MissaRadioProviders.PROVIDER_LAUT_FM, lautParsed[0].providerName)
        assertEquals("https://assets.laut.fm/afrobeats_120.jpg", lautParsed[0].logoUrl)

        // 5. M3U & PLS parsers extract multi-station lists while ignoring single HLS .m3u8 manifests
        val hlsManifest = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            segment0.ts
        """.trimIndent()
        assertTrue(MissaRadioProviders.isSingleHlsStreamManifest(hlsManifest))
        assertTrue(MissaRadioProviders.parsePlaylistContent(hlsManifest).isEmpty())

        val m3uPlaylist = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://img.example/abidjan.png" tvg-country="CI" group-title="Afrique;Gospel", Fréquence Vie Abidjan
            https://stream.example.org/frequence-vie.mp3
            #EXTINF:-1 group-title="Jazz", Jazz Radio Paris
            https://stream.zeno.fm/jazz-afrique
        """.trimIndent()
        val m3uParsed = MissaRadioProviders.parsePlaylistContent(m3uPlaylist)
        assertEquals(2, m3uParsed.size)
        assertEquals("CI", m3uParsed[0].country)
        assertEquals("https://img.example/abidjan.png", m3uParsed[0].logoUrl)
        assertEquals(MissaRadioProviders.PROVIDER_M3U_PLAYLISTS, m3uParsed[0].providerName)
        assertEquals(MissaRadioProviders.PROVIDER_ZENO_AFRICA, m3uParsed[1].providerName)

        val plsPlaylist = """
            [playlist]
            numberofentries=2
            File1=https://ice1.somafm.com/lush-128-mp3
            Title1=SomaFM: Lush (#1): Mellow vocals
            File2=https://ice2.somafm.com/lush-128-mp3
            Title2=SomaFM: Lush (#2): Mellow vocals
        """.trimIndent()
        val plsParsed = MissaRadioProviders.parsePlaylistContent(plsPlaylist)
        assertEquals(1, plsParsed.size)
        assertEquals(listOf("https://ice2.somafm.com/lush-128-mp3"), plsParsed[0].fallbackUrls)

        // 6. Icecast Xiph XML parser skips low-bitrate/unnamed entries and decodes XML entities
        val xiphXml = """
            <directory>
              <entry>
                <server_name>Unspecified name</server_name>
                <listen_url>http://ice.example.org/bad.mp3</listen_url>
                <bitrate>128</bitrate>
              </entry>
              <entry>
                <server_name> Jazz &amp; Soul Lounge </server_name>
                <listen_url>https://ice.example.org/jazzsoul.mp3</listen_url>
                <genre>Jazz Soul</genre>
                <bitrate>128</bitrate>
              </entry>
            </directory>
        """.trimIndent()
        val xiphParsed = MissaRadioProviders.parseIcecastXiphXml(xiphXml)
        assertEquals(1, xiphParsed.size)
        assertEquals("Jazz & Soul Lounge", xiphParsed[0].name)
        assertEquals(MissaRadioProviders.PROVIDER_ICECAST_XIPH, xiphParsed[0].providerName)

        // 7. Cross-provider deduplication merges alternate stream URLs of identical (station, country) into fallbackUrls
        val mergedCatalog = MissaRadioProviders.mergeStationCatalogues(
            listOf(
                RadioStationPreset(
                    id = "a",
                    name = "Radio Balafon 90.3",
                    genre = "Cameroun • Makossa",
                    country = "CM",
                    streamUrl = "https://primary.example/balafon.mp3",
                    providerName = MissaRadioProviders.PROVIDER_MISSA_DIRECT
                ),
                RadioStationPreset(
                    id = "b",
                    name = "Balafon FM 90.3",
                    genre = "Douala",
                    country = "CM",
                    streamUrl = "https://backup.example/balafon-aac.mp3",
                    logoUrl = "https://balafon.cm/logo.png",
                    providerName = MissaRadioProviders.PROVIDER_RADIO_BROWSER
                )
            )
        )
        assertEquals(1, mergedCatalog.size)
        assertEquals("https://balafon.cm/logo.png", mergedCatalog[0].logoUrl)
        assertEquals(listOf("https://backup.example/balafon-aac.mp3"), mergedCatalog[0].fallbackUrls)

        // 8. Country-alias & accent-insensitive search + expanded Radio editorial categories
        assertTrue(MissaRadioProviders.matchesStationQuery(mergedCatalog[0], "Cameroun"))
        assertTrue(MissaRadioProviders.matchesStationQuery(mergedCatalog[0], "Afrique"))
        assertTrue(MissaRadioProviders.matchesStationQuery(m3uParsed[0], "Côte d'Ivoire"))

        val cmCats = MediaUniverseClassifier.categorizeForUniverse(
            universeId = "radio",
            title = "Sweet FM Douala",
            sectionName = "Makossa • Afrobeats",
            tags = listOf("country:CM", MissaRadioProviders.PROVIDER_ZENO_AFRICA),
            effectiveType = TvType.Audio
        )
        assertTrue(cmCats.contains(MissaCategory.RADIO_AFRICA))
        assertTrue(cmCats.contains(MissaCategory.RADIO_AFRO_URBAN))

        val relaxCats = MediaUniverseClassifier.categorizeForUniverse(
            universeId = "radio",
            title = "SomaFM Groove Salad",
            sectionName = "Ambient, Chillout & Downtempo",
            tags = listOf("country:US", MissaRadioProviders.PROVIDER_SOMAFM),
            effectiveType = TvType.Audio
        )
        assertTrue(relaxCats.contains(MissaCategory.RADIO_RELAX))

        val francoCats = MediaUniverseClassifier.categorizeForUniverse(
            universeId = "radio",
            title = "France Culture",
            sectionName = "Savoirs & Documentaires",
            tags = listOf("country:FR", MissaRadioProviders.PROVIDER_MISSA_DIRECT),
            effectiveType = TvType.Audio
        )
        assertTrue(francoCats.contains(MissaCategory.RADIO_FRANCOPHONE))
        assertTrue(francoCats.contains(MissaCategory.RADIO_NEWS))
    }

    @Test
    fun `dedicated music providers parse Audius, HearThis, Funkwhale, ccMixter, OpenSubsonic, Piped and M3U tracks`() {
        assertTrue(MissaMusicProviders.curatedStarterTracks.isNotEmpty())
        assertTrue(MissaUniverses.MUSIC.directPlayOnClick)

        // 1. Audius host discovery + multi-node track stream & fallback URLs
        val audiusHosts = MissaMusicProviders.parseAudiusHostListJson(
            """{"data":["https://node1.audius.example/","https://node2.audius.example"]}"""
        )
        assertEquals(listOf("https://node1.audius.example", "https://node2.audius.example"), audiusHosts)

        val audiusTracks = MissaMusicProviders.parseAudiusTracksJson(
            """
            {
              "data": [
                {
                  "id": "D7KyD",
                  "title": "Douala Sunset Vibes",
                  "genre": "Afrobeats",
                  "mood": "Upbeat",
                  "tags": "makossa,cameroun",
                  "duration": 215,
                  "is_stream_gated": false,
                  "artwork": {"480x480": "https://art.audius.example/cover.jpg"},
                  "user": {"name": "Kocee Vibes", "handle": "koceevibes"}
                },
                {
                  "id": "GATED1",
                  "title": "Locked Track",
                  "duration": 180,
                  "is_stream_gated": true
                }
              ]
            }
            """.trimIndent(),
            nodes = audiusHosts,
            markTrending = true
        )
        assertEquals(1, audiusTracks.size)
        assertEquals("Kocee Vibes — Douala Sunset Vibes", audiusTracks[0].displayLabel())
        assertEquals("03:35", audiusTracks[0].toSearchResponse().formattedDuration())
        assertTrue(audiusTracks[0].streamUrl.startsWith("https://node1.audius.example/v1/tracks/D7KyD/stream"))
        assertEquals(1, audiusTracks[0].fallbackUrls.size)
        assertTrue(audiusTracks[0].fallbackUrls[0].startsWith("https://node2.audius.example/v1/tracks/D7KyD/stream"))

        // 2. HearThis.at direct MP3 tracks & DJ mixes
        val hearThisTracks = MissaMusicProviders.parseHearThisJson(
            """
            [
              {
                "id": "9912",
                "private": "0",
                "title": "Amapiano Club Mix Vol. 4",
                "genre": "Dance",
                "duration": "3725",
                "stream_url": "https://listen.hearthis.at/mix4.mp3",
                "artwork_url": "https://img.hearthis.at/mix4.jpg",
                "user": {"username": "DJ AfroKing"}
              }
            ]
            """.trimIndent()
        )
        assertEquals(1, hearThisTracks.size)
        assertEquals("1:02:05", hearThisTracks[0].toSearchResponse().formattedDuration())
        assertEquals(MissaMusicProviders.PROVIDER_HEARTHIS, hearThisTracks[0].providerName)

        // 3. Funkwhale open music pod (`open.audio`)
        val fwTracks = MissaMusicProviders.parseFunkwhaleTracksJson(
            """
            {
              "results": [
                {
                  "id": 4242,
                  "title": "Midnight Saxophone",
                  "is_playable": true,
                  "listen_url": "/api/v1/listen/abc-123/?upload=xyz",
                  "artist": {"name": "Blue Quartet"},
                  "album": {
                    "title": "Live in Paris",
                    "cover": {"urls": {"medium_square_crop": "/media/covers/sax.jpg"}}
                  },
                  "uploads": [{"duration": 264}],
                  "tags": ["jazz", "saxophone"]
                }
              ]
            }
            """.trimIndent(),
            podBaseUrl = "https://open.audio"
        )
        assertEquals(1, fwTracks.size)
        assertEquals("https://open.audio/api/v1/listen/abc-123/?upload=xyz", fwTracks[0].streamUrl)
        assertEquals("https://open.audio/media/covers/sax.jpg", fwTracks[0].artworkUrl)
        assertEquals(264, fwTracks[0].durationSeconds)

        // 4. ccMixter Creative Commons MP3 extractor
        val ccTracks = MissaMusicProviders.parseCcMixterJson(
            """
            [
              {
                "upload_id": 7788,
                "upload_name": "Urban Groove Beat",
                "user_real_name": "airtone",
                "upload_extra": {"usertags": "hip_hop,chill,instrumental"},
                "files": [
                  {
                    "download_url": "https://ccmixter.org/content/airtone/airtone_groove.mp3",
                    "file_format_info": {"ps": "3:18"}
                  }
                ]
              }
            ]
            """.trimIndent()
        )
        assertEquals(1, ccTracks.size)
        assertEquals(198, ccTracks[0].durationSeconds)

        // 5. OpenSubsonic / Navidrome MD5 auth & song parser (skips videos)
        val subConfig = OpenSubsonicServerConfig("https://navidrome.example.com/", "alice", "s3cr3t")
        val subTracks = MissaMusicProviders.parseOpenSubsonicSongsJson(
            """
            {
              "subsonic-response": {
                "status": "ok",
                "randomSongs": {
                  "song": [
                    {
                      "id": "tr-101",
                      "title": "Bikutsi Royal",
                      "artist": "Lady Ponce",
                      "album": "Essentiel",
                      "genre": "Cameroun",
                      "duration": 248,
                      "coverArt": "al-101",
                      "isVideo": false
                    },
                    {
                      "id": "vid-999",
                      "title": "Video Clip",
                      "isVideo": true
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
            config = subConfig,
            salt = "testsalt"
        )
        assertEquals(1, subTracks.size)
        assertTrue(subTracks[0].streamUrl.startsWith("https://navidrome.example.com/rest/stream.view?id=tr-101"))
        assertTrue(subTracks[0].artworkUrl!!.startsWith("https://navidrome.example.com/rest/getCoverArt.view?id=al-101"))

        // 6. Piped Music (`filter=music_songs`) skips 24/7 live radio streams (`duration == -1`) and resolves m4a first
        val pipedSearch = MissaMusicProviders.parsePipedMusicSearchJson(
            """
            {
              "items": [
                {
                  "url": "/watch?v=abcDEF12345",
                  "type": "stream",
                  "title": "Calm Down",
                  "uploaderName": "Rema - Topic",
                  "thumbnail": "https://pipedproxy.example/thumb.jpg",
                  "duration": 239,
                  "isShort": false
                },
                {
                  "url": "/watch?v=liveRADIO99",
                  "type": "stream",
                  "title": "24/7 Lofi Radio Live",
                  "uploaderName": "Live Channel",
                  "duration": -1,
                  "isShort": false
                }
              ]
            }
            """.trimIndent()
        )
        assertEquals(1, pipedSearch.size)
        assertEquals("Rema", pipedSearch[0].artist)
        assertEquals("pipedmusic://abcDEF12345", pipedSearch[0].streamUrl)
        assertTrue(pipedSearch[0].isPipedLazy)
        assertTrue(MissaMusicProviders.isLazyYouTubeAudioScheme("ytmusic://abcDEF12345"))
        assertTrue(MissaMusicProviders.isLazyYouTubeAudioScheme("pipedmusic://abcDEF12345"))

        val pipedStreams = MissaMusicProviders.parsePipedAudioStreamsJson(
            """
            {
              "audioStreams": [
                {"url": "https://cdn.example/opus-low.webm", "mimeType": "audio/webm", "bitrate": 160000},
                {"url": "https://cdn.example/aac-high.m4a", "mimeType": "audio/mp4", "bitrate": 130000},
                {"url": "https://cdn.example/aac-low.m4a", "mimeType": "audio/mp4", "bitrate": 64000}
              ]
            }
            """.trimIndent()
        )
        assertEquals("https://cdn.example/aac-high.m4a", pipedStreams.first())

        // 7. Music M3U playlist import parses finite tracks and rejects single HLS stream manifests
        val musicM3u = MissaMusicProviders.parseMusicPlaylistContent(
            """
            #EXTM3U
            #EXTINF:214 tvg-logo="https://example.org/cover1.jpg" group-title="Afrobeats",Tayc - Le Temps
            https://cdn.example.org/music/tayc-le-temps.mp3
            """.trimIndent()
        )
        assertEquals(1, musicM3u.size)
        assertEquals("Tayc", musicM3u[0].artist)
        assertEquals("Le Temps", musicM3u[0].title)
        assertEquals(214, musicM3u[0].durationSeconds)
    }

    @Test
    fun `youtube audio search query sanitization, title cleaning, multi-token matching and stream sorting`() {
        // 1. Stray quotes/apostrophes and extra whitespace are stripped cleanly
        assertEquals("lady ponce", MissaMusicProviders.sanitizeMusicQuery("lady ponce '"))
        assertEquals("Lady Ponce", MissaMusicProviders.sanitizeMusicQuery("  \"Lady   Ponce\" ’  "))
        assertEquals("l'oiseau rare", MissaMusicProviders.sanitizeMusicQuery(" 'l'oiseau rare' "))

        // 2. YouTube Video ID extractor handles watch URLs, music.youtube.com, youtu.be, and lazy schemes
        assertEquals(
            "dQw4w9WgXcQ",
            MissaMusicProviders.extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=RD")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            MissaMusicProviders.extractYouTubeVideoId("https://music.youtube.com/watch?v=dQw4w9WgXcQ")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            MissaMusicProviders.extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ?si=123")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            MissaMusicProviders.extractYouTubeVideoId("ytmusic://dQw4w9WgXcQ")
        )
        assertEquals(
            "dQw4w9WgXcQ",
            MissaMusicProviders.extractYouTubeVideoId("pipedmusic://dQw4w9WgXcQ")
        )

        // 3. YouTube title & uploader cleaner extracts clean artist and song title
        val (cleanTitle1, cleanArtist1) = MissaMusicProviders.cleanYouTubeTitleAndArtist(
            "Lady Ponce - Confession (Clip Officiel)",
            "Lady Ponce Officiel"
        )
        assertEquals("Confession", cleanTitle1)
        assertEquals("Lady Ponce", cleanArtist1)

        val (cleanTitle2, cleanArtist2) = MissaMusicProviders.cleanYouTubeTitleAndArtist(
            "Le Ventre et le Bas-Ventre [Audio Officiel]",
            "Lady Ponce - Topic"
        )
        assertEquals("Le Ventre et le Bas-Ventre", cleanTitle2)
        assertEquals("Lady Ponce", cleanArtist2)

        // 4. Tokenized accent-insensitive matching handles quotes, reversed words, and artist + song queries
        val ladyPonceTrack = MusicTrackPreset(
            id = "ytm_lp_confession",
            title = "Confession",
            artist = "Lady Ponce",
            album = "YouTube Music",
            genre = "Bikutsi & Cameroun",
            streamUrl = "ytmusic://lpConfess01",
            providerName = MissaMusicProviders.PROVIDER_YOUTUBE_MUSIC,
            isPipedLazy = true
        )
        val unrelatedTrack = MusicTrackPreset(
            id = "aud_random_01",
            title = "Midnight Synthwave",
            artist = "Neon Rider",
            album = "Synth",
            genre = "Électro",
            streamUrl = "https://cdn.example.org/neon.mp3",
            providerName = MissaMusicProviders.PROVIDER_AUDIUS
        )
        assertTrue(MissaMusicProviders.matchesTrackQuery(ladyPonceTrack, "lady ponce '"))
        assertTrue(MissaMusicProviders.matchesTrackQuery(ladyPonceTrack, "ponce lady"))
        assertTrue(MissaMusicProviders.matchesTrackQuery(ladyPonceTrack, "lady ponce confession"))
        assertFalse(MissaMusicProviders.matchesTrackQuery(unrelatedTrack, "lady ponce '"))

        // 5. Relevance ranking puts exact YouTube Audio artist matches ahead of weaker partial hits
        val partialHit = MusicTrackPreset(
            id = "aud_lady_02",
            title = "Lady in the Rain (feat. DJ Ponce)",
            artist = "Unknown Producer",
            album = "Mix",
            genre = "Pop",
            streamUrl = "https://cdn.example.org/rain.mp3",
            providerName = MissaMusicProviders.PROVIDER_AUDIUS
        )
        val ranked = MissaMusicProviders.rankTracksForQuery(
            listOf(partialHit, ladyPonceTrack),
            "lady ponce '"
        )
        assertEquals("ytm_lp_confession", ranked.first().id)

        // 6. NewPipe AudioStream sorter prioritizes progressive HTTP M4A (AAC) at highest bitrate for ExoPlayer
        val dashWebm = AudioStream.Builder()
            .setId("251")
            .setContent("https://rr1---sn.googlevideo.com/videoplayback?itag=251", true)
            .setDeliveryMethod(DeliveryMethod.DASH)
            .setMediaFormat(MediaFormat.WEBMA)
            .setAverageBitrate(160)
            .build()
        val progressiveM4aLow = AudioStream.Builder()
            .setId("139")
            .setContent("https://rr1---sn.googlevideo.com/videoplayback?itag=139", true)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setMediaFormat(MediaFormat.M4A)
            .setAverageBitrate(48)
            .build()
        val progressiveM4aHigh = AudioStream.Builder()
            .setId("140")
            .setContent("https://rr1---sn.googlevideo.com/videoplayback?itag=140", true)
            .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
            .setMediaFormat(MediaFormat.M4A)
            .setAverageBitrate(128)
            .build()

        val sortedUrls = MissaMusicProviders.sortNewPipeAudioStreams(
            listOf(dashWebm, progressiveM4aLow, progressiveM4aHigh)
        )
        assertEquals(3, sortedUrls.size)
        assertEquals("https://rr1---sn.googlevideo.com/videoplayback?itag=140", sortedUrls[0])
        assertEquals("https://rr1---sn.googlevideo.com/videoplayback?itag=139", sortedUrls[1])
        assertEquals("https://rr1---sn.googlevideo.com/videoplayback?itag=251", sortedUrls[2])
    }
}
