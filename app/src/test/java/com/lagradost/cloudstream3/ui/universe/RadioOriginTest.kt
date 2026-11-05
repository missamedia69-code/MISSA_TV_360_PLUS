package com.lagradost.cloudstream3.ui.universe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AUDIT H7 (V9): the panafrican stations showed the South African flag. Their `country` was
 * `"AF"`, meant as "Africa", but the flag came from SubtitleHelper.getFlagFromIso, which
 * resolves *language* codes first: "af" is afrikaans, whose country is ZA.
 *
 * These tests pin the resolver that replaced it: a continent is a region with no flag, only a
 * real ISO-3166 country gets one, and the legacy codes keep mapping to their region so a
 * station already saved in the favourites is not dropped on upgrade.
 */
class RadioOriginTest {

    @Test
    fun aRealIsoCountryKeepsItsFlag() {
        assertEquals(RadioOrigin.Origin.Country("CM"), RadioOrigin.from("CM"))
        assertEquals(RadioOrigin.Origin.Country("CM"), RadioOrigin.from("cm"))
        assertEquals("\uD83C\uDDE8\uD83C\uDDF2", RadioOrigin.flagFor("CM")) // Cameroun
    }

    @Test
    fun southAfricaStillWorksAsACountry() {
        assertEquals(RadioOrigin.Origin.Country("ZA"), RadioOrigin.from("ZA"))
        assertEquals("\uD83C\uDDFF\uD83C\uDDE6", RadioOrigin.flagFor("ZA")) // Afrique du Sud
    }

    @Test
    fun africaIsARegionAndNeverGetsAFlag() {
        assertEquals(
            RadioOrigin.Origin.Region(RadioOrigin.REGION_AFRICA),
            RadioOrigin.from(RadioOrigin.REGION_AFRICA)
        )
        assertNull(RadioOrigin.flagFor(RadioOrigin.REGION_AFRICA))
        assertEquals("Afrique", RadioOrigin.displayLabel(RadioOrigin.REGION_AFRICA))
    }

    @Test
    fun theLegacyAfricaCodeNeverResolvesToTheSouthAfricanFlag() {
        assertEquals(RadioOrigin.Origin.Region(RadioOrigin.REGION_AFRICA), RadioOrigin.from("AF"))
        assertNull("\"AF\" must never produce the South African flag", RadioOrigin.flagFor("AF"))
    }

    @Test
    fun theLegacyInternationalCodeMapsToTheWorldRegion() {
        assertEquals(RadioOrigin.Origin.Region(RadioOrigin.REGION_WORLD), RadioOrigin.from("INT"))
        assertEquals("Monde", RadioOrigin.displayLabel(RadioOrigin.REGION_WORLD))
    }

    @Test
    fun unknownAndBlankCodesStayUnknown() {
        assertEquals(RadioOrigin.Origin.Unknown, RadioOrigin.from("XX"))
        assertEquals(RadioOrigin.Origin.Unknown, RadioOrigin.from(null))
        assertEquals(RadioOrigin.Origin.Unknown, RadioOrigin.from("   "))
        assertNull(RadioOrigin.flagFor("XX"))
        assertNull(RadioOrigin.flagFor(""))
    }

    @Test
    fun aCountryPrintsAsAReadableNameNeverAsItsCode() {
        val label = RadioOrigin.displayLabel("CM")
        assertTrue(label.isNotBlank())
        assertTrue("\"$label\" should not be the raw code", !label.equals("CM", ignoreCase = true))
    }
}
