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
    fun `assignable groups cover every universe except home, music and kids`() {
        val ids = MissaExtensionGroups.ASSIGNABLE_GROUPS.map { it.id }
        // music et kids sont 100 % YouTube : jamais assignables (projectUniverse leur passe une
        // liste vide). home n'est pas un groupe, c'est la vue qui les contient tous.
        assertTrue("music" !in ids)
        assertTrue("kids" !in ids)
        assertTrue("home" !in ids)
        assertEquals(8, ids.size)
        // et rien que des identifiants d'univers qui existent vraiment
        assertTrue(ids.all { id -> MissaUniverses.NAVIGATION_UNIVERSES.any { it.id == id } })
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
    fun `mergeUniverses uses only the groups the content fits when every source is forced`() {
        assertEquals(
            setOf("documentaries"),
            MissaExtensionGroups.mergeUniverses(setOf("documentaries"), listOf(setOf("documentaries")))
        )
        assertEquals(
            setOf("series", "sports"),
            MissaExtensionGroups.mergeUniverses(
                setOf("series", "sports"),
                listOf(setOf("series"), setOf("sports"))
            )
        )
    }

    @Test
    fun `mergeUniverses adds forced groups to auto when sources are mixed`() {
        assertEquals(
            setOf("films", "documentaries"),
            MissaExtensionGroups.mergeUniverses(setOf("films"), listOf(setOf("documentaries"), null))
        )
    }

    @Test
    fun `an extension ticked films only still sends its series to Séries`() {
        assertEquals(
            setOf("series"),
            MissaExtensionGroups.mergeUniverses(setOf("series"), listOf(setOf("films")))
        )
    }

    @Test
    fun `on any other group the extension setting keeps correcting the heuristic`() {
        // réglée sur Documentaries, l'extension y verse son contenu même si la devinette dit « film »
        assertEquals(
            setOf("documentaries"),
            MissaExtensionGroups.mergeUniverses(setOf("films"), listOf(setOf("documentaries")))
        )
        assertEquals(
            setOf("sports"),
            MissaExtensionGroups.mergeUniverses(setOf("films"), listOf(setOf("sports")))
        )
    }

    @Test
    fun `none removes the entry from every universe`() {
        assertEquals(
            emptySet<String>(),
            MissaExtensionGroups.mergeUniverses(setOf("films"), listOf(emptySet()))
        )
    }

    private fun rawSeries(apiName: String, forced: Set<String>?) = RawHarvestedItem(
        response = FakeResponse(
            name = "Ma Serie Test",
            url = "https://pack.example/serie/ma-serie-test",
            apiName = apiName,
            type = TvType.TvSeries
        ),
        sectionName = "Séries",
        isHorizontalSection = false,
        providerSupportedTypes = setOf(TvType.TvSeries),
        isPreferredLanguage = true,
        forcedUniverses = forced
    )

    // --- le crible (MissaContentSieve) ---

    @Test
    fun `sift keeps only the groups the content has the structure of`() {
        assertEquals(setOf("series"), MissaContentSieve.sift(setOf("films", "series"), setOf("series")))
        assertEquals(setOf("films"), MissaContentSieve.sift(setOf("films", "series"), setOf("films")))
        assertEquals(
            setOf("documentaries"),
            MissaContentSieve.sift(setOf("documentaries"), setOf("documentaries", "live_tv"))
        )
    }

    @Test
    fun `sift never adds a group outside the films-series partition`() {
        // Films est coché, le contenu est un podcast : hors partition films/séries,
        // le réglage gagne et le podcast n'est pas versé ailleurs.
        assertEquals(setOf("films"), MissaContentSieve.sift(setOf("films"), setOf("podcasts")))
        assertEquals(setOf("sports"), MissaContentSieve.sift(setOf("sports"), setOf("series", "podcasts")))
    }

    @Test
    fun `sift keeps nothing when the user chose none`() {
        assertEquals(emptySet<String>(), MissaContentSieve.sift(emptySet(), setOf("films")))
    }

    @Test
    fun `an extension ticked films and series feeds each group its own content`() {
        val entries = MediaUniverseViewModel.deduplicateHarvestedItems(
            listOf(
                raw("PackSite", setOf("films", "series")),
                rawSeries("PackSite", setOf("films", "series"))
            )
        )
        assertEquals(2, entries.size)
        assertEquals(setOf("films"), entries.first { it.primary.type == TvType.Movie }.universes)
        assertEquals(setOf("series"), entries.first { it.primary.type == TvType.TvSeries }.universes)
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
            listOf(raw("ForcedSite", setOf("documentaries")), raw("AutoSite", null))
        )
        assertEquals(1, entries.size)
        val universes = entries.first().universes
        assertTrue("documentaries" in universes)
        assertTrue("films" in universes)
    }

    @Test
    fun `a film forced into Séries goes back to Films`() {
        // la partition films/séries tranche : forcer une série sur un film ne le déplace pas
        val entries = MediaUniverseViewModel.deduplicateHarvestedItems(
            listOf(raw("PackSite", setOf("series")))
        )
        assertEquals(setOf("films"), entries.first().universes)
    }

    @Test
    fun `none yields an entry that belongs to no universe`() {
        val entries = MediaUniverseViewModel.deduplicateHarvestedItems(
            listOf(raw("PackSite", emptySet()))
        )
        assertTrue(entries.all { it.universes.isEmpty() })
    }
}
