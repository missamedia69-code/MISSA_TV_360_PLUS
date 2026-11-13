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
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Sélection tournante de l'univers Documentaires & Live pour le lancement en cours. */
data class DocsCatalogSelection(
    val rows: LinkedHashMap<DocsCategory, List<MusicTrackPreset>>,
    val all: List<MusicTrackPreset>,
    val promotedIds: List<String>,
    val catalogStamp: Int
)

/**
 * Catalogue de l'univers **Documentaires & Live** : 100 % YouTube, sur le modèle de
 * [DirectKidsPlayerManager] (cache disque, rotation à chaque lancement, recherche en ligne).
 * La lecture passe par [DirectKidsPlayerManager.playDirectResponse] (lecteur vidéo ; flux HLS
 * pour un direct).
 */
object DirectDocsPlayerManager {

    private const val REMOTE_CACHE_KEY = "missa_yt_docs_catalog_v1"
    private const val REMOTE_UPDATED_AT_KEY = "missa_yt_docs_updated_at_v1"
    private const val PROMOTED_IDS_KEY = "missa_yt_docs_promoted_ids_v1"

    /** Les directs changent vite : le catalogue est renouvelé toutes les 30 minutes au plus. */
    private const val REMOTE_CACHE_TTL_MS = 30 * 60_000L
    private const val ROW_CAP = 24
    private const val ALL_ROW_CAP = 60
    private const val PROMOTED_MEMORY = 140

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _videosUpdated = MutableLiveData(0L)
    val videosUpdated: LiveData<Long> = _videosUpdated

    @Volatile
    private var inMemoryVideos: List<MusicTrackPreset> = emptyList()

    @Volatile
    private var refreshInFlight = false

    @Volatile
    private var selection: DocsCatalogSelection? = null

    private var refreshJob: Job? = null

    private val discoveredSearchVideos = ConcurrentHashMap<String, MusicTrackPreset>()
    private val onlineSearchLock = Any()
    private val onlineSearchByQuery = LinkedHashMap<String, List<MusicTrackPreset>>(16, 0.75f, true)

