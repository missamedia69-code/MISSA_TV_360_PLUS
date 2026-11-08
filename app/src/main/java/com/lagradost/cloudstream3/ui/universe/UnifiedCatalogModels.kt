package com.lagradost.cloudstream3.ui.universe

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.StringRes
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType

const val DIRECT_RADIO_API_NAME = "MISSA Radio Direct"
const val DIRECT_MUSIC_API_NAME = "YouTube"
const val DIRECT_PODCAST_API_NAME = "MISSA Podcasts"

enum class MusicItemKind {
    CLIP,
    SONG,
    ALBUM,
    PLAYLIST,
    /**
     * Un épisode de podcast. Il passe par le lecteur audio de Musiques (PODCASTS-03) mais il
     * n'est ni un clip ni une chanson : l'interface l'affiche avec son émission, pas avec un
     * artiste.
     */
    PODCAST
}

data class DirectRadioSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String = DIRECT_RADIO_API_NAME,
    override var type: TvType? = TvType.Audio,
    override var posterUrl: String? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var id: Int? = url.hashCode(),
    override var quality: SearchQuality? = null,
    override var score: Score? = null,
    val genre: String = "",
    val country: String = "",
    val logoUrl: String? = null
) : SearchResponse

data class DirectMusicSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String = DIRECT_MUSIC_API_NAME,
    override var type: TvType? = TvType.Music,
    override var posterUrl: String? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var id: Int? = url.hashCode(),
    override var quality: SearchQuality? = null,
    override var score: Score? = null,
    val trackId: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val durationSeconds: Int = 0,
    val artworkUrl: String? = null,
    val fallbackUrls: List<String> = emptyList(),
    val isPipedLazy: Boolean = true,
    val itemKind: MusicItemKind = MusicItemKind.CLIP,
    val trackCount: Int = 0
) : SearchResponse {
    fun formattedDuration(): String? {
        return when (itemKind) {
            MusicItemKind.ALBUM -> if (trackCount > 0) "ALBUM • $trackCount" else "ALBUM"
            MusicItemKind.PLAYLIST -> if (trackCount > 0) "PLAYLIST • $trackCount" else "PLAYLIST"
            MusicItemKind.PODCAST -> if (durationSeconds > 0) {
                "%02d:%02d".format((durationSeconds % 3600) / 60, durationSeconds % 3600 % 60)
            } else {
                "PODCAST"
            }
            MusicItemKind.CLIP, MusicItemKind.SONG -> {
                if (durationSeconds <= 0) return if (itemKind == MusicItemKind.CLIP) "CLIP" else null
                val hours = durationSeconds / 3600
                val mins = (durationSeconds % 3600) / 60
                val secs = durationSeconds % 3600 % 60
                if (hours > 0) {
                    "%d:%02d:%02d".format(hours, mins, secs)
                } else {
                    "%02d:%02d".format(mins, secs)
                }
            }
        }
    }
}

/**
 * A single YouTube Kids **video** (cartoon, nursery rhyme, family movie, tale…).
 *
 * KIDS-02: unlike music, Kids content is video-first — it opens in the standard video player
 * (GeneratorPlayer), never in the Musiques audio player. It is deliberately NOT a
 * [DirectMusicSearchResponse] so no existing audio path can claim the click.
 */
data class DirectKidsSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String = DIRECT_KIDS_API_NAME,
    override var type: TvType? = TvType.Cartoon,
    override var posterUrl: String? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var id: Int? = url.hashCode(),
    override var quality: SearchQuality? = null,
    override var score: Score? = null,
    val videoId: String = "",
    val channel: String = "",
    val categoryId: String = "",
    val durationSeconds: Int = 0,
    val artworkUrl: String? = null
) : SearchResponse {
    fun formattedDuration(): String? {
        if (durationSeconds <= 0) return null
        val hours = durationSeconds / 3600
        val mins = (durationSeconds % 3600) / 60
        val secs = durationSeconds % 3600 % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, mins, secs)
        } else {
            "%02d:%02d".format(mins, secs)
        }
    }
}

const val PODCAST_SHOW_URL_PREFIX = "missapodcast://show/"
const val PODCAST_EPISODE_URL_PREFIX = "missapodcast://episode/"

/**
 * Ce qu'est la carte : une **émission**, dans laquelle on entre pour voir ses épisodes, ou un
 * **épisode** prêt à être écouté.
 *
 * PODCASTS-01 : l'univers Podcasts est présenté par émission, comme Musiques présente des
 * albums, et non comme une longue liste d'épisodes toutes émissions confondues.
 */
