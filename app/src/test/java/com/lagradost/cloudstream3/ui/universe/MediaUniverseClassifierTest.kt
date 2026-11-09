package com.lagradost.cloudstream3.ui.universe

import android.view.View
import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
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
            title = "Kocee — Makossa & Amapiano Douala",
            sectionName = "Afrobeats & Afro-House",
            tags = listOf("YouTube"),
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
    fun `documentaires and live tab merges VOD documentaries with twitch youtube and discovery live streams`() {
        // 1. On-demand documentaries keep their thematic rows and never land in the live row
        val vodCats = MediaUniverseClassifier.categorizeForUniverse(
            universeId = "documentaries",
            title = "Ushua\u00efa Nature : Faune Sauvage d'Afrique",
            sectionName = "Nouveaut\u00e9s Documentaires",
            tags = listOf("Nature", "Afrique"),
            effectiveType = TvType.Documentary
        )
        assertTrue(vodCats.contains(MissaCategory.DOCS_NATURE))
        assertFalse(vodCats.contains(MissaCategory.DOCS_LIVE))

        // 2. Live-streaming platform channels are detected from the plugin feed url (DOC-LIVE-01)
        val twitchLive = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.Live,
            title = "Arte Documentaire Live",
            sectionName = "Cha\u00eenes live",
            url = "https://www.twitch.tv/artedoc"
        )
        assertTrue(twitchLive.contains("documentaries"))

        val youtubeLive = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.Others,
            title = "NASA Live : Earth from space",
            sectionName = "Directs",
            url = "https://www.youtube.com/live/abc123"
        )
        assertTrue(youtubeLive.contains("documentaries"))

        val kickLive = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.Movie,
            title = "Savoirs & D\u00e9couvertes",
            sectionName = "Cha\u00eenes Live",
            url = "https://kick.com/sciences"
        )
        assertTrue(kickLive.contains("documentaries"))

        // 3. A plain VOD YouTube page is NOT a live: it stays a catalogue documentary
        val youtubeVod = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.Movie,
            title = "Le Monde Secret des Oc\u00e9ans",
            sectionName = "Documentaires",
            url = "https://www.youtube.com/watch?v=abc123"
        )
        assertEquals(setOf("documentaries"), youtubeVod)

        // 4. Knowledge / discovery streams (webcams, zoos, earth cams) join the tab
        assertTrue(
            MediaUniverseClassifier.matches("documentaries", TvType.Live, "Zoo de Beauval - Webcam 24/7")
        )
        assertTrue(
            MediaUniverseClassifier.matches(
                universeId = "documentaries",
                type = TvType.Live,
                title = "Volcans du Cameroun",
                sectionName = "Cam\u00e9ras live"
            )
        )

        // 5. Live sport stays in Sports and 24/7 news stays in Live TV: no cross-contamination
        assertFalse(
            MediaUniverseClassifier.matches(
                universeId = "documentaries",
                type = TvType.Live,
                title = "21:00 Real Madrid vs FC Barcelone",
                sectionName = "\u00c9v\u00e9nements en Direct"
            )
        )
        assertFalse(
            MediaUniverseClassifier.matches("documentaries", TvType.Live, "BFMTV News 24/7")
        )
        assertFalse(
            MediaUniverseClassifier.matches("documentaries", TvType.Live, "BBC Radio 1 Live")
        )

        // 6. Live channels are promoted in a dedicated first row of the tab
        val liveCats = MediaUniverseClassifier.categorizeForUniverse(
            universeId = "documentaries",
            title = "National Geographic Live",
            sectionName = "Cha\u00eenes d\u00e9couverte en direct",
            effectiveType = TvType.Live,
            url = "https://www.twitch.tv/natgeo"
        )
        assertEquals(listOf(MissaCategory.DOCS_LIVE), liveCats)
        assertTrue(MissaCategory.DOCS_LIVE.editorialRank < MissaCategory.DOCS_FEATURED.editorialRank)
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
    fun `navigation universes cover all 10 content universes plus Home with family separators`() {
        assertEquals(10, MissaUniverses.HUB_SHORTCUTS.size)
        assertEquals(11, MissaUniverses.NAVIGATION_UNIVERSES.size)
        val groups = MissaUniverses.NAVIGATION_UNIVERSES.map { it.familyGroup }
        // [Home(0)] | [Films & Manga, Series, Documentaries, Shorts, Kids(1)] | [Live TV, Sports(2)] | [Music, Podcasts, Radio(3)]
        assertEquals(listOf(0, 1, 1, 1, 1, 1, 2, 2, 3, 3, 3), groups)
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

        val whenOffline = MediaUniverseViewModel.resolveEmptyReason(
            effectiveLoading = false,
            hasVisibleRows = false,
            hasActiveFilter = false,
            isOffline = true,
            hasError = true,
            activeProviderCount = 3
        )
        assertEquals(UniverseEmptyReason.OFFLINE_OR_ERROR, whenOffline)
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
        // Stations sans vrai logo connu : la carte affiche leurs initiales (MissaRadioLogosTest).
        val withoutRealLogo = setOf("radio_balafon_cm", "lautfm_afrobeats", "lautfm_gospelradio")
        assertTrue(curated.filter { it.id !in withoutRealLogo }.all { !it.logoUrl.isNullOrBlank() && !it.logoUrl!!.contains("infomaniak") })
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
    fun `youtube-only music engine enforces style onboarding, anti-long-mix filtering, albums and authentic covers`() {
        assertEquals("YouTube", MissaMusicProviders.PROVIDER_YOUTUBE_MUSIC)
        assertTrue(MissaUniverses.MUSIC.directPlayOnClick)
        assertEquals(12, MissaMusicProviders.MUSIC_STYLE_OPTIONS.size)

        // 1. Style selection resolves user styles and generates newest-release YouTube queries across all 4 formats
        val selectedStyles = MissaMusicProviders.resolveSelectedStyles(listOf("cameroon", "amapiano"))
        assertEquals(listOf("cameroon", "amapiano"), selectedStyles.map { it.id })
        val fallbackStyles = MissaMusicProviders.resolveSelectedStyles(emptyList())
        assertEquals(MissaMusicProviders.DEFAULT_STYLE_IDS, fallbackStyles.map { it.id })

        val queries2026 = MissaMusicProviders.buildStyleQueriesForYear(selectedStyles.first(), 2026)
        assertEquals(4, queries2026.size)
        assertTrue(queries2026.all { it.second.endsWith("2026") })

        // 2. Strict Anti-Long-Mix & Duration Filter: allows clips/singles (55s..540s), rejects DJ mixes & compilations
        assertTrue(MissaMusicProviders.isValidIndividualTrackDuration(215L))
        assertFalse(MissaMusicProviders.isValidIndividualTrackDuration(30L))
        assertFalse(MissaMusicProviders.isValidIndividualTrackDuration(1800L))

        assertTrue(MissaMusicProviders.isLongMixOrCompilation("DJ Mix Afrobeats 2026 Non Stop"))
        assertTrue(MissaMusicProviders.isLongMixOrCompilation("Non-Stop Makossa & Bikutsi Party Mix"))
        assertTrue(MissaMusicProviders.isLongMixOrCompilation("Amapiano Club Mix 1 Hour"))
        assertTrue(MissaMusicProviders.isLongMixOrCompilation("Megamix Zouk Kompa 45 min"))
        assertTrue(MissaMusicProviders.isLongMixOrCompilation("Compilation Hits Cameroun 2026"))
        assertTrue(MissaMusicProviders.isLongMixOrCompilation("24/7 Lofi Radio Live"))

        assertFalse(MissaMusicProviders.isLongMixOrCompilation("Lady Ponce - Confession (Clip Officiel)", "Lady Ponce Officiel"))
        assertFalse(MissaMusicProviders.isLongMixOrCompilation("Locko - Sawa Romance", "Locko - Topic"))
        assertFalse(MissaMusicProviders.isLongMixOrCompilation("Tayc - Le Temps (Official Remix)", "Tayc"))

        // 3. Official Albums & Playlists: accepts NORMAL playlists/albums, rejects YouTube autogenerated mixes & DJ mix playlists
        assertTrue(
            MissaMusicProviders.isValidYouTubePlaylistOrAlbum(
                name = "Héritage",
                uploader = "Lady Ponce - Topic",
                url = "https://www.youtube.com/playlist?list=OLAK5uy_album123",
                streamCount = 12L,
                playlistType = PlaylistInfo.PlaylistType.NORMAL
            )
        )
        assertFalse(
            MissaMusicProviders.isValidYouTubePlaylistOrAlbum(
                name = "Mix - Lady Ponce",
                uploader = "YouTube",
                url = "https://www.youtube.com/playlist?list=RDEM12345",
                streamCount = 25L,
                playlistType = PlaylistInfo.PlaylistType.MIX_MUSIC
            )
        )
        assertFalse(
            MissaMusicProviders.isValidYouTubePlaylistOrAlbum(
                name = "DJ Mix Afrobeats Non Stop",
                uploader = "DJ Mix Channel",
                url = "https://www.youtube.com/playlist?list=PL99999",
                streamCount = 15L,
                playlistType = PlaylistInfo.PlaylistType.NORMAL
            )
        )

        // 4. Card badge formatting distinguishes Clips, Albums, and Playlists
        val clipCard = MusicTrackPreset(
            id = "ytm_clip1",
            title = "Confession",
            artist = "Lady Ponce",
            streamUrl = "ytmusic://clip1",
            durationSeconds = 215,
            itemKind = MusicItemKind.CLIP
        ).toSearchResponse()
        val albumCard = MusicTrackPreset(
            id = "ytpl_alb1",
            title = "Héritage",
            artist = "Lady Ponce",
            streamUrl = "ytplaylist://https://www.youtube.com/playlist?list=OLAK5uy_1",
            itemKind = MusicItemKind.ALBUM,
            trackCount = 12
        ).toSearchResponse()
        val playlistCard = MusicTrackPreset(
            id = "ytpl_pl1",
            title = "Hits Cameroun 2026",
            artist = "YouTube Music",
            streamUrl = "ytplaylist://https://www.youtube.com/playlist?list=PL237",
            itemKind = MusicItemKind.PLAYLIST,
            trackCount = 24
        ).toSearchResponse()
        assertEquals("03:35", clipCard.formattedDuration())
        assertEquals("ALBUM • 12", albumCard.formattedDuration())
        assertEquals("PLAYLIST • 24", playlistCard.formattedDuration())
        assertTrue(MissaMusicProviders.isLazyYouTubeAudioScheme("ytmusic://abcDEF12345"))
        assertTrue(MissaMusicProviders.isYouTubePlaylistOrAlbumScheme("ytplaylist://https://www.youtube.com/playlist?list=PL237"))

        // 5. Authentic HD Cover Art upgrade, cross-matching onto Clips & iTunes sleeve verification
        assertEquals(
            "https://lh3.googleusercontent.com/albumSleeve123=w544-h544-l90-rj",
            MissaMusicProviders.upgradeToAuthenticHdArtworkUrl(
                "https://lh3.googleusercontent.com/albumSleeve123=w120-h120-l90-rj"
            )
        )
        assertEquals(
            "https://i.ytimg.com/vi/abcDEF12345/mqdefault.jpg",
            MissaMusicProviders.upgradeToAuthenticHdArtworkUrl(
                "https://i.ytimg.com/vi/abcDEF12345/hqdefault.jpg?sqp=-oaymwEcCNACELwBSFXyq4qpAw4IARUAAIhCGAFwAcABBg=="
            )
        )

        val studioSong = MusicTrackPreset(
            id = "ytm_song_1",
            title = "Calm Down",
            artist = "Rema",
            streamUrl = "ytmusic://song1",
            artworkUrl = "https://lh3.googleusercontent.com/remaCalmDown=w120-h120-l90-rj",
            durationSeconds = 239,
            itemKind = MusicItemKind.SONG
        )
        val videoClip = MusicTrackPreset(
            id = "ytm_clip_1",
            title = "Calm Down",
            artist = "Rema",
            streamUrl = "ytmusic://clip1",
            artworkUrl = "https://i.ytimg.com/vi/WcIcVapfqXw/mqdefault.jpg",
            durationSeconds = 240,
            itemKind = MusicItemKind.CLIP
        )
        val crossMatched = MissaMusicProviders.crossMatchStudioCovers(listOf(studioSong, videoClip))
        assertEquals(
            "https://lh3.googleusercontent.com/remaCalmDown=w544-h544-l90-rj",
            crossMatched[1].artworkUrl
        )

        val itunesArt = MissaMusicProviders.parseItunesCoverArtJson(
            jsonBody = """
                {
                  "resultCount": 1,
                  "results": [
                    {
                      "artistName": "Lady Ponce",
                      "trackName": "Confession",
                      "artworkUrl100": "https://is1-ssl.mzstatic.com/image/thumb/Music126/v4/aa/bb/cover/100x100bb.jpg"
                    }
                  ]
                }
            """.trimIndent(),
            expectedArtist = "Lady Ponce",
            expectedTitle = "Confession"
        )
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music126/v4/aa/bb/cover/600x600bb.jpg",
            itunesArt
        )

        // 6. Recency scoring prioritizes newest releases over older catalog entries
        val recentScore = MissaMusicProviders.computeRecencyScore("il y a 2 jours", "Nouveau Clip 2026", 2026)
        val oldScore = MissaMusicProviders.computeRecencyScore("4 years ago", "Old Song 2019", 2026)
        assertTrue(recentScore > oldScore)
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
            album = "Single",
            genre = "Cameroun • Bikutsi & Makossa",
            streamUrl = "ytmusic://lpConfess01",
            providerName = MissaMusicProviders.PROVIDER_YOUTUBE_MUSIC,
            isPipedLazy = true
        )
        val unrelatedTrack = MusicTrackPreset(
            id = "ytm_random_01",
            title = "Midnight Synthwave",
            artist = "Neon Rider",
            album = "Synth",
            genre = "Électro",
            streamUrl = "ytmusic://neon01",
            providerName = MissaMusicProviders.PROVIDER_YOUTUBE_MUSIC
        )
        assertTrue(MissaMusicProviders.matchesTrackQuery(ladyPonceTrack, "lady ponce '"))
        assertTrue(MissaMusicProviders.matchesTrackQuery(ladyPonceTrack, "ponce lady"))
        assertTrue(MissaMusicProviders.matchesTrackQuery(ladyPonceTrack, "lady ponce confession"))
        assertFalse(MissaMusicProviders.matchesTrackQuery(unrelatedTrack, "lady ponce '"))

        // 5. Relevance ranking puts exact YouTube artist matches ahead of weaker partial hits
        val partialHit = MusicTrackPreset(
            id = "ytm_lady_02",
            title = "Lady in the Rain (feat. Ponce)",
            artist = "Unknown Producer",
            album = "Single",
            genre = "Pop",
            streamUrl = "ytmusic://rain02",
            providerName = MissaMusicProviders.PROVIDER_YOUTUBE_MUSIC
        )
        val ranked = MissaMusicProviders.rankTracksForQuery(
            listOf(partialHit, ladyPonceTrack),
            "lady ponce '"
        )
        assertEquals("ytm_lp_confession", ranked.first().id)

        // 6. NewPipe AudioStream sorter prioritizes progressive HTTP lightweight low-latency (48..96 kbps) ahead of heavy bitrates
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
            listOf(dashWebm, progressiveM4aHigh, progressiveM4aLow)
        )
        assertEquals(3, sortedUrls.size)
        assertEquals("https://rr1---sn.googlevideo.com/videoplayback?itag=139", sortedUrls[0])
        assertEquals("https://rr1---sn.googlevideo.com/videoplayback?itag=140", sortedUrls[1])
        assertEquals("https://rr1---sn.googlevideo.com/videoplayback?itag=251", sortedUrls[2])
    }

    @Test
    fun `tv focus navigation helper keeps columns aligned vertically and horizontally without wrapping`() {
        // 1. Horizontal rail scroll math: visible columns never shift (dx = 0); right overflow steps by exact column width
        // Viewport [24..900] -> usable = 876, card width = 140 -> 6 full columns at x = 24, 164, 304, 444, 584, 724
        assertEquals(
            0,
            MissaTvFocusHelper.computeAlignedHorizontalScrollDx(
                leftEdge = 24,
                rightEdge = 900,
                childLeft = 304,
                childRight = 444,
                childWidth = 140
            )
        )
        // Card 6 at x = 864..1004 overflows rightEdge (900): steps by +140 so Card 6 lands at col 5 (x = 724)
        assertEquals(
            140,
            MissaTvFocusHelper.computeAlignedHorizontalScrollDx(
                leftEdge = 24,
                rightEdge = 900,
                childLeft = 864,
                childRight = 1004,
                childWidth = 140
            )
        )
        // Card clipped on left at x = -116..24 snaps back to leftEdge (24) -> dx = -140
        assertEquals(
            -140,
            MissaTvFocusHelper.computeAlignedHorizontalScrollDx(
                leftEdge = 24,
                rightEdge = 900,
                childLeft = -116,
                childRight = 24,
                childWidth = 140
            )
        )

        // 2. Grid navigation (spanCount = 5, itemCount = 13 -> rows: [0..4], [5..9], [10..12])
        // Right border of row 0 (pos 4) never wraps to row 1 (pos 5)
        assertEquals(
            4,
            MissaTvFocusHelper.computeUniformGridNextPosition(4, 13, 5, View.FOCUS_RIGHT)
        )
        // Left border of row 1 (pos 5, col 0) never wraps to row 0 (pos 4)
        assertEquals(
            5,
            MissaTvFocusHelper.computeUniformGridNextPosition(5, 13, 5, View.FOCUS_LEFT)
        )
        // Vertical DOWN and UP preserve exact column (col 2: 2 -> 7 -> 12 -> 7 -> 2)
        assertEquals(
            7,
            MissaTvFocusHelper.computeUniformGridNextPosition(2, 13, 5, View.FOCUS_DOWN)
        )
        assertEquals(
            12,
            MissaTvFocusHelper.computeUniformGridNextPosition(7, 13, 5, View.FOCUS_DOWN)
        )
        assertEquals(
            7,
            MissaTvFocusHelper.computeUniformGridNextPosition(12, 13, 5, View.FOCUS_UP)
        )
        assertEquals(
            2,
            MissaTvFocusHelper.computeUniformGridNextPosition(7, 13, 5, View.FOCUS_UP)
        )
        // Escaping UP from row 0 returns -1 so header/sort/search controls can receive focus
        assertEquals(
            -1,
            MissaTvFocusHelper.computeUniformGridNextPosition(2, 13, 5, View.FOCUS_UP)
        )
        // Bottom row traps in full-screen See-All sheet (`trapBottomEdge = true`) and escapes when embedded (`false`)
        assertEquals(
            12,
            MissaTvFocusHelper.computeUniformGridNextPosition(12, 13, 5, View.FOCUS_DOWN, trapBottomEdge = true)
        )
        assertEquals(
            -1,
            MissaTvFocusHelper.computeUniformGridNextPosition(12, 13, 5, View.FOCUS_DOWN, trapBottomEdge = false)
        )

        // 3. Column center matching selects exact aligned column across rows
        val rowCenters = listOf(94, 234, 374, 514, 654, 794)
        assertEquals(3, MissaTvFocusHelper.pickClosestHorizontalIndex(rowCenters, 510))

        // 4. Interval overlap adjacency picks the exact cell directly below/above in all layouts
        val posterCols = listOf(
            24 to 164,
            164 to 304,
            304 to 444,
            444 to 584,
            584 to 724,
            724 to 864
        )
        // Exact column match (col 2 -> col 2)
        assertEquals(2, MissaTvFocusHelper.pickBestAdjacentIndex(posterCols, 304, 444))
        // Hero CTA button [24..210] overlaps col 0 [24..164] by 140px vs col 1 [164..304] by 46px -> picks col 0
        assertEquals(0, MissaTvFocusHelper.pickBestAdjacentIndex(posterCols, 24, 210))
        // Shorter row [col 0, col 1] when coming from col 4 [584..724] picks nearest adjacent cell (col 1)
        assertEquals(1, MissaTvFocusHelper.pickBestAdjacentIndex(posterCols.take(2), 584, 724))
    }

    @Test
    fun updaterRepositoryPointsToMissaTvAndRemovesMonkeLocale() {
        assertEquals("missamedia69-code", com.lagradost.cloudstream3.ui.settings.GithubReleases.APK_USERNAME)
        assertEquals("MISSA_TV_360_PLUS", com.lagradost.cloudstream3.ui.settings.GithubReleases.APK_REPOSITORY)
        assertFalse(
            "Easter-egg monke locale ('qt') must not appear in supported app languages",
            com.lagradost.cloudstream3.ui.settings.appLanguages.any { it.second == "qt" }
        )
    }

    @Test
    fun `sophisticated MP3 player selects crisp 480p-720p clip streams and tracks playlist clips state`() {
        val muxed360 = org.schabi.newpipe.extractor.stream.VideoStream.Builder()
            .setId("18")
            .setContent("https://rr1.googlevideo.com/muxed_360.mp4", true)
            .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.MPEG_4)
            .setDeliveryMethod(org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP)
            .setIsVideoOnly(false)
            .setResolution("360p")
            .build()
        val muxed720 = org.schabi.newpipe.extractor.stream.VideoStream.Builder()
            .setId("22")
            .setContent("https://rr1.googlevideo.com/muxed_720.mp4", true)
            .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.MPEG_4)
            .setDeliveryMethod(org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP)
            .setIsVideoOnly(false)
            .setResolution("720p")
            .build()
        val videoOnly480 = org.schabi.newpipe.extractor.stream.VideoStream.Builder()
            .setId("135")
            .setContent("https://rr1.googlevideo.com/video_only_480.mp4", true)
            .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.MPEG_4)
            .setDeliveryMethod(org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP)
            .setIsVideoOnly(true)
            .setResolution("480p")
            .build()
        val videoOnly1440 = org.schabi.newpipe.extractor.stream.VideoStream.Builder()
            .setId("271")
            .setContent("https://rr1.googlevideo.com/video_only_1440.webm", true)
            .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.WEBM)
            .setDeliveryMethod(org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP)
            .setIsVideoOnly(true)
            .setResolution("1440p")
            .build()

        val (bestMuxed, bestVideoOnly) = MissaMusicProviders.selectBestVideoStreams(
            muxedStreams = listOf(muxed720, muxed360),
            videoOnlyStreams = listOf(videoOnly1440, videoOnly480)
        )
        assertEquals("https://rr1.googlevideo.com/muxed_360.mp4", bestMuxed)
        assertEquals("https://rr1.googlevideo.com/video_only_480.mp4", bestVideoOnly)

        val sampleClips = listOf(
            MusicTrackPreset(
                id = "ytm_abc1",
                title = "Sawa Romance",
                artist = "Locko",
                streamUrl = "ytmusic://abc1",
                durationSeconds = 215,
                itemKind = MusicItemKind.CLIP
            ),
            MusicTrackPreset(
                id = "ytm_abc2",
                title = "Bikutsi VIP",
                artist = "Lady Ponce",
                streamUrl = "ytmusic://abc2",
                durationSeconds = 242,
                itemKind = MusicItemKind.CLIP
            )
        )
        val playlistState = MusicPlaybackState(
            track = sampleClips.first(),
            isPlaying = true,
            queueSize = sampleClips.size,
            queueIndex = 0,
            queueTracks = sampleClips,
            activeCollectionTitle = "Hits Cameroun 2026",
            activeCollectionKind = MusicItemKind.PLAYLIST,
            isPlaylistExpanded = true,
            videoClipModeEnabled = true,
            hasRenderedVideo = true
        )
        assertTrue(playlistState.videoClipModeEnabled)
        assertTrue(playlistState.isPlaylistExpanded)
        assertEquals(2, playlistState.queueTracks.size)
        assertEquals("03:35", UniverseScreenAdapter.MusicDeckHolder.formatTimeMs(215_000L))
        assertEquals("1:05:09", UniverseScreenAdapter.MusicDeckHolder.formatTimeMs(3_909_000L))

        val layoutDir = java.io.File("src/main/res/layout").takeIf { it.isDirectory }
            ?: java.io.File("app/src/main/res/layout")
        val musicLayouts = listOf(
            "item_universe_music_deck.xml",
            "item_universe_music_playlist_queue.xml",
            "item_music_playlist_track_row.xml",
            "dialog_missa_music_fullscreen_clip.xml"
        )
        val hexColorRegex = Regex("#[0-9A-Fa-f]{6,8}")
        val rawSpRegex = Regex("\"\\d+(\\.\\d+)?sp\"")
        for (fileName in musicLayouts) {
            val f = java.io.File(layoutDir, fileName)
            assertTrue("Expected layout $fileName to exist", f.isFile)
            val xml = f.readText()
            assertFalse("Layout $fileName must not contain hardcoded hex colors", hexColorRegex.containsMatchIn(xml))
            assertFalse("Layout $fileName must not contain hardcoded sp sizes", rawSpRegex.containsMatchIn(xml))
        }
        val deckXml = java.io.File(layoutDir, "item_universe_music_deck.xml").readText()
        assertTrue(deckXml.contains("music_deck_video_surface"))
        assertTrue(deckXml.contains("music_deck_video_loading_spinner"))
        assertTrue(deckXml.contains("music_deck_mode_mp3_btn"))
        assertTrue(deckXml.contains("music_deck_mode_video_btn"))
        assertTrue(java.io.File(layoutDir, "item_universe_music_playlist_queue.xml").readText().contains("music_playlist_tracks_recycler"))
    }

    @Test
    fun `music MP3 and Video mode switching builds full video candidate chain without state races`() {
        val muxed360 = org.schabi.newpipe.extractor.stream.VideoStream.Builder()
            .setId("18")
            .setContent("https://rr1.googlevideo.com/videoplayback?itag=18", true)
            .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.MPEG_4)
            .setDeliveryMethod(org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP)
            .setIsVideoOnly(false)
            .setResolution("360p")
            .build()

        val vp9VideoOnly = org.schabi.newpipe.extractor.stream.VideoStream.Builder()
            .setId("244")
            .setContent("https://rr1.googlevideo.com/videoplayback?itag=244", true)
            .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.WEBM)
            .setDeliveryMethod(org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP)
            .setIsVideoOnly(true)
            .setResolution("480p")
            .build()

        val h264VideoOnly = org.schabi.newpipe.extractor.stream.VideoStream.Builder()
            .setId("135")
            .setContent("https://rr1.googlevideo.com/videoplayback?itag=135", true)
            .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.MPEG_4)
            .setDeliveryMethod(org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP)
            .setIsVideoOnly(true)
            .setResolution("480p")
            .build()

        val candidates = MissaMusicProviders.buildVideoClipCandidates(
            muxedStreams = listOf(muxed360),
            videoOnlyStreams = listOf(vp9VideoOnly, h264VideoOnly),
            hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/id/test.m3u8",
            hasAudioStreams = true
        )
        assertEquals(4, candidates.size)
        assertEquals(MissaMusicProviders.VideoClipSourceKind.MUXED_PROGRESSIVE, candidates[0].kind)
        assertEquals(MissaMusicProviders.VideoClipSourceKind.HLS_ADAPTIVE, candidates[1].kind)
        assertEquals(MissaMusicProviders.VideoClipSourceKind.VIDEO_ONLY_MERGED, candidates[2].kind)
        assertEquals("https://rr1.googlevideo.com/videoplayback?itag=135", candidates[2].url)
        assertEquals("https://rr1.googlevideo.com/videoplayback?itag=244", candidates[3].url)

        val javaRoot = java.io.File("src/main/java/com/lagradost/cloudstream3").takeIf { it.isDirectory }
            ?: java.io.File("app/src/main/java/com/lagradost/cloudstream3")
        val managerSrc = java.io.File(javaRoot, "ui/universe/DirectMusicPlayerManager.kt").readText()
        val postValueOccurrences = Regex("_state\\.postValue").findAll(managerSrc).count()
        assertEquals(
            "DirectMusicPlayerManager must funnel all state updates through atomic updateState() with a single postValue site",
            1,
            postValueOccurrences
        )
        assertTrue(managerSrc.contains("fun setVideoClipMode("))
    }

    @Test
    fun `all custom RecyclerView focus interceptors guard against detached focused views`() {
        assertEquals(
            MissaTvFocusHelper.FocusSearchOutcome.RETURN_FOCUSED_ONLY,
            MissaTvFocusHelper.resolveFocusSearchOutcome(
                sameAsFocused = true,
                focusedStillAttached = false,
                targetValidAndAttached = false
            )
        )
        assertEquals(
            MissaTvFocusHelper.FocusSearchOutcome.REQUEST_TARGET_AND_RETURN_FOCUSED,
            MissaTvFocusHelper.resolveFocusSearchOutcome(
                sameAsFocused = false,
                focusedStillAttached = false,
                targetValidAndAttached = true
            )
        )
        assertEquals(
            MissaTvFocusHelper.FocusSearchOutcome.RETURN_FOCUSED_ONLY,
            MissaTvFocusHelper.resolveFocusSearchOutcome(
                sameAsFocused = false,
                focusedStillAttached = false,
                targetValidAndAttached = false
            )
        )
        assertEquals(
            MissaTvFocusHelper.FocusSearchOutcome.RETURN_TARGET,
            MissaTvFocusHelper.resolveFocusSearchOutcome(
                sameAsFocused = false,
                focusedStillAttached = true,
                targetValidAndAttached = true
            )
        )
        assertEquals(
            MissaTvFocusHelper.FocusSearchOutcome.RETURN_NULL,
            MissaTvFocusHelper.resolveFocusSearchOutcome(
                sameAsFocused = false,
                focusedStillAttached = true,
                targetValidAndAttached = false
            )
        )

        val javaRoot = java.io.File("src/main/java/com/lagradost/cloudstream3").takeIf { it.isDirectory }
            ?: java.io.File("app/src/main/java/com/lagradost/cloudstream3")
        val guardedFiles = listOf(
            "ui/universe/MissaTvFocusNavigation.kt",
            "ui/result/LinearListLayout.kt",
            "ui/CustomRecyclerViews.kt",
            "widget/LinearRecycleViewLayoutManager.kt"
        )
        for (relPath in guardedFiles) {
            val file = java.io.File(javaRoot, relPath)
            assertTrue("Expected $relPath to exist", file.isFile)
            assertTrue(
                "$relPath must guard onInterceptFocusSearch via safeFocusSearchReturn",
                file.readText().contains("safeFocusSearchReturn")
            )
        }
        val commonActivityText = java.io.File(javaRoot, "CommonActivity.kt").readText()
        assertTrue(
            "CommonActivity.dispatchKeyEvent must sanitize detached windowFocused views",
            commonActivityText.contains("MissaTvFocusHelper.isDescendantOf(windowFocused, decorView)")
        )
        assertTrue(
            "CommonActivity.continueGetNextFocus must prevent cross-window ViewRootImpl returns",
            commonActivityText.contains("next.rootView !== viewWindowRoot")
        )
    }

    @Test
    fun `nouveautes loads only fresh individual clips, uses lightweight formats and restores home hero banner on scroll`() {
        // 1. Nouveautés (`selectNouveautesClips`) strictly excludes ALL Playlists & Albums even with "Nouveautés 2026" titles
        val playlistMasqueradingAsNew = MusicTrackPreset(
            id = "ytpl_999",
            title = "Top Nouveautés Afrobeats 2026",
            artist = "YouTube Music",
            album = "Playlist Officielle",
            streamUrl = "ytplaylist://https://www.youtube.com/playlist?list=PL999",
            isTrending = true,
            itemKind = MusicItemKind.PLAYLIST,
            trackCount = 30,
            recencyScore = 95
        )
        val albumMasqueradingAsNew = MusicTrackPreset(
            id = "ytpl_888",
            title = "Nouvel Album Officiel 2026",
            artist = "Fally Ipupa",
            album = "Album Officiel",
            streamUrl = "ytplaylist://https://www.youtube.com/playlist?list=OLAK5uy_888",
            isTrending = true,
            itemKind = MusicItemKind.ALBUM,
            trackCount = 14,
            recencyScore = 90
        )
        val freshClipHoursAgo = MusicTrackPreset(
            id = "ytm_fresh_hours",
            title = "Bikutsi du Moment",
            artist = "Lady Ponce",
            album = "Clip Officiel",
            streamUrl = "ytmusic://freshHours01",
            isTrending = true,
            itemKind = MusicItemKind.CLIP,
            durationSeconds = 210,
            recencyScore = MissaMusicProviders.computeRecencyScore("il y a 3 heures", "Bikutsi du Moment", 2026, fromRecentWindowQuery = true)
        )
        val freshClipDaysAgo = MusicTrackPreset(
            id = "ytm_fresh_days",
            title = "Douala Sunset",
            artist = "Locko",
            album = "Clip Officiel",
            streamUrl = "ytmusic://freshDays02",
            isTrending = true,
            itemKind = MusicItemKind.CLIP,
            durationSeconds = 198,
            recencyScore = MissaMusicProviders.computeRecencyScore("2 days ago", "Douala Sunset", 2026, fromRecentWindowQuery = true)
        )
        val multiYearOldTrack = MusicTrackPreset(
            id = "ytm_old_2021",
            title = "Ancien Titre",
            artist = "Artiste",
            album = "Clip Officiel",
            streamUrl = "ytmusic://old2021",
            isTrending = false,
            itemKind = MusicItemKind.CLIP,
            durationSeconds = 230,
            recencyScore = MissaMusicProviders.computeRecencyScore("3 years ago", "Ancien Titre 2026", 2026)
        )

        assertEquals(0, multiYearOldTrack.recencyScore)
        assertTrue(freshClipHoursAgo.recencyScore > freshClipDaysAgo.recencyScore)
        assertTrue(freshClipDaysAgo.recencyScore >= 80)

        val nouveautes = MissaMusicProviders.selectNouveautesClips(
            listOf(
                playlistMasqueradingAsNew,
                albumMasqueradingAsNew,
                multiYearOldTrack,
                freshClipDaysAgo,
                freshClipHoursAgo
            )
        )
        assertTrue("Nouveautés must never be empty when clips exist", nouveautes.isNotEmpty())
        assertTrue(
            "Nouveautés must strictly exclude all playlists and albums",
            nouveautes.none { it.isAlbumOrPlaylist() || it.itemKind == MusicItemKind.PLAYLIST || it.itemKind == MusicItemKind.ALBUM }
        )
        assertFalse(
            "Multi-year-old uploads must not pollute Nouveautés when fresh clips exist",
            nouveautes.any { it.id == "ytm_old_2021" }
        )
        assertEquals("ytm_fresh_hours", nouveautes[0].id)
        assertEquals("ytm_fresh_days", nouveautes[1].id)

        // 2. Live sliding-window query uses `VIDEOS` + `after:YYYY-MM-DD` so releases never freeze
        val style = MissaMusicProviders.MUSIC_STYLE_OPTIONS.first()
        val (filter, freshQuery) = MissaMusicProviders.buildFreshMomentClipQuery(
            style = style,
            nowMillis = 1_793_404_800_000L, // Late Oct 2026
            lookbackDays = 60
        )
        assertEquals(org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory.VIDEOS, filter)
        assertTrue("Expected sliding `after:YYYY-MM-DD` operator in $freshQuery", freshQuery.contains(Regex("after:2026-\\d{2}-\\d{2}")))

        // 3. Lightweight video clip candidate chain prefers 360p over heavy 720p/1080p to cut latency
        val muxed720 = org.schabi.newpipe.extractor.stream.VideoStream.Builder()
            .setId("22")
            .setContent("https://rr1.googlevideo.com/clip_720.mp4", true)
            .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.MPEG_4)
            .setDeliveryMethod(org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP)
            .setIsVideoOnly(false)
            .setResolution("720p")
            .build()
        val muxed360 = org.schabi.newpipe.extractor.stream.VideoStream.Builder()
            .setId("18")
            .setContent("https://rr1.googlevideo.com/clip_360.mp4", true)
            .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.MPEG_4)
            .setDeliveryMethod(org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP)
            .setIsVideoOnly(false)
            .setResolution("360p")
            .build()
        val videoOnly360 = org.schabi.newpipe.extractor.stream.VideoStream.Builder()
            .setId("134")
            .setContent("https://rr1.googlevideo.com/clip_vo_360.mp4", true)
            .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.MPEG_4)
            .setDeliveryMethod(org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP)
            .setIsVideoOnly(true)
            .setResolution("360p")
            .build()
        val candidates = MissaMusicProviders.buildVideoClipCandidates(
            muxedStreams = listOf(muxed720, muxed360),
            videoOnlyStreams = listOf(videoOnly360),
            hlsUrl = "https://manifest.googlevideo.com/api/manifest/hls_variant/clip.m3u8",
            hasAudioStreams = true
        )
        assertEquals("https://rr1.googlevideo.com/clip_360.mp4", candidates.first().url)
        assertEquals(
            MissaMusicProviders.VideoClipSourceKind.HLS_ADAPTIVE,
            candidates.first { it.url.endsWith(".m3u8") }.kind
        )
        // HLS comes right after muxed progressive so mid-track switching (e.g. at 03:53) seeks instantaneously
        val hlsIdx = candidates.indexOfFirst { it.kind == MissaMusicProviders.VideoClipSourceKind.HLS_ADAPTIVE }
        val voIdx = candidates.indexOfFirst { it.kind == MissaMusicProviders.VideoClipSourceKind.VIDEO_ONLY_MERGED }
        assertTrue("Expected HLS_ADAPTIVE ($hlsIdx) ahead of VIDEO_ONLY_MERGED ($voIdx)", hlsIdx in 0 until voIdx)

        // Inline small-screen TextureView must NEVER start View.GONE (`visibility="visible"` + `wantsInlineSurface`)
        // so Android allocates its SurfaceTexture BEFORE ExoPlayer decodes frame 0, with Fullscreen button ready!
        val resRoot = java.io.File("src/main/res").takeIf { it.isDirectory } ?: java.io.File("app/src/main/res")
        val deckXml = java.io.File(resRoot, "layout/item_universe_music_deck.xml").readText()
        assertTrue(
            "music_deck_video_surface must be visible in layout so TextureView allocates SurfaceTexture immediately",
            deckXml.contains("android:id=\"@+id/music_deck_video_surface\"") &&
                !deckXml.substringAfter("music_deck_video_surface").substringBefore("/>").contains("android:visibility=\"gone\"")
        )
        val javaRoot = java.io.File("src/main/java/com/lagradost/cloudstream3").takeIf { it.isDirectory }
            ?: java.io.File("app/src/main/java/com/lagradost/cloudstream3")
        val adapterText = java.io.File(javaRoot, "ui/universe/UniverseScreenAdapter.kt").readText()
        assertTrue(
            "MusicDeckHolder must keep inline videoSurface visible whenever wantsInlineSurface is true",
            adapterText.contains("if (wantsInlineSurface)") &&
                adapterText.contains("fullscreenBtn.isVisible = tr != null && state.videoClipModeEnabled")
        )

        // 4. Accueil Hero banner scroll snap: never stays half-hidden or stuck when scrolling back up
        // Banner [top = -160, bottom = 160] (half-hidden, viewportTop = 0):
        // Scrolling UP (`lastScrollDy < 0`) restores full banner cleanly (`dy = -160`), even on short pages!
        assertEquals(
            -160,
            MissaTvFocusHelper.computeHeroBannerSnapDy(
                heroTop = -160,
                heroBottom = 160,
                viewportTop = 0,
                lastScrollDy = -12,
                canScrollFurtherDown = true
            )
        )
        assertEquals(
            -160,
            MissaTvFocusHelper.computeHeroBannerSnapDy(
                heroTop = -160,
                heroBottom = 160,
                viewportTop = 0,
                lastScrollDy = -8,
                canScrollFurtherDown = false
            )
        )
        // Scrolling DOWN past 35% on a long page cleanly tucks the banner (`dy = +160`) instead of leaving it cut in half
        assertEquals(
            160,
            MissaTvFocusHelper.computeHeroBannerSnapDy(
                heroTop = -160,
                heroBottom = 160,
                viewportTop = 0,
                lastScrollDy = 24,
                canScrollFurtherDown = true
            )
        )
        // Already fully at top (`heroTop == 0`) -> no snap needed (`dy = 0`)
        assertEquals(
            0,
            MissaTvFocusHelper.computeHeroBannerSnapDy(
                heroTop = 0,
                heroBottom = 320,
                viewportTop = 0,
                lastScrollDy = -10,
                canScrollFurtherDown = true
            )
        )
    }

    @Test
    fun `music search partitions abundant trending clips recent clips and playlists without mixes`() {
        // 1. Rich YouTube playlists up to 300 clips are accepted; DJ mixes & RD mixes remain blocked
        assertTrue(
            MissaMusicProviders.isValidYouTubePlaylistOrAlbum(
                name = "Hits Cameroun & Afrobeats",
                uploader = "Missa Curated",
                url = "https://www.youtube.com/playlist?list=PL_CAMEROUN_150",
                streamCount = 150L,
                playlistType = PlaylistInfo.PlaylistType.NORMAL
            )
        )
        assertFalse(
            MissaMusicProviders.isValidYouTubePlaylistOrAlbum(
                name = "DJ Mix Non Stop 2 Hours",
                uploader = "DJ Party",
                url = "https://www.youtube.com/playlist?list=PL_DJ_MIX",
                streamCount = 40L,
                playlistType = PlaylistInfo.PlaylistType.NORMAL
            )
        )

        // 2. Duplicate video IDs across MUSIC_VIDEOS + VIDEOS merge viewCount, recencyScore & studio artwork
        val rawBatches = listOf(
            // Same video returned first from MUSIC_VIDEOS (0 views, 0 recency) and from VIDEOS (48M views)
            MusicTrackPreset(
                id = "ytm_viral1",
                title = "DIE",
                artist = "Gazo",
                streamUrl = "ytmusic://viral1",
                artworkUrl = "https://i.ytimg.com/vi/viral1/mqdefault.jpg",
                durationSeconds = 235,
                itemKind = MusicItemKind.CLIP,
                recencyScore = 10,
                viewCount = 0L
            ),
            MusicTrackPreset(
                id = "ytm_viral1",
                title = "DIE",
                artist = "Gazo",
                streamUrl = "ytmusic://viral1",
                artworkUrl = "https://lh3.googleusercontent.com/studio_die=w544-h544-l90-rj",
                durationSeconds = 235,
                itemKind = MusicItemKind.CLIP,
                recencyScore = 30,
                viewCount = 48_000_000L
            ),
            // Brand-new recent clip from sliding `after:YYYY-MM-DD` window
            MusicTrackPreset(
                id = "ytm_fresh1",
                title = "Probation",
                artist = "Gazo",
                streamUrl = "ytmusic://fresh1",
                artworkUrl = "https://i.ytimg.com/vi/fresh1/mqdefault.jpg",
                durationSeconds = 198,
                isTrending = true,
                itemKind = MusicItemKind.CLIP,
                recencyScore = 96,
                viewCount = 620_000L
            ),
            // Second recent clip
            MusicTrackPreset(
                id = "ytm_fresh2",
                title = "Nanani Nanana",
                artist = "Gazo",
                streamUrl = "ytmusic://fresh2",
                artworkUrl = "https://i.ytimg.com/vi/fresh2/mqdefault.jpg",
                durationSeconds = 210,
                isTrending = true,
                itemKind = MusicItemKind.CLIP,
                recencyScore = 88,
                viewCount = 1_900_000L
            ),
            // Curated YouTube playlist whose title doesn't literally repeat "gazo clip"
            MusicTrackPreset(
                id = "ytpl_rapfr1",
                title = "Top Drill & Rap FR",
                artist = "Rap Selects",
                streamUrl = "ytplaylist://https://www.youtube.com/playlist?list=PL_RAP_FR_1",
                artworkUrl = "https://i.ytimg.com/vi/p1/mqdefault.jpg",
                itemKind = MusicItemKind.PLAYLIST,
                trackCount = 45,
                recencyScore = 60
            ),
            // Official Album
            MusicTrackPreset(
                id = "ytpl_kmt",
                title = "KMT",
                artist = "Gazo",
                streamUrl = "ytplaylist://https://www.youtube.com/playlist?list=OLAK5uy_KMT",
                artworkUrl = "https://lh3.googleusercontent.com/kmt=w544-h544-l90-rj",
                itemKind = MusicItemKind.ALBUM,
                trackCount = 15,
                recencyScore = 40
            ),
            // Studio Single
            MusicTrackPreset(
                id = "ytm_song1",
                title = "Céline 3X",
                artist = "Gazo",
                streamUrl = "ytmusic://song1",
                artworkUrl = "https://lh3.googleusercontent.com/celine=w544-h544-l90-rj",
                durationSeconds = 184,
                itemKind = MusicItemKind.SONG,
                recencyScore = 45,
                viewCount = 8_500_000L
            ),
            // Long DJ mix must be stripped automatically
            MusicTrackPreset(
                id = "ytm_badmix",
                title = "Gazo Non Stop DJ Mix 1 Hour",
                artist = "DJ Mix",
                streamUrl = "ytmusic://badmix",
                durationSeconds = 3600,
                itemKind = MusicItemKind.CLIP,
                viewCount = 9_000_000L
            )
        )

        val sections = MissaMusicProviders.partitionMusicSearchResults(rawBatches, "Gazo")

        // DJ mix excluded everywhere
        assertTrue(sections.allRanked.none { it.id == "ytm_badmix" })

        // Merged viral clip keeps 48M viewCount + studio artwork and ranks #1 in Trending Clips ("clips en vogue")
        assertTrue(sections.trendingClips.isNotEmpty())
        assertEquals("ytm_viral1", sections.trendingClips.first().id)
        assertEquals(48_000_000L, sections.trendingClips.first().viewCount)
        assertTrue(MissaMusicProviders.isStudioSquareArtworkUrl(sections.trendingClips.first().artworkUrl))
        assertTrue(sections.trendingClips.none { it.isAlbumOrPlaylist() })

        // Recent Clips ("les récents") ranks the freshest release (`recencyScore = 96`) #1 and excludes playlists/albums
        assertTrue(sections.recentClips.isNotEmpty())
        assertEquals("ytm_fresh1", sections.recentClips.first().id)
        assertTrue(sections.recentClips.none { it.isAlbumOrPlaylist() })

        // Playlists & Albums preserves both the YouTube Playlist and Official Album
        assertEquals(2, sections.playlistsAndAlbums.size)
        assertTrue(sections.playlistsAndAlbums.any { it.id == "ytpl_rapfr1" && it.itemKind == MusicItemKind.PLAYLIST })
        assertTrue(sections.playlistsAndAlbums.any { it.id == "ytpl_kmt" && it.itemKind == MusicItemKind.ALBUM })

        // Songs & Singles preserves studio single
        assertEquals(1, sections.songsAndSingles.size)
        assertEquals("ytm_song1", sections.songsAndSingles.first().id)

        // All 6 unique valid items are retained in `allRanked`
        assertEquals(6, sections.allRanked.size)

        // Verify source wiring for parallel playlists + trending + recent search & ViewModel shelves
        val javaRoot = java.io.File("src/main/java/com/lagradost/cloudstream3").takeIf { it.isDirectory }
            ?: java.io.File("app/src/main/java/com/lagradost/cloudstream3")
        val providersSrc = java.io.File(javaRoot, "ui/universe/MissaMusicProviders.kt").readText()
        val viewModelSrc = java.io.File(javaRoot, "ui/universe/MediaUniverseViewModel.kt").readText()
        assertTrue(providersSrc.contains("YoutubeSearchQueryHandlerFactory.PLAYLISTS"))
        assertTrue(providersSrc.contains("after:${'$'}recentAfterDate"))
        assertTrue(viewModelSrc.contains("music_search_trending_clips"))
        assertTrue(viewModelSrc.contains("music_search_recent_clips"))
        assertTrue(viewModelSrc.contains("music_search_playlists_albums"))
        assertTrue(viewModelSrc.contains("getOnlineSearchResults"))
    }

    @Test
    fun `music universe hides single-source selector pill and elongates adjacent search bar`() {
        assertFalse(MissaUiFactory.shouldShowSourceFilterPill("music", 0))
        assertFalse(MissaUiFactory.shouldShowSourceFilterPill("music", 1))
        assertFalse(MissaUiFactory.shouldShowSourceFilterPill("music", 2))
        assertFalse(MissaUiFactory.shouldShowSourceFilterPill("films", 1))
        assertTrue(MissaUiFactory.shouldShowSourceFilterPill("films", 3))

        assertTrue(MissaUiFactory.computeElongatedSearchPillMinWidthDp(360) >= 148)
        assertTrue(
            MissaUiFactory.computeElongatedSearchPillMinWidthDp(960) >
                MissaUiFactory.computeElongatedSearchPillMinWidthDp(360)
        )
        assertTrue(
            MissaUiFactory.computeElongatedSearchPillMaxWidthDp(960) >
                MissaUiFactory.computeElongatedSearchPillMinWidthDp(960)
        )
    }

    @Test
    fun `video surface coordinator eliminates inline vs fullscreen surface contention`() {
        // 1. No track or MP3-only mode -> NONE
        assertEquals(
            VideoSurfaceTarget.NONE,
            MusicQueueAlgorithms.resolveActiveVideoSurfaceTarget(
                hasTrack = false,
                videoClipModeEnabled = true,
                isFullscreenOpen = false,
                isInlineAttached = true
            )
        )
        assertEquals(
            VideoSurfaceTarget.NONE,
            MusicQueueAlgorithms.resolveActiveVideoSurfaceTarget(
                hasTrack = true,
                videoClipModeEnabled = false,
                isFullscreenOpen = true,
                isInlineAttached = true
            )
        )

        // 2. Inline attached & fullscreen closed -> INLINE_DECK owns ExoPlayer surface
        assertEquals(
            VideoSurfaceTarget.INLINE_DECK,
            MusicQueueAlgorithms.resolveActiveVideoSurfaceTarget(
                hasTrack = true,
                videoClipModeEnabled = true,
                isFullscreenOpen = false,
                isInlineAttached = true
            )
        )

        // 3. Fullscreen open while inline is attached -> FULLSCREEN_MODAL exclusively owns ExoPlayer surface
        assertEquals(
            VideoSurfaceTarget.FULLSCREEN_MODAL,
            MusicQueueAlgorithms.resolveActiveVideoSurfaceTarget(
                hasTrack = true,
                videoClipModeEnabled = true,
                isFullscreenOpen = true,
                isInlineAttached = true
            )
        )

        // 4. Inline scrolled off-screen & fullscreen closed -> NONE
        assertEquals(
            VideoSurfaceTarget.NONE,
            MusicQueueAlgorithms.resolveActiveVideoSurfaceTarget(
                hasTrack = true,
                videoClipModeEnabled = true,
                isFullscreenOpen = false,
                isInlineAttached = false
            )
        )

        val javaRoot = java.io.File("src/main/java/com/lagradost/cloudstream3").takeIf { it.isDirectory }
            ?: java.io.File("app/src/main/java/com/lagradost/cloudstream3")
        val adapterSrc = java.io.File(javaRoot, "ui/universe/UniverseScreenAdapter.kt").readText()
        val dialogsSrc = java.io.File(javaRoot, "ui/universe/MissaUniverseDialogs.kt").readText()
        assertTrue(adapterSrc.contains("if (holder is MusicDeckHolder) holder.onAttached()"))
        assertTrue(adapterSrc.contains("DirectMusicPlayerManager.registerInlineVideoSurface(videoSurface)"))
        assertTrue(adapterSrc.contains("DirectMusicPlayerManager.unregisterInlineVideoSurface(videoSurface)"))
        assertTrue(dialogsSrc.contains("DirectMusicPlayerManager.acquireFullscreenVideoSurface(fullscreenPlayerView)"))
        assertTrue(dialogsSrc.contains("DirectMusicPlayerManager.releaseFullscreenVideoSurface(fullscreenPlayerView)"))
    }

    @Test
    fun `now playing synchronization splits 500ms progress ticks from structural playback updates`() {
        val sampleTrack = MusicTrackPreset(
            id = "ytm_sync_1",
            title = "Locko - Sawa Romance",
            artist = "Locko",
            album = "The Bridge",
            genre = "Afrobeats",
            streamUrl = "ytaudio://sync1234567",
            durationSeconds = 215,
            itemKind = MusicItemKind.CLIP
        )
        val baseState = MusicPlaybackState(
            track = sampleTrack,
            isPlaying = true,
            isBuffering = false,
            positionMs = 12_000L,
            durationMs = 215_000L,
            queueSize = 4,
            queueIndex = 1
        )
        val tickState = baseState.copy(positionMs = 12_500L)
        val pausedState = baseState.copy(isPlaying = false)

        // Pure 500ms clock tick -> progress-only fast-path (avoids catalog rebuilds & TV focus resets)
        assertTrue(
            UniverseScreenAdapter.isProgressOnlyMusicChange(
                UniverseListItem.MusicDeck(baseState, isFavoriteTrack = false),
                UniverseListItem.MusicDeck(tickState, isFavoriteTrack = false)
            )
        )
        // Play/Pause or Favorite change -> full structural update
        assertFalse(
            UniverseScreenAdapter.isProgressOnlyMusicChange(
                UniverseListItem.MusicDeck(baseState, isFavoriteTrack = false),
                UniverseListItem.MusicDeck(pausedState, isFavoriteTrack = false)
            )
        )
        assertFalse(
            UniverseScreenAdapter.isProgressOnlyMusicChange(
                UniverseListItem.MusicDeck(baseState, isFavoriteTrack = false),
                UniverseListItem.MusicDeck(tickState, isFavoriteTrack = true)
            )
        )

        val javaRoot = java.io.File("src/main/java/com/lagradost/cloudstream3").takeIf { it.isDirectory }
            ?: java.io.File("app/src/main/java/com/lagradost/cloudstream3")
        val cardBuilderSrc = java.io.File(javaRoot, "ui/search/SearchResultBuilder.kt").readText()
        assertTrue(cardBuilderSrc.contains("DirectMusicPlayerManager.isTrackOrCollectionActive"))
        assertTrue(cardBuilderSrc.contains("universe_music_card_now_playing"))
    }

    @Test
    fun `voice search declares Android 11+ recognize speech package visibility and top-bar mic button`() {
        val manifestFile = java.io.File("src/main/AndroidManifest.xml").takeIf { it.isFile }
            ?: java.io.File("app/src/main/AndroidManifest.xml")
        val manifestXml = manifestFile.readText()
        assertTrue(
            "AndroidManifest.xml <queries> must declare android.speech.action.RECOGNIZE_SPEECH for Android 11+ & Google TV",
            manifestXml.contains("android.speech.action.RECOGNIZE_SPEECH")
        )

        val fragmentLayoutFile = java.io.File("src/main/res/layout/fragment_media_universe.xml").takeIf { it.isFile }
            ?: java.io.File("app/src/main/res/layout/fragment_media_universe.xml")
        val layoutXml = fragmentLayoutFile.readText()
        assertTrue(layoutXml.contains("@+id/universe_top_voice_search_btn"))
        assertTrue(layoutXml.contains("@drawable/ic_mic"))

        val javaRoot = java.io.File("src/main/java/com/lagradost/cloudstream3").takeIf { it.isDirectory }
            ?: java.io.File("app/src/main/java/com/lagradost/cloudstream3")
        val mainActivitySrc = java.io.File(javaRoot, "MainActivity.kt").readText()
        assertTrue(mainActivitySrc.contains("KEYCODE_VOICE_ASSIST"))
        assertTrue(mainActivitySrc.contains("triggerVoiceOrTextSearch"))
    }

    @Test
    fun `playback queue drawer supports index-safe drag-and-drop reordering and contextual queue mutations`() {
        fun track(id: String) = MusicTrackPreset(
            id = id,
            title = "Track $id",
            artist = "Artist $id",
            album = "Single",
            genre = "Afrobeats",
            streamUrl = "ytaudio://vid_$id",
            durationSeconds = 195,
            itemKind = MusicItemKind.CLIP
        )

        val initialQueue = listOf(track("A"), track("B"), track("C"), track("D"))

        // 1. Dragging the currently playing track (A at 0 -> index 2) moves activeIndex with it seamlessly
        val (dragActiveQueue, dragActiveIdx) = MusicQueueAlgorithms.reorderQueueTracks(
            tracks = initialQueue,
            currentActiveIndex = 0,
            fromIndex = 0,
            toIndex = 2
        )
        assertEquals(listOf("B", "C", "A", "D"), dragActiveQueue.map { it.id })
        assertEquals(2, dragActiveIdx)

        // 2. Dragging an earlier track (A at 0) below the active track (C at 2 -> index 3) decrements activeIndex to 1
        val (dragAcrossDown, activeAfterDown) = MusicQueueAlgorithms.reorderQueueTracks(
            tracks = initialQueue,
            currentActiveIndex = 2,
            fromIndex = 0,
            toIndex = 3
        )
        assertEquals(listOf("B", "C", "D", "A"), dragAcrossDown.map { it.id })
        assertEquals(1, activeAfterDown)
        assertEquals("C", dragAcrossDown[activeAfterDown].id)

        // 3. Dragging a later track (D at 3) above the active track (C at 2 -> index 1) increments activeIndex to 3
        val (dragAcrossUp, activeAfterUp) = MusicQueueAlgorithms.reorderQueueTracks(
            tracks = initialQueue,
            currentActiveIndex = 2,
            fromIndex = 3,
            toIndex = 1
        )
        assertEquals(listOf("A", "D", "B", "C"), dragAcrossUp.map { it.id })
        assertEquals(3, activeAfterUp)
        assertEquals("C", dragAcrossUp[activeAfterUp].id)

        // 4. Contextual "Play Next" inserts a new track immediately after activeIndex
        val newTrackE = track("E")
        val (withPlayNext, activeWithPlayNext) = MusicQueueAlgorithms.insertPlayNextInQueue(
            tracks = initialQueue,
            currentActiveIndex = 1,
            candidate = newTrackE
        )
        assertEquals(listOf("A", "B", "E", "C", "D"), withPlayNext.map { it.id })
        assertEquals(1, activeWithPlayNext)

        // 5. Contextual "Play Next" on an existing track (D at 3 when B at 1 is playing) moves D right after B
        val (existingMovedNext, activeExistingNext) = MusicQueueAlgorithms.insertPlayNextInQueue(
            tracks = initialQueue,
            currentActiveIndex = 1,
            candidate = initialQueue[3]
        )
        assertEquals(listOf("A", "B", "D", "C"), existingMovedNext.map { it.id })
        assertEquals(1, activeExistingNext)

        // 6. Contextual "Remove from Queue" before active decrements activeIndex without flagging removedWasActive
        val (afterRemoveBefore, activeAfterRemoveBefore, removedActive1) = MusicQueueAlgorithms.removeTrackFromQueue(
            tracks = initialQueue,
            currentActiveIndex = 2,
            removeIndex = 0
        )
        assertEquals(listOf("B", "C", "D"), afterRemoveBefore.map { it.id })
        assertEquals(1, activeAfterRemoveBefore)
        assertFalse(removedActive1)

        // 7. Contextual "Remove from Queue" on the active track flags removedWasActive = true
        val (afterRemoveActive, activeAfterRemoveSelf, removedActive2) = MusicQueueAlgorithms.removeTrackFromQueue(
            tracks = initialQueue,
            currentActiveIndex = 1,
            removeIndex = 1
        )
        assertEquals(listOf("A", "C", "D"), afterRemoveActive.map { it.id })
        assertEquals(1, activeAfterRemoveSelf)
        assertTrue(removedActive2)

        // Verify ItemTouchHelper + drag handle + contextual menu button wiring
        val javaRoot = java.io.File("src/main/java/com/lagradost/cloudstream3").takeIf { it.isDirectory }
            ?: java.io.File("app/src/main/java/com/lagradost/cloudstream3")
        val adapterSrc = java.io.File(javaRoot, "ui/universe/UniverseScreenAdapter.kt").readText()
        val dialogsSrc = java.io.File(javaRoot, "ui/universe/MissaUniverseDialogs.kt").readText()
        assertTrue(adapterSrc.contains("ItemTouchHelper"))
        assertTrue(adapterSrc.contains("playlist_track_drag_handle"))
        assertTrue(adapterSrc.contains("playlist_track_more_btn"))
        assertTrue(dialogsSrc.contains("fun showMusicQueueTrackContextMenu("))
        assertTrue(dialogsSrc.contains("fun showMusicCatalogCardContextMenu("))
    }
}
