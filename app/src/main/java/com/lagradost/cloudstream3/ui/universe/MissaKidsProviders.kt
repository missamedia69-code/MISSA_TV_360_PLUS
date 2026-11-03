package com.lagradost.cloudstream3.ui.universe

import android.util.Log
import androidx.annotation.StringRes
import com.lagradost.cloudstream3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Calendar
import kotlin.random.Random

/** Source name shown by the Kids universe filter chip and on every kids card. */
const val DIRECT_KIDS_API_NAME = "YouTube Kids"

/**
 * A single YouTube discovery query prepared for one [KidsCategory].
 *
 * [query] already embeds the rotation variants (bilingual phrasing, optional theme token,
 * optional freshness operator) so two launches rarely ask YouTube the same thing.
 */
data class KidsQuery(
    val category: KidsCategory,
    val query: String,
    val language: String,
    val baseQuery: String = query
)

/**
 * Editorial rows of the **Enfants** universe, each mapped to a stable row/chip id
 * (the ids intentionally reuse the `kids_*` keys already used by the classifier).
 */
enum class KidsCategory(
    val id: String,
    val editorialRank: Int,
    @StringRes val rowTitleRes: Int,
    @StringRes val chipLabelRes: Int,
    val frenchQueries: List<String>,
    val englishQueries: List<String>,
    val minDurationSeconds: Long,
    val maxDurationSeconds: Long
) {
    SONGS(
        id = "kids_songs",
        editorialRank = 5,
        rowTitleRes = R.string.missa_cat_kids_songs,
        chipLabelRes = R.string.missa_chip_kids_songs,
        frenchQueries = listOf(
            "comptines pour enfants",
            "chansons pour enfants",
            "comptines maternelle français",
            "chansons éducatives enfants"
        ),
        englishQueries = listOf(
            "nursery rhymes",
            "kids songs",
            "children songs collection",
            "sing along songs for kids"
        ),
        minDurationSeconds = 25L,
        maxDurationSeconds = 1_800L
    ),
    CARTOONS(
        id = "kids_cartoons",
        editorialRank = 10,
        rowTitleRes = R.string.missa_cat_kids_cartoons,
        chipLabelRes = R.string.missa_chip_kids_cartoons,
        frenchQueries = listOf(
            "dessins animés pour enfants",
            "dessin animé français complet",
            "séries animées jeunesse",
            "histoires animées pour enfants"
        ),
        englishQueries = listOf(
            "cartoons for kids",
            "kids cartoon full episodes",
            "animated series for children",
            "cartoon compilation for kids"
        ),
        minDurationSeconds = 60L,
        maxDurationSeconds = 7_200L
    ),
    MOVIES(
        id = "kids_movies",
        editorialRank = 20,
        rowTitleRes = R.string.missa_cat_kids_movies,
        chipLabelRes = R.string.missa_chip_kids_movies,
        frenchQueries = listOf(
            "film animation complet français enfants",
            "film jeunesse complet",
            "films pour enfants complets",
            "film familial animation complet"
        ),
        englishQueries = listOf(
            "full animated movie for kids",
            "family movie full movie",
            "kids movie full length",
            "animated full movie for children"
        ),
        minDurationSeconds = 900L,
        maxDurationSeconds = 10_800L
    ),
    STORIES(
        id = "kids_magic",
        editorialRank = 30,
        rowTitleRes = R.string.missa_cat_kids_magic,
        chipLabelRes = R.string.missa_chip_kids_magic,
        frenchQueries = listOf(
            "contes pour enfants",
            "histoires du soir enfants",
            "contes et légendes animés",
            "aventures magiques enfants"
        ),
        englishQueries = listOf(
            "bedtime stories for kids",
            "fairy tales for children",
            "kids stories animated",
            "magic adventure for kids"
        ),
        minDurationSeconds = 60L,
        maxDurationSeconds = 5_400L
    ),
    LEARN(
        id = "kids_learn",
        editorialRank = 40,
        rowTitleRes = R.string.missa_cat_kids_learn,
        chipLabelRes = R.string.missa_chip_kids_learn,
        frenchQueries = listOf(
            "apprendre les couleurs enfants",
            "apprendre les chiffres enfants",
            "éveil éducatif enfants",
            "apprendre les animaux enfants"
        ),
        englishQueries = listOf(
            "learn colors for kids",
            "learn numbers for kids",
            "educational videos for kids",
            "learning animals for children"
        ),
        minDurationSeconds = 45L,
        maxDurationSeconds = 3_600L
    );

    companion object {
        /** Discovery order used both for querying and for the editorial row order. */
        fun allDiscoveryCategories(): List<KidsCategory> =
            listOf(SONGS, CARTOONS, MOVIES, STORIES, LEARN)

        fun byId(id: String): KidsCategory? = allDiscoveryCategories().firstOrNull { it.id == id }
    }
}

