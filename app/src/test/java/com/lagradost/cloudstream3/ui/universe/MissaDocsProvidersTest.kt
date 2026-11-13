package com.lagradost.cloudstream3.ui.universe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MissaDocsProvidersTest {

    @Test
    fun `trailers, clips, reactions and games are blocked`() {
        assertTrue(MissaDocsProviders.isBlocked("Le Roi Lion - Bande-annonce officielle"))
        assertTrue(MissaDocsProviders.isBlocked("Fally Ipupa - Clip officiel"))
        assertTrue(MissaDocsProviders.isBlocked("Je réagis au documentaire"))
        assertTrue(MissaDocsProviders.isBlocked("Minecraft gameplay 2 heures"))
        assertFalse(MissaDocsProviders.isBlocked("L'empire du Mali - Documentaire complet", "ARTE"))
        assertFalse(MissaDocsProviders.isBlocked("FRANCE 24 – EN DIRECT – Info en continu 24h/24", "FRANCE 24"))
    }

    @Test
    fun `documentary signal`() {
        assertTrue(MissaDocsProviders.looksDocumentary("Les grands félins d'Afrique"))
        assertTrue(MissaDocsProviders.looksDocumentary("Planet Earth", "BBC Earth"))
        assertFalse(MissaDocsProviders.looksDocumentary("Vlog du dimanche"))
    }

    @Test
    fun `query plan covers every row, lives first, never decorated`() {
        val plan = MissaDocsProviders.buildQueryPlan(Random(42), 1_800_000_000_000L, perCategory = 2)
        assertEquals(DocsCategory.all().toSet(), plan.map { it.category }.toSet())
        assertEquals(2 * DocsCategory.all().size, plan.size)
        assertTrue(plan.filter { it.category.isLive }.none { it.query.contains("after:") })
        assertTrue(DocsCategory.all().first().isLive)
    }

    @Test
    fun `duration window`() {
        assertFalse(MissaDocsProviders.isValidDuration(300, DocsCategory.HISTORY))
        assertTrue(MissaDocsProviders.isValidDuration(3_000, DocsCategory.HISTORY))
        assertFalse(MissaDocsProviders.isValidDuration(20_000, DocsCategory.HISTORY))
    }
}
