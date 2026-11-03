package com.lagradost.cloudstream3.ui.universe

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * One rotated Kids selection for the current app launch.
 *
 * Built at most once per catalogue refresh, then reused for every re-render so the rows never
 * reshuffle while the user is browsing them.
 */
data class KidsCatalogSelection(
    val rows: LinkedHashMap<KidsCategory, List<MusicTrackPreset>>,
    val all: List<MusicTrackPreset>,
    val promotedIds: List<String>,
    val catalogStamp: Int
)

/**
 * Catalogue controller for the **Enfants** universe (`id = "kids"`) — **KIDS-01 / FRESH-01**.
 *
 * Mirrors [DirectMusicPlayerManager] on the streaming side (YouTube via NewPipe, lazy
 * `ytmusic://` urls, disk cache) but adds what the Kids universe requires:
 * - YouTube is the **single** source of the universe,
 * - the discovery plan and the promoted cards are **re-rolled on every app launch** thanks to
 *   [MissaCatalogRotation], so the same videos are never pushed twice in a row.
 */
object DirectKidsPlayerManager {

    private const val REMOTE_CACHE_KEY = "missa_yt_kids_catalog_v1"
    private const val REMOTE_UPDATED_AT_KEY = "missa_yt_kids_updated_at_v1"
    private const val PROMOTED_IDS_KEY = "missa_yt_kids_promoted_ids_v1"

    /** Disk cache lifetime: a launch always re-rolls the promoted selection anyway. */
    private const val REMOTE_CACHE_TTL_MS = 45 * 60_000L
    private const val ROW_CAP = 24
    private const val ALL_ROW_CAP = 60
    private const val PROMOTED_MEMORY = 140

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _videosUpdated = MutableLiveData(0L)
    val videosUpdated: LiveData<Long> = _videosUpdated

    @Volatile
    private var inMemoryVideos: List<MusicTrackPreset> = emptyList()

    @Volatile
    private var refreshInFlight: Boolean = false

    @Volatile
    private var selection: KidsCatalogSelection? = null

    private var refreshJob: Job? = null

    private val discoveredSearchVideos = ConcurrentHashMap<String, MusicTrackPreset>()
    private val onlineSearchLock = Any()
    private val onlineSearchByQuery = LinkedHashMap<String, List<MusicTrackPreset>>(16, 0.75f, true)

    // -------------------------------------------------------------------------
    // Catalogue access
    // -------------------------------------------------------------------------