/**
 * YouTube Kids engine for the **Enfants** universe (`id = "kids"`) — requirement **KIDS-01**.
 *
 * Guarantees:
 * 1. **YouTube is the single source** of the Kids universe (`ServiceList.YouTube` through
 *    NewPipeExtractor), exactly like [MissaMusicProviders] is for the Musiques universe.
 * 2. **Bilingual FR/EN discovery**: every row mixes French and English queries so the catalogue
 *    covers both "comptines / dessins animés" and "nursery rhymes / cartoons".
 * 3. **Renewed on every launch** ([buildKidsQueryPlan] + [MissaCatalogRotation]): a random
 *    subset of queries, an optional rotating theme token and an optional sliding freshness
 *    window make each cold start promote a different selection.
 * 4. **Child safety net** ([isKidsSafe]): horror, weapons, news, betting and adult content are
 *    filtered out before a card can ever reach a Kids row.
 */
object MissaKidsProviders {

    const val PROVIDER_YOUTUBE_KIDS = DIRECT_KIDS_API_NAME

    /** Logcat tag used to diagnose an empty Kids catalogue (`adb logcat -s MissaKids:*`). */
    private const val TAG = "MissaKids"

    /**
     * Minimum number of videos a single query must keep after the strict child-safety gate.
     * Below that, the blocklist-only results are appended instead of dropping the row.
     */
    private const val MIN_STRICT_RESULTS = 3

    /** Default number of YouTube discovery queries issued per category on a given launch. */
    const val QUERIES_PER_CATEGORY = 2

    /** Themes appended at random to vary the results between two launches. */
    private val THEME_TOKENS_FR = listOf(
        "animaux", "dinosaures", "véhicules", "princesses", "pirates", "espace", "ferme", "océan"
    )
    private val THEME_TOKENS_EN = listOf(
        "animals", "dinosaurs", "vehicles", "princesses", "pirates", "space", "farm", "ocean"
    )

    private val blockedKidsRegex = Regex(
        listOf(
            "horreur", "horror", "gore", "sanglant", "bloody", "blood", "zombie", "scary",
            "effrayant", "faire peur", "pegi\\s*1[268]", "interdit aux moins", "18\\s*\\+",
            "adulte", "adult only", "sexe", "sexy", "nude", "nudity",
            "arme", "weapon", "gun", "fusil", "pistolet", "tuer", "kill", "meurtre",
            "guerre", "war\\b", "crash", "accident", "d[eé]c[eè]s",
            "journal t[eé]l[eé]vis[eé]", "news", "actualit[eé]", "politique", "[eé]lection",
            "covid", "complot", "conspiration", "divorce",
            "paris sportifs", "betting", "casino", "crypto"
        ).joinToString("|"),
        RegexOption.IGNORE_CASE
    )

    private val kidsPositiveRegex = Regex(
        listOf(
            "enfant", "kids?\\b", "children", "jeunesse", "comptine", "nursery", "rhymes",
            "cartoon", "dessin[s]?\\s+anim", "animation", "anim[eé]", "toddler", "baby",
            "b[eé]b[eé]", "maternelle", "[eé]veil", "apprendre", "learn(ing)?", "education",
            "[eé]ducatif", "family", "famille", "conte", "tales?", "stories", "story",
            "chanson", "songs?", "peppa", "pat[\\s']?patrouille", "paw patrol",
            "disney", "pixar", "dreamworks", "nickelodeon", "tiji", "gulli", "okoo",
            "piwi\\+", "boomerang", "for kids", "pour enfants", "tout public", "jeune public"
        ).joinToString("|"),
        RegexOption.IGNORE_CASE
    )

    // -------------------------------------------------------------------------
    // Pure helpers (unit-tested on the JVM, see MissaKidsProvidersTest)
    // -------------------------------------------------------------------------