enum class PodcastItemKind {
    SHOW,
    EPISODE
}

/**
 * Origine éditoriale de l'émission. Elle règle l'ordre des rangées : l'Afrique d'abord
 * (PODCASTS-02), ensuite le reste de la francophonie, ensuite le monde.
 */
enum class PodcastRegion(val order: Int) {
    AFRICA(0),
    FRANCOPHONE(1),
    WORLD(2)
}

/**
 * Une **émission** de podcast : une vignette de l'univers Podcasts. Ses épisodes ne sont pas
 * dedans, ils vivent dans le flux RSS pointé par [feedUrl] et ne sont téléchargés qu'à
 * l'ouverture de l'émission.
 */
data class PodcastShowPreset(
    val id: String,
    val title: String,
    val author: String = "",
    val description: String = "",
    val artworkUrl: String? = null,
    val feedUrl: String = "",
    val genre: String = "",
    val region: PodcastRegion = PodcastRegion.WORLD,
    val episodeCount: Int = 0,
    val providerName: String = DIRECT_PODCAST_API_NAME
) {
    val isAfrica: Boolean get() = region == PodcastRegion.AFRICA

    fun toSearchResponse(): DirectPodcastSearchResponse = DirectPodcastSearchResponse(
        name = title,
        url = PODCAST_SHOW_URL_PREFIX + id,
        posterUrl = artworkUrl,
        showTitle = title,
        author = author,
        artworkUrl = artworkUrl,
        feedUrl = feedUrl,
        episodeCount = episodeCount,
        itemKind = PodcastItemKind.SHOW
    )
}

/**
 * Un **épisode** d'une émission. [audioUrl] est un fichier audio direct (mp3, m4a…) : il n'y a
 * rien à extraire ni à transmuxer, contrairement aux pistes YouTube de l'univers Musiques.
 */
data class PodcastEpisode(
    val id: String,
    val showId: String = "",
    val showTitle: String = "",
    val author: String = "",
    val title: String = "",
    val audioUrl: String = "",
    val artworkUrl: String? = null,
    val durationSeconds: Int = 0,
    val publishedAtMillis: Long = 0L,
    val description: String = "",
    val region: PodcastRegion = PodcastRegion.WORLD,
    val providerName: String = DIRECT_PODCAST_API_NAME
) {
    fun toSearchResponse(): DirectPodcastSearchResponse = DirectPodcastSearchResponse(
        name = title.ifBlank { showTitle },
        url = audioUrl,
        posterUrl = artworkUrl,
        showTitle = showTitle,
        episodeTitle = title,
        author = author.ifBlank { showTitle },
        artworkUrl = artworkUrl,
        durationSeconds = durationSeconds,
        publishedAtMillis = publishedAtMillis,
        description = description,
        itemKind = PodcastItemKind.EPISODE
    )

    /**
     * PODCASTS-03 : l'épisode part dans le lecteur audio de Musiques, mais il s'y affiche
     * comme un podcast — l'émission en guise d'artiste, et plus tard la reprise de lecture.
     */
    fun toMusicTrackPreset(): MusicTrackPreset = MusicTrackPreset(
        id = id.ifBlank { audioUrl },
        title = title.ifBlank { showTitle },
        artist = author.ifBlank { showTitle },
        album = showTitle,
        genre = "Podcast",
        streamUrl = audioUrl,
        artworkUrl = artworkUrl,
        durationSeconds = durationSeconds,
        providerName = providerName,
        isPipedLazy = false,
        itemKind = MusicItemKind.PODCAST
    )
}

/**
 * Une carte de l'univers Podcasts. C'est la seule réponse de recherche que cet univers produit :
 * elle n'est ni une [DirectMusicSearchResponse] ni une [DirectRadioSearchResponse], pour qu'aucun
 * chemin audio existant ne puisse la revendiquer par erreur.
 */
data class DirectPodcastSearchResponse(
    override val name: String,
    override val url: String,
    override val apiName: String = DIRECT_PODCAST_API_NAME,
    override var type: TvType? = TvType.Podcast,
    override var posterUrl: String? = null,
    override var posterHeaders: Map<String, String>? = null,
    override var id: Int? = url.hashCode(),
    override var quality: SearchQuality? = null,
    override var score: Score? = null,
    val showTitle: String = "",
    val episodeTitle: String = "",
    val author: String = "",
    val artworkUrl: String? = null,
    val durationSeconds: Int = 0,
    val publishedAtMillis: Long = 0L,
    val description: String = "",
    val feedUrl: String = "",
    val episodeCount: Int = 0,
    val itemKind: PodcastItemKind = PodcastItemKind.EPISODE
) : SearchResponse {
    val isShow: Boolean get() = itemKind == PodcastItemKind.SHOW

    fun formattedDuration(): String? {
        if (isShow) {
            return if (episodeCount > 0) "ÉMISSION • $episodeCount" else "ÉMISSION"
        }
        if (durationSeconds <= 0) return null
        val hours = durationSeconds / 3600
        val mins = (durationSeconds % 3600) / 60
        val secs = durationSeconds % 3600 % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, mins, secs)
        } else {
            "%02d:%02d".format(mins, secs)
        }
    }
}

