package com.lagradost.cloudstream3.ui.settings.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MissaExtensionsConfigTest {

    @Test
    fun `export then read gives back repository, extension and universes`() {
        val entries = listOf(
            MissaExtensionsConfig.Entry("https://exemple.org/repo.json", "Dépôt", "Films1", "Films 1", setOf("films", "series")),
            MissaExtensionsConfig.Entry("https://exemple.org/repo.json", "Dépôt", "Aucun1", null, emptySet()),
            MissaExtensionsConfig.Entry("https://autre.org/repo.json", null, "Auto1", null, null)
        )
        val back = MissaExtensionsConfig.parse(MissaExtensionsConfig.write(entries))!!
        assertEquals(entries, back)
    }

    @Test
    fun `invalid entries and unknown universes are ignored`() {
        val text = """
            {"extensions": [
              {"depot": "http://pas-https.org/repo.json", "extension": "A", "univers": ["films"]},
              {"depot": "https://ok.org/repo.json", "extension": "", "univers": ["films"]},
              {"depot": "https://ok.org/repo.json", "extension": "B", "univers": ["musique", "sports"]},
              {"depot": "https://ok.org/repo.json", "extension": "C", "univers": ["inconnu"]}
            ]}
        """.trimIndent()
        val list = MissaExtensionsConfig.parse(text)!!
        assertEquals(listOf("B", "C"), list.map { it.internalName })
        assertEquals(setOf("sports"), list[0].universes)
        assertNull(list[1].universes)
        assertNull(MissaExtensionsConfig.parse("pas du json"))
    }

    @Test
    fun `legacy MISSA extension is removed only after MediaTri has been installed`() {
        val repo = "https://missatv.pages.dev/extensions/depot/repo.json"
        val wanted = setOf("MediaTri".lowercase())
        assertTrue(MissaExtensionsConfig.shouldRetireLegacyMissaPlugin(repo, wanted, true))
        assertTrue(!MissaExtensionsConfig.shouldRetireLegacyMissaPlugin(repo, wanted, false))
        assertTrue(!MissaExtensionsConfig.shouldRetireLegacyMissaPlugin(
            "https://other.example/repo.json", wanted, true))
        assertTrue(!MissaExtensionsConfig.shouldRetireLegacyMissaPlugin(
            repo, wanted + "multisources fr-en", true))
    }

    @Test
    fun `the published configuration is readable`() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "extensions/config.json") }
            .firstOrNull { it.isFile } ?: return
        val list = MissaExtensionsConfig.parse(file.readText())
        assertNotNull(list)
        assertTrue(list!!.all { it.repositoryUrl.startsWith("https://") })
    }
}
