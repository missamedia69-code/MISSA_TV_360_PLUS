package com.lagradost.cloudstream3.ui.universe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * JVM unit tests for the pure part of the YouTube Kids engine (KIDS-01 / FRESH-01):
 * child-safety filtering, per-category duration windows, the randomized bilingual discovery
 * plan, deduplication and row grouping.
 */
class MissaKidsProvidersTest {

    private val fixedNow = 1_764_000_000_000L // stable instant so query plans are reproducible

    private fun preset(
        id: String,
        title: String,
        artist: String = "Channel",
        genre: String = KidsCategory.SONGS.id,
        recency: Int = 0,
        views: Long = 0L
    ) = MusicTrackPreset(
        id = id,
        title = title,
        artist = artist,
        album = "",
        genre = genre,
        streamUrl = "${MissaMusicProviders.YT_MUSIC_LAZY_SCHEME}${id.removePrefix("ytk_")}",
        artworkUrl = null,
        durationSeconds = 120,
        providerName = MissaKidsProviders.PROVIDER_YOUTUBE_KIDS,
        isPipedLazy = true,
        itemKind = MusicItemKind.CLIP,
        recencyScore = recency,
        viewCount = views
    )

    // region Child safety

    @Test
    fun `kids safety gate accepts children content`() {
        assertTrue(MissaKidsProviders.isKidsSafe("Comptines pour enfants", "Comptines TV"))
        assertTrue(MissaKidsProviders.isKidsSafe("Peppa Pig épisode complet", "Peppa Pig FR"))
        assertTrue(MissaKidsProviders.isKidsSafe("Learn colors for kids", "Kids Academy"))
        assertTrue(MissaKidsProviders.isKidsSafe("Dessin animé français complet", "Gulli"))
        assertTrue(MissaKidsProviders.isKidsSafe("Histoire du soir pour enfants", "Contes et légendes"))
    }

    @Test
    fun `kids safety gate rejects horror weapons news and adult content`() {
        assertFalse(MissaKidsProviders.isKidsSafe("Horror movie", "Scary Channel"))
        assertFalse(MissaKidsProviders.isKidsSafe("Zombie apocalypse", "Gaming"))
        assertFalse(MissaKidsProviders.isKidsSafe("Actualité politique du jour", "Info"))
        assertFalse(MissaKidsProviders.isKidsSafe("Breaking news today", "News Channel"))
        assertFalse(MissaKidsProviders.isKidsSafe("Tuto maquillage", "Beauty"))
        assertFalse(MissaKidsProviders.isKidsSafe("", ""))
        assertFalse(MissaKidsProviders.isKidsSafe("   ", null))
    }

    @Test
    fun `blocklist and audience helpers split the strict and relaxed gates`() {
        assertTrue(MissaKidsProviders.isBlockedForKids("Horror movie", "Scary Channel"))
        assertFalse(MissaKidsProviders.isBlockedForKids("Comptines pour enfants", "Comptines TV"))
        assertTrue(MissaKidsProviders.matchesKidsAudience("Comptines pour enfants", "Comptines TV"))
        assertFalse(MissaKidsProviders.matchesKidsAudience("Vlog de voyage", "Travel"))
        // isKidsSafe == "not blocked" AND "aimed at kids"
        assertEquals(
            MissaKidsProviders.isKidsSafe("Comptines pour enfants", "Comptines TV"),
            !MissaKidsProviders.isBlockedForKids("Comptines pour enfants", "Comptines TV") &&
                MissaKidsProviders.matchesKidsAudience("Comptines pour enfants", "Comptines TV")
        )
        assertEquals(
            MissaKidsProviders.isKidsSafe("Horror movie", "Scary Channel"),
            !MissaKidsProviders.isBlockedForKids("Horror movie", "Scary Channel") &&
                MissaKidsProviders.matchesKidsAudience("Horror movie", "Scary Channel")
        )
        // A blank title is treated as blocked so it can never reach a Kids row.
        assertTrue(MissaKidsProviders.isBlockedForKids("", null))
    }

    @Test
    fun `planned queries keep their undecorated base query for the retry path`() {
        val plan = MissaKidsProviders.buildKidsQueryPlan(Random(2024), fixedNow, 2)
        assertTrue(plan.isNotEmpty())
        for (query in plan) {
            assertTrue(query.baseQuery.isNotBlank())
            assertTrue(
                "the decorated query must start with its base query: ${query.query}",
                query.query.startsWith(query.baseQuery)
            )
        }
        val plain = KidsQuery(KidsCategory.SONGS, "comptines pour enfants", "fr")
        assertEquals("comptines pour enfants", plain.baseQuery)
    }

    // endregion

    // region Duration windows

    @Test
    fun `duration windows are enforced per category`() {
        assertFalse(MissaKidsProviders.isValidKidsDuration(0L, KidsCategory.SONGS))
        assertFalse(MissaKidsProviders.isValidKidsDuration(-5L, KidsCategory.SONGS))
        assertFalse(MissaKidsProviders.isValidKidsDuration(10L, KidsCategory.SONGS))
        assertTrue(MissaKidsProviders.isValidKidsDuration(30L, KidsCategory.SONGS))
        assertTrue(MissaKidsProviders.isValidKidsDuration(1_800L, KidsCategory.SONGS))
        assertFalse(MissaKidsProviders.isValidKidsDuration(1_801L, KidsCategory.SONGS))

        assertFalse(MissaKidsProviders.isValidKidsDuration(600L, KidsCategory.MOVIES))
        assertTrue(MissaKidsProviders.isValidKidsDuration(900L, KidsCategory.MOVIES))
        assertTrue(MissaKidsProviders.isValidKidsDuration(10_800L, KidsCategory.MOVIES))
        assertFalse(MissaKidsProviders.isValidKidsDuration(10_801L, KidsCategory.MOVIES))

        assertTrue(MissaKidsProviders.isValidKidsDuration(3_600L, KidsCategory.CARTOONS))
        assertFalse(MissaKidsProviders.isValidKidsDuration(7_201L, KidsCategory.CARTOONS))
    }