/**
 * Navigation universe descriptor.
 * [familyGroup] groups related universes visually in the top strip (NAV-01):
 * 0 = Home, 1 = Cinema/VOD, 2 = Live Video, 3 = Audio, 4 = Retro Games.
 */
data class UniverseDefinition(
    val id: String,
    @IdRes val destinationId: Int,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val cardSubtitleRes: Int,
    @DrawableRes val iconRes: Int,
    @StringRes val eyebrowRes: Int,
    val familyGroup: Int,
    val isHorizontalCards: Boolean = false,
    val directPlayOnClick: Boolean = false,
    /** AUDIT H5: compact label for the top strip only. The full [titleRes] stays on the
     *  cards and page titles, where there is room for it. Optional fields stay last so a
     *  positional construction can never shift the required ones. */
    @StringRes val shortTitleRes: Int? = null
)

object MissaUniverses {
    val HOME = UniverseDefinition(
        id = "home",
        destinationId = R.id.navigation_home,
        titleRes = R.string.universe_home_title,
        descriptionRes = R.string.universe_home_description,
        cardSubtitleRes = R.string.universe_home_catalogue,
        iconRes = R.drawable.home_icon_filled_24,
        eyebrowRes = R.string.universe_eyebrow_home,
        familyGroup = 0
    )

    val FILMS = UniverseDefinition(
        id = "films",
        destinationId = R.id.navigation_universe_films,
        titleRes = R.string.universe_films_title,
        shortTitleRes = R.string.universe_films_title_short,
        descriptionRes = R.string.universe_films_description,
        cardSubtitleRes = R.string.universe_card_films_sub,
        iconRes = R.drawable.baseline_theaters_24,
        eyebrowRes = R.string.universe_eyebrow_films,
        familyGroup = 1
    )

    val SERIES = UniverseDefinition(
        id = "series",
        destinationId = R.id.navigation_universe_series,
        titleRes = R.string.universe_series_title,
        descriptionRes = R.string.universe_series_description,
        cardSubtitleRes = R.string.universe_card_series_sub,
        iconRes = R.drawable.ic_baseline_ondemand_video_24,
        eyebrowRes = R.string.universe_eyebrow_series,
        familyGroup = 1
    )

    val DOCUMENTARIES = UniverseDefinition(
        id = "documentaries",
        destinationId = R.id.navigation_universe_documentaries,
        titleRes = R.string.universe_documentaries_title,
        shortTitleRes = R.string.universe_documentaries_title_short,
        descriptionRes = R.string.universe_documentaries_description,
        cardSubtitleRes = R.string.universe_card_documentaries_sub,
        iconRes = R.drawable.ic_missa_documentaries,
        eyebrowRes = R.string.universe_eyebrow_documentaries,
        familyGroup = 1
    )

    val SHORTS = UniverseDefinition(
        id = "shorts",
        destinationId = R.id.navigation_universe_shorts,
        titleRes = R.string.universe_shorts_title,
        descriptionRes = R.string.universe_shorts_description,
        cardSubtitleRes = R.string.universe_card_shorts_sub,
        iconRes = R.drawable.ic_missa_shorts,
        eyebrowRes = R.string.universe_eyebrow_shorts,
        familyGroup = 1
    )

    val KIDS = UniverseDefinition(
        id = "kids",
        destinationId = R.id.navigation_universe_kids,
        titleRes = R.string.universe_kids_title,
        descriptionRes = R.string.universe_kids_description,
        cardSubtitleRes = R.string.universe_card_kids_sub,
        iconRes = R.drawable.kid_star_24px,
        eyebrowRes = R.string.universe_eyebrow_kids,
        familyGroup = 1
    )

