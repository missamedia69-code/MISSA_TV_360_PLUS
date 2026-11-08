package com.lagradost.cloudstream3.ui.universe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tests JVM du catalogue des jeux homebrew (`retro-catalog.json` à la racine du dépôt).
 *
 * Le dernier test lit le vrai fichier publié : si quelqu'un casse le catalogue, l'appli
 * n'afficherait plus aucun jeu distant, sans erreur visible. On préfère que la CI le dise.
 */
class MissaRetroCatalogTest {

    private val sha = "3ea2376b15b34bd26b10e6b31d2753bf8b086098dbdb5ee84d9c0f03d66d3d7f"

    @Test
    fun `lit une entree complete`() {
        val games = MissaRetroCatalog.parse(
            """
            [{"id":"2048-gb","title":"2048","systemId":"gb","platformLabel":"Game Boy",
              "genre":"Réflexion","author":"Sanqui","description":"Puzzle",
              "fileName":"2048.gb","downloadUrl":"https://example.org/2048.gb",
              "sourceUrl":"https://github.com/Sanqui/2048-gb","licenceNote":"Zlib",
              "estimatedBytes":32768,"sha256":"${sha.uppercase()}"}]
            """
        )
        assertEquals(1, games.size)
        val game = games[0]
        assertEquals("2048", game.title)
        assertEquals("Sanqui", game.author)
        assertEquals(32768L, game.estimatedBytes)
        assertEquals(sha, game.sha256)
    }

    @Test
    fun `ignore les entrees dangereuses ou incompletes`() {
        val games = MissaRetroCatalog.parse(
            """
            [{"fileName":"a.gb","downloadUrl":"http://example.org/a.gb"},
             {"fileName":"","downloadUrl":"https://example.org/b.gb"},
             {"fileName":"c.gb","downloadUrl":"https://example.org/c.gb","sha256":"pas-une-empreinte"},
             {"fileName":"c.gb","downloadUrl":"https://example.org/doublon.gb"}]
            """
        )
        assertEquals(listOf("c.gb"), games.map { it.fileName })
        assertNull(games[0].sha256)
    }

    @Test
    fun `le nom de fichier ne peut pas sortir du dossier des jeux`() {
        assertEquals("evil.gb", MissaRetroCatalog.sanitizeFileName("../../evil.gb"))
        assertEquals("evil.gb", MissaRetroCatalog.sanitizeFileName("..\\evil.gb"))
        assertEquals("game.gb", MissaRetroCatalog.sanitizeFileName(".."))
        assertEquals("RoboRun-NES_1.1.nes", MissaRetroCatalog.sanitizeFileName("RoboRun-NES_1.1.nes"))
    }

    @Test
    fun `empreinte d'un fichier`() {
        val file = File.createTempFile("missa", ".gb")
        try {
            file.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                MissaRetroCatalog.sha256Of(file)
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun `le catalogue publie dans le depot est lisible et complet`() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "retro-catalog.json") }
            .firstOrNull { it.isFile } ?: return // hors du dépôt : rien à vérifier
        val games = MissaRetroCatalog.parse(file.readText())
        assertTrue("catalogue vide", games.isNotEmpty())
        val systems = setOf("gb", "nes", "snes", "gba", "sega")
        games.forEach { game ->
            assertTrue(game.fileName, game.systemId in systems)
            assertTrue(game.fileName, RetroGamePlayerActivity.supportsExtension(game.fileName.substringAfterLast('.')))
            assertTrue(game.fileName, game.downloadUrl.startsWith("https://raw.githubusercontent.com/missamedia69-code/"))
            assertTrue(game.fileName, game.sha256 != null && game.estimatedBytes > 0L)
        }
    }
}
