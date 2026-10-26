package com.lagradost.cloudstream3.ui.universe

import com.lagradost.cloudstream3.TvType

/**
 * Provider-agnostic rules for assigning results to MISSA TV 360 media universes.
 * Keep the input fields generic so these rules can be unit-tested without Android views or plugins.
 */
internal object MediaUniverseClassifier {
    private val movieTypes = setOf(
        TvType.Movie, TvType.AnimeMovie, TvType.Documentary, TvType.Torrent, TvType.Video
    )
    private val seriesTypes = setOf(
        TvType.TvSeries, TvType.Anime, TvType.Cartoon, TvType.OVA, TvType.AsianDrama
    )
    private val audioTypes = setOf(TvType.Audio, TvType.Music, TvType.AudioBook)

    private val shortKeywords = Regex(
        "(court[ -]?m[eé]trage|short[ -]?film|shortfilm|shorts|film court)",
        RegexOption.IGNORE_CASE
    )
    private val kidsKeywords = Regex(
        "(enfant|jeunesse|kids|children|family|familial|tout public)",
        RegexOption.IGNORE_CASE
    )
    private val sportsKeywords = Regex(
        "(sport|football|soccer|basket|basketball|tennis|rugby|formula ?1|f1|match|league|champions|nba|ufc|live sport)",
        RegexOption.IGNORE_CASE
    )
    private val radioKeywords = Regex(
        "(radio|fm\\b|am\\b|station|broadcast|webradio|radio stream)",
        RegexOption.IGNORE_CASE
    )
    private val familyTags = Regex(
        "(family|familial|children|child|kids|jeunesse|enfant|tout public)",
        RegexOption.IGNORE_CASE
    )
    private val safeRating = Regex(
        "(^|[^A-Z0-9])(TV-Y7?|TV-G|G|0\\+|3\\+|6\\+|7\\+)([^A-Z0-9]|$)",
        RegexOption.IGNORE_CASE
    )
    private val durationSuffix = Regex("(\\d{1,3})\\s*(min|minutes|mn)\\b", RegexOption.IGNORE_CASE)

    fun shouldLoadDetails(universeId: String, type: TvType?, title: String): Boolean {
        if (type == TvType.NSFW || type == TvType.CustomMedia) return false
        val canBeMovie = type == null || type in movieTypes
        val canBeSeries = type == null || type in seriesTypes
        return when (universeId) {
            "shorts" -> canBeMovie && (
                shortKeywords.containsMatchIn(title) ||
                    durationSuffix.containsMatchIn(title) ||
                    type?.let { it in movieTypes } == true
                )
            "kids" -> canBeMovie || canBeSeries
            else -> false
        }
    }

    fun matches(
        universeId: String,
        type: TvType?,
        title: String,
        tags: Collection<String> = emptyList(),
        contentRating: String? = null,
        durationMinutes: Int? = null
    ): Boolean {
        if (type == TvType.NSFW || type == TvType.CustomMedia) return false

        val detailText = tags.joinToString(" ")
        val classificationText = "$title $detailText"
        return when (universeId) {
            "home" -> true
            "films" -> type != null && type in movieTypes
            "series" -> type != null && type in seriesTypes
            "shorts" -> {
                val canBeMovie = type == null || type in movieTypes
                val titleDuration = durationSuffix.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                canBeMovie && (
                    durationMinutes?.let { it in 1..40 } == true ||
                        shortKeywords.containsMatchIn(classificationText) ||
                        (durationMinutes == null && titleDuration != null && titleDuration in 1..40)
                    )
            }
            "kids" -> {
                val supportedType = type == null || type in movieTypes || type in seriesTypes
                supportedType && (
                    familyTags.containsMatchIn(detailText) ||
                        safeRating.containsMatchIn(contentRating.orEmpty()) ||
                        kidsKeywords.containsMatchIn(title)
                    )
            }
            "live_tv" -> type == TvType.Live &&
                !sportsKeywords.containsMatchIn(classificationText) &&
                !radioKeywords.containsMatchIn(classificationText)
            "sports" -> type == TvType.Live && sportsKeywords.containsMatchIn(classificationText)
            "podcasts" -> type == TvType.Podcast
            "radio" -> {
                val audioOrLive = type == null || type?.let { it == TvType.Live || it in audioTypes } == true
                audioOrLive && radioKeywords.containsMatchIn(classificationText) && type != TvType.Podcast
            }
            else -> false
        }
    }
}
