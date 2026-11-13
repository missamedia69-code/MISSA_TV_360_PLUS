package com.lagradost.cloudstream3.ui.universe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MissaLiveTvTest {

    @Test
    fun `reads extinf attributes, vlc options and groups`() {
        val list = MissaLiveTv.parse(
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="CRTV.cm" tvg-logo="https://x.org/crtv.png" tvg-country="CM" group-title="Généraliste;Actualités",CRTV, la chaîne
            #EXTVLCOPT:http-referrer=https://crtv.cm
            #EXTVLCOPT:http-user-agent=Test/1.0
            https://live.example/crtv.m3u8
            #EXTINF:-1 group-title="Sport" http-referrer="https://ref.example",Canal Sport
            http://live.example/sport.m3u8

            #EXTINF:-1,Sans flux
            #EXTINF:-1 tvg-name="Nom seul",
            https://live.example/seul.m3u8
            # un commentaire
            #EXTINF:-1,Doublon
            https://live.example/crtv.m3u8
            #EXTINF:-1,Lien invalide
            ftp://live.example/x
            """.trimIndent()
        )
        assertEquals(listOf("CRTV, la chaîne", "Canal Sport", "Nom seul"), list.map { it.name })
        val crtv = list[0]
        assertEquals("https://x.org/crtv.png", crtv.logo)
        assertEquals("CM", crtv.country)
        assertEquals(listOf("Généraliste", "Actualités"), crtv.groups)
        assertEquals("https://crtv.cm", crtv.referer)
        assertEquals("Test/1.0", crtv.userAgent)
        assertEquals("https://ref.example", list[1].referer)
        assertNull(list[1].country)
        assertNull(list[2].logo)
    }

    @Test
    fun `the published channel list is readable`() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "tv/chaines.m3u") }
            .firstOrNull { it.isFile } ?: return
        val list = file.bufferedReader().use { MissaLiveTv.parse(it) }
        assertTrue(list.size >= 50)
        assertTrue(list.all { it.url.startsWith("http") && it.name.isNotBlank() })
        assertTrue(list.any { it.country == "CM" })
    }
}
