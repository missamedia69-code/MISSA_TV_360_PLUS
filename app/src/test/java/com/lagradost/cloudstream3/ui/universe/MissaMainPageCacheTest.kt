package com.lagradost.cloudstream3.ui.universe

import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MissaMainPageCacheTest {

    @Test
    fun copyIsReadBackWithTheSameTitles() {
        // Les constructeurs des réponses d'extension sont réservés aux extensions : la copie
        // relit de toute façon tout en CachedCatalogSearchResponse.
        val movie = CachedCatalogSearchResponse(
            name = "Un film",
            url = "https://exemple.test/film/1",
            apiName = "Source A",
            type = TvType.Movie,
            posterUrl = "https://exemple.test/affiche.jpg",
            posterHeaders = mapOf("Referer" to "https://exemple.test/"),
            year = 2024
        )
        val series = CachedCatalogSearchResponse(
            name = "Une série",
            url = "https://exemple.test/serie/2",
            apiName = "Source A",
            type = TvType.TvSeries,
            posterUrl = null,
            year = 2023,
            episodes = 12
        )
        val entry = MissaMainPageCache.Entry(
            savedAtMs = 1_700_000_000_000L,
            items = listOf(
                MissaMainPageCache.Item(movie, "Nouveautés", isHorizontalSection = false),
                MissaMainPageCache.Item(series, "Séries", isHorizontalSection = true)
            )
        )

        val back = MissaMainPageCache.decode(MissaMainPageCache.encode("Source A", entry), "Source A")!!

        assertEquals(1_700_000_000_000L, back.savedAtMs)
        assertEquals(2, back.items.size)
        val first = back.items[0]
        assertEquals("Un film", first.response.name)
        assertEquals("https://exemple.test/film/1", first.response.url)
        assertEquals("Source A", first.response.apiName)
        assertEquals(TvType.Movie, first.response.type)
        assertEquals("https://exemple.test/affiche.jpg", first.response.posterUrl)
        assertEquals(mapOf("Referer" to "https://exemple.test/"), first.response.posterHeaders)
        assertEquals("Nouveautés", first.sectionName)
        // Année et épisodes servent à fusionner les doublons : ils doivent survivre à la copie.
        assertEquals(2024, MediaUniverseViewModel.extractYearHint(first.response))
        val second = back.items[1]
        assertTrue(second.isHorizontalSection)
        assertNull(second.response.posterUrl)
        assertEquals(12, MediaUniverseViewModel.extractEpisodeCountHint(second.response))
        assertEquals(2023, MediaUniverseViewModel.extractYearHint(second.response))
    }

    @Test
    fun copyOfAnotherExtensionIsIgnored() {
        val entry = MissaMainPageCache.Entry(1L, emptyList())
        assertNull(MissaMainPageCache.decode(MissaMainPageCache.encode("Source A", entry), "Source B"))
    }
}
