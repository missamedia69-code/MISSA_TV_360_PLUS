package com.lagradost.cloudstream3.ui.universe

import androidx.annotation.StringRes
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import java.text.Normalizer
import java.util.Locale

/**
 * Stable, language-independent discovery categories for MISSA TV 360 PLUS (I18N-01, HOME-08, HOME-10).
 *
 * - [id]: Immutable key used for category filtering and DiffUtil identity (never a translated string).
 * - [editorialRank]: Deterministic row display priority (lower appears first; generic catch-all rows last).
 * - [rowTitleRes]: Full editorial row heading (e.g. "Tendances & Nouveautés" / "Trending & New Releases").
 * - [chipLabelRes]: Short 1–2 word pill label (e.g. "Tendances" / "Trending") so mobile filter strips stay compact.
 */
enum class MissaCategory(
    val id: String,
    val editorialRank: Int,
    @StringRes val rowTitleRes: Int,
    @StringRes val chipLabelRes: Int
) {
    FAVORITES("favorites", 5, R.string.missa_cat_favorites, R.string.missa_chip_favorites),

    // Home
    HOME_TRENDING("home_trending", 10, R.string.missa_cat_home_trending, R.string.missa_chip_home_trending),
    HOME_MOVIES("home_movies", 20, R.string.missa_cat_home_movies, R.string.missa_chip_home_movies),
    HOME_SERIES("home_series", 30, R.string.missa_cat_home_series, R.string.missa_chip_home_series),
    HOME_LIVE("home_live", 35, R.string.missa_cat_home_live, R.string.missa_chip_home_live),

    // Shared genres
    GENRE_ACTION("genre_action", 40, R.string.missa_cat_action, R.string.missa_chip_action),
    GENRE_COMEDY("genre_comedy", 50, R.string.missa_cat_comedy, R.string.missa_chip_comedy),
    GENRE_SCIFI("genre_scifi", 60, R.string.missa_cat_scifi, R.string.missa_chip_scifi),
    GENRE_DRAMA("genre_drama", 70, R.string.missa_cat_drama, R.string.missa_chip_drama),
    GENRE_THRILLER("genre_thriller", 80, R.string.missa_cat_thriller, R.string.missa_chip_thriller),
    GENRE_ANIMATION("genre_animation", 90, R.string.missa_cat_animation, R.string.missa_chip_animation),
    GENRE_DOCUMENTARY("genre_documentary", 100, R.string.missa_cat_documentary, R.string.missa_chip_documentary),

    HOME_FAMILY("home_family", 110, R.string.missa_cat_home_family, R.string.missa_chip_home_family),
    HOME_AUDIO("home_audio", 120, R.string.missa_cat_home_audio, R.string.missa_chip_home_audio),
    HOME_SELECTION("home_selection", 900, R.string.missa_cat_home_selection, R.string.missa_chip_home_selection),

    // Films
    FILMS_FEATURED("films_featured", 10, R.string.missa_cat_films_featured, R.string.missa_chip_films_featured),
    FILMS_SHORTS("films_shorts", 115, R.string.missa_cat_films_shorts, R.string.missa_chip_films_shorts),
    FILMS_CATALOG("films_catalog", 900, R.string.missa_cat_films_catalog, R.string.missa_chip_films_catalog),

    // Series
    SERIES_TRENDING("series_trending", 10, R.string.missa_cat_series_trending, R.string.missa_chip_series_trending),
    SERIES_INTL("series_intl", 35, R.string.missa_cat_series_intl, R.string.missa_chip_series_intl),
    SERIES_CATALOG("series_catalog", 900, R.string.missa_cat_series_catalog, R.string.missa_chip_series_catalog),

    // Shorts
    SHORTS_MICRO("shorts_micro", 10, R.string.missa_cat_shorts_micro, R.string.missa_chip_shorts_micro),
    SHORTS_ANIMATION("shorts_anim", 20, R.string.missa_cat_shorts_anim, R.string.missa_chip_shorts_anim),
    SHORTS_DOCU("shorts_doc", 30, R.string.missa_cat_shorts_doc, R.string.missa_chip_shorts_doc),
    SHORTS_ALL("shorts_all", 900, R.string.missa_cat_shorts_all, R.string.missa_chip_shorts_all),

    // Kids
    KIDS_CARTOONS("kids_cartoons", 10, R.string.missa_cat_kids_cartoons, R.string.missa_chip_kids_cartoons),
    KIDS_MOVIES("kids_movies", 20, R.string.missa_cat_kids_movies, R.string.missa_chip_kids_movies),
    KIDS_MAGIC("kids_magic", 30, R.string.missa_cat_kids_magic, R.string.missa_chip_kids_magic),
    KIDS_ALL("kids_all", 900, R.string.missa_cat_kids_all, R.string.missa_chip_kids_all),

    // Live TV
    LIVE_NEWS("live_news", 15, R.string.missa_cat_live_news, R.string.missa_chip_live_news),
    LIVE_CINEMA("live_cinema", 25, R.string.missa_cat_live_cinema, R.string.missa_chip_live_cinema),
    LIVE_CULTURE("live_culture", 35, R.string.missa_cat_live_culture, R.string.missa_chip_live_culture),
    LIVE_MUSIC("live_music", 45, R.string.missa_cat_live_music, R.string.missa_chip_live_music),
    LIVE_ALL("live_all", 900, R.string.missa_cat_live_all, R.string.missa_chip_live_all),

    // Sports
    SPORTS_FOOTBALL("sports_football", 15, R.string.missa_cat_sports_football, R.string.missa_chip_sports_football),
    SPORTS_BASKET("sports_basket", 25, R.string.missa_cat_sports_basket, R.string.missa_chip_sports_basket),
    SPORTS_COMBAT("sports_combat", 35, R.string.missa_cat_sports_combat, R.string.missa_chip_sports_combat),
    SPORTS_MOTORS("sports_motors", 45, R.string.missa_cat_sports_motors, R.string.missa_chip_sports_motors),
    SPORTS_ALL("sports_all", 900, R.string.missa_cat_sports_all, R.string.missa_chip_sports_all),

    // Podcasts
    PODCASTS_SOCIETY("podcasts_society", 15, R.string.missa_cat_podcasts_society, R.string.missa_chip_podcasts_society),
    PODCASTS_CULTURE("podcasts_culture", 25, R.string.missa_cat_podcasts_culture, R.string.missa_chip_podcasts_culture),
    PODCASTS_ALL("podcasts_all", 900, R.string.missa_cat_podcasts_all, R.string.missa_chip_podcasts_all),

    // Radio
    RADIO_AFRICA("radio_africa", 12, R.string.missa_cat_radio_africa, R.string.missa_chip_radio_africa),
    RADIO_NEWS("radio_news", 22, R.string.missa_cat_radio_news, R.string.missa_chip_radio_news),
    RADIO_MUSIC("radio_music", 32, R.string.missa_cat_radio_music, R.string.missa_chip_radio_music),
    RADIO_ALL("radio_all", 900, R.string.missa_cat_radio_all, R.string.missa_chip_radio_all);

    companion object {
        fun fromId(id: String?): MissaCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Central provider-agnostic normalization, classification, deduplication and category engine
 * for MISSA TV 360 PLUS media universes.
 *
 * Pure Kotlin (no Android View dependencies) so all rules are deterministic and unit-tested.
 */
internal object MediaUniverseClassifier {
    val ALL_CONTENT_UNIVERSES = listOf(
        "films",
        "series",
        "shorts",
        "kids",
        "live_tv",
        "sports",
        "podcasts",
        "radio"
    )

    private val movieTypes = setOf(
        TvType.Movie, TvType.AnimeMovie, TvType.Documentary, TvType.Torrent, TvType.Video
    )
    private val seriesTypes = setOf(
        TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.OVA, TvType.AsianDrama
    )
    private val audioTypes = setOf(TvType.Audio, TvType.Music, TvType.AudioBook)

    private fun wordRegex(pattern: String): Regex = Regex(
        """(?<![\p{L}\p{N}])($pattern)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE
    )

    private val shortKeywords = wordRegex(
        """court[ -]?m[eé]trages?|short[ -]?films?|shortfilms?|shorts|films?\s+courts?|formats?\s+courts?"""
    )
    private val kidsKeywords = wordRegex(
        """enfants?|jeunesse|kids?|children|child|family|familles?|familial(?:e|es)?|tout\s+public|dessins?\s+anim[eé]s?|comptines?|disney|pixar|dreamworks"""
    )
    private val matureDisqualifiers = wordRegex(
        """horreur|horror|gore|slasher|thriller|[eé]rotique|erotic|adulte?s?|nsfw|xxx|porn|crime|narco|cartel|zombie|16\+|18\+|nc-17|tv-ma"""
    )
    private val matureRatingRegex = Regex(
        """(^|[^A-Z0-9])(R|NC-17|TV-MA|TV-14|16\+|18\+|ADULT)([^A-Z0-9]|$)""",
        RegexOption.IGNORE_CASE
    )
    private val sportsKeywords = wordRegex(
        """espn|bein(?:\s+sports?)?|sky\s+sports?|rmc\s+sport|canal\+?\s*sport|supersport|eurosport|dazn|sport\s*tv|sports?|football|soccer|ligue\s*1|ligue\s*2|premier\s+league|serie\s+a|bundesliga|laliga|la\s+liga|champions\s+league|europa\s+league|can\s+\d{4}|basket(?:ball)?|nba|tennis|roland[ -]garros|wimbledon|rugby|top\s*14|formula\s*1|formule\s*1|f1|motogp|ufc|mma|boxe|boxing|wrestling|wwe|matchs?|multiplex"""
    )
    private val radioKeywords = wordRegex(
        """radios?|webradios?|fm|am|mhz|stations?|broadcast|crtv|balafon|equinoxe|france\s+inter|france\s+info|france\s+culture|rfi|nrj|rtl|rmc\s+info|skyrock|nostalgie|fip|mouv[’']?|africa\s*n[°o]?\s*1|bbc\s+radio"""
    )
    private val podcastKeywords = wordRegex(
        """podcasts?|balados?|chroniques?\s+audio|[eé]missions?\s+audio|livres?\s+audio|audiobooks?"""
    )
    private val liveKeywords = wordRegex(
        """en\s+direct|live\s*tv|cha[iî]nes?\s+tv|iptv|direct\s+tv|24/7|tv\s+live"""
    )
    private val seriesHintKeywords = wordRegex(
        """s\d{1,2}\s*e\d{1,3}|saison\s*\d+|season\s*\d+|[eé]pisodes?\s*\d+|s[eé]ries?|animes?|dramas?|mini[ -]?s[eé]ries?"""
    )
    private val movieHintKeywords = wordRegex(
        """films?|movies?|cin[eé]ma|longs?[ -]m[eé]trages?|box[ -]?office|documentaires?"""
    )
    private val familyTags = wordRegex(
        """family|familles?|familial(?:e|es)?|children|child|kids?|jeunesse|enfants?|tout\s+public|dessins?\s+anim[eé]s?"""
    )
    private val safeRating = Regex(
        """(^|[^A-Z0-9])(TV-Y7?|TV-G|G|U|TP|TOUT\s*PUBLIC|0\+|3\+|6\+|7\+)([^A-Z0-9]|$)""",
        RegexOption.IGNORE_CASE
    )
    private val durationSuffix = Regex("""(\d{1,3})\s*(min|minutes|mn)\b""", RegexOption.IGNORE_CASE)

    private val noiseTagsRegex = Regex(
        """(\[[^\]]*]|\((?:19|20)\d{2}\)|\b(?:vf|vff|vfq|vostfr|vost|multi|french|truefrench|subbed|dubbed|hd|fhd|uhd|4k|2160p|1080p|720p|480p|webrip|bluray|bdrip|dvdrip|x264|x265|hevc)\b)""",
        RegexOption.IGNORE_CASE
    )
    private val nonAlphaNumRegex = Regex("""[^a-z0-9]+""")

    /** Checks if a title/metadata contains mature/adult cues (also used by TRUST-01 parental guard). */
    fun hasMatureSignals(title: String, tags: Collection<String> = emptyList(), contentRating: String? = null): Boolean {
        val text = "$title ${tags.joinToString(" ")}"
        return matureDisqualifiers.containsMatchIn(text) ||
            matureRatingRegex.containsMatchIn(contentRating.orEmpty())
    }

    fun normalizeTitleKey(title: String, universeGroup: String = ""): String {
        val decomposed = Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)
        val cleaned = decomposed
            .replace(noiseTagsRegex, " ")
            .replace(nonAlphaNumRegex, " ")
            .trim()
            .replace(Regex("\\s+"), " ")
        val core = cleaned.ifBlank { decomposed.trim() }
        return if (universeGroup.isBlank()) core else "$universeGroup::$core"
    }

    fun inferEffectiveType(
        rawType: TvType?,
        title: String,
        sectionName: String = "",
        providerSupportedTypes: Set<TvType> = emptySet()
    ): TvType? {
        if (rawType == TvType.NSFW || rawType == TvType.CustomMedia) return rawType
        val contextText = "$title $sectionName"

        if (rawType != null && rawType != TvType.Others) {
            return rawType
        }

        val cleanProviderTypes = providerSupportedTypes.filterNot {
            it == TvType.NSFW || it == TvType.CustomMedia || it == TvType.Others
        }.toSet()

        return when {
            podcastKeywords.containsMatchIn(contextText) -> TvType.Podcast
            radioKeywords.containsMatchIn(contextText) &&
                (cleanProviderTypes.isEmpty() || cleanProviderTypes.any { it == TvType.Live || it in audioTypes }) -> TvType.Audio
            liveKeywords.containsMatchIn(sectionName) ||
                (sportsKeywords.containsMatchIn(sectionName) && TvType.Live in cleanProviderTypes) -> TvType.Live
            seriesHintKeywords.containsMatchIn(contextText) -> TvType.TvSeries
            shortKeywords.containsMatchIn(contextText) -> TvType.Movie
            movieHintKeywords.containsMatchIn(sectionName) -> TvType.Movie
            cleanProviderTypes.size == 1 -> cleanProviderTypes.first()
            cleanProviderTypes.isNotEmpty() && cleanProviderTypes.all { it in movieTypes } -> TvType.Movie
            cleanProviderTypes.isNotEmpty() && cleanProviderTypes.all { it in seriesTypes } -> TvType.TvSeries
            cleanProviderTypes.isNotEmpty() && cleanProviderTypes.all { it == TvType.Live } -> TvType.Live
            cleanProviderTypes.isNotEmpty() && cleanProviderTypes.all { it == TvType.Podcast } -> TvType.Podcast
            cleanProviderTypes.isNotEmpty() && cleanProviderTypes.all { it in audioTypes } -> TvType.Audio
            else -> rawType
        }
    }

    fun shouldLoadDetails(
        universeId: String,
        type: TvType?,
        title: String,
        sectionName: String = ""
    ): Boolean {
        if (type == TvType.NSFW || type == TvType.CustomMedia) return false
        val effective = inferEffectiveType(type, title, sectionName)
        val canBeMovie = effective == null || effective == TvType.Others || effective in movieTypes
        val canBeSeries = effective == null || effective == TvType.Others || effective in seriesTypes
        val context = "$title $sectionName"
        return when (universeId) {
            "shorts" -> canBeMovie && (
                shortKeywords.containsMatchIn(context) ||
                    durationSuffix.containsMatchIn(title) ||
                    effective?.let { it in movieTypes } == true
                )
            "kids" -> (canBeMovie || canBeSeries) && !matureDisqualifiers.containsMatchIn(context)
            else -> false
        }
    }

    fun detailPriority(universeId: String, title: String, sectionName: String = ""): Int {
        val context = "$title $sectionName"
        return when (universeId) {
            "shorts" -> (if (shortKeywords.containsMatchIn(context)) 5 else 0) +
                (if (durationSuffix.containsMatchIn(title)) 3 else 0)
            "kids" -> (if (kidsKeywords.containsMatchIn(context)) 5 else 0)
            else -> 0
        }
    }

    fun matches(
        universeId: String,
        type: TvType?,
        title: String,
        tags: Collection<String> = emptyList(),
        contentRating: String? = null,
        durationMinutes: Int? = null,
        sectionName: String = "",
        providerSupportedTypes: Set<TvType> = emptySet()
    ): Boolean {
        if (type == TvType.NSFW || type == TvType.CustomMedia) return false

        val effectiveType = inferEffectiveType(type, title, sectionName, providerSupportedTypes)
        if (effectiveType == TvType.NSFW || effectiveType == TvType.CustomMedia) return false

        val detailText = buildString {
            if (sectionName.isNotBlank()) append(sectionName).append(' ')
            if (tags.isNotEmpty()) append(tags.joinToString(" "))
        }.trim()
        val classificationText = "$title $detailText"

        return when (universeId) {
            "home" -> true
            "films" -> effectiveType != null && effectiveType in movieTypes
            "series" -> effectiveType != null && effectiveType in seriesTypes
            "shorts" -> {
                val canBeMovie = effectiveType == null || effectiveType == TvType.Others || effectiveType in movieTypes
                val titleDuration = durationSuffix.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                canBeMovie && (
                    durationMinutes?.let { it in 1..40 } == true ||
                        shortKeywords.containsMatchIn(classificationText) ||
                        (durationMinutes == null && titleDuration != null && titleDuration in 1..40)
                    )
            }
            "kids" -> {
                val supportedType = effectiveType == null ||
                    effectiveType == TvType.Others ||
                    effectiveType in movieTypes ||
                    effectiveType in seriesTypes
                val isMature = matureDisqualifiers.containsMatchIn(classificationText) ||
                    matureRatingRegex.containsMatchIn(contentRating.orEmpty())
                supportedType && !isMature && (
                    effectiveType == TvType.Cartoon ||
                        familyTags.containsMatchIn(detailText) ||
                        safeRating.containsMatchIn(contentRating.orEmpty()) ||
                        kidsKeywords.containsMatchIn(title)
                    )
            }
            "live_tv" -> effectiveType == TvType.Live &&
                !sportsKeywords.containsMatchIn(classificationText) &&
                !radioKeywords.containsMatchIn(classificationText)
            "sports" -> effectiveType == TvType.Live &&
                sportsKeywords.containsMatchIn(classificationText)
            "podcasts" -> effectiveType == TvType.Podcast ||
                (effectiveType in audioTypes && podcastKeywords.containsMatchIn(classificationText))
            "radio" -> {
                val audioOrLive = effectiveType == null ||
                    effectiveType == TvType.Live ||
                    effectiveType in audioTypes
                audioOrLive &&
                    effectiveType != TvType.Music &&
                    effectiveType != TvType.Podcast &&
                    radioKeywords.containsMatchIn(classificationText) &&
                    !podcastKeywords.containsMatchIn(classificationText)
            }
            else -> false
        }
    }

    fun classifyAllUniverses(
        type: TvType?,
        title: String,
        tags: Collection<String> = emptyList(),
        contentRating: String? = null,
        durationMinutes: Int? = null,
        sectionName: String = "",
        providerSupportedTypes: Set<TvType> = emptySet()
    ): Set<String> {
        if (type == TvType.NSFW || type == TvType.CustomMedia) return emptySet()
        val matched = LinkedHashSet<String>()
        for (universe in ALL_CONTENT_UNIVERSES) {
            if (matches(
                    universeId = universe,
                    type = type,
                    title = title,
                    tags = tags,
                    contentRating = contentRating,
                    durationMinutes = durationMinutes,
                    sectionName = sectionName,
                    providerSupportedTypes = providerSupportedTypes
                )
            ) {
                matched.add(universe)
            }
        }
        return matched
    }

    /**
     * Maps normalized metadata and plugin section hints to stable [MissaCategory] enum values
     * (I18N-01, HOME-08, HOME-10) so category filtering and row ordering never depend on
     * translated display labels.
     */
    fun categorizeForUniverse(
        universeId: String,
        title: String,
        sectionName: String = "",
        tags: Collection<String> = emptyList(),
        effectiveType: TvType? = null,
        durationMinutes: Int? = null
    ): List<MissaCategory> {
        val text = "$title $sectionName ${tags.joinToString(" ")}".lowercase(Locale.ROOT)
        val categories = LinkedHashSet<MissaCategory>()

        val isTrending = containsAny(
            text, "tendance", "trending", "populaire", "popular", "top",
            "box office", "box-office", "incontournable", "featured", "vedette"
        )
        val isRecent = containsAny(
            text, "nouveau", "nouveaut", "recent", "récent", "latest", "2026", "2025", "ajout", "sortie"
        )

        when (universeId) {
            "home" -> {
                if (isTrending || isRecent) categories.add(MissaCategory.HOME_TRENDING)
                if (effectiveType in movieTypes) categories.add(MissaCategory.HOME_MOVIES)
                if (effectiveType in seriesTypes) categories.add(MissaCategory.HOME_SERIES)
                if (containsAny(text, "action", "aventure", "adventure", "guerre", "war", "western")) {
                    categories.add(MissaCategory.GENRE_ACTION)
                }
                if (containsAny(text, "comédie", "comedie", "comedy", "humour")) {
                    categories.add(MissaCategory.GENRE_COMEDY)
                }
                if (containsAny(text, "science-fiction", "sci-fi", "fantastique", "fantasy")) {
                    categories.add(MissaCategory.GENRE_SCIFI)
                }
                if (containsAny(text, "drame", "drama", "romance", "historique", "biopic")) {
                    categories.add(MissaCategory.GENRE_DRAMA)
                }
                if (effectiveType == TvType.Cartoon || containsAny(text, "famille", "family", "jeunesse", "kids", "enfant")) {
                    categories.add(MissaCategory.HOME_FAMILY)
                }
                if (effectiveType == TvType.Documentary || containsAny(text, "documentaire", "documentary")) {
                    categories.add(MissaCategory.GENRE_DOCUMENTARY)
                }
                if (effectiveType == TvType.Live) {
                    categories.add(MissaCategory.HOME_LIVE)
                }
                if (effectiveType == TvType.Podcast || effectiveType in audioTypes) {
                    categories.add(MissaCategory.HOME_AUDIO)
                }
                if (categories.isEmpty()) categories.add(MissaCategory.HOME_SELECTION)
            }

            "films" -> {
                if (isRecent || isTrending) categories.add(MissaCategory.FILMS_FEATURED)
                if (containsAny(text, "action", "aventure", "adventure")) categories.add(MissaCategory.GENRE_ACTION)
                if (containsAny(text, "comédie", "comedie", "comedy")) categories.add(MissaCategory.GENRE_COMEDY)
                if (containsAny(text, "drame", "drama", "romance")) categories.add(MissaCategory.GENRE_DRAMA)
                if (containsAny(text, "thriller", "policier", "crime", "suspense", "mystère", "mystery")) {
                    categories.add(MissaCategory.GENRE_THRILLER)
                }
                if (containsAny(text, "science-fiction", "sci-fi", "fantastique", "fantasy")) {
                    categories.add(MissaCategory.GENRE_SCIFI)
                }
                if (effectiveType == TvType.AnimeMovie || containsAny(text, "animation", "anime")) {
                    categories.add(MissaCategory.GENRE_ANIMATION)
                }
                if (effectiveType == TvType.Documentary || containsAny(text, "documentaire", "documentary")) {
                    categories.add(MissaCategory.GENRE_DOCUMENTARY)
                }
                if (durationMinutes != null && durationMinutes in 1..40) categories.add(MissaCategory.FILMS_SHORTS)
                if (categories.isEmpty()) categories.add(MissaCategory.FILMS_CATALOG)
            }

            "series" -> {
                if (isRecent || isTrending) categories.add(MissaCategory.SERIES_TRENDING)
                if (effectiveType == TvType.Anime || effectiveType == TvType.OVA || containsAny(text, "anime", "animé", "animation")) {
                    categories.add(MissaCategory.GENRE_ANIMATION)
                }
                if (effectiveType == TvType.AsianDrama || containsAny(text, "k-drama", "kdrama", "drama", "drame")) {
                    categories.add(MissaCategory.SERIES_INTL)
                }
                if (containsAny(text, "action", "aventure", "adventure", "science-fiction", "sci-fi", "fantastique", "fantasy")) {
                    categories.add(MissaCategory.GENRE_ACTION)
                }
                if (containsAny(text, "comédie", "comedie", "comedy")) {
                    categories.add(MissaCategory.GENRE_COMEDY)
                }
                if (containsAny(text, "thriller", "policier", "crime", "enquête", "mystère", "mystery")) {
                    categories.add(MissaCategory.GENRE_THRILLER)
                }
                if (categories.isEmpty()) categories.add(MissaCategory.SERIES_CATALOG)
            }

            "shorts" -> {
                if (durationMinutes != null && durationMinutes <= 15) categories.add(MissaCategory.SHORTS_MICRO)
                if (containsAny(text, "animation", "animé", "cartoon")) categories.add(MissaCategory.SHORTS_ANIMATION)
                if (effectiveType == TvType.Documentary || containsAny(text, "documentaire", "docu")) {
                    categories.add(MissaCategory.SHORTS_DOCU)
                }
                categories.add(MissaCategory.SHORTS_ALL)
            }

            "kids" -> {
                if (effectiveType in seriesTypes || effectiveType == TvType.Cartoon) categories.add(MissaCategory.KIDS_CARTOONS)
                if (effectiveType in movieTypes) categories.add(MissaCategory.KIDS_MOVIES)
                if (containsAny(text, "aventure", "magie", "conte", "disney", "pixar")) categories.add(MissaCategory.KIDS_MAGIC)
                if (categories.isEmpty()) categories.add(MissaCategory.KIDS_ALL)
            }

            "live_tv" -> {
                if (containsAny(text, "info", "news", "actualit", "24", "bfm", "franceinfo", "cnews", "lci", "euronews", "africanews", "crtv", "equinoxe", "canal 2")) {
                    categories.add(MissaCategory.LIVE_NEWS)
                }
                if (containsAny(text, "ciné", "cine", "film", "movie", "série", "serie")) {
                    categories.add(MissaCategory.LIVE_CINEMA)
                }
                if (containsAny(text, "doc", "découverte", "decouverte", "histoire", "science", "nature", "arte", "culture")) {
                    categories.add(MissaCategory.LIVE_CULTURE)
                }
                if (containsAny(text, "musique", "music", "hit", "trace", "mtv", "nrj hits")) {
                    categories.add(MissaCategory.LIVE_MUSIC)
                }
                categories.add(MissaCategory.LIVE_ALL)
            }

            "sports" -> {
                if (containsAny(text, "foot", "soccer", "ligue", "champions", "premier league", "laliga", "serie a", "bundesliga", "can ")) {
                    categories.add(MissaCategory.SPORTS_FOOTBALL)
                }
                if (containsAny(text, "basket", "nba")) {
                    categories.add(MissaCategory.SPORTS_BASKET)
                }
                if (containsAny(text, "ufc", "mma", "boxe", "boxing", "wwe", "combat")) {
                    categories.add(MissaCategory.SPORTS_COMBAT)
                }
                if (containsAny(text, "f1", "formula", "formule", "motogp", "auto", "tennis", "rugby")) {
                    categories.add(MissaCategory.SPORTS_MOTORS)
                }
                categories.add(MissaCategory.SPORTS_ALL)
            }

            "podcasts" -> {
                if (containsAny(text, "actu", "info", "société", "societe", "débat", "histoire", "politique")) {
                    categories.add(MissaCategory.PODCASTS_SOCIETY)
                }
                if (containsAny(text, "culture", "cinéma", "science", "tech", "humour", "comédie")) {
                    categories.add(MissaCategory.PODCASTS_CULTURE)
                }
                categories.add(MissaCategory.PODCASTS_ALL)
            }

            "radio" -> {
                if (containsAny(text, "afrique", "africa", "cameroun", "cameroon", "douala", "yaound", "crtv", "balafon", "equinoxe", "cm ", " af")) {
                    categories.add(MissaCategory.RADIO_AFRICA)
                }
                if (containsAny(text, "info", "actu", "inter", "rfi", "rtl", "rmc", "bbc")) {
                    categories.add(MissaCategory.RADIO_NEWS)
                }
                if (containsAny(text, "musique", "music", "hit", "jazz", "classique", "groove", "afro", "makossa", "bikutsi", "fip", "nrj", "skyrock", "mouv", "nostalgie")) {
                    categories.add(MissaCategory.RADIO_MUSIC)
                }
                categories.add(MissaCategory.RADIO_ALL)
            }
        }

        return categories.sortedBy { it.editorialRank }
    }

    private fun containsAny(haystack: String, vararg needles: String): Boolean =
        needles.any { haystack.contains(it) }
}
