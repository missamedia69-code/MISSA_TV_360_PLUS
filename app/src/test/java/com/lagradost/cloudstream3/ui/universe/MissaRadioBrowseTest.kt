package com.lagradost.cloudstream3.ui.universe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MissaRadioBrowseTest {

    private val json = """
        [{"name":"The United States Of America","iso_3166_1":"US","stationcount":6904},
         {"name":"Cameroon","iso_3166_1":"CM","stationcount":52},
         {"name":"Coted Ivoire","iso_3166_1":"CI","stationcount":60},
         {"name":"France","iso_3166_1":"FR","stationcount":3000},
         {"name":"Kenya","iso_3166_1":"KE","stationcount":40},
         {"name":"Nowhere","iso_3166_1":"ZZ","stationcount":3},
         {"name":"Empty","iso_3166_1":"BE","stationcount":0}]
    """.trimIndent()

    @Test
    fun `countries are translated, grouped by continent, Cameroon first`() {
        val list = MissaRadioBrowse.parseCountries(json, Locale.FRENCH)
        assertEquals(listOf("CM", "CI", "KE", "FR", "US"), list.map { it.iso })
        assertEquals("Cameroun", list[0].name)
        assertEquals(MissaRadioBrowse.Continent.AFRICA, list[0].continent)
        assertEquals(MissaRadioBrowse.Continent.AMERICAS, list.last().continent)
    }

    @Test
    fun `pages are appended without duplicates`() {
        fun st(name: String, url: String) = RadioStationPreset(id = url, name = name, genre = "", country = "CM", streamUrl = url)
        val first = listOf(st("Balafon FM", "https://a"), st("Equinoxe", "https://b"))
        val page = listOf(
            st("Radio Balafon", "https://a2"), // même station, autre flux
            st("Autre nom", "https://B"),       // même flux
            st("Magic FM", "https://d"),
            st("Radio FM", "https://e"),        // nom sans contenu : gardé
            st("FM Radio", "https://f")
        )
        val merged = MissaRadioBrowse.appendUnique(first, page)
        assertEquals(listOf("Balafon FM", "Equinoxe", "Magic FM", "Radio FM", "FM Radio"), merged.map { it.name })
        assertTrue(MissaRadioBrowse.themes.map { it.id }.toSet().size == MissaRadioBrowse.themes.size)
    }
}
