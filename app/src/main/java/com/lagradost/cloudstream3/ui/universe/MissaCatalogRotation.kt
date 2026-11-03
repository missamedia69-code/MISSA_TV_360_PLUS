package com.lagradost.cloudstream3.ui.universe

import kotlin.random.Random

/**
 * Pure, Android-framework-free rotation engine shared by every YouTube-powered surface of
 * MISSA TV 360 PLUS (`Musiques` and `Enfants`) — requirement **KIDS-01 / FRESH-01**.
 *
 * Goal: two consecutive app launches must never promote the same YouTube selection. Each cold
 * start rolls a new [startNewLaunch] seed that drives:
 * 1. which discovery queries are sent to YouTube (random subset of a larger pool),
 * 2. the ordering of the resulting cards inside every row,
 * 3. the demotion of the items promoted during the previous launches ([rotateAvoidingRecent]).
 *
 * Everything here is deterministic given a [Random], which makes the behaviour unit-testable on
 * the JVM (see `MissaCatalogRotationTest`).
 */
object MissaCatalogRotation {

    /** Default number of recently promoted ids kept per surface. */
    const val DEFAULT_RECENT_MEMORY = 120

    @Volatile
    private var launchSeed: Long = Random.nextLong()

    @Volatile
    private var launchCount: Int = 0

    @Volatile
    private var launchRefreshConsumed: Boolean = false

    /** Seed driving the selection of the *current* process. */
    fun currentLaunchSeed(): Long = launchSeed

    /** [Random] bound to the current launch seed (stable for the whole process). */
    fun currentRandom(): Random = Random(launchSeed)

    fun launchCount(): Int = launchCount

    /**
     * Rolls a brand-new seed for a fresh app process. Call once per cold start, before any
     * YouTube catalogue is fetched, so the queries and the promoted cards change.
     */
    fun startNewLaunch(): Long {
        launchSeed = Random.nextLong()
        launchCount += 1
        launchRefreshConsumed = false
        return launchSeed
    }

    /**
     * Returns `true` exactly once per process so a cold start can force a YouTube refresh
     * (bypassing the disk cache TTL) without hammering the network on every configuration change.
     */
    fun consumeLaunchRefreshTicket(): Boolean {
        if (launchRefreshConsumed) return false
        launchRefreshConsumed = true
        return true
    }

    /**
     * Draws [count] distinct elements from [pool] using a partial Fisher-Yates shuffle.
     * The result order is itself randomized, so a stable pool still yields a different
     * query plan on every launch.
     */
    fun <T> pickRandomSubset(pool: List<T>, count: Int, random: Random): List<T> {
        if (pool.isEmpty() || count <= 0) return emptyList()
        val wanted = count.coerceAtMost(pool.size)
        val indices = ArrayList<Int>(pool.size)
        for (i in pool.indices) indices.add(i)
        for (i in 0 until wanted) {
            val j = i + random.nextInt(pool.size - i)
            val tmp = indices[i]
            indices[i] = indices[j]
            indices[j] = tmp
        }
        val picked = ArrayList<T>(wanted)
        for (i in 0 until wanted) picked.add(pool[indices[i]])
        return picked
    }

    /** Non-destructive shuffle driven by [random]. */
    fun <T> shuffled(items: List<T>, random: Random): List<T> {
        if (items.size <= 1) return items
        val mutable = items.toMutableList()
        mutable.shuffle(random)
        return mutable
    }

    /**
     * Rotates [items] so that anything already promoted during a previous launch ([recentIds])
     * is pushed to the tail, each group being shuffled with [random].
     *
     * This is what guarantees "pas toujours les mêmes contenus mis en avant": the head of every
     * row is filled with cards the user has not been shown first recently.
     */
    fun <T> rotateAvoidingRecent(
        items: List<T>,
        recentIds: Set<String>,
        random: Random,
        keySelector: (T) -> String
    ): List<T> {
        if (items.isEmpty()) return emptyList()
        if (recentIds.isEmpty()) return shuffled(items, random)
        val fresh = ArrayList<T>(items.size)
        val alreadySeen = ArrayList<T>()
        for (item in items) {
            if (keySelector(item) in recentIds) alreadySeen.add(item) else fresh.add(item)
        }
        fresh.shuffle(random)
        alreadySeen.shuffle(random)
        fresh.addAll(alreadySeen)
        return fresh
    }

    /**
     * Merges [newlyPromoted] into [previous] (most recent last) and trims the memory to [keep].
     * Pure: the caller persists the result.
     */
    fun mergeRecentIds(
        previous: Collection<String>,
        newlyPromoted: Collection<String>,
        keep: Int = DEFAULT_RECENT_MEMORY
    ): List<String> {
        val limit = keep.coerceAtLeast(1)
        val merged = ArrayList<String>(previous.size + newlyPromoted.size)
        for (id in previous) {
            if (id !in merged) merged.add(id)
        }
        for (id in newlyPromoted) {
            merged.remove(id)
            merged.add(id)
        }
        return if (merged.size > limit) merged.takeLast(limit) else merged
    }

    /**
     * Stable signature of a catalogue, used to know whether a cached row selection is still in
     * sync with the underlying items (a refresh must invalidate the previous rotation).
     */
    fun catalogueStamp(items: List<*>, keySelector: (Any?) -> String? = { it?.toString() }): Int {
        if (items.isEmpty()) return 0
        var hash = items.size
        hash = 31 * hash + (keySelector(items.first()).hashCode())
        hash = 31 * hash + (keySelector(items.last()).hashCode())
        return hash
    }
}
