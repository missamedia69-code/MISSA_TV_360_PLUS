package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.AppContextUtils.filterProviderByPreferredMedia
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private data class CachedCatalogSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String,
    override var type: TvType?,
    override var posterUrl: String?,
    override var posterHeaders: Map<String, String>? = null,
    override var id: Int? = url.hashCode(),
    override var quality: SearchQuality? = null,
    override var score: Score? = null
) : SearchResponse

data class RawHarvestedItem(
    val response: SearchResponse,
    val sectionName: String,
    val isHorizontalSection: Boolean,
    val providerSupportedTypes: Set<TvType>,
    val isPreferredLanguage: Boolean
)

data class UnifiedCatalogSnapshot(
    val isLoading: Boolean = true,
    val hasError: Boolean = false,
    val isOffline: Boolean = false,
    val activeProviderCount: Int = 0,
    val loadedProvidersCount: Int = 0,
    val totalProvidersCount: Int = 0,
    val entries: List<UnifiedCatalogEntry> = emptyList(),
    val resumeWatching: List<DataStoreHelper.ResumeWatchingResult> = emptyList(),
    val updatedAtMs: Long = 0L,
    val isFromDiskCache: Boolean = false
)

data class UniverseScreenProjection(
    val definition: UniverseDefinition,
    val isLoading: Boolean,
    val hasError: Boolean,
    val isOffline: Boolean,
    val emptyReason: UniverseEmptyReason?,
    val activeProviderCount: Int,
    val loadedProvidersCount: Int,
    val totalProvidersCount: Int,
    val updatedAtMs: Long,
    val heroSlides: List<HeroBannerSlide>,
    val resumeItems: List<DataStoreHelper.ResumeWatchingResult>,
    val hubShortcuts: List<UniverseHubCardItem>,
    val availableCategories: List<CategoryFilterChip>,
    val availableSources: List<String>,
    val rows: List<UniverseCategoryRow>,
    val hiddenRowCount: Int,
    val totalItemCount: Int,
    val lastLiveChannel: SearchResponse? = null
)

/**
 * Activity-scoped multi-plugin catalogue coordinator for MISSA TV 360 PLUS.
 *
 * Implements:
 * - HOME-04: Frozen hero slide order per universe until manual refresh.
 * - HOME-05: Streaming provider wave progress (loadedProvidersCount / totalProvidersCount).
 * - HOME-06: Explicit hasError, isOffline & 4-state UniverseEmptyReason.
 * - HOME-07: Progressive search progress, recent search chips, multi-source Set<String> filtering.
 * - HOME-08 / I18N-01: Stable MissaCategory enum keys + short chip labels + localized plurals.
 * - HOME-10: Deterministic editorial row ordering + 12-row fold.
 * - HOME-11: Resume item removal with Undo snapshot.
 * - HOME-12: Preferred source memory per title.
 * - HOME-13: Sub-second disk catalog snapshot cache + freshness timestamp.
 * - FLOW-02: Live channel favorites row + last watched live channel zapping.
 */
class MediaUniverseViewModel : ViewModel() {
    companion object {
        private const val PROVIDER_MAINPAGE_TIMEOUT_MS = 14_000L
        private const val SEARCH_PROVIDER_TIMEOUT_MS = 15_000L
        private const val DETAIL_TIMEOUT_MS = 3_200L
        private const val MAX_PROVIDERS_TO_HARVEST = 28
        private const val MAX_HERO_ENRICH = 8
        private const val MAX_CLASSIFIER_ENRICH = 28
        private const val SNAPSHOT_TTL_MS = 5 * 60_000L
        private const val DISK_CACHE_FILE = "missa_catalog_cache_v1.json"
        private const val PREF_RECENT_SEARCHES = "missa_recent_searches_v1"
        private const val PREF_PREFERRED_SOURCE_PREFIX = "missa_pref_source_v1_"
        private const val PREF_FAVORITE_LIVE_URLS = "missa_fav_live_urls_v1"
        private const val PREF_LAST_LIVE_URL = "missa_last_live_url_v1"
        const val MAX_COLLAPSED_ROWS = 12
    }

    private val detailsCache = ConcurrentHashMap<String, LoadResponse>()
    private val sourcesByItemUrl = ConcurrentHashMap<String, List<SearchResponse>>()
    private val frozenHeroKeysByUniverse = ConcurrentHashMap<String, List<String>>()

    private val _catalogSnapshot = MutableLiveData(UnifiedCatalogSnapshot())
    val catalogSnapshot: LiveData<UnifiedCatalogSnapshot> = _catalogSnapshot

    private var catalogJob: Job? = null
    private var lastLoadedProviderSignature: String = ""

    private val onPluginsLoadedListener: (Boolean) -> Unit = { force ->
        ensureCatalogLoaded(forceReload = force)
    }
    private val onMainPluginsLoadedListener: (Boolean) -> Unit = {
        ensureCatalogLoaded(forceReload = false)
    }
    private val onReloadHomeListener: (Boolean) -> Unit = {
        ensureCatalogLoaded(forceReload = true)
    }
    private val onBookmarksUpdatedListener: (Boolean) -> Unit = {
        refreshResumeWatching()
    }

    init {
        MainActivity.afterPluginsLoadedEvent += onPluginsLoadedListener
        MainActivity.mainPluginsLoadedEvent += onMainPluginsLoadedListener
        MainActivity.reloadHomeEvent += onReloadHomeListener
        MainActivity.bookmarksUpdatedEvent += onBookmarksUpdatedListener
        viewModelScope.launch {
            DirectRadioPlayerManager.refreshRemotePresetsIfNeeded()
        }
        hydrateDiskCacheAndLoad()
    }

    override fun onCleared() {
        MainActivity.afterPluginsLoadedEvent -= onPluginsLoadedListener
        MainActivity.mainPluginsLoadedEvent -= onMainPluginsLoadedListener
        MainActivity.reloadHomeEvent -= onReloadHomeListener
        MainActivity.bookmarksUpdatedEvent -= onBookmarksUpdatedListener
        super.onCleared()
    }

