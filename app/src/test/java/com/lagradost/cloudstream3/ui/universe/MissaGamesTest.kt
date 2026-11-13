package com.lagradost.cloudstream3.ui.universe

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Univers Jeux (Neo Geo) : lecture du catalogue, installation sûre et commandes du lecteur.
 * Le dernier test lit le vrai game/catalogue.json publié dans le dépôt.
 */
class MissaGamesTest {

    private val sha = "a".repeat(64)

    private fun pkg(name: String, scheme: String = "https", hash: String = sha) =
        """"url":"$scheme://example.org/$name","sha256":"$hash","bytes":300"""

    private fun catalogueJson(bios: String = """{"fileName":"neogeo.zip",${pkg("neogeo.zip")},"licence":"Contrat"}"""): String = """
        {"version":2,"console":"neogeo","mode":"mvs",
         "moteur":{"version":"geolith-0.5.0","licence":"BSD-3-Clause",
           "abis":{"arm64-v8a":{${pkg("a.zip")}},"armeabi-v7a":{${pkg("b.zip")}},"mips":{${pkg("c.zip", "http")}}}},
         "bios":[$bios,{"fileName":"autre.zip",${pkg("autre.zip")}}],
         "jeux":[
           {"id":"a","title":"A","fileName":"a.neo",${pkg("a.neo")},"bannerUrl":"https://example.org/a.webp"},
           {"id":"b","title":"Pas un .neo","fileName":"b.zip",${pkg("b.zip")}},
           {"id":"c","title":"Pas en HTTPS","fileName":"c.neo",${pkg("c.neo", "http")}},
           {"id":"d","title":"Sans empreinte","fileName":"d.neo",${pkg("d.neo", hash = "x")}},
           {"id":"a","title":"Doublon","fileName":"a2.neo",${pkg("a2.neo")}}
         ]}
    """

    @Test
    fun `the catalogue keeps only verifiable Neo Geo games`() {
        val cat = MissaGames.parseCatalogue(catalogueJson())
        assertNotNull(cat)
        assertEquals(listOf("a"), cat!!.games.map { it.id })
        assertEquals("https://example.org/a.webp", cat.games[0].coverUrl)
        assertEquals(setOf("arm64-v8a", "armeabi-v7a"), cat.engines.keys)
        assertEquals("neogeo.zip", cat.requiredBios?.fileName)
        assertEquals(listOf("neogeo.zip"), cat.bios.map { it.fileName })
    }

    @Test
    fun `the engine follows the device architectures in order`() {
        val cat = MissaGames.parseCatalogue(catalogueJson())!!
        assertEquals("arm64-v8a", cat.engineForDevice(arrayOf("arm64-v8a", "armeabi-v7a"))?.abi)
        assertEquals("armeabi-v7a", cat.engineForDevice(arrayOf("armeabi-v7a", "armeabi"))?.abi)
        assertNull(cat.engineForDevice(arrayOf("x86")))
    }

    @Test
    fun `without the BIOS of the chosen mode there is nothing to boot`() {
        val cat = MissaGames.parseCatalogue(catalogueJson(bios = """{"fileName":"aes.zip",${pkg("aes.zip")}}"""))!!
        assertEquals("mvs", cat.mode)
        assertNull(cat.requiredBios)
    }

    @Test
    fun `broken catalogues are refused`() {
        assertNull(MissaGames.parseCatalogue("{"))
        assertNull(MissaGames.parseCatalogue("""{"console":"nes","moteur":{"abis":{"arm64-v8a":{${pkg("a.zip")}}}}}"""))
        assertNull(MissaGames.parseCatalogue("""{"console":"neogeo","moteur":{"abis":{}}}"""))
    }

    @Test
    fun `file names are sanitized`() {
        assertEquals("evil.neo", MissaGames.safeFileName("../../evil.neo"))
        assertNull(MissaGames.safeFileName(".."))
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, content) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(content.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `engine packages unzip safely`() {
        val dir = Files.createTempDirectory("neogeo").toFile()
        try {
            MissaGames.unzipSafely(ByteArrayInputStream(zipOf("libgeolith.so" to "x", "liblibretrodroid.so" to "y")), dir)
            assertTrue(File(dir, "libgeolith.so").isFile)
            assertTrue(File(dir, "liblibretrodroid.so").isFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = IOException::class)
    fun `zip slip is refused`() {
        val dir = Files.createTempDirectory("neogeo").toFile()
        try {
            MissaGames.unzipSafely(ByteArrayInputStream(zipOf("../evil.so" to "x")), dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `remote keys map to Neo Geo buttons`() {
        assertEquals(KeyEvent.KEYCODE_BUTTON_B, NeoGeoPlayerActivity.remoteToPad(KeyEvent.KEYCODE_DPAD_CENTER)) // A
        assertEquals(KeyEvent.KEYCODE_BUTTON_A, NeoGeoPlayerActivity.remoteToPad(KeyEvent.KEYCODE_2)) // B
        assertEquals(KeyEvent.KEYCODE_BUTTON_Y, NeoGeoPlayerActivity.remoteToPad(KeyEvent.KEYCODE_3)) // C
        assertEquals(KeyEvent.KEYCODE_BUTTON_X, NeoGeoPlayerActivity.remoteToPad(KeyEvent.KEYCODE_4)) // D
        assertEquals(KeyEvent.KEYCODE_BUTTON_SELECT, NeoGeoPlayerActivity.remoteToPad(KeyEvent.KEYCODE_5)) // pièce
        assertEquals(KeyEvent.KEYCODE_BUTTON_START, NeoGeoPlayerActivity.remoteToPad(KeyEvent.KEYCODE_MENU))
        assertEquals(KeyEvent.KEYCODE_DPAD_UP, NeoGeoPlayerActivity.remoteToPad(KeyEvent.KEYCODE_DPAD_UP))
        assertNull(NeoGeoPlayerActivity.remoteToPad(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun `the bundled catalogue stays in sync with the published catalogue`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "game/catalogue.json").isFile } ?: return
        val published = File(root, "game/catalogue.json")
        val bundled = File(root, "app/src/main/assets/game/catalogue.json")
        assertTrue("The APK needs an offline catalogue", bundled.isFile)
        assertEquals(published.readText(), bundled.readText())
        assertTrue(MissaGames.parseCatalogue(bundled.readText())!!.games.isNotEmpty())
    }

    @Test
    fun `the published catalogue is a Neo Geo catalogue`() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "game/catalogue.json") }
            .firstOrNull { it.isFile } ?: return
        val cat = MissaGames.parseCatalogue(file.readText())
        assertNotNull(cat)
        // Un moteur pour les processeurs ARM des téléviseurs et téléphones.
        assertTrue(cat!!.engines.keys.containsAll(listOf("arm64-v8a", "armeabi-v7a")))
        assertTrue(cat.engines.values.all { it.pkg.url.startsWith("https://raw.githubusercontent.com/missamedia69-code/") })
        // Des jeux publiés exigent le BIOS du mode choisi.
        if (cat.games.isNotEmpty()) assertNotNull(cat.requiredBios)
    }
}
