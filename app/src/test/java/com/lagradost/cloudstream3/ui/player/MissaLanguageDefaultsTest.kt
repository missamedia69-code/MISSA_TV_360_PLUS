package com.lagradost.cloudstream3.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MissaLanguageDefaultsTest {

    @Test
    fun languageCodeFromTag() {
        assertEquals("fr", MissaLanguageDefaults.languageOf("fr-CM"))
        assertEquals("pt", MissaLanguageDefaults.languageOf("pt_BR"))
        assertEquals("en", MissaLanguageDefaults.languageOf("EN"))
        assertEquals("en", MissaLanguageDefaults.languageOf(""))
    }

    @Test
    fun frenchAppPrefersFrenchVersion() {
        assertEquals(MissaLinkVersion.VF, MissaLanguageDefaults.defaultVersion("fr"))
        assertNull(MissaLanguageDefaults.defaultVersion("en"))
    }

    @Test
    fun audioTrackLanguageMatching() {
        assertTrue(MissaLanguageDefaults.sameLanguage("fr", "fr"))
        assertTrue(MissaLanguageDefaults.sameLanguage("fre", "fr"))
        assertTrue(MissaLanguageDefaults.sameLanguage("fra", "fr"))
        assertTrue(MissaLanguageDefaults.sameLanguage("fr-FR", "fr"))
        assertTrue(MissaLanguageDefaults.sameLanguage("eng", "en"))
        assertFalse(MissaLanguageDefaults.sameLanguage("en", "fr"))
        assertFalse(MissaLanguageDefaults.sameLanguage(null, "fr"))
    }
}