    val LIVE_TV = UniverseDefinition(
        id = "live_tv",
        destinationId = R.id.navigation_universe_live_tv,
        titleRes = R.string.universe_live_tv_title,
        descriptionRes = R.string.universe_live_tv_description,
        cardSubtitleRes = R.string.universe_card_live_tv_sub,
        iconRes = R.drawable.ic_baseline_tv_24,
        eyebrowRes = R.string.universe_eyebrow_live_tv,
        familyGroup = 2,
        isHorizontalCards = true,
        directPlayOnClick = true
    )

    val SPORTS = UniverseDefinition(
        id = "sports",
        destinationId = R.id.navigation_universe_sports,
        titleRes = R.string.universe_sports_title,
        descriptionRes = R.string.universe_sports_description,
        cardSubtitleRes = R.string.universe_card_sports_sub,
        iconRes = R.drawable.ic_missa_sports,
        eyebrowRes = R.string.universe_eyebrow_sports,
        familyGroup = 2,
        isHorizontalCards = true,
        directPlayOnClick = true
    )

    val MUSIC = UniverseDefinition(
        id = "music",
        destinationId = R.id.navigation_universe_music,
        titleRes = R.string.universe_music_title,
        descriptionRes = R.string.universe_music_description,
        cardSubtitleRes = R.string.universe_card_music_sub,
        iconRes = R.drawable.ic_missa_music,
        eyebrowRes = R.string.universe_eyebrow_music,
        familyGroup = 3,
        directPlayOnClick = true
    )

    val PODCASTS = UniverseDefinition(
        id = "podcasts",
        destinationId = R.id.navigation_universe_podcasts,
        titleRes = R.string.universe_podcasts_title,
        descriptionRes = R.string.universe_podcasts_description,
        cardSubtitleRes = R.string.universe_card_podcasts_sub,
        iconRes = R.drawable.ic_missa_podcasts,
        eyebrowRes = R.string.universe_eyebrow_podcasts,
        familyGroup = 3
    )

    val RADIO = UniverseDefinition(
        id = "radio",
        destinationId = R.id.navigation_universe_radio,
        titleRes = R.string.universe_radio_title,
        descriptionRes = R.string.universe_radio_description,
        cardSubtitleRes = R.string.universe_card_radio_sub,
        iconRes = R.drawable.ic_missa_radio,
        eyebrowRes = R.string.universe_eyebrow_radio,
        familyGroup = 3,
        isHorizontalCards = true,
        directPlayOnClick = true
    )

    val RETRO_GAMES = UniverseDefinition(
        id = "retro_games",
        destinationId = R.id.navigation_universe_retro_games,
        titleRes = R.string.universe_games_title,
        shortTitleRes = R.string.universe_games_title_short,
        descriptionRes = R.string.universe_games_description,
        cardSubtitleRes = R.string.universe_card_games_sub,
        iconRes = R.drawable.ic_missa_gamepad,
        eyebrowRes = R.string.universe_eyebrow_games,
        familyGroup = 4
    )

    /** Primary navigation bar order (Home + 11 content universes). */
    val NAVIGATION_UNIVERSES = listOf(
        HOME, FILMS, SERIES, DOCUMENTARIES, SHORTS, KIDS, LIVE_TV, SPORTS, MUSIC, PODCASTS, RADIO, RETRO_GAMES
    )

    /** Hub shortcut cards displayed on Home (the 11 dedicated universes). */
    val HUB_SHORTCUTS = listOf(
        FILMS, SERIES, DOCUMENTARIES, SHORTS, KIDS, LIVE_TV, SPORTS, MUSIC, PODCASTS, RADIO, RETRO_GAMES
    )

    fun byId(universeId: String): UniverseDefinition =
        NAVIGATION_UNIVERSES.firstOrNull { it.id == universeId } ?: HOME
}

data class UnifiedCatalogEntry(
    val dedupKey: String,
    val primary: SearchResponse,
    val sources: List<SearchResponse>,
    val effectiveType: TvType?,
    val sectionHints: Set<String>,
    val isHorizontalHint: Boolean,
    val universes: Set<String>,
    val details: LoadResponse? = null,
    val cachedTags: List<String> = emptyList(),
    val cachedContentRating: String? = null,
    val cachedDuration: Int? = null
)

data class HeroBannerSlide(
    val card: SearchResponse,
    val details: LoadResponse?,
    val allSources: List<SearchResponse>,
    val eyebrow: String,
    val plot: String?,
    val yearText: String?,
    val durationText: String?,
    val scoreText: String?,
    val tags: List<String>,
    val isDirectStream: Boolean = false
)

/**
 * Short filter chip model decoupled from row display titles (I18N-01, HOME-08).
 */
