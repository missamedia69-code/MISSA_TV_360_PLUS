package com.lagradost.cloudstream3.ui.universe

import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MissaExtensionGroupsTest {

    private data class FakeResponse(
        override val name: String,
        override val url: String,
        override val apiName: String,
        override var type: TvType?,
        override var posterUrl: String? = "https://img.example/poster.jpg",
        override var posterHeaders: Map<String, String>? = null,
        override var id: Int? = url.hashCode(),
        override var quality: SearchQuality? = null,
        override var score: Score? = null
    ) : SearchResponse

    private fun raw(apiName: String, forced: Set<String>?) = RawHarvestedItem(
        response = FakeResponse(
            name = "Mon Film Test",
            url = "https://pack.example/film/mon-film-test",
            apiName = apiName,
            type = TvType.Movie
        ),
        sectionName = "Nouveautés",
        isHorizontalSection = false,
        providerSupportedTypes = setOf(TvType.Movie),
        isPreferredLanguage = true,
        forcedUniverses = forced
    )

    @Test
    fun `normalize handles auto, none, valid and forbidden groups`() {
        assertNull(MissaExtensionGroups.normalize(null))
        assertNull(MissaExtensionGroups.normalize(emptyList()))
        assertNull(MissaExtensionGroups.normalize(listOf("  ", "")))
        assertEquals(emptySet<String>(), MissaExtensionGroups.normalize(listOf("none")))
        assertEquals(emptySet<String>(), MissaExtensionGroups.normalize(listOf("films", "NONE")))
        assertEquals(setOf("films", "series"), MissaExtensionGroups.normalize(listOf(" Films ", "SERIES")))
        // music et kids sont 100% YouTube : jamais assignables
        assertNull(MissaExtensionGroups.normalize(listOf("music", "kids")))
        assertEquals(setOf("films"), MissaExtensionGroups.normalize(listOf("music", "films")))
    }

    @Test
    fun `assignable groups never contain music or kids`() {
        val ids = MissaExtensionGroups.ASSIGNABLE_GROUPS.map { it.id }
        assertTrue("music" !in ids)
        assertTrue("kids" !in ids)
        assertEquals(8, ids.size)
    }

    @Test
    fun `extension key ignores folder and file extension`() {
        assertEquals(
            "ext:MonPack.123456",
            MissaExtensionGroups.extensionKey("/data/user/0/app/files/plugins/repo.99/MonPack.123456.cs3")
        )
    }

    @Test
    fun `mergeUniverses keeps auto when nothing is forced`() {
        assertEquals(setOf("films"), MissaExtensionGroups.mergeUniverses(setOf("films"), listOf(null, null)))
        assertEquals(setOf("films"), MissaExtensionGroups.mergeUniverses(setOf("films"), emptyList()))
    }

    @Test
    fun `mergeUniverses uses only forced groups when every source is forced`() {
        assertEquals(
            setOf("documentaries"),
            MissaExtensionGroups.mergeUniverses(setOf("films"), listOf(setOf("documentaries")))
        )
        assertEquals(
            setOf("series", "sports"),
            MissaExtensionGroups.mergeUniverses(setOf("films"), listOf(setOf("series"), setOf("sports")))
        )
    }

    @Test
    fun `mergeUniverses adds forced groups to auto when sources are mixed`() {
        assertEquals(
            setOf("films", "series"),
            MissaExtensionGroups.mergeUniverses(setOf("films"), listOf(setOf("series"), null))
        )
    }

    @Test
    fun `none removes the entry from every universe`() {
        assertEquals(
            emptySet<String>(),
            MissaExtensionGroups.mergeUniverses(setOf("films"), listOf(emptySet()))
        )
    }

    @Test
    fun `forced universes override the heuristic during deduplication`() {
        val entries = MediaUniverseViewModel.deduplicateHarvestedItems(
            listOf(raw("PackSite", setOf("documentaries")))
        )
        assertEquals(1, entries.size)
        assertEquals(setOf("documentaries"), entries.first().universes)
    }

    @Test
    fun `without a choice the heuristic is unchanged`() {
        val entries = MediaUniverseViewModel.deduplicateHarvestedItems(
            listOf(raw("PackSite", null))
        )
        assertEquals(1, entries.size)
        assertTrue("films" in entries.first().universes)
    }

    @Test
    fun `a forced source and an auto source of the same title share one entry with both groups`() {
        val entries = MediaUniverseViewModel.deduplicateHarvestedItems(
            listOf(raw("ForcedSite", setOf("series")), raw("AutoSite", null))
        )
        assertEquals(1, entries.size)
        val universes = entries.first().universes
        assertTrue("series" in universes)
        assertTrue("films" in universes)
    }

    @Test
    fun `none yields an entry that belongs to no universe`() {
        val entries = MediaUniverseViewModel.deduplicateHarvestedItems(
            listOf(raw("PackSite", emptySet()))
        )
        assertTrue(entries.all { it.universes.isEmpty() })
    }
}