    private fun hydrateDiskCacheAndLoad() {
        viewModelScope.launch {
            val cached = loadCatalogFromDiskCache()
            val resume = loadResumeListSafe()
            if (cached != null && cached.first.isNotEmpty() && (_catalogSnapshot.value?.entries.isNullOrEmpty())) {
                _catalogSnapshot.value = UnifiedCatalogSnapshot(
                    isLoading = true,
                    hasError = false,
                    isOffline = !isNetworkConnected(),
                    activeProviderCount = countActiveProviders(),
                    entries = cached.first,
                    resumeWatching = resume,
                    updatedAtMs = cached.second,
                    isFromDiskCache = true
                )
            }
            ensureCatalogLoaded(forceReload = false)
        }
    }

    fun refreshResumeWatching() {
        viewModelScope.launch {
            val resume = loadResumeListSafe()
            val current = _catalogSnapshot.value ?: UnifiedCatalogSnapshot()
            _catalogSnapshot.value = current.copy(resumeWatching = resume)
        }
    }

    // --- Resume Watching Management (HOME-11) ---

    fun removeSingleResumeItem(parentId: Int?): DownloadObjects.ResumeWatching? {
        if (parentId == null) return null
        val snapshot = DataStoreHelper.getLastWatched(parentId)
        DataStoreHelper.removeLastWatched(parentId)
        refreshResumeWatching()
        return snapshot
    }

    fun restoreResumeItem(saved: DownloadObjects.ResumeWatching) {
        DataStoreHelper.setLastWatched(
            parentId = saved.parentId,
            episodeId = saved.episodeId,
            episode = saved.episode,
            season = saved.season,
            isFromDownload = saved.isFromDownload,
            updateTime = saved.updateTime
        )
        refreshResumeWatching()
    }

    fun clearResumeItems(items: List<DataStoreHelper.ResumeWatchingResult>) {
        items.forEach { item ->
            DataStoreHelper.removeLastWatched(item.id)
        }
        refreshResumeWatching()
    }

    // --- Preferred Source per Title (HOME-12) ---

    fun getAlternateSourcesFor(card: SearchResponse): List<SearchResponse> {
        return sourcesByItemUrl[itemIdentity(card)] ?: listOf(card)
    }

    fun resolvePreferredCard(card: SearchResponse, allSources: List<SearchResponse> = getAlternateSourcesFor(card)): SearchResponse {
        if (allSources.size <= 1) return card
        val key = PREF_PREFERRED_SOURCE_PREFIX + MediaUniverseClassifier.normalizeTitleKey(card.name)
        val preferredApi = getKey<String>(key)
        return allSources.firstOrNull { it.apiName.equals(preferredApi, ignoreCase = true) } ?: card
    }

    fun rememberPreferredSource(card: SearchResponse) {
        val key = PREF_PREFERRED_SOURCE_PREFIX + MediaUniverseClassifier.normalizeTitleKey(card.name)
        setKey(key, card.apiName)
    }

    // --- Recent Searches (HOME-07) ---

    fun getRecentSearches(): List<String> = runCatching {
        getKey<Array<String>>(PREF_RECENT_SEARCHES)?.toList().orEmpty()
    }.getOrDefault(emptyList())

