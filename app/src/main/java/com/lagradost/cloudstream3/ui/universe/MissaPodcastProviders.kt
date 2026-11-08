package com.lagradost.cloudstream3.ui.universe

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Locale

/**
 * Moteur d'agrégation des **sources gratuites** de l'univers Podcasts (PODCASTS-01).
 *
 * L'univers existait mais n'avait aucune source : il ne se remplissait que si une extension
 * déclarait du contenu `TvType.Podcast`, ce que presque aucune ne fait. Il est maintenant
 * alimenté par :
 *
 * 1. **[PROVIDER_APPLE]** — l'annuaire Apple Podcasts (`itunes.apple.com/search`). C'est le plus
 *    grand annuaire de podcasts au monde, **gratuit et sans clé** : une simple requête renvoie le
 *    nom de l'émission, son auteur, sa pochette en 600 px et surtout l'adresse de son **flux
 *    RSS**, seule véritable source des épisodes.
 * 2. **[PROVIDER_RSS]** — les flux RSS eux-mêmes. Ce sont eux qui portent les épisodes et les
 *    fichiers audio ; ils sont ouverts, gratuits, et aucun compte n'est nécessaire pour les lire.
 *
 * Aucune clé, aucun compte, aucun secret : tout est public. C'est volontaire, pour que la
 * fonction marche dès l'installation (l'utilisateur a refusé de dépendre d'une inscription
 * tierce, comme pour OpenSubtitles).
 *
 * Le catalogue est construit **Afrique d'abord** (PODCASTS-02) : les requêtes africaines
 * partent en premier et leurs émissions gardent la tête des rangées.
 */
internal object MissaPodcastProviders {

    const val PROVIDER_APPLE = "Apple Podcasts"
    const val PROVIDER_RSS = "Flux RSS"

    private const val USER_AGENT = "MISSA-TV-360-PLUS/2.0 (Android; Universal Media Hub)"
    private const val REQUEST_TIMEOUT_MS = 9_000L
    private const val FEED_TIMEOUT_MS = 12_000L

    private const val MAX_SHOWS = 140
    private const val MAX_EPISODES_PER_SHOW = 60
    private const val MAX_CACHED_FEEDS = 32
    private const val EPISODES_TTL_MS = 10 * 60 * 1_000L

    private const val ITUNES_SEARCH_URL = "https://itunes.apple.com/search"