    /** Hard child-safety gate applied to every candidate video before it can enter a Kids row. */
    fun isKidsSafe(rawTitle: String, rawChannel: String? = null): Boolean {
        val context = "${rawTitle.trim()} ${rawChannel?.trim().orEmpty()}"
        if (context.isBlank()) return false
        if (blockedKidsRegex.containsMatchIn(context)) return false
        return kidsPositiveRegex.containsMatchIn(context)
    }

    /** Hard blocklist only (horror, weapons, news, betting, adult content). */
    fun isBlockedForKids(rawTitle: String, rawChannel: String? = null): Boolean {
        val context = "${rawTitle.trim()} ${rawChannel?.trim().orEmpty()}"
        if (context.isBlank()) return true
        return blockedKidsRegex.containsMatchIn(context)
    }

    /** Soft signal: does the video explicitly target a young audience? */
    fun matchesKidsAudience(rawTitle: String, rawChannel: String? = null): Boolean {
        val context = "${rawTitle.trim()} ${rawChannel?.trim().orEmpty()}"
        if (context.isBlank()) return false
        return kidsPositiveRegex.containsMatchIn(context)
    }

    /** Duration window per category: songs stay short, full movies must be long-form. */
    fun isValidKidsDuration(durationSeconds: Long, category: KidsCategory): Boolean {
        if (durationSeconds <= 0L) return false
        return durationSeconds >= category.minDurationSeconds &&
            durationSeconds <= category.maxDurationSeconds
    }

    /**
     * Builds the randomized, bilingual YouTube query plan for one launch.
     *
     * Pure: given the same [random] and [nowMillis] it returns the same plan, which makes the
     * "different content on every launch" behaviour verifiable in unit tests.
     */
    fun buildKidsQueryPlan(
        random: Random = MissaCatalogRotation.currentRandom(),
        nowMillis: Long = System.currentTimeMillis(),
        queriesPerCategory: Int = QUERIES_PER_CATEGORY
    ): List<KidsQuery> {
        val perCategory = queriesPerCategory.coerceAtLeast(1)
        val plan = ArrayList<KidsQuery>()
        for (category in KidsCategory.allDiscoveryCategories()) {
            val frenchPick = MissaCatalogRotation.pickRandomSubset(category.frenchQueries, 1, random)
            val englishPick = MissaCatalogRotation.pickRandomSubset(category.englishQueries, 1, random)
            val alreadyPicked = frenchPick + englishPick
            val remainderPool = (category.frenchQueries + category.englishQueries)
                .filter { it !in alreadyPicked }
            val remainder = if (perCategory > 2) {
                MissaCatalogRotation.pickRandomSubset(remainderPool, perCategory - 2, random)
            } else {
                emptyList()
            }

            val selected = ArrayList<Pair<String, String>>()
            frenchPick.forEach { selected.add("fr" to it) }
            englishPick.forEach { selected.add("en" to it) }
            remainder.forEach { base ->
                val language = if (category.englishQueries.contains(base)) "en" else "fr"
                selected.add(language to base)
            }

            for ((language, baseQuery) in selected) {
                plan.add(
                    KidsQuery(
                        category = category,
                        query = decorateKidsQuery(baseQuery, language, random, nowMillis),
                        language = language,
                        baseQuery = baseQuery
                    )
                )
            }
        }
        return plan
    }

    /**
     * Adds the rotation variants to a base query: an optional theme token and an optional
     * sliding freshness window (`after:YYYY-MM-DD`).
     */
    internal fun decorateKidsQuery(
        baseQuery: String,
        language: String,
        random: Random,
        nowMillis: Long
    ): String {
        var query = baseQuery.trim()
        if (random.nextBoolean()) {
            val themes = if (language == "fr") THEME_TOKENS_FR else THEME_TOKENS_EN
            val theme = MissaCatalogRotation.pickRandomSubset(themes, 1, random).firstOrNull()
            if (!theme.isNullOrBlank()) {
                query = "$query $theme"
            }
        }
        if (random.nextInt(100) < 55) {
            val lookbackDays = 45 + random.nextInt(320)
            val afterDate = MissaMusicProviders.buildRecentAfterDate(
                nowMillis = nowMillis,
                lookbackDays = lookbackDays
            )
            query = "$query after:$afterDate"
        }
        return query
    }

