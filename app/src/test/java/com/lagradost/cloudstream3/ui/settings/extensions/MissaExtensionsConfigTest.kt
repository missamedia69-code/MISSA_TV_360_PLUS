package com.lagradost.cloudstream3.ui.settings.extensions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // Vérifications générales, valables pour toute configuration exportée : pas de
        // nombre d'extensions écrit en dur, sinon chaque nouvel export ferait échouer la CI.
        val text = file.readText()
        val list = MissaExtensionsConfig.parse(text)
        assertNotNull(list)
        assertTrue(list!!.all { it.repositoryUrl.startsWith("https://") })
        assertEquals(list.size, list.distinctBy { it.repositoryUrl to it.internalName }.size)
        assertFalse(list.any { it.internalName.equals("All for U", ignoreCase = true) })
        // Chaque univers écrit dans le fichier doit être reconnu par l'application : un nom
        // inconnu serait ignoré sans bruit et l'extension tomberait en « Auto ».
        val raw = org.json.JSONObject(text).getJSONArray("extensions")
        for (i in 0 until raw.length()) {
            val o = raw.getJSONObject(i)
            val declared = o.optJSONArray("univers")
                ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }.orEmpty()
            val entry = list.firstOrNull {
                it.repositoryUrl == o.getString("depot").trim() && it.internalName == o.getString("extension").trim()
            } ?: continue
            when {
                declared.isEmpty() -> assertNull(entry.universes)
                declared == listOf("none") -> assertEquals(emptySet<String>(), entry.universes)
                else -> assertEquals("univers de ${o.getString("extension")}", declared.toSet(), entry.universes)
            }
        }
    }
}