    private val httpHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, */*"
    )
    private val xmlHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/rss+xml, application/xml, text/xml, */*"
    )

    /** Une requête de catalogue : le terme cherché, la boutique interrogée, et son rang. */
    private data class CatalogueQuery(
        val term: String,
        val store: String,
        val region: PodcastRegion
    )

    /**
     * Les requêtes qui composent le catalogue, **Afrique d'abord** (PODCASTS-02).
     *
     * Ce sont des termes larges et non des noms d'émissions : l'annuaire renvoie toujours
     * quelque chose pour « cameroun » ou « africa podcast », alors qu'une émission précise peut
     * disparaître. Le catalogue ne contient donc jamais de lien mort.
     */
    private val CATALOGUE_QUERIES: List<CatalogueQuery> = listOf(
        // 1. Afrique d'abord : Cameroun, Afrique francophone, puis le continent anglophone.
        CatalogueQuery("podcast cameroun", "FR", PodcastRegion.AFRICA),
        CatalogueQuery("podcast afrique", "FR", PodcastRegion.AFRICA),
        CatalogueQuery("africa podcast", "US", PodcastRegion.AFRICA),
        CatalogueQuery("podcast senegal", "FR", PodcastRegion.AFRICA),
        CatalogueQuery("podcast cote d ivoire", "FR", PodcastRegion.AFRICA),
        CatalogueQuery("nigeria podcast", "US", PodcastRegion.AFRICA),
        CatalogueQuery("kenya podcast", "US", PodcastRegion.AFRICA),
        CatalogueQuery("afrique podcast", "FR", PodcastRegion.AFRICA),
        // 2. Francophonie : la langue de l'utilisateur, après l'Afrique.
        CatalogueQuery("podcast francais", "FR", PodcastRegion.FRANCOPHONE),
        CatalogueQuery("podcast actualite", "FR", PodcastRegion.FRANCOPHONE),
        CatalogueQuery("podcast culture", "FR", PodcastRegion.FRANCOPHONE),
        // 3. Le monde, pour compléter.
        CatalogueQuery("news podcast", "US", PodcastRegion.WORLD),
        CatalogueQuery("technology podcast", "US", PodcastRegion.WORLD),
        CatalogueQuery("business podcast", "US", PodcastRegion.WORLD),
        CatalogueQuery("sport podcast", "US", PodcastRegion.WORLD),
        CatalogueQuery("history podcast", "US", PodcastRegion.WORLD)
    )

    private val DATE_PATTERNS = listOf(
        "EEE, dd MMM yyyy HH:mm:ss Z",
        "EEE, dd MMM yyyy HH:mm:ss z",
        "EEE, dd MMM yyyy HH:mm Z",
        "EEE, d MMM yyyy HH:mm:ss Z",
        "dd MMM yyyy HH:mm:ss Z",
        "yyyy-MM-dd'T'HH:mm:ss",
        "yyyy-MM-dd"
    )

    /** Épisodes déjà téléchargés, par identifiant d'émission. Vite relus, jamais requis. */
    private val episodeCache = Collections.synchronizedMap(
        LinkedHashMap<String, Pair<Long, List<PodcastEpisode>>>()
    )

    // --- Réseau -------------------------------------------------------------------------

    private suspend fun fetchItunesSearch(term: String, store: String, limit: Int): String? {
        val encoded = runCatching { URLEncoder.encode(term, "UTF-8") }.getOrDefault(term)
        val url = "$ITUNES_SEARCH_URL?term=$encoded&media=podcast&entity=podcast" +
            "&limit=$limit&country=$store"
        return withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            try {
                val resp = app.get(url, headers = httpHeaders)
                resp.text.takeIf { it.trimStart().startsWith("{") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    private suspend fun fetchFeedXml(feedUrl: String): String? =
        withTimeoutOrNull(FEED_TIMEOUT_MS) {
            try {
                app.get(feedUrl, headers = xmlHeaders).text.takeIf { it.isNotBlank() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }

    // --- Analyses (publiques : elles sont vérifiées par des tests unitaires) ------------

    /**
     * Analyse une réponse de l'annuaire Apple. Seules les émissions **qui ont un flux RSS**
     * sont gardées : sans lui, il n'y a aucun épisode à lire.
     */
    fun parseItunesSearchJson(
        jsonText: String,
        region: PodcastRegion = PodcastRegion.WORLD,
        defaultProvider: String = PROVIDER_APPLE
    ): List<PodcastShowPreset> {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val arr: JSONArray = root.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<PodcastShowPreset>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val title = obj.optString("collectionName").trim().replace(Regex("\\s+"), " ")
            val feedUrl = obj.optString("feedUrl").trim()
            if (title.isBlank() || !isHttpUrl(feedUrl)) continue
            val rawId = obj.optString("collectionId").trim().ifBlank { feedUrl }
            val artwork = obj.optString("artworkUrl600").trim()
                .ifBlank { obj.optString("artworkUrl100").trim() }
                .takeIf { isHttpUrl(it) }
            val genres = obj.optJSONArray("genres")?.let { joinGenres(it) }.orEmpty()
            out += PodcastShowPreset(
                id = "apple_$rawId",
                title = title,
                author = obj.optString("artistName").trim(),
                artworkUrl = artwork,
                feedUrl = feedUrl,
                genre = obj.optString("primaryGenreName").trim().ifBlank { genres },
                region = region,
                episodeCount = obj.optInt("trackCount", 0),
                providerName = defaultProvider
            )
        }
        return out
    }

    /**
     * Analyse un flux RSS de podcast et en sort les épisodes, du plus récent au plus ancien
     * (les flux publient dans cet ordre et on le respecte).
     */
    fun parsePodcastRss(
        xml: String,
        show: PodcastShowPreset,
        limit: Int = MAX_EPISODES_PER_SHOW
    ): List<PodcastEpisode> {
        if (xml.isBlank()) return emptyList()
        val doc = runCatching { Jsoup.parse(xml, "", Parser.xmlParser()) }.getOrNull()
            ?: return emptyList()
        var nodes = doc.select("item")
        if (nodes.isEmpty()) nodes = doc.select("entry")
        if (nodes.isEmpty()) return emptyList()

        val channelArtwork = doc.selectFirst("channel")?.let { firstImageUrl(it) }
            ?: firstImageUrl(doc)
        val fallbackArtwork = show.artworkUrl ?: channelArtwork

        val out = ArrayList<PodcastEpisode>(nodes.size.coerceAtMost(limit))
        for (node in nodes.take(limit)) {
            val audioUrl = firstAudioUrl(node)
            if (!isHttpUrl(audioUrl)) continue
            val title = childText(node, "title").trim().replace(Regex("\\s+"), " ")
            val description = childText(node, "description")
                .ifBlank { childText(node, "summary") }
                .ifBlank { childText(node, "content") }
                .trim()
            out += PodcastEpisode(
                id = "ep_" + audioUrl.hashCode(),
                showId = show.id,
                showTitle = show.title,
                author = childText(node, "author").ifBlank { show.author },
                title = title.ifBlank { show.title },
                audioUrl = audioUrl,
                artworkUrl = firstImageUrl(node) ?: fallbackArtwork,
                durationSeconds = parseDurationSeconds(childText(node, "duration")),
                publishedAtMillis = parseFeedDateMillis(childText(node, "pubDate", "published", "updated")),
                description = description.take(600),
                region = show.region,
                providerName = show.providerName
            )
        }
        return out
    }

    /**
     * Convertit une durée de flux en secondes. Les flux sont peu regardants : on rencontre
     * `2533` (secondes), `42:15`, `1:02:33` et parfois `01:02:33.0`.
     */
    fun parseDurationSeconds(raw: String): Int {
        val text = raw.trim()
        if (text.isEmpty()) return 0
        if (!text.contains(':')) {
            return text.toDoubleOrNull()?.toInt()?.coerceAtLeast(0)
                ?: text.toIntOrNull()?.coerceAtLeast(0)
                ?: 0
        }
        val parts = text.split(':').map { part ->
            part.trim().toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0
        }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    /** Date d'un flux RSS (RFC 822 le plus souvent). Renvoie 0 quand elle est illisible. */
    fun parseFeedDateMillis(raw: String): Long {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return 0L
        for (pattern in DATE_PATTERNS) {
            val millis = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { isLenient = true }.parse(text)?.time
            }.getOrNull()
            if (millis != null && millis > 0) return millis
        }
        return 0L
    }

    // --- Assemblage du catalogue -------------------------------------------------------

    /**
     * Fusionne les lots rapportés par les requêtes : une émission n'apparaît qu'une fois, et
     * l'Afrique garde la tête (PODCASTS-02).
     */
    fun mergeShowCatalogues(batches: List<PodcastShowPreset>): List<PodcastShowPreset> {
        val byKey = LinkedHashMap<String, PodcastShowPreset>()
        for (show in batches) {
            if (show.title.isBlank() || !isHttpUrl(show.feedUrl)) continue
            val key = normalizeShowKey(show)
            val previous = byKey[key]
            if (previous == null || isBetterCandidate(show, previous)) {
                byKey[key] = show
            }
        }
        return byKey.values
            .sortedWith(
                compareBy<PodcastShowPreset> { it.region.order }
                    .thenByDescending { it.episodeCount }
                    .thenBy { it.title.lowercase(Locale.ROOT) }
            )
            .take(MAX_SHOWS)
    }

    /** Une émission reconnue d'Afrique doit rester marquée Afrique, quel que soit le lot. */
    private fun isBetterCandidate(candidate: PodcastShowPreset, current: PodcastShowPreset): Boolean {
        if (candidate.region.order < current.region.order) return true
        if (candidate.region != current.region) return false
        return candidate.episodeCount > current.episodeCount
    }

    /** Deux adresses de flux différentes peuvent donner la même émission. */
    fun normalizeShowKey(show: PodcastShowPreset): String {
        val host = runCatching {
            val uri = java.net.URI(show.feedUrl)
            (uri.host ?: "").removePrefix("www.").lowercase(Locale.ROOT)
        }.getOrDefault("")
        val path = runCatching { java.net.URI(show.feedUrl).path ?: "" }
            .getOrDefault("")
            .trimEnd('/')
            .lowercase(Locale.ROOT)
        val byPath = "$host$path".takeIf { it.isNotBlank() }
        return byPath ?: normalizeForMatch(show.title).ifBlank { show.id.lowercase(Locale.ROOT) }
    }

    /** Recherche locale, sans accents : sert à filtrer le catalogue déjà en mémoire. */
    fun matchesShowQuery(show: PodcastShowPreset, rawQuery: String): Boolean {
        val query = normalizeForMatch(rawQuery)
        if (query.isBlank()) return true
        val haystack = normalizeForMatch("${show.title} ${show.author} ${show.genre}")
        return query.split(' ').filter { it.length > 1 }.all { token -> token in haystack }
    }

    fun normalizeForMatch(raw: String): String {
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
        return decomposed
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    // --- Moisson ----------------------------------------------------------------------

    /**
     * Construit le catalogue complet en interrogeant toutes les requêtes en parallèle, avec un
     * nombre de connexions borné pour ne pas saturer la connexion de la télé.
     */
    suspend fun harvestAllFreeProviders(): List<PodcastShowPreset> = coroutineScope {
        val semaphore = Semaphore(4)
        val batches = CATALOGUE_QUERIES.map { query ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val json = fetchItunesSearch(query.term, query.store, limit = 20)
                        ?: return@withPermit emptyList()
                    parseItunesSearchJson(json, query.region, PROVIDER_APPLE)
                }
            }
        }
        mergeShowCatalogues(batches.awaitAll().flatten())
    }

    /**
     * Recherche en ligne. Deux boutiques sont interrogées (France et États-Unis) afin qu'une
     * émission africaine anglophone ne soit pas invisible depuis un appareil francophone.
     */
    suspend fun searchOnlineProviders(query: String): List<PodcastShowPreset> = coroutineScope {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@coroutineScope emptyList()
        val francais = async(Dispatchers.IO) {
            fetchItunesSearch(trimmed, "FR", 25)?.let {
                parseItunesSearchJson(it, PodcastRegion.FRANCOPHONE, PROVIDER_APPLE)
            }.orEmpty()
        }
        val monde = async(Dispatchers.IO) {
            fetchItunesSearch(trimmed, "US", 25)?.let {
                parseItunesSearchJson(it, PodcastRegion.WORLD, PROVIDER_APPLE)
            }.orEmpty()
        }
        mergeShowCatalogues(francais.await() + monde.await())
    }

    /**
     * Les épisodes d'une émission. Le flux RSS n'est téléchargé qu'ici, à l'ouverture de
     * l'émission : le catalogue reste léger, une télé n'a pas à tirer quinze flux d'un coup.
     */
    suspend fun fetchEpisodes(
        show: PodcastShowPreset,
        forceRefresh: Boolean = false
    ): List<PodcastEpisode> = withContext(Dispatchers.IO) {
        val cached = episodeCache[show.id]
        val now = System.currentTimeMillis()
        if (!forceRefresh && cached != null && now - cached.first < EPISODES_TTL_MS) {
            return@withContext cached.second
        }
        val xml = fetchFeedXml(show.feedUrl)
        if (xml.isNullOrBlank()) {
            // Réseau indisponible : mieux vaut rendre les épisodes d'avant que rien du tout.
            return@withContext cached?.second.orEmpty()
        }
        val episodes = parsePodcastRss(xml, show)
        if (episodes.isEmpty()) {
            return@withContext cached?.second.orEmpty()
        }
        synchronized(episodeCache) {
            if (episodeCache.size >= MAX_CACHED_FEEDS) {
                val oldest = episodeCache.keys.firstOrNull()
                if (oldest != null) episodeCache.remove(oldest)
            }
            episodeCache[show.id] = now to episodes
        }
        episodes
    }

    /** Oublie les épisodes en mémoire (changement de réseau, réglage, …). */
    fun clearEpisodeCache() {
        synchronized(episodeCache) { episodeCache.clear() }
    }

    // --- Lecture du XML ---------------------------------------------------------------

    /**
     * Nom d'une balise sans son préfixe de domaine : `itunes:duration` et `duration` donnent
     * tous les deux `duration`, sans avoir à échapper les deux-points dans un sélecteur.
     */
    private fun localName(element: Element): String =
        element.tagName().substringAfter(':').lowercase(Locale.ROOT)

    private fun childText(node: Element, vararg localNames: String): String {
        // Les noms demandés sont normalisés comme ceux des balises : sans quoi « pubDate »
        // ne retrouverait pas la balise, que le flux écrive pubDate, PubDate ou PUBDATE.
        val wanted = localNames.map { it.substringAfter(':').lowercase(Locale.ROOT) }.toSet()
        for (child in node.children()) {
            if (localName(child) in wanted) {
                val text = child.text().trim()
                if (text.isNotBlank()) return text
            }
        }
        return ""
    }

    /** Le fichier audio d'un épisode : `<enclosure>`, `<media:content>` ou `<link rel=enclosure>`. */
    private fun firstAudioUrl(node: Element): String {
        for (child in node.children()) {
            when (localName(child)) {
                "enclosure" -> {
                    val url = child.attr("url").trim()
                    val type = child.attr("type")
                    if (isHttpUrl(url) && (type.isBlank() || type.startsWith("audio", true))) return url
                }
                "content" -> {
                    val url = child.attr("url").trim()
                    if (isHttpUrl(url) && child.attr("type").startsWith("audio", true)) return url
                }
                "link" -> {
                    val href = child.attr("href").trim()
                    val rel = child.attr("rel")
                    if (isHttpUrl(href) &&
                        rel.equals("enclosure", ignoreCase = true) &&
                        child.attr("type").startsWith("audio", true)
                    ) {
                        return href
                    }
                }
            }
        }
        return ""
    }

    /** Pochette d'un nœud : `<itunes:image href=…>`, `<media:thumbnail url=…>` ou `<image><url>`. */
    private fun firstImageUrl(node: Element): String? {
        for (child in node.children()) {
            when (localName(child)) {
                "image" -> {
                    val href = child.attr("href").trim()
                    if (isHttpUrl(href)) return href
                    val nested = childText(child, "url")
                    if (isHttpUrl(nested)) return nested
                }
                "thumbnail" -> {
                    val url = child.attr("url").trim()
                    if (isHttpUrl(url)) return url
                }
            }
        }
        return null
    }

    private fun joinGenres(arr: JSONArray): String {
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val value = arr.optString(i).trim()
            if (value.isNotBlank() && value != "Podcasts") out += value
        }
        return out.take(2).joinToString(" • ")
    }

    private fun isHttpUrl(raw: String): Boolean {
        val value = raw.trim()
        return value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true)
    }
}
