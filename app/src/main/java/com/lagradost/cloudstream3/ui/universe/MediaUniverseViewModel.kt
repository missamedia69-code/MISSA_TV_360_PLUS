package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.fixUrl
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
    val isPreferredLanguage: Boolean,
    /** EXT-GROUPS : choix de l'utilisateur pour la source de cet item (null = Auto). */
    val forcedUniverses: Set<String>? = null
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
        private const val TAG = "MissaCatalog"

        private const val PROVIDER_MAINPAGE_TIMEOUT_MS = 14_000L
        private const val SEARCH_PROVIDER_TIMEOUT_MS = 15_000L
        private const val DETAIL_TIMEOUT_MS = 3_200L
        private const val MAX_PROVIDERS_TO_HARVEST = 28
        // PERF-OOM (2026-11-04): the first catalogue load used to walk the heap from 54 MB to
        // 174/198 MB (12% free) in under two seconds and the app was killed while "loading".
        // The bulk of that was full LoadResponse objects (a single series carries thousands of
        // Episode objects, roughly 2 MB each) retained by detailsCache. Fewer, lighter and
        // less parallel detail loads keeps the heap far away from the OOM threshold.
        private const val MAX_HERO_ENRICH = 6
        private const val MAX_CLASSIFIER_ENRICH = 24
        /** Hard upper bound for [detailsCache]; unreferenced snapshots are dropped past it. */
        private const val MAX_DETAILS_CACHE = 96
        /** Concurrent details loads. 6 of them made the GC thrash every second. */
        private const val MAX_PARALLEL_DETAILS = 3
        /**
         * CRASH-01: heap that must stay free for the details enrichment to run at all.
         * A cold start has no disk snapshot, so it harvests every provider at once and the
         * heap is at its tightest when the enrichment begins. Measured on the TV emulator the
         * app settles around 24MB free, so anything above ~16MB still leaves room for the
         * 27 LoadResponse allocations the pass performs.
         */
        private const val MIN_FREE_MEMORY_FOR_ENRICHMENT = 16L * 1024L * 1024L
        private const val SNAPSHOT_TTL_MS = 5 * 60_000L
        private const val LEGACY_DISK_CACHE_FILE = "missa_catalog_cache_v1.json"
        private const val DISK_CACHE_FILE = "missa_catalog_cache_v2.json"
        private const val PREF_RECENT_SEARCHES = "missa_recent_searches_v1"
        private const val PREF_PREFERRED_SOURCE_PREFIX = "missa_pref_source_v1_"
        private const val PREF_FAVORITE_LIVE_URLS = "missa_fav_live_urls_v1"
        private const val PREF_LAST_LIVE_URL = "missa_last_live_url_v1"
        const val MAX_COLLAPSED_ROWS = 12
        private val titleYearRegex = Regex("""\(((?:19|20)\d{2})\)""")

        internal fun extractEpisodeCountHint(response: SearchResponse): Int? = when (response) {
            is TvSeriesSearchResponse -> response.episodes
            is AnimeSearchResponse -> response.episodes.values.maxOrNull()
            else -> null
        }

        internal fun extractYearHint(response: SearchResponse): Int? = when (response) {
            is MovieSearchResponse -> response.year
            is TvSeriesSearchResponse -> response.year
            is AnimeSearchResponse -> response.year
            else -> null
        } ?: titleYearRegex.find(response.name)?.groupValues?.getOrNull(1)?.toIntOrNull()

        internal fun resolveLoadResponseIsSeries(details: LoadResponse?): Boolean? = when (details) {
            is TvSeriesLoadResponse -> details.episodes.isNotEmpty() || details.type != TvType.Movie
            is AnimeLoadResponse -> !(details.type == TvType.AnimeMovie && details.episodes.values.sumOf { it.size } <= 1)
            is MovieLoadResponse -> false
            else -> null
        }

        private fun rawDomainBucket(effectiveType: TvType?): String = when (effectiveType) {
            TvType.Documentary -> "doc"
            TvType.AnimeMovie -> "manga_film"
            TvType.Anime, TvType.OVA -> "manga"
            TvType.TvSeries, TvType.Cartoon, TvType.AsianDrama -> "series"
            TvType.Music -> "music"
            TvType.Live -> "live"
            TvType.Podcast, TvType.Audio, TvType.AudioBook -> "audio"
            else -> "film"
        }

        private fun isVodComparableBucket(bucket: String): Boolean =
            bucket == "film" || bucket == "series" || bucket == "doc" || bucket == "manga" || bucket == "manga_film"

        /**
         * Normalizes, resolves cross-plugin type disagreements (e.g. a sloppy plugin stamping
         * TvType.Movie on a series while another plugin proves it is a TvSeries), and deduplicates
         * harvested items so the same work never splits across Films & Mangas and Series.
         */
        internal fun deduplicateHarvestedItems(
            rawItems: List<RawHarvestedItem>,
            detailsLookup: (SearchResponse) -> LoadResponse? = { null },
            onSourcesMerged: ((List<SearchResponse>) -> Unit)? = null
        ): List<UnifiedCatalogEntry> {
            data class PreparedRaw(
                val raw: RawHarvestedItem,
                val cachedDetails: LoadResponse?,
                val preliminaryType: TvType?,
                val preliminaryBucket: String,
                val baseTitleKey: String,
                val yearHint: Int?,
                val episodeCountHint: Int?,
                val isSeriesLoad: Boolean?
            )

            val prepared = ArrayList<PreparedRaw>(rawItems.size)
            for (raw in rawItems) {
                val item = raw.response
                if (item.type == TvType.NSFW || item.type == TvType.CustomMedia) continue
                val cachedDetails = detailsLookup(item)
                val epHint = extractEpisodeCountHint(item)
                val seriesLoad = resolveLoadResponseIsSeries(cachedDetails)
                val preliminaryType = MediaUniverseClassifier.inferEffectiveType(
                    rawType = cachedDetails?.type ?: item.type,
                    title = item.name,
                    sectionName = raw.sectionName,
                    providerSupportedTypes = raw.providerSupportedTypes,
                    url = item.url,
                    tags = cachedDetails?.tags.orEmpty(),
                    episodeCountHint = epHint,
                    isSeriesLoadResponse = seriesLoad
                )
                if (preliminaryType == TvType.NSFW || preliminaryType == TvType.CustomMedia) continue
                prepared.add(
                    PreparedRaw(
                        raw = raw,
                        cachedDetails = cachedDetails,
                        preliminaryType = preliminaryType,
                        preliminaryBucket = rawDomainBucket(preliminaryType),
                        baseTitleKey = MediaUniverseClassifier.normalizeTitleKey(item.name),
                        yearHint = cachedDetails?.year ?: extractYearHint(item),
                        episodeCountHint = epHint,
                        isSeriesLoad = seriesLoad
                    )
                )
            }

            // Group VOD items by normalized base title first to reconcile cross-plugin Movie vs Series conflicts
            val vodByTitle = prepared
                .filter { isVodComparableBucket(it.preliminaryBucket) }
                .groupBy { it.baseTitleKey }

            val canonicalVodBucketByTitle = HashMap<String, String>(vodByTitle.size)
            for ((titleKey, candidates) in vodByTitle) {
                val distinctBuckets = candidates.map { it.preliminaryBucket }.toSet()
                if (distinctBuckets.size <= 1) {
                    canonicalVodBucketByTitle[titleKey] = distinctBuckets.first()
                    continue
                }
                // Keep genuinely distinct adaptations separate only when explicit years differ (> 1 year)
                // AND both movie and series sides carry strong structural proof (e.g. Fargo 1996 film vs Fargo 2014 series)
                val movieYears = candidates.filter { it.preliminaryBucket == "film" }.mapNotNull { it.yearHint }
                val seriesYears = candidates.filter { it.preliminaryBucket == "series" }.mapNotNull { it.yearHint }
                val hasDistinctAdaptationYears = movieYears.isNotEmpty() &&
                    seriesYears.isNotEmpty() &&
                    movieYears.none { my -> seriesYears.any { sy -> kotlin.math.abs(my - sy) <= 1 } }
                val hasProvenMovie = candidates.any {
                    MediaUniverseClassifier.hasStrongMovieEvidence(
                        it.raw.response.type,
                        it.raw.response.name,
                        it.raw.sectionName,
                        it.raw.response.url,
                        it.isSeriesLoad
                    )
                }
                val hasProvenSeries = candidates.any {
                    MediaUniverseClassifier.hasStrongSeriesEvidence(
                        it.raw.response.type,
                        it.raw.response.name,
                        it.raw.sectionName,
                        it.raw.response.url,
                        it.episodeCountHint,
                        it.isSeriesLoad
                    )
                }
                if (hasDistinctAdaptationYears && hasProvenMovie && hasProvenSeries) {
                    continue
                }

                val resolvedBucket = when {
                    "doc" in distinctBuckets -> "doc"
                    "manga" in distinctBuckets -> "manga"
                    "manga_film" in distinctBuckets -> "manga_film"
                    "series" in distinctBuckets && hasProvenSeries -> "series"
                    else -> "film"
                }
                canonicalVodBucketByTitle[titleKey] = resolvedBucket
            }

            val grouped = LinkedHashMap<String, MutableList<PreparedRaw>>()
            for (p in prepared) {
                val finalBucket = if (isVodComparableBucket(p.preliminaryBucket)) {
                    canonicalVodBucketByTitle[p.baseTitleKey] ?: p.preliminaryBucket
                } else {
                    p.preliminaryBucket
                }
                val key = "${finalBucket}::${p.baseTitleKey}"
                grouped.getOrPut(key) { ArrayList() }.add(p)
            }

            val entries = ArrayList<UnifiedCatalogEntry>(grouped.size)
            for ((dedupKey, group) in grouped) {
                val resolvedBucket = dedupKey.substringBefore("::")
                // Prefer sources whose preliminary bucket already matches the resolved canonical bucket
                val sortedGroup = group.sortedWith(
                    compareByDescending<PreparedRaw> { it.preliminaryBucket == resolvedBucket }
                        .thenByDescending { it.raw.isPreferredLanguage }
                        .thenByDescending { !it.raw.response.posterUrl.isNullOrBlank() }
                        .thenByDescending { it.raw.response.score != null }
                )
                val bestPrepared = sortedGroup.first()
                val bestRaw = bestPrepared.raw
                val uniqueSources = sortedGroup
                    .map { it.raw.response }
                    .distinctBy { "${it.apiName.lowercase(Locale.ROOT)}|${it.url}" }

                onSourcesMerged?.invoke(uniqueSources)

                val sectionHints = sortedGroup
                    .map { it.raw.sectionName.trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
                val combinedProviderTypes = sortedGroup.flatMap { it.raw.providerSupportedTypes }.toSet()
                val cachedDetails = sortedGroup.firstNotNullOfOrNull { it.cachedDetails }
                val mergedTags = cachedDetails?.tags.orEmpty()
                val maxEpisodeHint = sortedGroup.mapNotNull { it.episodeCountHint }.maxOrNull()
                val mergedSeriesLoad = sortedGroup.firstNotNullOfOrNull { it.isSeriesLoad }

                val inferred = MediaUniverseClassifier.inferEffectiveType(
                    rawType = cachedDetails?.type ?: bestRaw.response.type,
                    title = bestRaw.response.name,
                    sectionName = sectionHints.joinToString(" "),
                    providerSupportedTypes = combinedProviderTypes,
                    url = bestRaw.response.url,
                    tags = mergedTags,
                    episodeCountHint = maxEpisodeHint,
                    isSeriesLoadResponse = mergedSeriesLoad
                )
                val effectiveType = when (resolvedBucket) {
                    "doc" -> TvType.Documentary
                    "manga" -> if (inferred == TvType.OVA) TvType.OVA else TvType.Anime
                    "manga_film" -> TvType.AnimeMovie
                    "series" -> if (inferred == TvType.Cartoon || inferred == TvType.AsianDrama) inferred else TvType.TvSeries
                    else -> inferred
                }

                val autoUniverses = MediaUniverseClassifier.classifyAllUniverses(
                    type = effectiveType ?: bestRaw.response.type,
                    title = bestRaw.response.name,
                    tags = mergedTags,
                    contentRating = cachedDetails?.contentRating,
                    durationMinutes = cachedDetails?.duration,
                    sectionName = sectionHints.joinToString(" "),
                    providerSupportedTypes = combinedProviderTypes,
                    url = bestRaw.response.url,
                    episodeCountHint = maxEpisodeHint,
                    isSeriesLoadResponse = mergedSeriesLoad
                )
                // EXT-GROUPS : le choix de l'utilisateur écrase / complète la devinette
                val universes = MissaExtensionGroups.mergeUniverses(
                    auto = autoUniverses,
                    forcedPerSource = sortedGroup.map { it.raw.forcedUniverses }
                )

                entries.add(
                    UnifiedCatalogEntry(
                        dedupKey = dedupKey,
                        primary = bestRaw.response,
                        sources = uniqueSources,
                        effectiveType = effectiveType,
                        sectionHints = sectionHints,
                        isHorizontalHint = sortedGroup.any { it.raw.isHorizontalSection },
                        universes = universes,
                        details = cachedDetails,
                        cachedTags = mergedTags,
                        cachedContentRating = cachedDetails?.contentRating,
                        cachedDuration = cachedDetails?.duration
                    )
                )
            }

            return entries
        }

        /**
         * Resolves the empty/error banner state for a universe screen.
         * When no plugin/extension is installed yet ([activeProviderCount] == 0), never display
         * a plugin-setup card on any universe page.
         */
        fun resolveEmptyReason(
            effectiveLoading: Boolean,
            hasVisibleRows: Boolean,
            hasActiveFilter: Boolean,
            isOffline: Boolean,
            hasError: Boolean,
            activeProviderCount: Int,
            isDirectProviderLoading: Boolean = false
        ): UniverseEmptyReason? = when {
            effectiveLoading || isDirectProviderLoading || hasVisibleRows -> null
            hasActiveFilter -> UniverseEmptyReason.EMPTY_SEARCH_OR_FILTER
            activeProviderCount == 0 -> null
            isOffline || hasError -> UniverseEmptyReason.OFFLINE_OR_ERROR
            else -> UniverseEmptyReason.EMPTY_UNIVERSE
        }
    }

    private val detailsCache = ConcurrentHashMap<String, LoadResponse>()
    private val sourcesByItemUrl = ConcurrentHashMap<String, List<SearchResponse>>()
    /**
     * PERF-OOM: per (universe, entry) memo of [MediaUniverseClassifier.categorizeForUniverse].
     * projectUniverse() re-runs a dozen regexes per entry on every recompute and it is called
     * for ~2000 entries several times per screen — that showed up as "Skipped 140 frames".
     */
    private val categoryMemo = ConcurrentHashMap<String, List<MissaCategory>>()

    /**
     * Strips the heavy payloads a catalogue row never reads (episodes, recommendations,
     * actors, trailers) from a freshly loaded [LoadResponse] before it is cached.
     * The universe screens only need metadata: tags, duration, rating, year, score, poster.
     */
    private fun trimLoadedDetails(response: LoadResponse): LoadResponse = response.apply {
        when (this) {
            is TvSeriesLoadResponse -> episodes = emptyList()
            is AnimeLoadResponse -> episodes.clear()
        }
        recommendations = null
        actors = null
        if (trailers.isNotEmpty()) trailers = mutableListOf()
    }

    /** Drops cached snapshots nobody points at any more, then hard-caps the map. */
    private fun trimDetailsCache(retain: Set<String> = emptySet()) {
        if (detailsCache.size <= MAX_DETAILS_CACHE) return
        detailsCache.keys.removeIf { key -> key !in retain }
        val overflow = detailsCache.size - MAX_DETAILS_CACHE
        if (overflow > 0) {
            detailsCache.keys.take(overflow).forEach { detailsCache.remove(it) }
        }
    }

    private fun clearCachesForReload() {
        categoryMemo.clear()
        trimDetailsCache()
    }
    private val frozenHeroKeysByUniverse = ConcurrentHashMap<String, List<String>>()

    private val _catalogSnapshot = MutableLiveData(UnifiedCatalogSnapshot())
    val catalogSnapshot: LiveData<UnifiedCatalogSnapshot> = _catalogSnapshot

    private var catalogJob: Job? = null
    private var lastLoadedProviderSignature: String = ""

    /** Launch seed already used to re-roll the Musiques rows (FRESH-01). */
    @Volatile
    private var musicRotationLaunchSeed: Long = Long.MIN_VALUE

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
        // FRESH-01: the very first refresh of a process bypasses the disk cache so every
        // YouTube-powered surface (Enfants + Musiques) renews itself at each app launch.
        val forceYouTubeRefresh = MissaCatalogRotation.consumeLaunchRefreshTicket()
        viewModelScope.launch {
            DirectRadioPlayerManager.refreshRemotePresetsIfNeeded()
        }
        viewModelScope.launch {
            DirectMusicPlayerManager.refreshRemoteTracksIfNeeded(force = forceYouTubeRefresh)
        }
        viewModelScope.launch {
            DirectKidsPlayerManager.refreshRemoteVideosIfNeeded(force = forceYouTubeRefresh)
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
            // Force the next projection to re-roll the promoted YouTube cards (FRESH-01).
            musicRotationLaunchSeed = Long.MIN_VALUE
            viewModelScope.launch {
                DirectRadioPlayerManager.refreshRemotePresetsIfNeeded(force = true)
            }
            viewModelScope.launch {
                DirectMusicPlayerManager.refreshRemoteTracksIfNeeded(force = true)
            }
            viewModelScope.launch {
                DirectKidsPlayerManager.refreshRemoteVideosIfNeeded(force = true)
            }
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
                clearCachesForReload()
                val selectedProviders = selectProvidersForCatalog(requireMainPage = true)
                val activeCount = countActiveProviders()
                val totalSelected = selectedProviders.size
                Log.i(
                    TAG,
                    "Catalog load start: activeApis=$activeCount selected=$totalSelected " +
                        "(${selectedProviders.joinToString { it.first.name }}) online=$online"
                )

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
                        // CRASH-01: persist this partial harvest immediately. When the process
                        // is killed during the secondary wave or the enrichment — which only
                        // happens on a cold start, when no snapshot exists yet — the next launch
                        // still boots from disk instead of harvesting everything again from
                        // scratch. That is why the app used to need two or three attempts.
                        if (quickEntries.isNotEmpty()) {
                            saveCatalogToDiskCache(quickEntries, System.currentTimeMillis())
                        }
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
                if (harvested.isEmpty() || baseEntries.isEmpty()) {
                    // Every provider is harvested inside per-provider try/catch + withTimeoutOrNull,
                    // so a total miss is silent. Surface it: zero here means getMainPage() failed,
                    // returned no sections, or exceeded PROVIDER_MAINPAGE_TIMEOUT_MS for all providers.
                    Log.w(
                        TAG,
                        "Catalog harvest produced nothing: rawItems=${harvested.size} entries=${baseEntries.size} " +
                            "selected=$totalSelected activeApis=$activeCount"
                    )
                } else {
                    val perUniverse = MissaUniverses.HUB_SHORTCUTS
                        .associate { it.id to baseEntries.count { entry -> entry.universes.contains(it.id) } }
                        .filterValues { count -> count > 0 }
                    Log.i(
                        TAG,
                        "Catalog harvest ok: rawItems=${harvested.size} entries=${baseEntries.size} " +
                            "perUniverse=$perUniverse"
                    )
                }
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
                    // CRASH-01: never start allocating one LoadResponse per card when the
                    // harvest has already eaten the heap — the catalogue still renders from the
                    // raw harvest, it just gets fewer enriched tags on this pass.
                    val enriched = if (hasEnrichmentHeadroom()) {
                        enrichEntriesWithDetails(
                            baseEntries,
                            maxHero = if (dataSaver) 3 else MAX_HERO_ENRICH,
                            maxClassifier = if (dataSaver) 16 else MAX_CLASSIFIER_ENRICH
                        )
                    } else {
                        Log.w(
                            TAG,
                            "Enrichment skipped: only ${freeHeapMb()}MB of heap left " +
                                "(need ${MIN_FREE_MEMORY_FOR_ENRICHMENT / (1024 * 1024)}MB)"
                        )
                        baseEntries
                    }
                    val enrichedAt = System.currentTimeMillis()
                    saveCatalogToDiskCache(enriched, enrichedAt)
                    _catalogSnapshot.value = UnifiedCatalogSnapshot(
                        isLoading = false,
                        hasError = false,
                        isOffline = false,
                        activeProviderCount = activeCount,
                        loadedProvidersCount = totalSelected,
                        totalProvidersCount = totalSelected,
                        entries = enriched,
                        resumeWatching = loadResumeListSafe(),
                        updatedAtMs = enrichedAt
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (oom: OutOfMemoryError) {
                // CRASH-01: OutOfMemoryError is an Error, not an Exception, so it escaped the
                // catch below and killed the whole app — always on a cold start, because that
                // is the only launch that has no disk snapshot and harvests everything at once.
                // Keep whatever the harvest already produced instead of dying.
                Log.e(
                    TAG,
                    "Catalog load ran out of memory, keeping " +
                        "${_catalogSnapshot.value?.entries?.size ?: 0} harvested entries",
                    oom
                )
                publishHarvestFallback(resume)
            } catch (e: Exception) {
                // Was a bare catch(_) - a crashing catalog load looked exactly like an empty one.
                Log.e(TAG, "Catalog load failed, keeping ${_catalogSnapshot.value?.entries?.size ?: 0} cached entries", e)
                publishHarvestFallback(resume)
            }
        }
    }

    /**
     * CRASH-01: after a failed harvest, show whatever is already in the snapshot instead of
     * blanking the screen (or the process, for an OutOfMemoryError).
     */
    private fun publishHarvestFallback(resumeWatching: List<DataStoreHelper.ResumeWatchingResult>) {
        val fallback = _catalogSnapshot.value?.entries.orEmpty()
        _catalogSnapshot.value = UnifiedCatalogSnapshot(
            isLoading = false,
            hasError = fallback.isEmpty(),
            isOffline = !isNetworkConnected(),
            activeProviderCount = countActiveProviders(),
            entries = fallback,
            resumeWatching = resumeWatching,
            updatedAtMs = System.currentTimeMillis()
        )
    }

    /** Free heap in MB, for the CRASH-01 guards and their logs. */
    private fun freeHeapMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())) / (1024 * 1024)
    }

    private fun hasEnrichmentHeadroom(): Boolean = freeHeapMb() * 1024 * 1024 >= MIN_FREE_MEMORY_FOR_ENRICHMENT

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
                                val forcedUniverses = MissaExtensionGroups.resolveForApi(api)
                                when (val res = repo.search(trimmed, 1)) {
                                    is Resource.Success -> res.value.items.map { item ->
                                        RawHarvestedItem(
                                            response = item,
                                            sectionName = "",
                                            isHorizontalSection = api.supportedTypes.contains(TvType.Live),
                                            providerSupportedTypes = api.supportedTypes.toSet(),
                                            isPreferredLanguage = isPreferred,
                                            forcedUniverses = forcedUniverses
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

    /**
     * PERF-OOM: memoized [MediaUniverseClassifier.categorizeForUniverse]. The classifier walks
     * a dozen regexes per call and projectUniverse() runs it for every entry of a ~2000 entry
     * catalogue on each recompute (source change, favourite change, rotation tick...), which
     * was stalling the main thread ("Skipped 140 frames", "Davey! duration=2497ms").
     * The key embeds [UnifiedCatalogEntry.dedupKey], which changes whenever the entry is
     * re-classified with fresh details, so the memo is invalidated with the data it derives from.
     */
    private fun categorizeEntry(
        universeId: String,
        entry: UnifiedCatalogEntry,
        sectionName: String,
        tags: List<String>?,
        durationMinutes: Int?
    ): List<MissaCategory> {
        val key = "$universeId|${entry.dedupKey}"
        categoryMemo[key]?.let { return it }
        if (categoryMemo.size > 20_000) categoryMemo.clear()
        return MediaUniverseClassifier.categorizeForUniverse(
            universeId = universeId,
            title = entry.primary.name,
            sectionName = sectionName,
            tags = tags.orEmpty(),
            effectiveType = entry.effectiveType,
            durationMinutes = durationMinutes,
            url = entry.primary.url
        ).also { categoryMemo[key] = it }
    }

    fun projectUniverse(
        universeId: String,
        snapshot: UnifiedCatalogSnapshot,
        overrideEntries: List<UnifiedCatalogEntry>? = null,
        searchQuery: String = "",
        selectedCategoryKey: String? = null,
        selectedSources: Set<String> = emptySet(),
        showAllRows: Boolean = false,
        localizedContext: Context? = null
    ): UniverseScreenProjection {
        val ctx = localizedContext ?: context
        val definition = MissaUniverses.byId(universeId)
        val baseSourceEntries = overrideEntries ?: snapshot.entries

        // Match entries belonging to this universe + filter local query if overrideEntries not provided.
        // "music" is 100% exclusively powered by YouTube (DirectMusicPlayerManager) and "kids" is
        // 100% exclusively powered by YouTube Kids (DirectKidsPlayerManager) — zero plugin entries.
        val universeEntries = if (universeId == "music" || universeId == "kids") {
            emptyList()
        } else {
            baseSourceEntries.filter { entry ->
                val inUniverse = universeId == "home" || entry.universes.contains(universeId)
                val matchesLocalFilter = overrideEntries != null || searchQuery.isBlank() ||
                    entry.primary.name.contains(searchQuery.trim(), ignoreCase = true) ||
                    entry.sectionHints.any { it.contains(searchQuery.trim(), ignoreCase = true) }
                inUniverse && matchesLocalFilter
            }
        }

        val normalizedSelectedSources = selectedSources.map { it.lowercase(Locale.ROOT) }.toSet()

        val builtInProviderSources = when (universeId) {
            "radio" -> DirectRadioPlayerManager.allPresetStations()
                .map { it.providerName.ifBlank { DIRECT_RADIO_API_NAME } }
            "music" -> listOf(MissaMusicProviders.PROVIDER_YOUTUBE_MUSIC)
            "kids" -> listOf(MissaKidsProviders.PROVIDER_YOUTUBE_KIDS)
            else -> emptyList()
        }

        val availableSources = when (universeId) {
            "music" -> listOf(MissaMusicProviders.PROVIDER_YOUTUBE_MUSIC)
            "kids" -> listOf(MissaKidsProviders.PROVIDER_YOUTUBE_KIDS)
            else -> (
                (overrideEntries ?: snapshot.entries)
                    .filter { universeId == "home" || it.universes.contains(universeId) }
                    .flatMap { entry -> entry.sources.map { it.apiName } } +
                    builtInProviderSources
                )
                .distinct()
                .sortedBy { it.lowercase(Locale.ROOT) }
        }

        val sourceFilteredEntries = if (normalizedSelectedSources.isEmpty()) {
            universeEntries
        } else {
            universeEntries.mapNotNull { entry ->
                val matching = entry.sources.firstOrNull {
                    it.apiName.lowercase(Locale.ROOT) in normalizedSelectedSources
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

        // Radio multi-provider presets & categorize them into Favorites / Africa / Francophone / News / Afro-Urban / Music / Relax / All
        if (universeId == "radio") {
            val presets = DirectRadioPlayerManager.allPresetStations()
                .filter { station ->
                    val providerOk = normalizedSelectedSources.isEmpty() ||
                        station.providerName.ifBlank { DIRECT_RADIO_API_NAME }.lowercase(Locale.ROOT) in normalizedSelectedSources
                    val queryOk = MissaRadioProviders.matchesStationQuery(station, searchQuery)
                    providerOk && queryOk
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
                    isHorizontal = true
                )
            }
            for (station in presets) {
                val resp = station.toSearchResponse()
                val cats = MediaUniverseClassifier.categorizeForUniverse(
                    universeId = "radio",
                    title = "${station.name} ${RadioOrigin.displayLabel(station.country, context)}",
                    sectionName = station.genre,
                    tags = listOf("country:${station.country}", station.providerName),
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
                    val rowCap = when {
                        searchQuery.isNotBlank() -> 240
                        cat == MissaCategory.RADIO_ALL -> 180
                        else -> 80
                    }
                    if (bucket.items.size < rowCap && bucket.items.none { it.url == resp.url }) {
                        bucket.items.add(resp)
                    }
                }
            }
        }

        // Dedicated 100%-YouTube Kids universe (KIDS-01): YouTube is the single source of the
        // Enfants universe, and the selection is re-rolled on every launch (FRESH-01).
        if (universeId == "kids") {
            val kidsSearchHits = if (searchQuery.isNotBlank()) {
                DirectKidsPlayerManager.getOnlineSearchResults(searchQuery)
            } else {
                emptyList()
            }
            val localKidsMatches = if (searchQuery.isNotBlank()) {
                DirectKidsPlayerManager.allPresetVideos()
                    .filter { MissaKidsProviders.matchesKidsQuery(it, searchQuery) }
            } else {
                emptyList()
            }
            val kidsSearchResults = (kidsSearchHits + localKidsMatches)
                .distinctBy { it.id }
                .filter { video ->
                    normalizedSelectedSources.isEmpty() ||
                        video.providerName.ifBlank { DIRECT_KIDS_API_NAME }
                            .lowercase(Locale.ROOT) in normalizedSelectedSources
                }

            if (searchQuery.isNotBlank()) {
                if (kidsSearchResults.isNotEmpty()) {
                    val resultsTitle = ctx?.getString(
                        R.string.universe_search_results_for,
                        searchQuery.trim()
                    ) ?: searchQuery.trim()
                    buckets["kids_search_results"] = BucketData(
                        key = "kids_search_results",
                        rowTitle = resultsTitle,
                        chipLabel = resultsTitle,
                        rank = 1,
                        items = kidsSearchResults.map { MissaKidsProviders.toKidsSearchResponse(it) }
                            .toMutableList(),
                        isHorizontal = false
                    )
                }
            } else {
                val selection = DirectKidsPlayerManager.currentSelection()
                for ((category, items) in selection.rows) {
                    if (items.isEmpty()) continue
                    buckets[category.id] = BucketData(
                        key = category.id,
                        rowTitle = resolveStr(ctx, category.rowTitleRes, category.id),
                        chipLabel = resolveStr(ctx, category.chipLabelRes, category.id),
                        rank = category.editorialRank,
                        items = items.map { MissaKidsProviders.toKidsSearchResponse(it) }.toMutableList(),
                        isHorizontal = false
                    )
                }
                if (selection.all.isNotEmpty()) {
                    buckets["kids_all"] = BucketData(
                        key = "kids_all",
                        rowTitle = resolveStr(ctx, R.string.missa_cat_kids_all, "kids_all"),
                        chipLabel = resolveStr(ctx, R.string.missa_chip_kids_all, "kids_all"),
                        rank = 900,
                        items = selection.all.map { MissaKidsProviders.toKidsSearchResponse(it) }
                            .toMutableList(),
                        isHorizontal = false
                    )
                }
            }
        }

        // Dedicated 100%-YouTube Music universe: newest clips, singles, official albums & playlists (zero long mixes)
        var musicSearchSections: MissaMusicProviders.MusicSearchSections? = null
        val rankedMusicSearchResponses: List<DirectMusicSearchResponse> = if (universeId == "music") {
            val musicTracks = if (searchQuery.isNotBlank()) {
                val directOnline = DirectMusicPlayerManager.getOnlineSearchResults(searchQuery)
                val localMatches = DirectMusicPlayerManager.allPresetTracks()
                    .filter { MissaMusicProviders.matchesTrackQuery(it, searchQuery) }
                val combinedForQuery = (directOnline + localMatches)
                    .distinctBy { it.id }
                    .filter { track ->
                        normalizedSelectedSources.isEmpty() ||
                            track.providerName.ifBlank { DIRECT_MUSIC_API_NAME }.lowercase(Locale.ROOT) in normalizedSelectedSources
                    }
                val sections = MissaMusicProviders.partitionMusicSearchResults(combinedForQuery, searchQuery)
                musicSearchSections = sections
                sections.allRanked
            } else {
                DirectMusicPlayerManager.allPresetTracks()
                    .filter { track ->
                        normalizedSelectedSources.isEmpty() ||
                            track.providerName.ifBlank { DIRECT_MUSIC_API_NAME }.lowercase(Locale.ROOT) in normalizedSelectedSources
                    }
            }
            val favMusicIds = DirectMusicPlayerManager.favoriteIds()
            val favTracks = musicTracks.filter { favMusicIds.contains(it.id) }.map { it.toSearchResponse() }
            if (favTracks.isNotEmpty()) {
                val favCat = MissaCategory.FAVORITES
                buckets[favCat.id] = BucketData(
                    key = favCat.id,
                    rowTitle = resolveStr(ctx, favCat.rowTitleRes, favCat.id),
                    chipLabel = resolveStr(ctx, favCat.chipLabelRes, favCat.id),
                    rank = favCat.editorialRank,
                    isHorizontal = false,
                    items = favTracks.toMutableList()
                )
            }
            val rowCap = if (searchQuery.isNotBlank()) 240 else 80
            // `Nouveautés` (`MUSIC_TRENDING`) strictly contains ONLY fresh individual clips — NEVER playlists or albums
            val nouveautesClips = MissaMusicProviders.selectNouveautesClips(musicTracks, maxItems = rowCap)
            if (nouveautesClips.isNotEmpty()) {
                val trendingCat = MissaCategory.MUSIC_TRENDING
                buckets[trendingCat.id] = BucketData(
                    key = trendingCat.id,
                    rowTitle = resolveStr(ctx, trendingCat.rowTitleRes, trendingCat.id),
                    chipLabel = resolveStr(ctx, trendingCat.chipLabelRes, trendingCat.id),
                    rank = trendingCat.editorialRank,
                    isHorizontal = false,
                    items = nouveautesClips.map { it.toSearchResponse() }.toMutableList()
                )
            }
            for (track in musicTracks) {
                val resp = track.toSearchResponse()
                val thematicCats = MediaUniverseClassifier.categorizeForUniverse(
                    universeId = "music",
                    title = "${track.artist} ${track.title} ${track.album}",
                    sectionName = track.genre,
                    tags = listOf(track.genre, track.album).filter { it.isNotBlank() },
                    isTrending = false,
                    effectiveType = TvType.Music
                ).filter { it != MissaCategory.MUSIC_TRENDING }
                val formatCat = if (track.isAlbumOrPlaylist()) {
                    MissaCategory.MUSIC_STREAMS
                } else {
                    MissaCategory.MUSIC_CLIPS_LIVE
                }
                val targetCats = (listOf(formatCat) + thematicCats).distinct()
                for (cat in targetCats) {
                    val bucket = buckets.getOrPut(cat.id) {
                        BucketData(
                            key = cat.id,
                            rowTitle = resolveStr(ctx, cat.rowTitleRes, cat.id),
                            chipLabel = resolveStr(ctx, cat.chipLabelRes, cat.id),
                            rank = cat.editorialRank,
                            isHorizontal = false
                        )
                    }
                    if (bucket.items.size < rowCap && bucket.items.none { it.url == resp.url }) {
                        bucket.items.add(resp)
                    }
                }
            }
            // FRESH-01: re-roll the promoted YouTube cards once per app launch so the Musiques
            // rows never open twice in a row on the very same clips.
            val musicLaunchSeed = MissaCatalogRotation.currentLaunchSeed()
            if (musicRotationLaunchSeed != musicLaunchSeed) {
                musicRotationLaunchSeed = musicLaunchSeed
                val rotationRandom = MissaCatalogRotation.currentRandom()
                val recentMusicIds = DirectMusicPlayerManager.promotedIds()
                val promotedNow = ArrayList<String>()
                for (bucket in buckets.values) {
                    if (bucket.key == MissaCategory.FAVORITES.id) continue
                    if (bucket.items.size < 2) continue
                    val rotated = MissaCatalogRotation.rotateAvoidingRecent(
                        items = bucket.items.toList(),
                        recentIds = recentMusicIds,
                        random = rotationRandom,
                        keySelector = { it.url }
                    )
                    bucket.items.clear()
                    bucket.items.addAll(rotated)
                    promotedNow.addAll(rotated.take(3).map { it.url })
                }
                DirectMusicPlayerManager.notePromotedIds(promotedNow)
            }

            if (searchQuery.isNotBlank()) musicTracks.map { it.toSearchResponse() } else emptyList()
        } else {
            emptyList()
        }

        // Home universe live radio showcase + music audio search hits alongside podcasts/audio entries
        if (universeId == "home" && normalizedSelectedSources.isEmpty()) {
            val homeMusicHits = if (searchQuery.isNotBlank()) {
                val directOnline = DirectMusicPlayerManager.getOnlineSearchResults(searchQuery)
                val localMatches = DirectMusicPlayerManager.allPresetTracks()
                    .filter { MissaMusicProviders.matchesTrackQuery(it, searchQuery) }
                MissaMusicProviders.partitionMusicSearchResults(
                    (directOnline + localMatches).distinctBy { it.id },
                    searchQuery
                ).allRanked.take(60)
            } else {
                emptyList()
            }
            val homeStations = DirectRadioPlayerManager.allPresetStations()
                .filter { station -> MissaRadioProviders.matchesStationQuery(station, searchQuery) }
                .take(16)
            if (homeMusicHits.isNotEmpty() || homeStations.isNotEmpty()) {
                val homeAudioCat = MissaCategory.HOME_AUDIO
                val audioBucket = buckets.getOrPut(homeAudioCat.id) {
                    BucketData(
                        key = homeAudioCat.id,
                        rowTitle = resolveStr(ctx, homeAudioCat.rowTitleRes, homeAudioCat.id),
                        chipLabel = resolveStr(ctx, homeAudioCat.chipLabelRes, homeAudioCat.id),
                        rank = homeAudioCat.editorialRank,
                        isHorizontal = homeMusicHits.isEmpty()
                    )
                }
                for (track in homeMusicHits) {
                    val resp = track.toSearchResponse()
                    if (audioBucket.items.size < 64 && audioBucket.items.none { it.url == resp.url }) {
                        audioBucket.items.add(resp)
                    }
                }
                for (station in homeStations) {
                    val resp = station.toSearchResponse()
                    if (audioBucket.items.size < 72 && audioBucket.items.none { it.url == resp.url }) {
                        audioBucket.items.add(resp)
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
            val entryUrl = entry.primary.url
            // DOC-LIVE-01: live channels & streams detected in plugin feeds (url, section, tags)
            // even when the plugin never stamped TvType.Live on the item.
            val sectionName = entry.sectionHints.joinToString(" ")
            val tags = entry.details?.tags ?: entry.cachedTags
            val durationMinutes = entry.details?.duration ?: entry.cachedDuration
            val entryIsLive = MediaUniverseClassifier.isLiveStreamSignal(
                effectiveOrRawType = entry.effectiveType ?: entry.primary.type,
                title = entry.primary.name,
                sectionName = sectionName,
                tags = tags,
                url = entryUrl
            )
            val categories = categorizeEntry(universeId, entry, sectionName, tags, durationMinutes)
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
                    cat == MissaCategory.DOCS_LIVE ||
                    entryIsLive ||
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
            val cleanDisplayQuery = MissaMusicProviders.sanitizeMusicQuery(searchQuery).ifBlank { searchQuery.trim() }
            if (universeId == "music" && musicSearchSections != null) {
                val sections = musicSearchSections!!
                if (!selectedCategoryKey.isNullOrBlank()) {
                    val scopedBucket = sortedBuckets.firstOrNull { it.key == selectedCategoryKey }
                    val scopedItems = (scopedBucket?.items ?: sections.allRanked.map { it.toSearchResponse() })
                        .distinctBy { "${it.apiName}|${it.url}" }
                    if (scopedItems.isEmpty()) {
                        emptyList()
                    } else {
                        val scopedTitle = scopedBucket?.rowTitle
                            ?: ctx?.getString(R.string.universe_search_results_for, cleanDisplayQuery)
                            ?: cleanDisplayQuery
                        listOf(
                            UniverseCategoryRow(
                                id = "search_results_grid",
                                title = scopedTitle,
                                items = scopedItems,
                                isHorizontalCards = false,
                                editorialRank = 1,
                                renderAsGridInSearch = true
                            )
                        )
                    }
                } else if (sections.allRanked.isEmpty()) {
                    emptyList()
                } else {
                    buildList {
                        if (sections.trendingClips.isNotEmpty()) {
                            add(
                                UniverseCategoryRow(
                                    id = "music_search_trending_clips",
                                    title = ctx?.getString(R.string.universe_music_search_trending_clips, cleanDisplayQuery)
                                        ?: cleanDisplayQuery,
                                    items = sections.trendingClips.map { it.toSearchResponse() },
                                    isHorizontalCards = false,
                                    editorialRank = 1,
                                    renderAsGridInSearch = false
                                )
                            )
                        }
                        if (sections.recentClips.isNotEmpty()) {
                            add(
                                UniverseCategoryRow(
                                    id = "music_search_recent_clips",
                                    title = ctx?.getString(R.string.universe_music_search_recent_clips, cleanDisplayQuery)
                                        ?: cleanDisplayQuery,
                                    items = sections.recentClips.map { it.toSearchResponse() },
                                    isHorizontalCards = false,
                                    editorialRank = 2,
                                    renderAsGridInSearch = false
                                )
                            )
                        }
                        if (sections.playlistsAndAlbums.isNotEmpty()) {
                            add(
                                UniverseCategoryRow(
                                    id = "music_search_playlists_albums",
                                    title = ctx?.getString(R.string.universe_music_search_playlists_albums, cleanDisplayQuery)
                                        ?: cleanDisplayQuery,
                                    items = sections.playlistsAndAlbums.map { it.toSearchResponse() },
                                    isHorizontalCards = false,
                                    editorialRank = 3,
                                    renderAsGridInSearch = false
                                )
                            )
                        }
                        if (sections.songsAndSingles.isNotEmpty()) {
                            add(
                                UniverseCategoryRow(
                                    id = "music_search_singles",
                                    title = ctx?.getString(R.string.universe_music_search_singles, cleanDisplayQuery)
                                        ?: cleanDisplayQuery,
                                    items = sections.songsAndSingles.map { it.toSearchResponse() },
                                    isHorizontalCards = false,
                                    editorialRank = 4,
                                    renderAsGridInSearch = false
                                )
                            )
                        }
                        add(
                            UniverseCategoryRow(
                                id = "search_results_grid",
                                title = ctx?.getString(R.string.universe_music_search_all_results, cleanDisplayQuery)
                                    ?: cleanDisplayQuery,
                                items = sections.allRanked.map { it.toSearchResponse() },
                                isHorizontalCards = false,
                                editorialRank = 5,
                                renderAsGridInSearch = true
                            )
                        )
                    }
                }
            } else {
                // Group search results cleanly by relevance; if a category pill yields 0 hits for the query, fall back to all universe hits
                val categoryScoped = if (!selectedCategoryKey.isNullOrBlank()) {
                    sortedBuckets.filter { it.key == selectedCategoryKey }.ifEmpty { sortedBuckets }
                } else {
                    sortedBuckets
                }
                val bucketFlat = categoryScoped.flatMap { it.items }
                val flatUnique = (if (selectedCategoryKey.isNullOrBlank()) rankedMusicSearchResponses + bucketFlat else bucketFlat)
                    .distinctBy { "${it.apiName}|${it.url}" }
                if (flatUnique.isEmpty()) {
                    emptyList()
                } else {
                    val title = ctx?.getString(R.string.universe_search_results_for, cleanDisplayQuery)
                        ?: cleanDisplayQuery
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
            buildHeroSlides(ctx, universeId, definition, sourceFilteredEntries, normalizedSelectedSources)
        }

        val resumeItems = if (searchQuery.isNotBlank()) {
            emptyList()
        } else {
            filterResumeForUniverse(universeId, snapshot.resumeWatching)
        }

        val hubShortcuts = if (universeId == "home" && searchQuery.isBlank()) {
            MissaUniverses.HUB_SHORTCUTS.map { uni ->
                when (uni.id) {
                    "retro_games" -> {
                        // AUDIT H10: the card used to print a hardcoded "5 games". The only
                        // figure the app can stand behind is the number of ROMs the player
                        // can actually open, i.e. the ones already in the retro library.
                        val total = ctx?.let {
                            File(it.filesDir, "retro-games").listFiles()?.count { file -> file.isFile }
                        } ?: 0
                        UniverseHubCardItem(
                            definition = uni,
                            itemCount = total,
                            countBadge = if (total > 0) {
                                ctx?.resources?.getQuantityString(R.plurals.missa_games_count, total, total)
                            } else {
                                null
                            },
                            hasLiveNow = false
                        )
                    }
                    "music" -> {
                        val total = DirectMusicPlayerManager.allPresetTracks().size
                        val badge = if (total > 0) {
                            ctx?.resources?.getQuantityString(R.plurals.missa_tracks_count, total, total)
                        } else {
                            null
                        }
                        UniverseHubCardItem(
                            definition = uni,
                            itemCount = total,
                            countBadge = badge,
                            hasLiveNow = false
                        )
                    }
                    "radio" -> {
                        val pluginCount = snapshot.entries.count { it.universes.contains("radio") }
                        val total = pluginCount + DirectRadioPlayerManager.allPresetStations().size
                        // AUDIT H10: while the harvest is still running snapshot.entries only
                        // holds a partial page, so this is a floor and not the size of the
                        // universe - printing it showed "16 titres" for a universe of 302.
                        val badge = if (total > 0 && !snapshot.isLoading) {
                            ctx?.resources?.getQuantityString(R.plurals.missa_stations_count, total, total)
                        } else {
                            null
                        }
                        UniverseHubCardItem(
                            definition = uni,
                            itemCount = total,
                            countBadge = badge,
                            hasLiveNow = DirectRadioPlayerManager.state.value?.isPlaying == true
                        )
                    }
                    else -> {
                        val count = snapshot.entries.count { it.universes.contains(uni.id) }
                        // AUDIT H10: same floor rule as the radio card, see above.
                        val badge = if (count > 0 && !snapshot.isLoading) {
                            ctx?.resources?.getQuantityString(R.plurals.missa_titles_count, count, count)
                        } else {
                            null
                        }
                        UniverseHubCardItem(
                            definition = uni,
                            itemCount = count,
                            countBadge = badge,
                            hasLiveNow = false
                        )
                    }
                }
            }
        } else {
            emptyList()
        }

        val totalUniqueCount = allMatchingRows.flatMap { it.items }.distinctBy { it.url }.size
        // A YouTube-only universe (Enfants) must not flash an "empty universe" card while its
        // first catalogue is still downloading (KIDS-01).
        val directProviderLoading = universeId == "kids" &&
            visibleRows.isEmpty() &&
            DirectKidsPlayerManager.isAwaitingFirstCatalogue()
        val effectiveLoading = (snapshot.isLoading || directProviderLoading) && visibleRows.isEmpty()

        // Compute explicit empty/error reason without ever showing a plugin-missing card when no plugin is installed
        val hasActiveFilter = searchQuery.isNotBlank() ||
            !selectedCategoryKey.isNullOrBlank() ||
            selectedSources.isNotEmpty()
        val emptyReason: UniverseEmptyReason? = resolveEmptyReason(
            effectiveLoading = effectiveLoading,
            hasVisibleRows = visibleRows.isNotEmpty(),
            hasActiveFilter = hasActiveFilter,
            isOffline = snapshot.isOffline,
            hasError = snapshot.hasError,
            activeProviderCount = snapshot.activeProviderCount,
            isDirectProviderLoading = directProviderLoading
        )

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
        entries: List<UnifiedCatalogEntry>,
        normalizedSelectedSources: Set<String> = emptySet()
    ): List<HeroBannerSlide> {
        val eyebrowText = resolveStr(ctx, definition.eyebrowRes, "")
        val liveBadge = resolveStr(ctx, R.string.universe_live_badge, "LIVE")

        if (universeId == "radio" && entries.isEmpty()) {
            val basePool = DirectRadioPlayerManager.allPresetStations()
            val presetPool = if (normalizedSelectedSources.isEmpty()) {
                basePool
            } else {
                basePool.filter {
                    it.providerName.ifBlank { DIRECT_RADIO_API_NAME }.lowercase(Locale.ROOT) in normalizedSelectedSources
                }
            }
            return presetPool.take(5).map { station ->
                HeroBannerSlide(
                    card = station.toSearchResponse(),
                    details = null,
                    allSources = listOf(station.toSearchResponse()),
                    eyebrow = eyebrowText,
                    // Country and provider already feed the (deduplicated) meta line, so
                    // the plot only carries the genre — printed exactly once.
                    plot = station.genre.takeIf { it.isNotBlank() },
                    yearText = null,
                    durationText = liveBadge,
                    scoreText = null,
                    tags = listOf(RadioOrigin.displayLabel(station.country, context))
                        .filter { it.isNotBlank() },
                    isDirectStream = true
                )
            }
        }

        // KIDS-02: the Enfants universe highlights a few rotated titles in a hero banner, the
        // same way Films & Mangas does, instead of opening on a flat list of rows.
        if (universeId == "kids") {
            val heroKids = DirectKidsPlayerManager.heroVideos(max = 5)
            if (heroKids.isNotEmpty()) {
                return heroKids.map { video ->
                    val resp = MissaKidsProviders.toKidsSearchResponse(video)
                    val categoryLabel = KidsCategory.byId(video.genre)?.let {
                        resolveStr(ctx, it.chipLabelRes, it.id)
                    }
                    HeroBannerSlide(
                        card = resp,
                        details = null,
                        allSources = listOf(resp),
                        eyebrow = eyebrowText,
                        plot = video.artist.takeIf { it.isNotBlank() },
                        yearText = resp.apiName,
                        durationText = resp.formattedDuration(),
                        scoreText = null,
                        tags = listOfNotNull(categoryLabel),
                        isDirectStream = true
                    )
                }
            }
        }

        if (universeId == "music") {
            val baseTracks = DirectMusicPlayerManager.allPresetTracks()
            val filteredTracks = if (normalizedSelectedSources.isEmpty()) {
                baseTracks
            } else {
                baseTracks.filter {
                    it.providerName.ifBlank { DIRECT_MUSIC_API_NAME }.lowercase(Locale.ROOT) in normalizedSelectedSources
                }
            }
            val prioritizedTracks = filteredTracks.sortedByDescending { !it.artworkUrl.isNullOrBlank() }
            if (prioritizedTracks.isNotEmpty()) {
                return prioritizedTracks.take(5).map { track ->
                    val resp = track.toSearchResponse()
                    val plotParts = listOf(track.artist, track.album, track.genre)
                        .filter { it.isNotBlank() }
                    HeroBannerSlide(
                        card = resp,
                        details = null,
                        allSources = listOf(resp),
                        eyebrow = eyebrowText,
                        plot = plotParts.joinToString(" • "),
                        yearText = track.providerName,
                        durationText = resp.formattedDuration(),
                        scoreText = null,
                        tags = listOf(track.providerName, track.genre).filter { it.isNotBlank() },
                        isDirectStream = false
                    )
                }
            }
        }

        val eligible = entries.filter { entry ->
            if (universeId == "home") {
                entry.universes.contains("films") ||
                    entry.universes.contains("series") ||
                    entry.universes.contains("documentaries") ||
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
            val durationMin = details?.duration ?: entry.cachedDuration
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
                tags = (details?.tags ?: entry.cachedTags).take(4),
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
            "documentaries" -> allResume.filter { MediaUniverseClassifier.matches("documentaries", it.type, it.name) }.take(20)
            "kids" -> allResume.filter { MediaUniverseClassifier.matches("kids", it.type, it.name) }.take(20)
            "music" -> allResume.filter { MediaUniverseClassifier.matches("music", it.type, it.name) }.take(20)
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
                // The permit block must evaluate to the item list: a trailing statement here
                // silently turns the whole block into Unit and breaks awaitAll().flatten().
                val items: List<RawHarvestedItem> = semaphore.withPermit {
                    withTimeoutOrNull(PROVIDER_MAINPAGE_TIMEOUT_MS) {
                        try {
                            val repository = APIRepository(api)
                            val supportedTypes = api.supportedTypes.toSet()
                            val forcedUniverses = MissaExtensionGroups.resolveForApi(api)
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
                                                isPreferredLanguage = isPreferred,
                                                forcedUniverses = forcedUniverses
                                            )
                                        }
                                    }
                                else -> {
                                    Log.w(
                                        TAG,
                                        "Provider '${api.name}' main page failed: ${response::class.simpleName}"
                                    )
                                    emptyList()
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            Log.w(TAG, "Provider '${api.name}' main page threw: ${e::class.simpleName}: ${e.message}")
                            emptyList()
                        }
                    }.orEmpty()
                }
                // A provider that resolves but returns nothing is the most common silent
                // failure: site moved, selector broke, geo-block, or the 14s timeout hit.
                if (items.isEmpty()) {
                    Log.w(TAG, "Provider '${api.name}' yielded no items (empty response or timeout)")
                }
                onProviderFinished()
                items
            }
        }.awaitAll().flatten()
    }

    private fun normalizeAndDeduplicate(rawItems: List<RawHarvestedItem>): List<UnifiedCatalogEntry> {
        return deduplicateHarvestedItems(
            rawItems = rawItems,
            detailsLookup = { card -> detailsCache[itemIdentity(card)] },
            onSourcesMerged = { uniqueSources ->
                uniqueSources.forEach { src ->
                    sourcesByItemUrl[itemIdentity(src)] = uniqueSources
                }
            }
        )
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
                val sec = entry.sectionHints.joinToString(" ")
                val curType = entry.effectiveType ?: entry.primary.type
                val url = entry.primary.url
                entry.details == null &&
                    entry !in heroTargets &&
                    (MediaUniverseClassifier.shouldLoadDetails("shorts", curType, entry.primary.name, sec, url) ||
                        MediaUniverseClassifier.shouldLoadDetails("kids", curType, entry.primary.name, sec, url) ||
                        MediaUniverseClassifier.shouldLoadDetails("documentaries", curType, entry.primary.name, sec, url) ||
                        MediaUniverseClassifier.shouldLoadDetails("disambiguate", curType, entry.primary.name, sec, url))
            }
            .sortedByDescending { entry ->
                val sec = entry.sectionHints.joinToString(" ")
                val url = entry.primary.url
                maxOf(
                    MediaUniverseClassifier.detailPriority("shorts", entry.primary.name, sec, url),
                    MediaUniverseClassifier.detailPriority("kids", entry.primary.name, sec, url),
                    MediaUniverseClassifier.detailPriority("documentaries", entry.primary.name, sec, url),
                    MediaUniverseClassifier.detailPriority("disambiguate", entry.primary.name, sec, url)
                )
            }
            .take(maxClassifier)

        val toFetch = (heroTargets + classifierTargets).distinctBy { it.dedupKey }
        if (toFetch.isEmpty()) {
            Log.d(TAG, "Enrichment skipped: nothing needs details (${entries.size} entries)")
            return@coroutineScope entries
        }
        val enrichOk = AtomicInteger(0)
        val enrichFail = AtomicInteger(0)

        val semaphore = Semaphore(MAX_PARALLEL_DETAILS)
        toFetch.map { entry ->
            async(Dispatchers.IO) {
                val id = itemIdentity(entry.primary)
                val api = apisByName[entry.primary.apiName] ?: return@async
                semaphore.withPermit {
                    withTimeoutOrNull(DETAIL_TIMEOUT_MS) {
                        try {
                            // PERF-OOM: the provider is called directly instead of going
                            // through APIRepository.load(), because the repository keeps the
                            // response it returns in its own 20-entry cache — trimming the
                            // episodes out of that shared instance would empty the episode
                            // list of the detail page. Going direct gives us an object we own.
                            val loaded = api.load(api.fixUrl(entry.primary.url))
                            if (loaded == null) {
                                enrichFail.incrementAndGet()
                                Log.w(TAG, "Details null for '${entry.primary.name}' via ${api.name}")
                            } else {
                                detailsCache[id] = trimLoadedDetails(loaded)
                                enrichOk.incrementAndGet()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (t: Throwable) {
                            // Throwable, not Exception: APIRepository.safeApiCall used to catch
                            // those for us (a plugin can throw NotImplementedError/Error and that
                            // used to be swallowed) — a single rogue provider must never take the
                            // whole census down while the catalogue is loading.
                            enrichFail.incrementAndGet()
                            Log.w(TAG, "Details threw for '${entry.primary.name}' via ${api.name}: ${t.message}")
                        }
                    }
                }
            }
        }.awaitAll()

        // PERF-OOM: release the snapshots that are no longer referenced so a long session
        // cannot slowly grow the heap back up to the kill threshold.
        trimDetailsCache(toFetch.mapTo(HashSet()) { itemIdentity(it.primary) })

        Log.i(
            TAG,
            "Enrichment done: ok=${enrichOk.get()} failed=${enrichFail.get()} " +
                "skipped=${toFetch.size - enrichOk.get() - enrichFail.get()} " +
                "(per-call timeout ${DETAIL_TIMEOUT_MS}ms)"
        )

        val refreshed = entries.map { entry ->
            val details = entry.details ?: entry.sources.firstNotNullOfOrNull { src -> detailsCache[itemIdentity(src)] }
            if (details == null) {
                entry
            } else {
                val seriesLoad = resolveLoadResponseIsSeries(details)
                val epHint = entry.sources.mapNotNull { extractEpisodeCountHint(it) }.maxOrNull()
                val sec = entry.sectionHints.joinToString(" ")
                val tags = details.tags.orEmpty()
                val updatedType = MediaUniverseClassifier.inferEffectiveType(
                    rawType = details.type,
                    title = entry.primary.name,
                    sectionName = sec,
                    url = entry.primary.url,
                    tags = tags,
                    episodeCountHint = epHint,
                    isSeriesLoadResponse = seriesLoad
                ) ?: entry.effectiveType
                val newBucket = rawDomainBucket(updatedType)
                val updatedKey = MediaUniverseClassifier.normalizeTitleKey(entry.primary.name, newBucket)
                val updatedUniverses = MediaUniverseClassifier.classifyAllUniverses(
                    type = updatedType,
                    title = entry.primary.name,
                    tags = tags,
                    contentRating = details.contentRating,
                    durationMinutes = details.duration,
                    sectionName = sec,
                    url = entry.primary.url,
                    episodeCountHint = epHint,
                    isSeriesLoadResponse = seriesLoad
                )
                entry.copy(
                    dedupKey = updatedKey,
                    effectiveType = updatedType,
                    universes = updatedUniverses,
                    details = details,
                    cachedTags = tags,
                    cachedContentRating = details.contentRating,
                    cachedDuration = details.duration
                )
            }
        }

        // Merge any entries whose canonical dedupKey converged after LoadResponse disambiguation
        refreshed.groupBy { it.dedupKey }.values.map { sameKeyEntries ->
            if (sameKeyEntries.size == 1) {
                sameKeyEntries.first()
            } else {
                val primaryEntry = sameKeyEntries.firstOrNull { it.details != null } ?: sameKeyEntries.first()
                val mergedSources = sameKeyEntries
                    .flatMap { it.sources }
                    .distinctBy { "${it.apiName.lowercase(Locale.ROOT)}|${it.url}" }
                mergedSources.forEach { src ->
                    sourcesByItemUrl[itemIdentity(src)] = mergedSources
                }
                primaryEntry.copy(
                    sources = mergedSources,
                    sectionHints = sameKeyEntries.flatMap { it.sectionHints }.toSet(),
                    universes = sameKeyEntries.flatMap { it.universes }.toSet().let { combined ->
                        if ("films" in combined && "series" in combined) {
                            if (primaryEntry.effectiveType == TvType.TvSeries ||
                                primaryEntry.effectiveType == TvType.Cartoon ||
                                primaryEntry.effectiveType == TvType.AsianDrama
                            ) {
                                combined - "films"
                            } else {
                                combined - "series"
                            }
                        } else {
                            combined
                        }
                    }
                )
            }
        }
    }

    // --- Lightweight Disk Cache for Instant Cold Start (HOME-13) ---

    private suspend fun saveCatalogToDiskCache(entries: List<UnifiedCatalogEntry>, updatedAtMs: Long) {
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = context?.cacheDir ?: return@withContext
                File(dir, LEGACY_DISK_CACHE_FILE).takeIf { it.exists() }?.delete()
                val arr = JSONArray()
                for (entry in entries.take(220)) {
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
                    val tags = entry.details?.tags ?: entry.cachedTags
                    if (tags.isNotEmpty()) {
                        obj.put("tags", JSONArray(tags.take(8)))
                    }
                    val rating = entry.details?.contentRating ?: entry.cachedContentRating
                    if (!rating.isNullOrBlank()) {
                        obj.put("contentRating", rating)
                    }
                    val duration = entry.details?.duration ?: entry.cachedDuration
                    if (duration != null && duration > 0) {
                        obj.put("duration", duration)
                    }
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
                File(dir, LEGACY_DISK_CACHE_FILE).takeIf { it.exists() }?.delete()
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
                    val tags = ArrayList<String>()
                    obj.optJSONArray("tags")?.let { ta ->
                        for (j in 0 until ta.length()) ta.optString(j).takeIf { it.isNotBlank() }?.let { tags.add(it) }
                    }
                    val contentRating = obj.optString("contentRating").ifBlank { null }
                    val duration = obj.optInt("duration", -1).takeIf { it > 0 }
                    val recomputedUniverses = MediaUniverseClassifier.classifyAllUniverses(
                        type = tvType,
                        title = name,
                        tags = tags,
                        contentRating = contentRating,
                        durationMinutes = duration,
                        sectionName = hints.joinToString(" "),
                        url = url
                    )
                    restored.add(
                        UnifiedCatalogEntry(
                            dedupKey = obj.optString("dedupKey").ifBlank { url },
                            primary = card,
                            sources = listOf(card),
                            effectiveType = tvType,
                            sectionHints = hints,
                            isHorizontalHint = obj.optBoolean("isHorizontalHint", false),
                            universes = recomputedUniverses,
                            cachedTags = tags,
                            cachedContentRating = contentRating,
                            cachedDuration = duration
                        )
                    )
                }
                restored to updatedAt
            }.getOrNull()
        }

    private fun itemIdentity(card: SearchResponse): String =
        "${card.apiName.lowercase(Locale.ROOT)}|${card.url}"
}
