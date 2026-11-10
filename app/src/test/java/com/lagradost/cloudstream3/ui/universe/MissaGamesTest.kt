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
 * Univers Jeux : lecture du catalogue, installation sûre et commandes du lecteur.
 * Le dernier test lit le vrai game/catalogue.json publié dans le dépôt.
 */
class MissaGamesTest {

    private val sha = "a".repeat(64)

    private fun catalogueJson(gameExtra: String = ""): String = """
        {"version":1,
         "moteur":{"version":"4.2.3","url":"https://example.org/m.zip","sha256":"$sha","bytes":100},
         "emulateurs":[{"system":"nes","label":"NES","core":"fceumm","url":"https://example.org/n.zip","sha256":"$sha","bytes":200,"licence":"GPL-2.0"}],
         "jeux":[
           {"id":"a","title":"A","system":"nes","fileName":"a.nes","url":"https://example.org/a.nes","sha256":"$sha","bytes":300$gameExtra},
           {"id":"b","title":"Sans console","system":"snes","fileName":"b.sfc","url":"https://example.org/b.sfc","sha256":"$sha","bytes":300},
           {"id":"c","title":"Pas en HTTPS","system":"nes","fileName":"c.nes","url":"http://example.org/c.nes","sha256":"$sha","bytes":300},
           {"id":"d","title":"Sans empreinte","system":"nes","fileName":"d.nes","url":"https://example.org/d.nes","sha256":"x","bytes":300},
           {"id":"a","title":"Doublon","system":"nes","fileName":"a2.nes","url":"https://example.org/a2.nes","sha256":"$sha","bytes":300}
         ]}
    """

    @Test
    fun `the catalogue keeps only verifiable games of a known console`() {
        val cat = MissaGames.parseCatalogue(catalogueJson())
        assertNotNull(cat)
        assertEquals(listOf("a"), cat!!.games.map { it.id })
        assertEquals("fceumm", cat.emulatorFor(cat.games[0])?.core)
        assertEquals("4.2.3", cat.engineVersion)
    }

    @Test
    fun `a broken catalogue is rejected`() {
        assertNull(MissaGames.parseCatalogue("{"))
        assertNull(MissaGames.parseCatalogue("""{"moteur":{"url":"http://x","sha256":"$sha","bytes":1}}"""))
    }

    @Test
    fun `a file name can never leave the games folder`() {
        assertEquals("evil.nes", MissaGames.safeFileName("../../evil.nes"))
        assertNull(MissaGames.safeFileName(".."))
        assertNull(MissaGames.parseCatalogue(catalogueJson())?.games?.firstOrNull { it.fileName.contains("/") })
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, body) ->
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `unzip extracts the engine layout`() {
        val dir = Files.createTempDirectory("ejs").toFile()
        try {
            MissaGames.unzipSafely(ByteArrayInputStream(zipOf("lanceur.html" to "<html>", "data/loader.js" to "x")), dir)
            assertTrue(File(dir, "lanceur.html").isFile)
            assertTrue(File(dir, "data/loader.js").isFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test(expected = IOException::class)
    fun `unzip refuses an entry that escapes the folder`() {
        val dir = Files.createTempDirectory("ejs").toFile()
        try {
            MissaGames.unzipSafely(ByteArrayInputStream(zipOf("../evil.txt" to "boom")), dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the web view only reaches the engine and the games`() {
        val root = Files.createTempDirectory("games").toFile()
        try {
            File(root, "ejs/data").mkdirs(); File(root, "ejs/data/loader.js").writeText("x")
            File(root, "roms/nes").mkdirs(); File(root, "roms/nes/a.nes").writeText("x")
            File(root, "catalogue.json").writeText("{}")
            assertNotNull(GamePlayerActivity.resolveLocalFile(root, "/ejs/data/loader.js"))
            assertNotNull(GamePlayerActivity.resolveLocalFile(root, "/roms/nes/a.nes"))
            assertNull(GamePlayerActivity.resolveLocalFile(root, "/catalogue.json"))
            assertNull(GamePlayerActivity.resolveLocalFile(root, "/ejs/../catalogue.json"))
            assertNull(GamePlayerActivity.resolveLocalFile(root, "/roms/%2E%2E/catalogue.json"))
            assertNull(GamePlayerActivity.resolveLocalFile(root, "/ejs/missing.js"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `remote and controller keys map to console buttons`() {
        assertEquals(GamePlayerActivity.BTN_A, GamePlayerActivity.buttonFor(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(GamePlayerActivity.BTN_UP, GamePlayerActivity.buttonFor(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(GamePlayerActivity.BTN_B, GamePlayerActivity.buttonFor(KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(GamePlayerActivity.BTN_START, GamePlayerActivity.buttonFor(KeyEvent.KEYCODE_MENU))
        assertNull(GamePlayerActivity.buttonFor(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals("application/wasm", GamePlayerActivity.mimeFor("core.wasm"))
        assertEquals("text/html", GamePlayerActivity.mimeFor("lanceur.html"))
    }

    @Test
    fun `the published catalogue is complete`() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "game/catalogue.json") }
            .firstOrNull { it.isFile } ?: return
        val cat = MissaGames.parseCatalogue(file.readText())
        assertNotNull(cat)
        // Le catalogue peut être vide (univers Jeux remis à zéro), mais il reste lisible.
        assertTrue(cat!!.games.all { cat.emulatorFor(it) != null })
        assertTrue(cat.engine.url.startsWith("https://raw.githubusercontent.com/missamedia69-code/"))
        // Pas d'émulateur dont la licence interdit l'usage commercial.
        assertTrue(cat.emulators.values.none { it.core in setOf("snes9x", "genesis_plus_gx", "picodrive") })
    }
}
