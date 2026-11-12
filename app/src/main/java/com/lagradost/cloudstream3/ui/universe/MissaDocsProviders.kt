package com.lagradost.cloudstream3.ui.universe

import android.util.Log
import androidx.annotation.StringRes
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
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

/** Source unique de l'univers Documentaires & Live. */
const val DIRECT_DOCS_API_NAME = "YouTube"

/** Une recherche YouTube préparée pour une rangée de l'univers. */
data class DocsQuery(
    val category: DocsCategory,
    val query: String,
    val baseQuery: String = query
)

/**
 * Rangées de l'univers **Documentaires & Live**. Les rangées [isLive] ne gardent que les
 * directs YouTube en cours ; les autres, des documentaires longs (15 min à 4 h).
 */
enum class DocsCategory(
    val id: String,
    val editorialRank: Int,
    @StringRes val rowTitleRes: Int,
    @StringRes val chipLabelRes: Int,
    val queries: List<String>,
    val isLive: Boolean = false,
    val minDurationSeconds: Long = 900L,
    val maxDurationSeconds: Long = 14_400L
) {
    LIVE_NEWS(
        id = "docs_live_news",
        editorialRank = 5,
        rowTitleRes = R.string.missa_docs_row_live_news,
        chipLabelRes = R.string.missa_docs_chip_live_news,
        queries = listOf(
            "france 24 en direct", "info en direct 24h/24", "africanews en direct",
            "tv5monde info direct", "live news 24/7", "euronews en direct",
            "journal en direct afrique", "dw news live"
        ),
        isLive = true
    ),
    LIVE_DOCS(
        id = "docs_live_docs",
        editorialRank = 8,
        rowTitleRes = R.string.missa_docs_row_live_docs,
        chipLabelRes = R.string.missa_docs_chip_live_docs,
        queries = listOf(
            "documentaire en direct 24h/24", "documentary live 24/7", "nature live 24/7",
            "wildlife live camera africa", "space live nasa", "histoire documentaire direct",
            "documentaires en continu", "safari live"
        ),
        isLive = true
    ),
    AFRICA(
        id = "docs_africa",
        editorialRank = 10,
        rowTitleRes = R.string.missa_docs_row_africa,
        chipLabelRes = R.string.missa_docs_chip_africa,
        queries = listOf(
            "documentaire afrique", "histoire de l'afrique documentaire", "documentaire cameroun",
            "african history documentary", "royaumes africains documentaire", "africa documentary full"
        )
    ),
    HISTORY(
        id = "docs_history",
        editorialRank = 20,
        rowTitleRes = R.string.missa_docs_row_history,
        chipLabelRes = R.string.missa_docs_chip_history,
        queries = listOf(
            "documentaire histoire complet", "documentaire seconde guerre mondiale",
            "history documentary full", "ancient civilizations documentary",
            "documentaire égypte antique", "documentaire histoire de france"
        )
    ),
    NATURE(
        id = "docs_nature",
        editorialRank = 30,
        rowTitleRes = R.string.missa_docs_row_nature,
        chipLabelRes = R.string.missa_docs_chip_nature,
        queries = listOf(
            "documentaire animaux sauvages", "documentaire nature complet", "wildlife documentary full",
            "documentaire océan", "nature documentary 4k", "documentaire savane afrique"
        )
    ),
    SCIENCE(
        id = "docs_science",
        editorialRank = 40,
        rowTitleRes = R.string.missa_docs_row_science,
        chipLabelRes = R.string.missa_docs_chip_science,
        queries = listOf(
            "documentaire science univers", "documentaire espace", "space documentary full",
            "science documentary", "documentaire technologie", "documentaire santé corps humain"
        )
    ),
    SOCIETY(
        id = "docs_society",
        editorialRank = 50,
        rowTitleRes = R.string.missa_docs_row_society,
        chipLabelRes = R.string.missa_docs_chip_society,
        queries = listOf(
            "documentaire enquête", "reportage complet société", "investigative documentary",
            "grand reportage documentaire", "documentaire économie", "documentaire sport"
        )
    ),
    TRAVEL(
        id = "docs_travel",
        editorialRank = 60,
        rowTitleRes = R.string.missa_docs_row_travel,
        chipLabelRes = R.string.missa_docs_chip_travel,
        queries = listOf(
            "documentaire voyage découverte", "documentaire pays du monde", "travel documentary full",
            "documentaire culture traditions", "documentaire villes du monde", "documentaire peuples"
        )
    );

    companion object {
        fun all(): List<DocsCategory> = entries.toList()
        fun byId(id: String): DocsCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Moteur YouTube de l'univers **Documentaires & Live** (`id = "documentaries"`).
 *
 * Comme les univers Musiques et Enfants :
 * 1. **YouTube est la seule source** (NewPipeExtractor) ; les extensions CloudStream ne
 *    fournissent plus rien à cet univers ;
 * 2. la sélection change à chaque lancement ([MissaCatalogRotation]) ;
 * 3. les vidéos s'ouvrent dans le lecteur vidéo (cartes [DirectKidsSearchResponse], le type
 *    « vidéo YouTube » commun aux univers vidéo) ; un direct est lu par son flux HLS.
 */
object MissaDocsProviders {

    const val PROVIDER_YOUTUBE = DIRECT_DOCS_API_NAME
    private const val TAG = "MissaDocs"
    const val QUERIES_PER_CATEGORY = 2

    private val blockedRegex = Regex(
        listOf(
            "bande[\\s-]?annonce", "trailer", "teaser", "\\breact", "r[eé]action", "r[eé]agi", "#shorts",
            "clip officiel", "official (music )?video", "lyrics", "parole", "karaok[eé]",
            "porn", "sexe", "sexy", "nude", "18\\s*\\+", "gore", "casino", "betting", "paris sportifs",
            "asmr", "prank", "gameplay", "walkthrough", "let'?s play"
        ).joinToString("|"),
        RegexOption.IGNORE_CASE
    )

    private val documentaryRegex = Regex(
        listOf(
            "documentaire", "documentary", "docu", "reportage", "enqu[eê]te", "investigat",
            "histoire", "history", "nature", "wildlife", "animaux", "animals", "science", "espace",
            "space", "univers", "civilisation", "civilization", "voyage", "travel", "d[eé]couverte",
            "discovery", "national geographic", "arte", "planet", "plan[eè]te", "afrique", "africa"
        ).joinToString("|"),
        RegexOption.IGNORE_CASE
    )

    /** Écarte bandes-annonces, clips, réactions, jeux vidéo et contenus adultes. */
    fun isBlocked(rawTitle: String, rawChannel: String? = null): Boolean {
        val text = "${rawTitle.trim()} ${rawChannel?.trim().orEmpty()}"
        if (text.isBlank()) return true
        return blockedRegex.containsMatchIn(text)
    }

    /** Le titre ou la chaîne annonce-t-il un documentaire ? (signal souple) */
    fun looksDocumentary(rawTitle: String, rawChannel: String? = null): Boolean =
        documentaryRegex.containsMatchIn("${rawTitle.trim()} ${rawChannel?.trim().orEmpty()}")

    fun isValidDuration(durationSeconds: Long, category: DocsCategory): Boolean =
        durationSeconds in category.minDurationSeconds..category.maxDurationSeconds

    private fun isLiveType(type: StreamType?): Boolean =
        type == StreamType.LIVE_STREAM || type == StreamType.AUDIO_LIVE_STREAM

    /** Plan de recherches du lancement : [perCategory] requêtes tirées au hasard par rangée. */
    fun buildQueryPlan(
        random: Random = MissaCatalogRotation.currentRandom(),
        nowMillis: Long = System.currentTimeMillis(),
        perCategory: Int = QUERIES_PER_CATEGORY
    ): List<DocsQuery> = DocsCategory.all().flatMap { category ->
        MissaCatalogRotation.pickRandomSubset(category.queries, perCategory.coerceAtLeast(1), random).map { base ->
            // Pour les documentaires, une fois sur trois : seulement des vidéos récentes.
            val query = if (!category.isLive && random.nextInt(100) < 33) {
                "$base after:" + MissaMusicProviders.buildRecentAfterDate(nowMillis, 120 + random.nextInt(600))
            } else {
                base
            }
            DocsQuery(category, query, base)
        }
    }

    fun dedupe(videos: List<MusicTrackPreset>): List<MusicTrackPreset> {
        val seenIds = HashSet<String>()
        val seenSignatures = HashSet<String>()
        val out = ArrayList<MusicTrackPreset>(videos.size)
        for (video in videos) {
            if (video.title.isBlank() || video.streamUrl.isBlank()) continue
            if (!seenIds.add(video.id)) continue
            val signature = MissaMusicProviders.normalizeForMatch(video.artist) + "|" +
                MissaMusicProviders.normalizeForMatch(video.title)
            if (!seenSignatures.add(signature)) continue
            out += video
        }
        return out
    }

    fun groupByCategory(videos: List<MusicTrackPreset>): LinkedHashMap<DocsCategory, List<MusicTrackPreset>> {
        val grouped = LinkedHashMap<DocsCategory, List<MusicTrackPreset>>()
        for (category in DocsCategory.all()) {
            val items = videos.filter { it.genre == category.id }
            if (items.isNotEmpty()) grouped[category] = items
        }
        return grouped
    }

    fun isLiveVideo(video: MusicTrackPreset): Boolean = DocsCategory.byId(video.genre)?.isLive == true

    /** Carte vidéo : un appui ouvre le lecteur vidéo (même chemin que l'univers Enfants). */
    fun toSearchResponse(video: MusicTrackPreset): DirectKidsSearchResponse =
        DirectKidsSearchResponse(
            name = video.displayLabel(),
            url = video.streamUrl,
            apiName = PROVIDER_YOUTUBE,
            type = if (isLiveVideo(video)) TvType.Live else TvType.Documentary,
            posterUrl = video.artworkUrl,
            videoId = MissaMusicProviders.extractYouTubeVideoId(video.streamUrl).orEmpty(),
            channel = video.artist,
            categoryId = video.genre,
            durationSeconds = video.durationSeconds,
            artworkUrl = video.artworkUrl
        )

    fun matchesQuery(video: MusicTrackPreset, rawQuery: String): Boolean =
        MissaMusicProviders.matchesTrackQuery(video, rawQuery)

    /** Une recherche YouTube : directs en cours ou documentaires longs selon la rangée. */
    suspend fun search(query: String, category: DocsCategory, maxResults: Int = 14): List<MusicTrackPreset> {
        val clean = query.trim()
        if (clean.isBlank()) return emptyList()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        return MissaMusicProviders.youTubeQuerySemaphore.withPermit {
            withTimeoutOrNull(10_000L) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        MissaMusicProviders.ensureNewPipeReady()
                        val extractor = ServiceList.YouTube.getSearchExtractor(
                            clean, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), ""
                        )
                        extractor.fetchPage()
                        val items = extractor.initialPage.items
                        val strict = ArrayList<MusicTrackPreset>(maxResults)
                        val relaxed = ArrayList<MusicTrackPreset>(maxResults)
                        val seen = HashSet<String>()
                        for (info in items) {
                            if (strict.size >= maxResults) break
                            val item = info as? StreamInfoItem ?: continue
                            val live = isLiveType(item.streamType)
                            if (category.isLive != live) continue
                            if (item.streamType == StreamType.POST_LIVE_STREAM ||
                                item.streamType == StreamType.POST_LIVE_AUDIO_STREAM
                            ) continue
                            if (runCatching { item.isShortFormContent }.getOrDefault(false)) continue
                            if (!live && !isValidDuration(item.duration, category)) continue
                            val rawTitle = item.name?.trim().orEmpty()
                            if (rawTitle.isEmpty()) continue
                            val rawChannel = runCatching { item.uploaderName }.getOrNull()
                            if (isBlocked(rawTitle, rawChannel)) continue
                            val videoId = MissaMusicProviders.extractYouTubeVideoId(item.url) ?: continue
                            if (!seen.add(videoId)) continue
                            val thumb = MissaMusicProviders.pickBestThumbnailUrl(
                                runCatching { item.thumbnails }.getOrNull(), fallbackVideoId = videoId
                            )
                            val recency = if (live) 100 else MissaMusicProviders.computeRecencyScore(
                                textualUploadDate = runCatching { item.textualUploadDate }.getOrNull(),
                                title = rawTitle,
                                currentYear = currentYear,
                                fromRecentWindowQuery = clean.contains("after:", ignoreCase = true)
                            )
                            val preset = MusicTrackPreset(
                                id = "ytd_$videoId",
                                title = rawTitle,
                                artist = rawChannel?.trim().orEmpty(),
                                album = "",
                                genre = category.id,
                                streamUrl = "${MissaMusicProviders.YT_MUSIC_LAZY_SCHEME}$videoId",
                                artworkUrl = thumb,
                                durationSeconds = if (live) 0 else item.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                                providerName = PROVIDER_YOUTUBE,
                                isPipedLazy = true,
                                isTrending = live || recency >= 60,
                                itemKind = MusicItemKind.CLIP,
                                recencyScore = recency,
                                viewCount = runCatching { item.viewCount }.getOrDefault(-1L).coerceAtLeast(0L)
                            )
                            // Un direct n'a pas besoin du mot « documentaire » ; un documentaire si,
                            // sauf s'il manque de candidats (rangée jamais vide pour un mot absent).
                            if (live || looksDocumentary(rawTitle, rawChannel)) strict += preset else relaxed += preset
                        }
                        val kept = if (strict.size >= 4) strict else (strict + relaxed).take(maxResults)
                        Log.d(TAG, "query='$clean' ${category.id} scanned=${items.size} kept=${kept.size}")
                        kept
                    }.getOrElse { e ->
                        Log.w(TAG, "query='$clean' failed: ${e.message}")
                        emptyList()
                    }
                }
            } ?: emptyList()
        }
    }

    private suspend fun runQuery(q: DocsQuery): List<MusicTrackPreset> {
        val found = search(q.query, q.category)
        if (found.isNotEmpty() || q.baseQuery == q.query) return found
        return search(q.baseQuery, q.category)
    }

    /** Toutes les recherches du plan ; [onFirstWave] dès que la première moitié est prête. */
    suspend fun fetchCatalog(
        plan: List<DocsQuery>,
        onFirstWave: ((List<MusicTrackPreset>) -> Unit)? = null
    ): List<MusicTrackPreset> = withContext(Dispatchers.IO) {
        coroutineScope {
            if (plan.isEmpty()) return@coroutineScope emptyList()
            val jobs = plan.map { q -> async { runQuery(q) } }
            val half = (jobs.size + 1) / 2
            val first = ArrayList<MusicTrackPreset>()
            for (i in 0 until half) first += jobs[i].await()
            onFirstWave?.invoke(dedupe(first))
            val all = ArrayList(first)
            for (i in half until jobs.size) all += jobs[i].await()
            dedupe(all)
        }
    }
}