    private suspend fun cachedRemoteVideos(): List<MusicTrackPreset> = withContext(Dispatchers.IO) {
        inMemoryVideos.takeIf { it.isNotEmpty() }?.let { return@withContext it }
        val persisted = runCatching {
            MissaCatalogueCache.read<Array<MusicTrackPreset>>(REMOTE_CACHE_KEY)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        if (persisted.isNotEmpty()) inMemoryVideos = persisted
        persisted
    }

    suspend fun allPresetVideos(): List<MusicTrackPreset> {
        val cached = cachedRemoteVideos()
        val discovered = discoveredSearchVideos.values.toList()
        if (cached.isEmpty() && discovered.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) { MissaDocsProviders.dedupe(cached + discovered) }
    }

    private fun promotedIds(): Set<String> = runCatching {
        getKey<Array<String>>(PROMOTED_IDS_KEY)?.toSet().orEmpty()
    }.getOrDefault(emptySet())

    suspend fun currentSelection(): DocsCatalogSelection {
        val videos = allPresetVideos()
        val stamp = MissaCatalogRotation.catalogueStamp(videos) { (it as? MusicTrackPreset)?.id }
        selection?.takeIf { it.catalogStamp == stamp }?.let { return it }
        val random = MissaCatalogRotation.currentRandom()
        val recent = promotedIds()
        val rows = LinkedHashMap<DocsCategory, List<MusicTrackPreset>>()
        for ((category, items) in MissaDocsProviders.groupByCategory(videos)) {
            // Les directs gardent l'ordre de YouTube (les plus regardés d'abord).
            rows[category] = if (category.isLive) items.take(ROW_CAP)
            else MissaCatalogRotation.rotateAvoidingRecent(items, recent, random) { it.id }.take(ROW_CAP)
        }
        val all = MissaCatalogRotation.rotateAvoidingRecent(
            videos.filterNot { MissaDocsProviders.isLiveVideo(it) }, recent, random
        ) { it.id }.take(ALL_ROW_CAP)
        val promoted = (rows.values.flatMap { it.take(4) } + all.take(6)).map { it.id }.distinct()
        val built = DocsCatalogSelection(rows, all, promoted, stamp)
        selection = built
        runCatching {
            val merged = MissaCatalogRotation.mergeRecentIds(promotedIds(), promoted, PROMOTED_MEMORY)
            setKey(PROMOTED_IDS_KEY, merged.toTypedArray())
        }
        return built
    }

    suspend fun isAwaitingFirstCatalogue(): Boolean =
        allPresetVideos().isEmpty() && (refreshInFlight || refreshJob?.isActive == true)

    suspend fun refreshRemoteVideosIfNeeded(force: Boolean = false) {
        val now = System.currentTimeMillis()
        val lastUpdated = runCatching { getKey<Long>(REMOTE_UPDATED_AT_KEY) ?: 0L }.getOrDefault(0L)
        val hasCached = cachedRemoteVideos().isNotEmpty()
        if (!force && hasCached && now - lastUpdated < REMOTE_CACHE_TTL_MS) return
        if (refreshInFlight && !force) return
        refreshInFlight = true
        try {
            MissaMusicProviders.warmUpYouTubeEngine()
            val plan = MissaDocsProviders.buildQueryPlan(
                perCategory = if (MissaDeviceProfile.isLowRam()) 1 else MissaDocsProviders.QUERIES_PER_CATEGORY
            )
            val catalog = MissaDocsProviders.fetchCatalog(plan) { first ->
                if (first.isNotEmpty()) {
                    inMemoryVideos = first
                    selection = null
                    _videosUpdated.postValue(System.currentTimeMillis())
                }
            }
            if (catalog.isNotEmpty()) {
                inMemoryVideos = catalog
                selection = null
                runCatching {
                    MissaCatalogueCache.write(REMOTE_CACHE_KEY, catalog.toTypedArray())
                    setKey(REMOTE_UPDATED_AT_KEY, System.currentTimeMillis())
                }
                _videosUpdated.postValue(System.currentTimeMillis())
            }
        } finally {
            refreshInFlight = false
        }
    }

    fun forceRefreshCatalog() {
        refreshJob?.cancel()
        refreshJob = ioScope.launch { refreshRemoteVideosIfNeeded(force = true) }
    }

    /** Bannière : un direct puis un documentaire de chaque rangée. */
    suspend fun heroVideos(max: Int = 5): List<MusicTrackPreset> {
        val sel = currentSelection()
        val picked = ArrayList<MusicTrackPreset>(max)
        for (items in sel.rows.values) {
            items.firstOrNull { v -> picked.none { it.id == v.id } }?.let { picked += it }
            if (picked.size >= max) break
        }
        return picked.sortedByDescending { !it.artworkUrl.isNullOrBlank() }.take(max)
    }

    fun getOnlineSearchResults(query: String): List<MusicTrackPreset> {
        val key = MissaMusicProviders.normalizeForMatch(query.trim().lowercase())
        if (key.isEmpty()) return emptyList()
        return synchronized(onlineSearchLock) { onlineSearchByQuery[key].orEmpty() }
    }

    /** Recherche YouTube : documentaires et directs correspondant à [query]. */
    suspend fun searchVideosOnline(query: String): List<MusicTrackPreset> {
        val clean = query.trim()
        if (clean.length < 2) return emptyList()
        val plan = listOf(
            DocsQuery(DocsCategory.SOCIETY, "documentaire $clean"),
            DocsQuery(DocsCategory.HISTORY, "$clean documentary"),
            DocsQuery(DocsCategory.LIVE_NEWS, "$clean en direct"),
            DocsQuery(DocsCategory.LIVE_DOCS, "$clean live")
        )
        val found = MissaDocsProviders.fetchCatalog(plan)
        if (found.isNotEmpty()) {
            val key = MissaMusicProviders.normalizeForMatch(clean.lowercase())
            synchronized(onlineSearchLock) {
                if (onlineSearchByQuery.size >= 16) {
                    onlineSearchByQuery.entries.firstOrNull()?.key?.let { onlineSearchByQuery.remove(it) }
                }
                onlineSearchByQuery[key] = found
            }
            found.forEach { discoveredSearchVideos[it.id] = it }
            _videosUpdated.postValue(System.currentTimeMillis())
        }
        return found
    }
}