    /** Drops duplicates by video id then by normalized "channel|title" signature. */
    fun dedupeKidsVideos(batches: List<MusicTrackPreset>): List<MusicTrackPreset> {
        val seenIds = HashSet<String>()
        val seenSignatures = HashSet<String>()
        val merged = ArrayList<MusicTrackPreset>(batches.size)
        val sorted = batches.sortedWith(
            compareByDescending<MusicTrackPreset> { it.recencyScore }
                .thenByDescending { it.viewCount }
        )
        for (track in sorted) {
            if (track.title.isBlank() || track.streamUrl.isBlank()) continue
            if (!seenIds.add(track.id)) continue
            val signature = "${MissaMusicProviders.normalizeForMatch(track.artist)}|" +
                MissaMusicProviders.normalizeForMatch(track.title)
            if (!seenSignatures.add(signature)) continue
            merged.add(track)
        }
        return merged
    }

    /** Local (offline) match used by the in-universe kids search field. */
    fun matchesKidsQuery(track: MusicTrackPreset, rawQuery: String): Boolean =
        MissaMusicProviders.matchesTrackQuery(track, rawQuery)

    /**
     * Runs one planned query. When the rotation variants (theme token / sliding window) return
     * nothing on the current YouTube front-end, the plain base query is retried once so a row is
     * never left empty just because of the freshness operator.
     */
    private suspend fun runKidsQuery(kidsQuery: KidsQuery): List<MusicTrackPreset> {
        val decorated = searchYouTubeKidsVideos(
            query = kidsQuery.query,
            category = kidsQuery.category
        )
        if (decorated.isNotEmpty()) return decorated
        val base = kidsQuery.baseQuery.trim()
        if (base.isBlank() || base == kidsQuery.query.trim()) return emptyList()
        Log.d(TAG, "no result with rotation variants, retrying plain query: '$base'")
        return searchYouTubeKidsVideos(
            query = base,
            category = kidsQuery.category
        )
    }

    /** Groups a catalogue by row, preserving the editorial order of [KidsCategory]. */
    fun groupByCategory(videos: List<MusicTrackPreset>): LinkedHashMap<KidsCategory, List<MusicTrackPreset>> {
        val grouped = LinkedHashMap<KidsCategory, List<MusicTrackPreset>>()
        for (category in KidsCategory.allDiscoveryCategories()) {
            val items = videos.filter { it.genre == category.id }
            if (items.isNotEmpty()) {
                grouped[category] = items
            }
        }
        return grouped
    }

    // -------------------------------------------------------------------------
    // NewPipe YouTube search
    // -------------------------------------------------------------------------