    fun recordRecentSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return
        val updated = getRecentSearches().toMutableList().apply {
            removeAll { it.equals(trimmed, ignoreCase = true) }
            add(0, trimmed)
        }.take(8)
        setKey(PREF_RECENT_SEARCHES, updated.toTypedArray())
    }

    fun clearRecentSearches() {
        setKey(PREF_RECENT_SEARCHES, emptyArray<String>())
    }

    // --- Live TV Favorites & Last Channel Zapping (FLOW-02) ---

    fun favoriteLiveUrls(): Set<String> = runCatching {
        getKey<Array<String>>(PREF_FAVORITE_LIVE_URLS)?.toSet().orEmpty()
    }.getOrDefault(emptySet())

    fun isFavoriteLiveChannel(url: String): Boolean = favoriteLiveUrls().contains(url)

    fun toggleFavoriteLiveChannel(url: String): Boolean {
        val mutable = favoriteLiveUrls().toMutableSet()
        val added = if (mutable.contains(url)) {
            mutable.remove(url)
            false
        } else {
            mutable.add(url)
            true
        }
        setKey(PREF_FAVORITE_LIVE_URLS, mutable.toTypedArray())
        return added
    }

    fun recordLastLiveChannel(card: SearchResponse) {
        setKey(PREF_LAST_LIVE_URL, card.url)
    }

    fun getLastLiveChannelUrl(): String? = getKey<String>(PREF_LAST_LIVE_URL)

    // --- Catalog Harvesting & Enrichment ---

    fun ensureCatalogLoaded(forceReload: Boolean = false) {
        if (forceReload) {
            frozenHeroKeysByUniverse.clear()
        }
        val current = _catalogSnapshot.value ?: UnifiedCatalogSnapshot()
        val now = System.currentTimeMillis()
        val providerSignature = computeProviderSignature()

        if (!forceReload &&
            catalogJob?.isActive == true &&
            providerSignature == lastLoadedProviderSignature
        ) {
            return
        }

        if (!forceReload &&
            current.entries.isNotEmpty() &&
            !current.isFromDiskCache &&
            providerSignature == lastLoadedProviderSignature &&
            now - current.updatedAtMs < SNAPSHOT_TTL_MS
        ) {
            refreshResumeWatching()
            return
        }

        lastLoadedProviderSignature = providerSignature
        catalogJob?.cancel()
        catalogJob = viewModelScope.launch {
            val existingEntries = _catalogSnapshot.value?.entries.orEmpty()
            val resume = loadResumeListSafe()
            val online = isNetworkConnected()

            try {
                val selectedProviders = selectProvidersForCatalog(requireMainPage = true)
                val activeCount = countActiveProviders()
                val totalSelected = selectedProviders.size

                _catalogSnapshot.value = UnifiedCatalogSnapshot(
                    isLoading = true,
                    hasError = false,
                    isOffline = !online,
                    activeProviderCount = activeCount,
                    loadedProvidersCount = 0,
                    totalProvidersCount = totalSelected,
                    entries = existingEntries,
                    resumeWatching = resume,
                    updatedAtMs = if (existingEntries.isNotEmpty()) current.updatedAtMs else now
                )

                if (selectedProviders.isEmpty()) {
                    _catalogSnapshot.value = UnifiedCatalogSnapshot(
                        isLoading = false,
                        hasError = false,
                        isOffline = !online,
                        activeProviderCount = activeCount,
                        loadedProvidersCount = 0,
                        totalProvidersCount = 0,
                        entries = emptyList(),
                        resumeWatching = resume,
                        updatedAtMs = System.currentTimeMillis()
                    )
                    return@launch
                }

                if (!online && existingEntries.isEmpty()) {
                    _catalogSnapshot.value = UnifiedCatalogSnapshot(
                        isLoading = false,
                        hasError = true,
                        isOffline = true,
                        activeProviderCount = activeCount,
                        loadedProvidersCount = 0,
                        totalProvidersCount = totalSelected,
                        entries = emptyList(),
                        resumeWatching = resume,
                        updatedAtMs = System.currentTimeMillis()
                    )
                    return@launch
                }

                val completedCounter = AtomicInteger(0)
                val preferredWave = selectedProviders.filter { it.second }.map { it.first }
                val secondaryWave = selectedProviders.filterNot { it.second }.map { it.first }

                val harvested = ArrayList<RawHarvestedItem>(256)
                if (preferredWave.isNotEmpty()) {
                    harvested.addAll(
                        harvestProvidersMainPages(preferredWave, isPreferred = true) {
                            val done = completedCounter.incrementAndGet()
                            val prev = _catalogSnapshot.value ?: return@harvestProvidersMainPages
                            _catalogSnapshot.postValue(
                                prev.copy(
                                    loadedProvidersCount = done,
                                    totalProvidersCount = totalSelected
                                )
                            )
                        }
                    )
                    if (harvested.isNotEmpty() && secondaryWave.isNotEmpty()) {
                        val quickEntries = normalizeAndDeduplicate(harvested)
                        _catalogSnapshot.value = UnifiedCatalogSnapshot(
                            isLoading = true,
                            hasError = false,
                            isOffline = false,
                            activeProviderCount = activeCount,
                            loadedProvidersCount = completedCounter.get(),
                            totalProvidersCount = totalSelected,
                            entries = quickEntries,
                            resumeWatching = resume,
                            updatedAtMs = System.currentTimeMillis()
                        )
                    }
                }
                if (secondaryWave.isNotEmpty()) {
                    harvested.addAll(
                        harvestProvidersMainPages(secondaryWave, isPreferred = false) {
                            val done = completedCounter.incrementAndGet()
                            val prev = _catalogSnapshot.value ?: return@harvestProvidersMainPages
                            _catalogSnapshot.postValue(
                                prev.copy(
                                    loadedProvidersCount = done,
                                    totalProvidersCount = totalSelected
                                )
                            )
                        }
                    )
                }

                val baseEntries = normalizeAndDeduplicate(harvested)
                val finalEntriesIfEmpty = if (baseEntries.isEmpty() && existingEntries.isNotEmpty()) {
                    existingEntries
                } else {
                    baseEntries
                }
                val hasHarvestError = baseEntries.isEmpty() && activeCount > 0 && !isNetworkConnected()
                val updatedAt = System.currentTimeMillis()

                _catalogSnapshot.value = UnifiedCatalogSnapshot(
                    isLoading = false,
                    hasError = hasHarvestError,
                    isOffline = !isNetworkConnected(),
                    activeProviderCount = activeCount,
                    loadedProvidersCount = totalSelected,
                    totalProvidersCount = totalSelected,
                    entries = finalEntriesIfEmpty,
                    resumeWatching = resume,
                    updatedAtMs = updatedAt
                )

                if (baseEntries.isNotEmpty()) {
                    saveCatalogToDiskCache(baseEntries, updatedAt)
                    val ctx = context
                    val dataSaver = ctx != null && MissaUiFactory.isDataSaverActive(ctx)
                    val enriched = enrichEntriesWithDetails(
                        baseEntries,
                        maxHero = if (dataSaver) 3 else MAX_HERO_ENRICH,
                        maxClassifier = if (dataSaver) 12 else MAX_CLASSIFIER_ENRICH
                    )
                    _catalogSnapshot.value = UnifiedCatalogSnapshot(
                        isLoading = false,
                        hasError = false,
                        isOffline = false,
                        activeProviderCount = activeCount,
                        loadedProvidersCount = totalSelected,
                        totalProvidersCount = totalSelected,
                        entries = enriched,
                        resumeWatching = loadResumeListSafe(),
                        updatedAtMs = System.currentTimeMillis()
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val fallback = _catalogSnapshot.value?.entries.orEmpty()
                _catalogSnapshot.value = UnifiedCatalogSnapshot(
                    isLoading = false,
                    hasError = fallback.isEmpty(),
                    isOffline = !isNetworkConnected(),
                    activeProviderCount = countActiveProviders(),
                    entries = fallback,
                    resumeWatching = resume,
                    updatedAtMs = System.currentTimeMillis()
                )
            }
        }
    }

    suspend fun searchUnified(
        query: String,
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): List<UnifiedCatalogEntry> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return _catalogSnapshot.value?.entries.orEmpty()

        val providers = selectProvidersForCatalog(requireMainPage = false)
            .take(MAX_PROVIDERS_TO_HARVEST)
        if (providers.isEmpty()) return emptyList()

        val total = providers.size
        val doneCounter = AtomicInteger(0)
        onProgress?.invoke(0, total)

        val semaphore = Semaphore(6)
        val rawResults = coroutineScope {
            providers.map { (api, isPreferred) ->
                async(Dispatchers.IO) {
                    val items = semaphore.withPermit {
                        withTimeoutOrNull(SEARCH_PROVIDER_TIMEOUT_MS) {
                            try {
                                val repo = APIRepository(api)
                                when (val res = repo.search(trimmed, 1)) {
                                    is Resource.Success -> res.value.items.map { item ->
                                        RawHarvestedItem(
                                            response = item,
                                            sectionName = "",
                                            isHorizontalSection = api.supportedTypes.contains(TvType.Live),
                                            providerSupportedTypes = api.supportedTypes.toSet(),
                                            isPreferredLanguage = isPreferred
                                        )
                                    }
                                    else -> emptyList()
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }.orEmpty()
                    }
                    val done = doneCounter.incrementAndGet()
                    onProgress?.invoke(done, total)
                    items
                }
            }.awaitAll().flatten()
        }

        val normalized = normalizeAndDeduplicate(rawResults)
        return enrichEntriesWithDetails(normalized, maxHero = 3, maxClassifier = 10)
    }

    fun projectUniverse(
        universeId: String,
        snapshot: UnifiedCatalogSnapshot,
        overrideEntries: List<UnifiedCatalogEntry>? = null,
        searchQuery: String = "",
        selectedCategoryKey: String? = null,
        selectedSources: Set<String> = emptySet(),
        showAllRows: Boolean = false
    ): UniverseScreenProjection {
        val ctx = context
        val definition = MissaUniverses.byId(universeId)
        val baseSourceEntries = overrideEntries ?: snapshot.entries

        // Match entries belonging to this universe + filter local query if overrideEntries not provided
        val universeEntries = baseSourceEntries.filter { entry ->
            val inUniverse = universeId == "home" || entry.universes.contains(universeId)
            val matchesLocalFilter = overrideEntries != null || searchQuery.isBlank() ||
                entry.primary.name.contains(searchQuery.trim(), ignoreCase = true) ||
                entry.sectionHints.any { it.contains(searchQuery.trim(), ignoreCase = true) }
            inUniverse && matchesLocalFilter
        }

        val availableSources = (overrideEntries ?: snapshot.entries)
            .filter { universeId == "home" || it.universes.contains(universeId) }
            .flatMap { entry -> entry.sources.map { it.apiName } }
            .distinct()
            .sortedBy { it.lowercase(Locale.ROOT) }

        val sourceFilteredEntries = if (selectedSources.isEmpty()) {
            universeEntries
        } else {
            val normalizedSelected = selectedSources.map { it.lowercase(Locale.ROOT) }.toSet()
            universeEntries.mapNotNull { entry ->
                val matching = entry.sources.firstOrNull {
                    it.apiName.lowercase(Locale.ROOT) in normalizedSelected
                } ?: return@mapNotNull null
                entry.copy(primary = matching)
            }
        }

        data class BucketData(
            val key: String,
            val rowTitle: String,
            val chipLabel: String,
            val rank: Int,
            val items: MutableList<SearchResponse> = ArrayList(),
            var isHorizontal: Boolean = definition.isHorizontalCards
        )

        val buckets = LinkedHashMap<String, BucketData>()

        // Radio direct presets & categorize them into Favorites / Africa & Cameroon / News / Music
        if (universeId == "radio" && selectedSources.isEmpty()) {
            val presets = DirectRadioPlayerManager.allPresetStations()
                .filter { station ->
                    searchQuery.isBlank() ||
                        station.name.contains(searchQuery, ignoreCase = true) ||
                        station.genre.contains(searchQuery, ignoreCase = true) ||
                        station.country.contains(searchQuery, ignoreCase = true)
                }
            val favRadioIds = DirectRadioPlayerManager.favoriteIds()
            val favPresets = presets.filter { favRadioIds.contains(it.id) }.map { it.toSearchResponse() }
            if (favPresets.isNotEmpty()) {
                val favCat = MissaCategory.FAVORITES
                buckets[favCat.id] = BucketData(
                    key = favCat.id,
                    rowTitle = resolveStr(ctx, favCat.rowTitleRes, "Favorites"),
                    chipLabel = resolveStr(ctx, favCat.chipLabelRes, "Favorites"),
                    rank = favCat.editorialRank,
                    items = favPresets.toMutableList(),
                    isHorizontal = false
                )
            }
            for (station in presets) {
                val resp = station.toSearchResponse()
                val cats = MediaUniverseClassifier.categorizeForUniverse(
                    universeId = "radio",
                    title = "${station.name} ${station.country}",
                    sectionName = station.genre,
                    effectiveType = TvType.Audio
                )
                for (cat in cats) {
                    val bucket = buckets.getOrPut(cat.id) {
                        BucketData(
                            key = cat.id,
                            rowTitle = resolveStr(ctx, cat.rowTitleRes, cat.id),
                            chipLabel = resolveStr(ctx, cat.chipLabelRes, cat.id),
                            rank = cat.editorialRank
                        )
                    }
                    if (bucket.items.none { it.url == resp.url }) {
                        bucket.items.add(resp)
                    }
                }
            }
        }

        // Live TV / Sports favorite channels row (FLOW-02)
        val favLiveSet = if (universeId == "live_tv" || universeId == "sports") favoriteLiveUrls() else emptySet()
        if (favLiveSet.isNotEmpty()) {
            val favMatches = sourceFilteredEntries
                .map { it.primary }
                .filter { favLiveSet.contains(it.url) }
            if (favMatches.isNotEmpty()) {
                val favCat = MissaCategory.FAVORITES
                buckets[favCat.id] = BucketData(
                    key = favCat.id,
                    rowTitle = resolveStr(ctx, favCat.rowTitleRes, "Favorites"),
                    chipLabel = resolveStr(ctx, favCat.chipLabelRes, "Favorites"),
                    rank = favCat.editorialRank,
                    items = favMatches.toMutableList(),
                    isHorizontal = true
                )
            }
        }

        for (entry in sourceFilteredEntries) {
            val categories = MediaUniverseClassifier.categorizeForUniverse(
                universeId = universeId,
                title = entry.primary.name,
                sectionName = entry.sectionHints.joinToString(" "),
                tags = entry.details?.tags.orEmpty(),
                effectiveType = entry.effectiveType,
                durationMinutes = entry.details?.duration
            )
            for (cat in categories) {
                val bucket = buckets.getOrPut(cat.id) {
                    BucketData(
                        key = cat.id,
                        rowTitle = resolveStr(ctx, cat.rowTitleRes, cat.id),
                        chipLabel = resolveStr(ctx, cat.chipLabelRes, cat.id),
                        rank = cat.editorialRank
                    )
                }
                if (bucket.items.size < 40 && bucket.items.none { it.url == entry.primary.url }) {
                    bucket.items.add(entry.primary)
                }
                if (definition.isHorizontalCards ||
                    cat == MissaCategory.HOME_LIVE ||
                    entry.effectiveType == TvType.Live
                ) {
                    bucket.isHorizontal = true
                }
            }
        }

        val sortedBuckets = buckets.values
            .filter { it.items.isNotEmpty() }
            .sortedBy { it.rank }

        val availableCategories = sortedBuckets.map { bucket ->
            CategoryFilterChip(
                key = bucket.key,
                shortLabel = bucket.chipLabel,
                fullTitle = bucket.rowTitle
            )
        }

        val allMatchingRows = if (searchQuery.isNotBlank()) {
            // Group search results cleanly by category/universe (HOME-07)
            val filteredBuckets = if (!selectedCategoryKey.isNullOrBlank()) {
                sortedBuckets.filter { it.key == selectedCategoryKey }
            } else {
                sortedBuckets
            }
            val flatUnique = filteredBuckets.flatMap { it.items }.distinctBy { "${it.apiName}|${it.url}" }
            if (flatUnique.isEmpty()) {
                emptyList()
            } else {
                val title = ctx?.getString(R.string.universe_search_results_for, searchQuery.trim())
                    ?: searchQuery.trim()
                listOf(
                    UniverseCategoryRow(
                        id = "search_results_grid",
                        title = title,
                        items = flatUnique,
                        isHorizontalCards = definition.isHorizontalCards,
                        editorialRank = 1
                    )
                )
            }
        } else {
            sortedBuckets
                .filter { selectedCategoryKey.isNullOrBlank() || it.key == selectedCategoryKey }
                .map { bucket ->
                    UniverseCategoryRow(
                        id = bucket.key,
                        title = bucket.rowTitle,
                        items = bucket.items,
                        isHorizontalCards = bucket.isHorizontal,
                        editorialRank = bucket.rank
                    )
                }
        }

        val visibleRows = if (!showAllRows && selectedCategoryKey.isNullOrBlank() && allMatchingRows.size > MAX_COLLAPSED_ROWS) {
            allMatchingRows.take(MAX_COLLAPSED_ROWS)
        } else {
            allMatchingRows
        }
        val hiddenCount = (allMatchingRows.size - visibleRows.size).coerceAtLeast(0)

        val heroSlides = if (searchQuery.isNotBlank()) {
            emptyList()
        } else {
            buildHeroSlides(ctx, universeId, definition, sourceFilteredEntries)
        }

        val resumeItems = if (searchQuery.isNotBlank()) {
            emptyList()
        } else {
            filterResumeForUniverse(universeId, snapshot.resumeWatching)
        }

        val hubShortcuts = if (universeId == "home" && searchQuery.isBlank()) {
            MissaUniverses.HUB_SHORTCUTS.map { uni ->
                when (uni.id) {
                    "retro_games" -> UniverseHubCardItem(
                        definition = uni,
                        itemCount = 5,
                        countBadge = ctx?.resources?.getQuantityString(R.plurals.missa_games_count, 5, 5),
                        hasLiveNow = false
                    )
                    "radio" -> {
                        val pluginCount = snapshot.entries.count { it.universes.contains("radio") }
                        val total = pluginCount + DirectRadioPlayerManager.allPresetStations().size
                        val badge = ctx?.resources?.getQuantityString(R.plurals.missa_stations_count, total, total)
                        UniverseHubCardItem(
                            definition = uni,
                            itemCount = total,
                            countBadge = badge,
                            hasLiveNow = true
                        )
                    }
                    else -> {
                        val count = snapshot.entries.count { it.universes.contains(uni.id) }
                        val badge = if (count > 0) {
                            ctx?.resources?.getQuantityString(R.plurals.missa_titles_count, count, count)
                        } else {
                            null
                        }
                        val isLiveUni = (uni.id == "live_tv" || uni.id == "sports") && count > 0
                        UniverseHubCardItem(
                            definition = uni,
                            itemCount = count,
                            countBadge = badge,
                            hasLiveNow = isLiveUni
                        )
                    }
                }
            }
        } else {
            emptyList()
        }

        val totalUniqueCount = allMatchingRows.flatMap { it.items }.distinctBy { it.url }.size
        val effectiveLoading = snapshot.isLoading && visibleRows.isEmpty()

        // Compute explicit 4-state empty/error reason (HOME-06)
        val hasActiveFilter = searchQuery.isNotBlank() ||
            !selectedCategoryKey.isNullOrBlank() ||
            selectedSources.isNotEmpty()
        val emptyReason: UniverseEmptyReason? = if (effectiveLoading || visibleRows.isNotEmpty()) {
            null
        } else if (hasActiveFilter) {
            UniverseEmptyReason.EMPTY_SEARCH_OR_FILTER
        } else if (snapshot.isOffline || snapshot.hasError) {
            UniverseEmptyReason.OFFLINE_OR_ERROR
        } else if (snapshot.activeProviderCount == 0 && universeId != "radio") {
            UniverseEmptyReason.NO_PLUGINS_INSTALLED
        } else {
            UniverseEmptyReason.EMPTY_UNIVERSE
        }

        val lastLiveUrl = if (universeId == "live_tv" || universeId == "sports") getLastLiveChannelUrl() else null
        val lastLiveCard = if (!lastLiveUrl.isNullOrBlank()) {
            sourceFilteredEntries.firstOrNull { it.primary.url == lastLiveUrl }?.primary
        } else {
            null
        }

        return UniverseScreenProjection(
            definition = definition,
            isLoading = effectiveLoading,
            hasError = snapshot.hasError,
            isOffline = snapshot.isOffline,
            emptyReason = emptyReason,
            activeProviderCount = snapshot.activeProviderCount,
            loadedProvidersCount = snapshot.loadedProvidersCount,
            totalProvidersCount = snapshot.totalProvidersCount,
            updatedAtMs = snapshot.updatedAtMs,
            heroSlides = heroSlides,
            resumeItems = resumeItems,
            hubShortcuts = hubShortcuts,
            availableCategories = availableCategories,
            availableSources = availableSources,
            rows = visibleRows,
            hiddenRowCount = hiddenCount,
            totalItemCount = totalUniqueCount,
            lastLiveChannel = lastLiveCard
        )
    }

    private fun resolveStr(ctx: Context?, resId: Int, fallback: String): String =
        ctx?.getString(resId) ?: fallback

    private fun buildHeroSlides(
        ctx: Context?,
        universeId: String,
        definition: UniverseDefinition,
        entries: List<UnifiedCatalogEntry>
    ): List<HeroBannerSlide> {
        val eyebrowText = resolveStr(ctx, definition.eyebrowRes, "")
        val liveBadge = resolveStr(ctx, R.string.universe_live_badge, "LIVE")

        if (universeId == "radio" && entries.isEmpty()) {
            return DirectRadioPlayerManager.allPresetStations().take(5).map { station ->
                HeroBannerSlide(
                    card = station.toSearchResponse(),
                    details = null,
                    allSources = listOf(station.toSearchResponse()),
                    eyebrow = eyebrowText,
                    plot = "${station.genre} • ${station.country}",
                    yearText = station.country,
                    durationText = liveBadge,
                    scoreText = null,
                    tags = listOf(station.country, station.genre),
                    isDirectStream = true
                )
            }
        }

        val eligible = entries.filter { entry ->
            if (universeId == "home") {
                entry.universes.contains("films") ||
                    entry.universes.contains("series") ||
                    !entry.primary.posterUrl.isNullOrBlank()
            } else {
                true
            }
        }
        if (eligible.isEmpty()) return emptyList()

        // Freeze initial hero slide order per universe so late detail enrichment only enriches metadata
        // in place without jumping slides under the user's thumb (HOME-04).
        val byKey = eligible.associateBy { it.dedupKey }
        val frozenKeys = frozenHeroKeysByUniverse[universeId]
        val orderedCandidates: List<UnifiedCatalogEntry> = if (
            !frozenKeys.isNullOrEmpty() && frozenKeys.any { byKey.containsKey(it) }
        ) {
            frozenKeys.mapNotNull { byKey[it] }
        } else {
            val ranked = eligible.sortedWith(
                compareByDescending<UnifiedCatalogEntry> { !it.primary.posterUrl.isNullOrBlank() }
                    .thenByDescending { it.details != null }
                    .thenByDescending { !it.details?.backgroundPosterUrl.isNullOrBlank() }
                    .thenByDescending { it.sources.size }
            ).take(6)
            if (ranked.isNotEmpty()) {
                frozenHeroKeysByUniverse[universeId] = ranked.map { it.dedupKey }
            }
            ranked
        }

        return orderedCandidates.map { entry ->
            val details = entry.details
            val scoreText = details?.score?.toStringNull(0.1, 10, 1, false)
                ?: entry.primary.score?.toStringNull(0.1, 10, 1, false)
            val durationMin = details?.duration
            val durationFormatted = when {
                entry.effectiveType == TvType.Live -> liveBadge
                durationMin != null && durationMin >= 60 -> "${durationMin / 60}h %02dm".format(Locale.ROOT, durationMin % 60)
                durationMin != null && durationMin > 0 -> "$durationMin min"
                else -> null
            }
            HeroBannerSlide(
                card = resolvePreferredCard(entry.primary, entry.sources),
                details = details,
                allSources = entry.sources,
                eyebrow = eyebrowText,
                plot = details?.plot?.takeIf { it.isNotBlank() }
                    ?: entry.sectionHints.take(2).joinToString(" • ").ifBlank { null },
                yearText = details?.year?.toString(),
                durationText = durationFormatted,
                scoreText = scoreText?.let { "★ $it" },
                tags = details?.tags?.take(4).orEmpty(),
                isDirectStream = definition.directPlayOnClick || entry.effectiveType == TvType.Live
            )
        }
    }

    private fun filterResumeForUniverse(
        universeId: String,
        allResume: List<DataStoreHelper.ResumeWatchingResult>
    ): List<DataStoreHelper.ResumeWatchingResult> {
        if (allResume.isEmpty()) return emptyList()
        return when (universeId) {
            "home" -> allResume.take(24)
            "films" -> allResume.filter { MediaUniverseClassifier.matches("films", it.type, it.name) }.take(20)
            "series" -> allResume.filter { MediaUniverseClassifier.matches("series", it.type, it.name) }.take(20)
            "kids" -> allResume.filter { MediaUniverseClassifier.matches("kids", it.type, it.name) }.take(20)
            "podcasts" -> allResume.filter {
                it.type == TvType.Podcast || it.type == TvType.Audio || it.type == TvType.AudioBook
            }.take(20)
            else -> emptyList()
        }
    }

    private suspend fun loadResumeListSafe(): List<DataStoreHelper.ResumeWatchingResult> =
        withContext(Dispatchers.IO) {
            try {
                HomeViewModel.getResumeWatching().orEmpty().take(30)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
        }

    private fun isNetworkConnected(): Boolean {
        val ctx = context ?: return true
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    private fun computeProviderSignature(): String {
        return apis.withLock {
            apis.filter { !it.supportedTypes.all { t -> t == TvType.NSFW } }
                .joinToString("|") { "${it.name}:${it.lang}:${it.hasMainPage}" }
        }
    }

    private fun countActiveProviders(): Int {
        return apis.withLock {
            apis.count { api ->
                api.name != "None" &&
                    api.name != "Random" &&
                    !api.supportedTypes.all { it == TvType.NSFW }
            }
        }
    }

    private suspend fun selectProvidersForCatalog(requireMainPage: Boolean): List<Pair<MainAPI, Boolean>> =
        withContext(Dispatchers.IO) {
            val ctx = context
            val preferredNames = runCatching {
                ctx?.filterProviderByPreferredMedia(hasHomePageIsRequired = requireMainPage)
                    ?.map { it.name }
                    ?.toSet()
                    .orEmpty()
            }.getOrDefault(emptySet())

            val allCompatible = apis.withLock {
                apis.filter { api ->
                    api.name != "None" &&
                        api.name != "Random" &&
                        (!requireMainPage || api.hasMainPage) &&
                        !api.supportedTypes.all { it == TvType.NSFW || it == TvType.CustomMedia }
                }
            }

            val preferred = allCompatible
                .filter { it.name in preferredNames }
                .map { it to true }

            val specialtyTypes = setOf(TvType.Live, TvType.Podcast, TvType.Audio, TvType.Music, TvType.AudioBook, TvType.Cartoon)
            val remaining = allCompatible
                .filterNot { it.name in preferredNames }
                .filter { api ->
                    preferred.size < 8 || api.supportedTypes.any { it in specialtyTypes }
                }
                .map { it to false }

            (preferred + remaining).take(MAX_PROVIDERS_TO_HARVEST)
        }

    private suspend fun harvestProvidersMainPages(
        providers: List<MainAPI>,
        isPreferred: Boolean,
        onProviderFinished: () -> Unit
    ): List<RawHarvestedItem> = coroutineScope {
        val semaphore = Semaphore(5)
        providers.map { api ->
            async(Dispatchers.IO) {
                val items = semaphore.withPermit {
                    withTimeoutOrNull(PROVIDER_MAINPAGE_TIMEOUT_MS) {
                        try {
                            val repository = APIRepository(api)
                            val supportedTypes = api.supportedTypes.toSet()
                            when (val response = repository.getMainPage(1, null)) {
                                is Resource.Success -> response.value
                                    .filterNotNull()
                                    .flatMap { page -> page.items }
                                    .flatMap { section ->
                                        section.list.map { item ->
                                            RawHarvestedItem(
                                                response = item,
                                                sectionName = section.name,
                                                isHorizontalSection = section.isHorizontalImages,
                                                providerSupportedTypes = supportedTypes,
                                                isPreferredLanguage = isPreferred
                                            )
                                        }
                                    }
                                else -> emptyList()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            emptyList()
                        }
                    }.orEmpty()
                }
                onProviderFinished()
                items
            }
        }.awaitAll().flatten()
    }

    private fun normalizeAndDeduplicate(rawItems: List<RawHarvestedItem>): List<UnifiedCatalogEntry> {
        val grouped = LinkedHashMap<String, MutableList<RawHarvestedItem>>()

        for (raw in rawItems) {
            val item = raw.response
            if (item.type == TvType.NSFW || item.type == TvType.CustomMedia) continue
            val effectiveType = MediaUniverseClassifier.inferEffectiveType(
                rawType = item.type,
                title = item.name,
                sectionName = raw.sectionName,
                providerSupportedTypes = raw.providerSupportedTypes
            )
            if (effectiveType == TvType.NSFW || effectiveType == TvType.CustomMedia) continue

            val domainBucket = when (effectiveType) {
                TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.OVA, TvType.AsianDrama -> "series"
                TvType.Live -> "live"
                TvType.Podcast, TvType.Audio, TvType.Music, TvType.AudioBook -> "audio"
                else -> "film"
            }
            val key = MediaUniverseClassifier.normalizeTitleKey(item.name, domainBucket)
            grouped.getOrPut(key) { ArrayList() }.add(raw)
        }

        val entries = ArrayList<UnifiedCatalogEntry>(grouped.size)
        for ((dedupKey, group) in grouped) {
            val sortedGroup = group.sortedWith(
                compareByDescending<RawHarvestedItem> { it.isPreferredLanguage }
                    .thenByDescending { !it.response.posterUrl.isNullOrBlank() }
                    .thenByDescending { it.response.score != null }
            )
            val bestRaw = sortedGroup.first()
            val uniqueSources = sortedGroup
                .map { it.response }
                .distinctBy { "${it.apiName.lowercase(Locale.ROOT)}|${it.url}" }

            uniqueSources.forEach { src ->
                sourcesByItemUrl[itemIdentity(src)] = uniqueSources
            }

            val sectionHints = sortedGroup
                .map { it.sectionName.trim() }
                .filter { it.isNotBlank() }
                .toSet()
            val combinedProviderTypes = sortedGroup.flatMap { it.providerSupportedTypes }.toSet()
            val effectiveType = MediaUniverseClassifier.inferEffectiveType(
                rawType = bestRaw.response.type,
                title = bestRaw.response.name,
                sectionName = sectionHints.joinToString(" "),
                providerSupportedTypes = combinedProviderTypes
            )

            val cachedDetails = detailsCache[itemIdentity(bestRaw.response)]
            val universes = MediaUniverseClassifier.classifyAllUniverses(
                type = effectiveType ?: bestRaw.response.type,
                title = bestRaw.response.name,
                tags = cachedDetails?.tags.orEmpty(),
                contentRating = cachedDetails?.contentRating,
                durationMinutes = cachedDetails?.duration,
                sectionName = sectionHints.joinToString(" "),
                providerSupportedTypes = combinedProviderTypes
            )

            entries.add(
                UnifiedCatalogEntry(
                    dedupKey = dedupKey,
                    primary = bestRaw.response,
                    sources = uniqueSources,
                    effectiveType = effectiveType,
                    sectionHints = sectionHints,
                    isHorizontalHint = sortedGroup.any { it.isHorizontalSection },
                    universes = universes,
                    details = cachedDetails
                )
            )
        }

        return entries
    }

    private suspend fun enrichEntriesWithDetails(
        entries: List<UnifiedCatalogEntry>,
        maxHero: Int = MAX_HERO_ENRICH,
        maxClassifier: Int = MAX_CLASSIFIER_ENRICH
    ): List<UnifiedCatalogEntry> = coroutineScope {
        val apisByName = apis.withLock { apis.associateBy { it.name } }

        val heroTargets = entries
            .filter { it.details == null && !it.primary.posterUrl.isNullOrBlank() }
            .take(maxHero)

        val classifierTargets = entries
            .filter { entry ->
                entry.details == null &&
                    entry !in heroTargets &&
                    (MediaUniverseClassifier.shouldLoadDetails(
                        "shorts",
                        entry.effectiveType ?: entry.primary.type,
                        entry.primary.name,
                        entry.sectionHints.joinToString(" ")
                    ) || MediaUniverseClassifier.shouldLoadDetails(
                        "kids",
                        entry.effectiveType ?: entry.primary.type,
                        entry.primary.name,
                        entry.sectionHints.joinToString(" ")
                    ))
            }
            .sortedByDescending { entry ->
                val sec = entry.sectionHints.joinToString(" ")
                maxOf(
                    MediaUniverseClassifier.detailPriority("shorts", entry.primary.name, sec),
                    MediaUniverseClassifier.detailPriority("kids", entry.primary.name, sec)
                )
            }
            .take(maxClassifier)

        val toFetch = (heroTargets + classifierTargets).distinctBy { it.dedupKey }
        if (toFetch.isEmpty()) return@coroutineScope entries

        val semaphore = Semaphore(5)
        toFetch.map { entry ->
            async(Dispatchers.IO) {
                val id = itemIdentity(entry.primary)
                val api = apisByName[entry.primary.apiName] ?: return@async
                semaphore.withPermit {
                    withTimeoutOrNull(DETAIL_TIMEOUT_MS) {
                        try {
                            when (val loaded = APIRepository(api).load(entry.primary.url)) {
                                is Resource.Success -> {
                                    detailsCache[id] = loaded.value
                                }
                                else -> Unit
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Unit
                        }
                    }
                }
            }
        }.awaitAll()

        entries.map { entry ->
            val details = entry.details ?: detailsCache[itemIdentity(entry.primary)]
            if (details == null) {
                entry
            } else {
                val updatedType = MediaUniverseClassifier.inferEffectiveType(
                    rawType = details.type,
                    title = entry.primary.name,
                    sectionName = entry.sectionHints.joinToString(" ")
                ) ?: entry.effectiveType
                val updatedUniverses = MediaUniverseClassifier.classifyAllUniverses(
                    type = updatedType,
                    title = entry.primary.name,
                    tags = details.tags.orEmpty(),
                    contentRating = details.contentRating,
                    durationMinutes = details.duration,
                    sectionName = entry.sectionHints.joinToString(" ")
                )
                entry.copy(
                    effectiveType = updatedType,
                    universes = updatedUniverses,
                    details = details
                )
            }
        }
    }

    // --- Lightweight Disk Cache for Instant Cold Start (HOME-13) ---

    private suspend fun saveCatalogToDiskCache(entries: List<UnifiedCatalogEntry>, updatedAtMs: Long) {
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = context?.cacheDir ?: return@withContext
                val arr = JSONArray()
                for (entry in entries.take(180)) {
                    val obj = JSONObject()
                    obj.put("dedupKey", entry.dedupKey)
                    obj.put("name", entry.primary.name)
                    obj.put("url", entry.primary.url)
                    obj.put("apiName", entry.primary.apiName)
                    obj.put("type", entry.effectiveType?.name ?: entry.primary.type?.name ?: "")
                    obj.put("posterUrl", entry.primary.posterUrl ?: "")
                    obj.put("isHorizontalHint", entry.isHorizontalHint)
                    obj.put("sectionHints", JSONArray(entry.sectionHints.take(4).toList()))
                    obj.put("universes", JSONArray(entry.universes.toList()))
                    arr.put(obj)
                }
                val root = JSONObject().apply {
                    put("updatedAtMs", updatedAtMs)
                    put("entries", arr)
                }
                File(dir, DISK_CACHE_FILE).writeText(root.toString())
            }
        }
    }

    private suspend fun loadCatalogFromDiskCache(): Pair<List<UnifiedCatalogEntry>, Long>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = context?.cacheDir ?: return@withContext null
                val file = File(dir, DISK_CACHE_FILE)
                if (!file.exists()) return@withContext null
                val root = JSONObject(file.readText())
                val updatedAt = root.optLong("updatedAtMs", 0L)
                val arr = root.optJSONArray("entries") ?: return@withContext null
                val restored = ArrayList<UnifiedCatalogEntry>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name")
                    val url = obj.optString("url")
                    val apiName = obj.optString("apiName")
                    if (name.isBlank() || url.isBlank()) continue
                    val tvType = obj.optString("type").takeIf { it.isNotBlank() }?.let { raw ->
                        runCatching { TvType.valueOf(raw) }.getOrNull()
                    }
                    val card = CachedCatalogSearchResponse(
                        name = name,
                        url = url,
                        apiName = apiName,
                        type = tvType,
                        posterUrl = obj.optString("posterUrl").ifBlank { null }
                    )
                    sourcesByItemUrl[itemIdentity(card)] = listOf(card)
                    val hints = LinkedHashSet<String>()
                    obj.optJSONArray("sectionHints")?.let { ha ->
                        for (j in 0 until ha.length()) ha.optString(j).takeIf { it.isNotBlank() }?.let { hints.add(it) }
                    }
                    val unis = LinkedHashSet<String>()
                    obj.optJSONArray("universes")?.let { ua ->
                        for (j in 0 until ua.length()) ua.optString(j).takeIf { it.isNotBlank() }?.let { unis.add(it) }
                    }
                    restored.add(
                        UnifiedCatalogEntry(
                            dedupKey = obj.optString("dedupKey").ifBlank { url },
                            primary = card,
                            sources = listOf(card),
                            effectiveType = tvType,
                            sectionHints = hints,
                            isHorizontalHint = obj.optBoolean("isHorizontalHint", false),
                            universes = unis
                        )
                    )
                }
                restored to updatedAt
            }.getOrNull()
        }

    private fun itemIdentity(card: SearchResponse): String =
        "${card.apiName.lowercase(Locale.ROOT)}|${card.url}"
}
