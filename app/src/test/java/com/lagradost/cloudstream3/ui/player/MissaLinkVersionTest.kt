package com.lagradost.cloudstream3.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MissaLinkVersionTest {

    @Test
    fun frenchVersions() {
        assertEquals(MissaLinkVersion.VF, MissaLinkVersion.detect("Uqload VF"))
        assertEquals(MissaLinkVersion.VF, MissaLinkVersion.detect("Voe - FRENCH"))
        assertEquals(MissaLinkVersion.VF, MissaLinkVersion.detect("TrueFrench 1080p"))
        assertEquals(MissaLinkVersion.VF, MissaLinkVersion.detect("Filemoon [FR]"))
        assertEquals(MissaLinkVersion.VF, MissaLinkVersion.detect("Lecteur (Français)"))
    }

    @Test
    fun subtitledOriginalIsNotFrench() {
        assertEquals(MissaLinkVersion.VOSTFR, MissaLinkVersion.detect("Voe VOSTFR"))
        assertEquals(MissaLinkVersion.VOSTFR, MissaLinkVersion.detect("Doodstream SUB-FR"))
        assertEquals(MissaLinkVersion.VOSTFR, MissaLinkVersion.detect("Lecteur", "VOST"))
    }

    @Test
    fun otherVersions() {
        assertEquals(MissaLinkVersion.MULTI, MissaLinkVersion.detect("Filemoon MULTI"))
        assertEquals(MissaLinkVersion.VO, MissaLinkVersion.detect("Vidsrc English"))
        assertEquals(MissaLinkVersion.VO, MissaLinkVersion.detect("Server EN"))
        assertEquals(MissaLinkVersion.DUB, MissaLinkVersion.detect("Gogo Dub"))
        assertEquals(MissaLinkVersion.SUB, MissaLinkVersion.detect("Gogo Sub"))
    }

    @Test
    fun noVersionWhenNothingIsSaid() {
        assertNull(MissaLinkVersion.detect("Uqload 1080p"))
        assertNull(MissaLinkVersion.detect(null, ""))
        // « en », « fr » en minuscules : mots ordinaires, pas une langue.
        assertNull(MissaLinkVersion.detect("Lecteur en HD"))
        assertNull(MissaLinkVersion.detect("frembed"))
        assertNull(MissaLinkVersion.detect("Vostok"))
    }
}
