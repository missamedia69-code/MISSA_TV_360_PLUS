package com.lagradost.cloudstream3.ui.universe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * JVM unit tests for [MissaCatalogRotation], the engine that guarantees every YouTube-powered
 * surface (Enfants, Musiques) is renewed on each app launch (KIDS-01 / FRESH-01).
 */
class MissaCatalogRotationTest {

    private data class Item(val id: String)

    @Test
    fun `pickRandomSubset is deterministic for a given seed`() {
        val pool = listOf("a", "b", "c", "d", "e")
        val first = MissaCatalogRotation.pickRandomSubset(pool, 3, Random(42))
        val second = MissaCatalogRotation.pickRandomSubset(pool, 3, Random(42))
        assertEquals(first, second)
        assertEquals(3, first.size)
        assertEquals(first.toSet().size, first.size)
        assertTrue(first.all { it in pool })
    }

    @Test
    fun `pickRandomSubset clamps to the pool size and handles empty pools`() {
        val pool = listOf(1, 2, 3, 4)
        val picked = MissaCatalogRotation.pickRandomSubset(pool, 99, Random(7))
        assertEquals(4, picked.size)
        assertEquals(pool.toSet(), picked.toSet())
        assertTrue(MissaCatalogRotation.pickRandomSubset(emptyList<String>(), 3, Random(1)).isEmpty())
        assertTrue(MissaCatalogRotation.pickRandomSubset(pool, 0, Random(1)).isEmpty())
    }

    @Test
    fun `pickRandomSubset explores the whole pool across launches`() {
        val pool = (0 until 12).toList()
        val seen = HashSet<Int>()
        for (seed in 0 until 60) {
            seen.addAll(MissaCatalogRotation.pickRandomSubset(pool, 2, Random(seed)))
        }
        assertEquals("every element must be reachable across launches", pool.toSet(), seen)
    }

    @Test
    fun `shuffled keeps every element and stays deterministic`() {
        val items = listOf("a", "b", "c", "d", "e", "f")
        val first = MissaCatalogRotation.shuffled(items, Random(9))
        val second = MissaCatalogRotation.shuffled(items, Random(9))
        assertEquals(first, second)
        assertEquals(items.toSet(), first.toSet())
        assertEquals(items.size, first.size)
        assertEquals(listOf("only"), MissaCatalogRotation.shuffled(listOf("only"), Random(3)))
    }

    @Test
    fun `rotateAvoidingRecent pushes previously promoted items to the tail`() {
        val items = listOf(Item("a"), Item("b"), Item("c"), Item("d"), Item("e"))
        val rotated = MissaCatalogRotation.rotateAvoidingRecent(
            items = items,
            recentIds = setOf("a", "c"),
            random = Random(5),
            keySelector = { it.id }
        )
        assertEquals(5, rotated.size)
        assertEquals(items.toSet(), rotated.toSet())
        val head = rotated.take(3).map { it.id }.toSet()
        val tail = rotated.takeLast(2).map { it.id }.toSet()
        assertTrue("fresh items must lead the row: $rotated", head.none { it in setOf("a", "c") })
        assertEquals(setOf("a", "c"), tail)
    }

    @Test
    fun `rotateAvoidingRecent with no memory is a plain shuffle`() {
        val items = listOf(Item("a"), Item("b"), Item("c"))
        val rotated = MissaCatalogRotation.rotateAvoidingRecent(items, emptySet(), Random(11)) { it.id }
        assertEquals(items.toSet(), rotated.toSet())
        assertTrue(rotated.isNotEmpty())
    }

    @Test
    fun `mergeRecentIds moves promoted ids to the end and trims the memory`() {
        val merged = MissaCatalogRotation.mergeRecentIds(
            previous = listOf("a", "b", "c"),
            newlyPromoted = listOf("b", "d"),
            keep = 10
        )
        assertEquals(listOf("a", "c", "b", "d"), merged)

        val trimmed = MissaCatalogRotation.mergeRecentIds(
            previous = listOf("a", "b", "c"),
            newlyPromoted = listOf("b", "d"),
            keep = 3
        )
        assertEquals(listOf("c", "b", "d"), trimmed)
        assertEquals(
            listOf("x"),
            MissaCatalogRotation.mergeRecentIds(emptyList(), listOf("x", "x"), keep = 5)
        )
    }

    @Test
    fun `catalogueStamp changes with the catalogue and is stable otherwise`() {
        val items = listOf(Item("a"), Item("b"), Item("c"))
        val stamp = MissaCatalogRotation.catalogueStamp(items) { (it as? Item)?.id }
        assertEquals(stamp, MissaCatalogRotation.catalogueStamp(items.toList()) { (it as? Item)?.id })
        val changed = MissaCatalogRotation.catalogueStamp(items + Item("d")) { (it as? Item)?.id }
        assertTrue(stamp != changed)
        assertEquals(0, MissaCatalogRotation.catalogueStamp(emptyList<Item>()) { null })
    }

    @Test
    fun `startNewLaunch rolls a new seed`() {
        val before = MissaCatalogRotation.currentLaunchSeed()
        var changed = false
        for (i in 0 until 25) {
            MissaCatalogRotation.startNewLaunch()
            if (MissaCatalogRotation.currentLaunchSeed() != before) {
                changed = true
                break
            }
        }
        assertTrue("a new launch must roll a new seed", changed)
        assertEquals(
            MissaCatalogRotation.currentLaunchSeed(),
            MissaCatalogRotation.currentLaunchSeed()
        )
    }

    @Test
    fun `launch refresh ticket is granted once per launch`() {
        MissaCatalogRotation.startNewLaunch()
        assertTrue(MissaCatalogRotation.consumeLaunchRefreshTicket())
        assertFalse(MissaCatalogRotation.consumeLaunchRefreshTicket())
        MissaCatalogRotation.startNewLaunch()
        assertTrue(MissaCatalogRotation.consumeLaunchRefreshTicket())
    }
}