data class CategoryFilterChip(
    val key: String,
    val shortLabel: String,
    val fullTitle: String
)

data class UniverseCategoryRow(
    val id: String,
    val title: String,
    val items: List<SearchResponse>,
    val isHorizontalCards: Boolean = false,
    val editorialRank: Int = 500,
    val renderAsGridInSearch: Boolean = true
)

data class UniverseHubCardItem(
    val definition: UniverseDefinition,
    val itemCount: Int = 0,
    val countBadge: String? = null,
    val hasLiveNow: Boolean = false
)

/**
 * Distinct empty / error states for HOME-06 (never shows a plugin-missing card on universe pages).
 */
enum class UniverseEmptyReason {
    EMPTY_UNIVERSE,
    OFFLINE_OR_ERROR,
    EMPTY_SEARCH_OR_FILTER
}

data class RadioStationPreset(
    val id: String,
    val name: String,
    val genre: String,
    val country: String,
    val streamUrl: String,
    val isCustom: Boolean = false,
    val logoUrl: String? = null,
    val providerName: String = DIRECT_RADIO_API_NAME,
    val fallbackUrls: List<String> = emptyList()
) {
    fun toSearchResponse(): DirectRadioSearchResponse {
        val resolvedLogo = logoUrl ?: streamUrl.toRadioLogoFallback()
        return DirectRadioSearchResponse(
            name = name,
            url = streamUrl,
            apiName = providerName.ifBlank { DIRECT_RADIO_API_NAME },
            posterUrl = resolvedLogo,
            genre = genre,
            country = country,
            logoUrl = resolvedLogo
        )
    }
}

private val genericStreamingCdnHosts = listOf(
    "infomaniak.ch",
    "radioking.com",
    "zeno.fm",
    "zenomedia.com",
    "caster.fm",
    "listen2myradio",
    "streamguys",
    "securenetsystems",
    "radionomy",
    "shoutca.st",
    "myradiostream"
)

/** Uses a station-provided logo first; maps broadcaster CDNs cleanly and skips generic hosting CDNs. */
private fun String.toRadioLogoFallback(): String? {
    val rawHost = runCatching { java.net.URI(this).host }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: runCatching { Uri.parse(this).host }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: return null
    val lower = rawHost.lowercase()
    if (genericStreamingCdnHosts.any { lower.contains(it) }) return null
    val brandHost = when {
        lower.endsWith("radiofrance.fr") -> "radiofrance.fr"
        lower.contains("bbcmedia.co.uk") -> "bbc.com"
        lower.endsWith("somafm.com") -> "somafm.com"
        lower.endsWith("laut.fm") -> "laut.fm"
        else -> rawHost
    }
    val encoded = runCatching { java.net.URLEncoder.encode(brandHost, "UTF-8") }.getOrDefault(brandHost)
    return "https://www.google.com/s2/favicons?domain=$encoded&sz=128"
}

data class MusicTrackPreset(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val streamUrl: String,
    val artworkUrl: String? = null,
    val durationSeconds: Int = 0,
    val providerName: String = DIRECT_MUSIC_API_NAME,
    val fallbackUrls: List<String> = emptyList(),
    val isCustom: Boolean = false,
    val isPipedLazy: Boolean = true,
    val isTrending: Boolean = false,
    val itemKind: MusicItemKind = MusicItemKind.CLIP,
    val trackCount: Int = 0,
    val recencyScore: Int = 0,
    val viewCount: Long = 0L
) {
    fun isAlbumOrPlaylist(): Boolean =
        itemKind == MusicItemKind.ALBUM ||
            itemKind == MusicItemKind.PLAYLIST ||
            streamUrl.startsWith("ytplaylist://", ignoreCase = true)

    fun displayLabel(): String {
        val cleanArtist = artist.trim()
        val cleanTitle = title.trim()
        return if (cleanArtist.isNotEmpty() && !cleanTitle.contains(cleanArtist, ignoreCase = true)) {
            "$cleanArtist — $cleanTitle"
        } else {
            cleanTitle
        }
    }

    fun toSearchResponse(): DirectMusicSearchResponse =
        DirectMusicSearchResponse(
            name = displayLabel(),
            url = streamUrl,
            apiName = providerName.ifBlank { DIRECT_MUSIC_API_NAME },
            posterUrl = artworkUrl,
            trackId = id,
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            durationSeconds = durationSeconds,
            artworkUrl = artworkUrl,
            fallbackUrls = fallbackUrls,
            isPipedLazy = isPipedLazy,
            itemKind = itemKind,
            trackCount = trackCount
        )
}
