package com.lagradost.cloudstream3.ui.universe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Logos des radios : un vrai logo d'abord, des logos de secours ensuite, les initiales en
 * dernier recours. Jamais un logo générique (Google, Facebook, plateforme d'hébergement)
 * qui ferait croire que plusieurs stations sont la même.
 */
class MissaRadioLogosTest {

    @Test
    fun `curated stations carry a real logo and a fallback where one exists`() {
        val byId = MissaRadioProviders.curatedInstantStations.associateBy { it.id }
        val rfi = byId.getValue("rfi_afrique")
        assertTrue(rfi.logoUrl!!.contains("RFI_logo_2013"))
        assertTrue(rfi.logoFallbackUrls.isNotEmpty())
        assertTrue(byId.getValue("fip_jazz").logoUrl!!.contains("radiofrance.fr/pikapi/images"))
        assertTrue(byId.getValue("swiss_jazz").logoUrl!!.contains("radioswissjazz.ch"))
        // Toutes les adresses de logo sont en HTTPS.
        MissaRadioProviders.curatedInstantStations.forEach { st ->
            (listOfNotNull(st.logoUrl) + st.logoFallbackUrls).forEach { url ->
                assertTrue("${st.id}: $url", url.startsWith("https://"))
            }
        }
    }

    @Test
    fun `search response exposes the primary logo then its fallbacks`() {
        val preset = RadioStationPreset(
            id = "t", name = "Test FM", genre = "", country = "CM",
            streamUrl = "https://stream.example.org/live",
            logoUrl = "https://a.example.org/logo.png",
            logoFallbackUrls = listOf("https://b.example.org/logo.png", "https://a.example.org/logo.png")
        )
        val response = preset.toSearchResponse()
        assertEquals("https://a.example.org/logo.png", response.posterUrl)
        assertEquals(listOf("https://b.example.org/logo.png"), response.logoFallbacks)
    }

    @Test
    fun `a station on a generic platform gets its initials rather than the platform icon`() {
        val laut = RadioStationPreset(
            id = "l", name = "Laut.fm Afrobeats", genre = "", country = "DE",
            streamUrl = "https://stream.laut.fm/afrobeats"
        )
        assertNull(laut.toSearchResponse().posterUrl)
        assertTrue(MissaRadioProviders.isGenericLogoUrl("https://www.facebook.com/favicon.ico"))
        assertTrue(MissaRadioProviders.isGenericLogoHost("static.xx.fbcdn.net"))
        assertFalse(MissaRadioProviders.isGenericLogoHost("www.rfi.fr"))
    }

    @Test
    fun `radio browser ignores a facebook favicon and keeps the station site as fallback`() {
        val json = """
            [{"stationuuid":"1","name":"Radio Balafon","url_resolved":"https://stream.example.cm/live",
              "url":"https://stream.example.cm/live","favicon":"https://www.facebook.com/favicon.ico",
              "homepage":"https://radiobalafon.example.cm","countrycode":"CM","tags":"news"}]
        """
        val parsed = MissaRadioProviders.parseRadioBrowserArray(json)
        assertEquals(1, parsed.size)
        assertTrue(parsed[0].logoUrl!!.contains("radiobalafon.example.cm"))
        assertFalse(parsed[0].logoUrl!!.contains("facebook"))
    }

    @Test
    fun `merging two sources of the same stream keeps both logos`() {
        val a = RadioStationPreset(
            id = "a", name = "Sweet FM", genre = "", country = "CM",
            streamUrl = "https://stream.example.cm/sweet", logoUrl = null
        )
        val b = a.copy(id = "b", logoUrl = "https://logos.example.cm/sweet.png")
        val c = a.copy(id = "c", logoUrl = "https://other.example.cm/sweet.png")
        val merged = MissaRadioProviders.mergeStationCatalogues(listOf(a, b, c)).single()
        assertEquals("https://logos.example.cm/sweet.png", merged.logoUrl)
        assertEquals(listOf("https://other.example.cm/sweet.png"), merged.logoFallbackUrls)
    }
}