    private fun cachedRemoteVideos(): List<MusicTrackPreset> {
        val mem = inMemoryVideos
        if (mem.isNotEmpty()) return mem
        val persisted = runCatching {
            getKey<Array<MusicTrackPreset>>(REMOTE_CACHE_KEY)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        if (persisted.isNotEmpty()) {
            inMemoryVideos = persisted
        }
        return persisted
    }

    /** Every Kids video currently known (cached catalogue + on-demand searches). */
    fun allPresetVideos(): List<MusicTrackPreset> {
        val cached = cachedRemoteVideos()
        val discovered = discoveredSearchVideos.values.toList()
        if (cached.isEmpty() && discovered.isEmpty()) return emptyList()
        val merged = ArrayList<MusicTrackPreset>(cached.size + discovered.size)
        val seenIds = HashSet<String>()
        for (video in cached + discovered) {
            if (video.title.isBlank() || video.streamUrl.isBlank()) continue
            if (seenIds.add(video.id)) merged.add(video)
        }
        return merged
    }

    /** Ids promoted during the previous launches; they are demoted by the next rotation. */
    fun promotedIds(): Set<String> = runCatching {
        getKey<Array<String>>(PROMOTED_IDS_KEY)?.toSet().orEmpty()
    }.getOrDefault(emptySet())

    private fun persistPromotedIds(newlyPromoted: Collection<String>) {
        if (newlyPromoted.isEmpty()) return
        val merged = MissaCatalogRotation.mergeRecentIds(
            previous = promotedIds(),
            newlyPromoted = newlyPromoted,
            keep = PROMOTED_MEMORY
        )
        runCatching { setKey(PROMOTED_IDS_KEY, merged.toTypedArray()) }
    }

    /**
     * Returns the rotated selection for this launch, rebuilding it only when the underlying
     * catalogue changed (or on the very first call of the process).
     */
    fun currentSelection(): KidsCatalogSelection {
        val videos = allPresetVideos()
        val stamp = MissaCatalogRotation.catalogueStamp(videos) { (it as? MusicTrackPreset)?.id }
        val cached = selection
        if (cached != null && cached.catalogStamp == stamp) return cached
        val built = buildSelection(videos, stamp)
        selection = built
        persistPromotedIds(built.promotedIds)
        return built
    }

    /** Drops the cached rotation (called after a refresh so the new catalogue is re-rolled). */
    fun invalidateSelection() {
        selection = null
    }

    private fun buildSelection(videos: List<MusicTrackPreset>, stamp: Int): KidsCatalogSelection {
        val random = MissaCatalogRotation.currentRandom()
        val recent = promotedIds()
        val grouped = MissaKidsProviders.groupByCategory(videos)

        val rows = LinkedHashMap<KidsCategory, List<MusicTrackPreset>>()
        for ((category, items) in grouped) {
            val rotated = MissaCatalogRotation.rotateAvoidingRecent(items, recent, random) { it.id }
            rows[category] = rotated.take(ROW_CAP)
        }

        val all = MissaCatalogRotation
            .rotateAvoidingRecent(videos, recent, random) { it.id }
            .take(ALL_ROW_CAP)

        val promoted = ArrayList<String>()
        for (row in rows.values) {
            for (item in row.take(4)) promoted.add(item.id)
        }
        for (item in all.take(6)) promoted.add(item.id)

        return KidsCatalogSelection(
            rows = rows,
            all = all,
            promotedIds = promoted.distinct(),
            catalogStamp = stamp
        )
    }

    // -------------------------------------------------------------------------
    // Refresh
    // -------------------------------------------------------------------------

    /**
     * Fetches a fresh Kids catalogue from YouTube.
     *
     * [force] bypasses the disk cache TTL: it is what the app uses on every cold start so that
     * the YouTube surfaces (Kids **and** Musiques) renew themselves at each launch.
     */
    suspend fun refreshRemoteVideosIfNeeded(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val lastUpdated = runCatching { getKey<Long>(REMOTE_UPDATED_AT_KEY) ?: 0L }.getOrDefault(0L)
        val hasCached = cachedRemoteVideos().isNotEmpty()

        if (!force && hasCached && (now - lastUpdated) < REMOTE_CACHE_TTL_MS) {
            return
        }
        if (refreshInFlight && !force) return
        if (force) refreshJob?.cancel()
        refreshInFlight = true
        try {
            MissaMusicProviders.warmUpYouTubeEngine()
            val plan = MissaKidsProviders.buildKidsQueryPlan()
            val catalog = MissaKidsProviders.fetchKidsCatalog(plan) { firstWave ->
                if (firstWave.isNotEmpty()) {
                    inMemoryVideos = firstWave
                    selection = null
                    _videosUpdated.postValue(System.currentTimeMillis())
                }
            }
            if (catalog.isNotEmpty()) {
                inMemoryVideos = catalog
                selection = null
                runCatching {
                    setKey(REMOTE_CACHE_KEY, catalog.toTypedArray())
                    setKey(REMOTE_UPDATED_AT_KEY, System.currentTimeMillis())
                }
                _videosUpdated.postValue(System.currentTimeMillis())
            }
        } finally {
            refreshInFlight = false
        }
    }

    /** Fire-and-forget refresh used by pull-to-refresh and by the app cold start. */
    fun forceRefreshCatalog() {
        refreshJob?.cancel()
        refreshJob = ioScope.launch {
            refreshRemoteVideosIfNeeded(force = true)
        }
    }

    // -------------------------------------------------------------------------
    // In-universe search
    // -------------------------------------------------------------------------

    fun getOnlineSearchResults(query: String): List<MusicTrackPreset> {
        val normKey = MissaMusicProviders.normalizeForMatch(query.trim().lowercase())
        if (normKey.isEmpty()) return emptyList()
        return synchronized(onlineSearchLock) {
            onlineSearchByQuery[normKey].orEmpty()
        }
    }

    /** Searches YouTube for kids-safe videos matching [query] (bilingual FR/EN). */
    suspend fun searchVideosOnline(query: String): List<MusicTrackPreset> {
        val clean = query.trim()
        if (clean.length < 2) return emptyList()
        val plan = listOf(
            KidsQuery(category = KidsCategory.CARTOONS, query = "$clean pour enfants", language = "fr"),
            KidsQuery(category = KidsCategory.CARTOONS, query = "$clean for kids", language = "en"),
            KidsQuery(category = KidsCategory.SONGS, query = "comptines $clean", language = "fr"),
            KidsQuery(category = KidsCategory.STORIES, query = "histoire $clean enfants", language = "fr")
        )
        val found = MissaKidsProviders.fetchKidsCatalog(plan)
        if (found.isNotEmpty()) {
            val normKey = MissaMusicProviders.normalizeForMatch(clean.lowercase())
            synchronized(onlineSearchLock) {
                if (onlineSearchByQuery.size >= 16) {
                    onlineSearchByQuery.entries.firstOrNull()?.key?.let { onlineSearchByQuery.remove(it) }
                }
                onlineSearchByQuery[normKey] = found
            }
            found.forEach { video -> discoveredSearchVideos[video.id] = video }
            _videosUpdated.postValue(System.currentTimeMillis())
        }
        return found
    }
}
