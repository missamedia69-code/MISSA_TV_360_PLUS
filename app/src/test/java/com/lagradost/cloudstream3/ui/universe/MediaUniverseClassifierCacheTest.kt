package com.lagradost.cloudstream3.ui.universe

import com.lagradost.cloudstream3.TvType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PERF A7 : [MediaUniverseClassifier.classifyAllUniverses] est mémoïsé dans un LRU de
 * 6000 entrées. Le risque propre à ce genre de cache n'est pas la lenteur mais la
 * confusion : une clé qui oublierait un paramètre (l'url, le type, les tags…) ferait
 * renvoyer à une entrée le résultat d'une autre.
 *
 * Ces tests appellent donc volontairement le classifieur avec des métadonnées presque
 * identiques, ce qui est exactement le cas qui casse si la clé est incomplète.
 */
class MediaUniverseClassifierCacheTest {

    /** Un film annoncé Movie mais hébergé sous /serie/ : le classifieur en fait une série. */
    private fun strangerThingsByUrl() = MediaUniverseClassifier.classifyAllUniverses(
        type = TvType.Movie,
        title = "Stranger Things",
        sectionName = "Tendances",
        url = "https://plugin-a.example/serie/stranger-things"
    )

    /** Une série annoncée TvSeries mais en section « Films Box-Office » : un film. */
    private fun gladiatorBySectionAndUrl() = MediaUniverseClassifier.classifyAllUniverses(
        type = TvType.TvSeries,
        title = "Gladiator II",
        sectionName = "Films Box-Office",
        url = "https://plugin-b.example/film/gladiator-2"
    )

    @Test
    fun `the same metadata always gives the same universes`() {
        assertEquals(setOf("series"), strangerThingsByUrl())
        assertEquals(strangerThingsByUrl(), strangerThingsByUrl())
        assertEquals(setOf("films"), gladiatorBySectionAndUrl())
        assertEquals(gladiatorBySectionAndUrl(), gladiatorBySectionAndUrl())
    }

    @Test
    fun `a cached series does not leak onto the same title served as a film`() {
        // Mêmes titre, type et section : seule l'url change.
        val asSeries = strangerThingsByUrl()
        val asFilm = MediaUniverseClassifier.classifyAllUniverses(
            type = TvType.Movie,
            title = "Stranger Things",
            sectionName = "Tendances",
            url = "https://plugin-a.example/film/stranger-things"
        )
        assertEquals(setOf("series"), asSeries)
        assertEquals(setOf("films"), asFilm)
    }

    @Test
    fun `a full cache keeps answering correctly`() {
        // On pousse volontairement plus d'entrées que la capacité du LRU (6000) pour
        // vérifier que l'éviction ne mélange pas les résultats.
        assertEquals(setOf("series"), strangerThingsByUrl())
        for (i in 0 until 6100) {
            MediaUniverseClassifier.classifyAllUniverses(
                type = TvType.Movie,
                title = "Film de remplissage $i",
                sectionName = "Films",
                url = "https://plugin-fill.example/film/$i"
            )
        }
        assertEquals(setOf("series"), strangerThingsByUrl())
        assertEquals(setOf("films"), gladiatorBySectionAndUrl())
    }
}