    /**
     * Runs a single YouTube discovery query and returns safe, in-window Kids videos.
     *
     * Reuses the very same lazy scheme as the Musiques universe (`ytmusic://<videoId>`) so the
     * existing resolution, playback, queue and favourite plumbing works unchanged.
     */
    suspend fun searchYouTubeKidsVideos(
        query: String,
        category: KidsCategory,
        maxResults: Int = 12
    ): List<MusicTrackPreset> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        return MissaMusicProviders.youTubeQuerySemaphore.withPermit {
            withTimeoutOrNull(9_000L) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        MissaMusicProviders.ensureNewPipeReady()
                        val extractor = ServiceList.YouTube.getSearchExtractor(
                            cleanQuery,
                            listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
                            ""
                        )
                        extractor.fetchPage()
                        val items = extractor.initialPage.items
                        val strict = ArrayList<MusicTrackPreset>(maxResults)
                        val relaxed = ArrayList<MusicTrackPreset>(maxResults)
                        val seenVideoIds = HashSet<String>()

                        for (infoItem in items) {
                            if (strict.size >= maxResults) break
                            val item = infoItem as? StreamInfoItem ?: continue
                            val streamType = item.streamType
                            if (streamType == StreamType.LIVE_STREAM ||
                                streamType == StreamType.AUDIO_LIVE_STREAM ||
                                streamType == StreamType.POST_LIVE_STREAM ||
                                streamType == StreamType.POST_LIVE_AUDIO_STREAM
                            ) {
                                continue
                            }
                            if (runCatching { item.isShortFormContent }.getOrDefault(false)) continue

                            val durationSec = item.duration
                            if (!isValidKidsDuration(durationSec, category)) continue

                            val rawTitle = item.name?.trim().orEmpty()
                            if (rawTitle.isEmpty()) continue
                            val rawChannel = runCatching { item.uploaderName }.getOrNull()
                            if (isBlockedForKids(rawTitle, rawChannel)) continue

                            val videoId = MissaMusicProviders.extractYouTubeVideoId(item.url) ?: continue
                            if (!seenVideoIds.add(videoId)) continue

                            val (cleanTitle, cleanChannel) = MissaMusicProviders.cleanYouTubeTitleAndArtist(
                                rawTitle,
                                rawChannel
                            )
                            val thumb = MissaMusicProviders.pickBestThumbnailUrl(
                                runCatching { item.thumbnails }.getOrNull(),
                                fallbackVideoId = videoId
                            )
                            val fromRecentWindowQuery = cleanQuery.contains("after:", ignoreCase = true)
                            val recency = MissaMusicProviders.computeRecencyScore(
                                textualUploadDate = runCatching { item.textualUploadDate }.getOrNull(),
                                title = rawTitle,
                                currentYear = currentYear,
                                fromRecentWindowQuery = fromRecentWindowQuery
                            )
                            val rawViewCount = runCatching { item.viewCount }
                                .getOrDefault(-1L)
                                .coerceAtLeast(0L)

                            val preset = MusicTrackPreset(
                                id = "ytk_$videoId",
                                title = cleanTitle,
                                artist = cleanChannel,
                                album = "",
                                genre = category.id,
                                streamUrl = "${MissaMusicProviders.YT_MUSIC_LAZY_SCHEME}$videoId",
                                artworkUrl = thumb,
                                durationSeconds = durationSec.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                providerName = PROVIDER_YOUTUBE_KIDS,
                                isPipedLazy = true,
                                isTrending = recency >= 60,
                                itemKind = MusicItemKind.CLIP,
                                recencyScore = recency,
                                viewCount = rawViewCount
                            )
                            // Strict gate: blocked AND explicitly aimed at a young audience.
                            // Relaxed gate: only the hard blocklist, used as a fallback when the
                            // strict filter would otherwise empty the row.
                            if (matchesKidsAudience(rawTitle, rawChannel)) {
                                strict.add(preset)
                            } else {
                                relaxed.add(preset)
                            }
                        }

                        val kept = if (strict.size >= MIN_STRICT_RESULTS) {
                            strict
                        } else {
                            val alreadyKept = strict.mapTo(HashSet()) { it.id }
                            (strict + relaxed.filter { it.id !in alreadyKept }).take(maxResults)
                        }
                        Log.d(
                            TAG,
                            "query='$cleanQuery' category=${category.id} scanned=${items.size} " +
                                "strict=${strict.size} relaxed=${relaxed.size} kept=${kept.size}"
                        )
                        kept
                    }.getOrElse { error ->
                        Log.w(TAG, "query='$cleanQuery' failed: ${error.message}")
                        emptyList()
                    }
                }
            } ?: run {
                Log.w(TAG, "query='$cleanQuery' timed out")
                emptyList()
            }
        }
    }

    /**
     * Fetches a whole Kids catalogue in parallel for the given [plan].
     *
     * [onFirstWaveReady] is invoked as soon as the first half of the queries resolved so the
     * rows can render immediately, then the full (deduplicated) catalogue is returned.
     */
    suspend fun fetchKidsCatalog(
        plan: List<KidsQuery>,
        onFirstWaveReady: ((List<MusicTrackPreset>) -> Unit)? = null
    ): List<MusicTrackPreset> = coroutineScope {
        if (plan.isEmpty()) return@coroutineScope emptyList()
        val jobs = plan.map { kidsQuery ->
            async { runKidsQuery(kidsQuery) }
        }
        val half = (jobs.size + 1) / 2
        val firstWave = ArrayList<MusicTrackPreset>()
        for (i in 0 until half) {
            firstWave.addAll(jobs[i].await())
        }
        val firstWaveDeduped = dedupeKidsVideos(firstWave)
        onFirstWaveReady?.invoke(firstWaveDeduped)

        val all = ArrayList(firstWave)
        for (i in half until jobs.size) {
            all.addAll(jobs[i].await())
        }
        dedupeKidsVideos(all)
    }
}