    // endregion

    // region Randomized bilingual discovery plan

    @Test
    fun `query plan is stable for a given seed`() {
        val a = MissaKidsProviders.buildKidsQueryPlan(Random(1234), fixedNow, 2)
        val b = MissaKidsProviders.buildKidsQueryPlan(Random(1234), fixedNow, 2)
        assertEquals(a, b)
    }

    @Test
    fun `query plan covers every category in both languages`() {
        val plan = MissaKidsProviders.buildKidsQueryPlan(Random(99), fixedNow, 2)
        assertEquals(10, plan.size)
        for (category in KidsCategory.allDiscoveryCategories()) {
            val queries = plan.filter { it.category == category }
            assertEquals("2 queries per category", 2, queries.size)
            assertTrue("${category.id} must include French", queries.any { it.language == "fr" })
            assertTrue("${category.id} must include English", queries.any { it.language == "en" })
            assertTrue(
                "${category.id} queries must come from its own pool",
                queries.all { query ->
                    category.frenchQueries.any { query.query.startsWith(it) } ||
                        category.englishQueries.any { query.query.startsWith(it) }
                }
            )
        }
    }

    @Test
    fun `query plan differs between two launches`() {
        val plans = (1..14).map { seed ->
            MissaKidsProviders.buildKidsQueryPlan(Random(seed), fixedNow, 2)
                .map { it.query }
                .toSet()
        }
        assertTrue("two launches must not ask YouTube the same thing", plans.toSet().size > 1)

        val allQueries = (1..40).flatMap { seed ->
            MissaKidsProviders.buildKidsQueryPlan(Random(seed), fixedNow, 2).map { it.query }
        }.toSet()
        assertTrue("the rotation must explore the pool: ${allQueries.size}", allQueries.size > 20)
    }

    @Test
    fun `decorated queries only append rotation variants`() {
        var sawFreshness = false
        var sawTheme = false
        for (seed in 0 until 200) {
            val random = Random(seed)
            val decorated = MissaKidsProviders.decorateKidsQuery(
                baseQuery = "comptines pour enfants",
                language = "fr",
                random = random,
                nowMillis = fixedNow
            )
            assertTrue(decorated.startsWith("comptines pour enfants"))
            if (decorated.contains("after:")) {
                sawFreshness = true
                assertTrue(Regex(".*after:\\d{4}-\\d{2}-\\d{2}$").matches(decorated))
            }
            if (decorated.length > "comptines pour enfants".length && !decorated.contains("after:")) {
                sawTheme = true
            }
        }
        assertTrue("rotation must sometimes use the sliding freshness window", sawFreshness)
        assertTrue("rotation must sometimes use a theme token", sawTheme)
    }

    // endregion

    // region Deduplication & grouping

    @Test
    fun `dedupe drops repeated ids and repeated channel title pairs`() {
        val batches = listOf(
            preset("ytk_1", "Comptine A", recency = 50, views = 100),
            preset("ytk_2", "Comptine A", recency = 80, views = 50),
            preset("ytk_1", "Autre titre", recency = 95),
            preset("ytk_3", "Comptine B", recency = 10, views = 9)
        )
        val deduped = MissaKidsProviders.dedupeKidsVideos(batches)
        // Sorted by recency: the "Autre titre" variant (95) wins the "ytk_1" id, then "Comptine A"
        // keeps its higher-recency "ytk_2" row while the identical "ytk_1" copy is dropped.
        assertEquals(listOf("ytk_1", "ytk_2", "ytk_3"), deduped.map { it.id })
    }

    @Test
    fun `dedupe skips incomplete entries`() {
        val deduped = MissaKidsProviders.dedupeKidsVideos(
            listOf(
                preset("ytk_9", ""),
                preset("ytk_8", "Comptine C")
            )
        )
        assertEquals(listOf("ytk_8"), deduped.map { it.id })
    }

    @Test
    fun `groupByCategory preserves the editorial row order and drops unknown genres`() {
        val videos = listOf(
            preset("ytk_1", "A", genre = KidsCategory.LEARN.id),
            preset("ytk_2", "B", genre = KidsCategory.MOVIES.id),
            preset("ytk_3", "C", genre = KidsCategory.SONGS.id),
            preset("ytk_4", "D", genre = KidsCategory.CARTOONS.id),
            preset("ytk_5", "E", genre = KidsCategory.STORIES.id),
            preset("ytk_6", "F", genre = "unknown_genre")
        )
        val grouped = MissaKidsProviders.groupByCategory(videos)
        assertEquals(
            listOf(
                KidsCategory.SONGS.id,
                KidsCategory.CARTOONS.id,
                KidsCategory.MOVIES.id,
                KidsCategory.STORIES.id,
                KidsCategory.LEARN.id
            ),
            grouped.keys.map { it.id }
        )
        assertEquals(5, grouped.values.flatten().size)
    }

    @Test
    fun `kids videos carry the YouTube Kids provider and the shared lazy scheme`() {
        val track = preset("ytk_42", "Comptine test")
        assertEquals(MissaKidsProviders.PROVIDER_YOUTUBE_KIDS, track.providerName)
        assertTrue(MissaMusicProviders.isLazyYouTubeAudioScheme(track.streamUrl))
        assertEquals("Comptine test", track.title)
    }

    // endregion
}
