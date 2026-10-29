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
    val directPlayOnClick: Boolean = false
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
        familyGroup = 3
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
        directPlayOnClick = true
    )

    val RETRO_GAMES = UniverseDefinition(
        id = "retro_games",
        destinationId = R.id.navigation_universe_retro_games,
        titleRes = R.string.universe_games_title,
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
    val editorialRank: Int = 500
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
    val logoUrl: String? = null
) {
    fun toSearchResponse(): DirectRadioSearchResponse = DirectRadioSearchResponse(
        name = name,
        url = streamUrl,
        posterUrl = logoUrl ?: streamUrl.toRadioLogoFallback(),
        genre = genre,
        country = country,
        logoUrl = logoUrl ?: streamUrl.toRadioLogoFallback()
    )
}

/** Uses a station-provided logo first; a host favicon is a graceful fallback for older presets. */
private fun String.toRadioLogoFallback(): String? {
    val host = runCatching { Uri.parse(this).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    return "https://www.google.com/s2/favicons?domain=${Uri.encode(host)}&sz=128"
}
