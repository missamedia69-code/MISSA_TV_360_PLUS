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

    // Films & Manga
    FILMS_FEATURED("films_featured", 10, R.string.missa_cat_films_featured, R.string.missa_chip_films_featured),
    FILMS_MANGA("films_manga", 15, R.string.missa_cat_films_manga, R.string.missa_chip_films_manga),
    FILMS_SHORTS("films_shorts", 115, R.string.missa_cat_films_shorts, R.string.missa_chip_films_shorts),
    FILMS_CATALOG("films_catalog", 900, R.string.missa_cat_films_catalog, R.string.missa_chip_films_catalog),

    // Series
    SERIES_TRENDING("series_trending", 10, R.string.missa_cat_series_trending, R.string.missa_chip_series_trending),
    SERIES_INTL("series_intl", 35, R.string.missa_cat_series_intl, R.string.missa_chip_series_intl),
    SERIES_CATALOG("series_catalog", 900, R.string.missa_cat_series_catalog, R.string.missa_chip_series_catalog),

    // Documentaries (Documentaires)
    DOCS_FEATURED("docs_featured", 10, R.string.missa_cat_docs_featured, R.string.missa_chip_docs_featured),
    DOCS_NATURE("docs_nature", 20, R.string.missa_cat_docs_nature, R.string.missa_chip_docs_nature),
    DOCS_HISTORY("docs_history", 30, R.string.missa_cat_docs_history, R.string.missa_chip_docs_history),
    DOCS_SCIENCE("docs_science", 40, R.string.missa_cat_docs_science, R.string.missa_chip_docs_science),
    DOCS_SOCIETY("docs_society", 50, R.string.missa_cat_docs_society, R.string.missa_chip_docs_society),
    DOCS_ALL("docs_all", 900, R.string.missa_cat_docs_all, R.string.missa_chip_docs_all),

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

    // Music (Musiques — 100% dedicated on-demand music tracks, albums & DJ mixes)
    MUSIC_TRENDING("music_trending", 8, R.string.missa_cat_music_trending, R.string.missa_chip_music_trending),
    MUSIC_AFRICA("music_africa", 12, R.string.missa_cat_music_africa, R.string.missa_chip_music_africa),
    MUSIC_URBAN("music_urban", 20, R.string.missa_cat_music_urban, R.string.missa_chip_music_urban),
    MUSIC_DJ_MIXES("music_dj_mixes", 26, R.string.missa_cat_music_dj_mixes, R.string.missa_chip_music_dj_mixes),
    MUSIC_POP_WORLD("music_pop", 32, R.string.missa_cat_music_pop, R.string.missa_chip_music_pop),
    MUSIC_JAZZ_CLASSICAL("music_jazz_classical", 38, R.string.missa_cat_music_jazz_classical, R.string.missa_chip_music_jazz_classical),
    MUSIC_CLIPS_LIVE("music_clips", 44, R.string.missa_cat_music_clips, R.string.missa_chip_music_clips),
    MUSIC_STREAMS("music_streams", 52, R.string.missa_cat_music_streams, R.string.missa_chip_music_streams),
    MUSIC_ALL("music_all", 900, R.string.missa_cat_music_all, R.string.missa_chip_music_all),

    // Podcasts
    PODCASTS_SOCIETY("podcasts_society", 15, R.string.missa_cat_podcasts_society, R.string.missa_chip_podcasts_society),
    PODCASTS_CULTURE("podcasts_culture", 25, R.string.missa_cat_podcasts_culture, R.string.missa_chip_podcasts_culture),
    PODCASTS_ALL("podcasts_all", 900, R.string.missa_cat_podcasts_all, R.string.missa_chip_podcasts_all),

    // Radio
    RADIO_AFRICA("radio_africa", 12, R.string.missa_cat_radio_africa, R.string.missa_chip_radio_africa),
    RADIO_FRANCOPHONE("radio_francophone", 16, R.string.missa_cat_radio_francophone, R.string.missa_chip_radio_francophone),
    RADIO_NEWS("radio_news", 22, R.string.missa_cat_radio_news, R.string.missa_chip_radio_news),
    RADIO_AFRO_URBAN("radio_afro_urban", 28, R.string.missa_cat_radio_afro_urban, R.string.missa_chip_radio_afro_urban),
    RADIO_MUSIC("radio_music", 34, R.string.missa_cat_radio_music, R.string.missa_chip_radio_music),
    RADIO_RELAX("radio_relax", 40, R.string.missa_cat_radio_relax, R.string.missa_chip_radio_relax),
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
        "documentaries",
        "shorts",
        "kids",
        "live_tv",
        "sports",
        "music",
        "podcasts",
        "radio"
    )

    private val movieTypes = setOf(
        TvType.Movie, TvType.AnimeMovie, TvType.Torrent, TvType.Video
    )
    private val vodMovieOrDocTypes = movieTypes + TvType.Documentary
    private val mangaTypes = setOf(
        TvType.Anime, TvType.AnimeMovie, TvType.OVA
    )
    // Pure TV/episodic series universe types (Mangas/Animes/OVAs are grouped exclusively in "films" = Films & Mangas)
    private val seriesTypes = setOf(
        TvType.TvSeries, TvType.Cartoon, TvType.AsianDrama
    )
    private val audioTypes = setOf(TvType.Audio, TvType.Music, TvType.AudioBook)

    private fun wordRegex(pattern: String): Regex = Regex(
        """(?<![\p{L}\p{N}])($pattern)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE
    )

    private val mangaKeywords = wordRegex(
        """mangas?|animes?|anim[eé]s?\s+japonais|oav|ova|shonen|shōnen|seinen|shojo|shōjo|isekai|mecha|donghua|manhwa|manhua|webtoon|japanimation|crunchyroll"""
    )
    private val documentaryKeywords = wordRegex(
        """documentaires?|documentary|documentaries|docu[ -]?s[eé]ries?|docus?|docufiction|reportages?|grands?\s+reportages?|enqu[eê]tes?\s+exclusives?|biographies?|arch[eé]ologie|national\s+geographic|nat\s*geo(?:\s+wild)?|discovery(?:\s+channel|\s+science|\s+investigation)?|ushua[iï]a|plan[eè]te\+|rmc\s+d[eé]couverte|histoire\s+tv|toute\s+l['’]histoire|science\s+(?:&|et)\s+vie|faune\s+sauvage|wildlife|true\s+crime"""
    )
    private val musicKeywords = wordRegex(
        """musiques?|music|clips?\s+(?:vid[eé]os?|musicaux|officiels?)|music\s+videos?|official\s+(?:music\s+)?video|concerts?|live\s+sessions?|showcase|albums?|playlists?|chansons?|songs?|singles?|karaok[eé]|afrobeats?|afropop|amapiano|makossa|bikutsi|benskin|mbol[eé]|coup[eé][ -]d[eé]cal[eé]|ndombolo|rumba|soukous|zouglou|zouk|kompa|hip[ -]?hop|rap|r&b|rnb|trap|drill|jazz|blues|soul|reggae|dancehall|gospel|louange|[eé]lectro|mtv|trace\s+(?:urban|africa|toca|gospel|mizik|naija)|nrj\s+hits|vevo|boiler\s+room|colors\s+show"""
    )

    private val shortKeywords = wordRegex(
        """court[ -]?m[eé]trages?|short[ -]?films?|shortfilms?|shorts|films?\s+courts?|formats?\s+courts?"""
    )
    private val kidsKeywords = wordRegex(
        """enfants?|jeunesse|kids?|children|child|family|familles?|familial(?:e|es)?|tous?\s+publics?|jeune\s+public|dessins?\s+anim[eé]s?|comptines?|disney|pixar|dreamworks|illumination|nickelodeon|cartoon\s+network|gulli|tiji|piwi|boomerang|okoo|pat['’\s]?patrouille|paw\s+patrol|peppa\s+pig|pyjamasques|pj\s+masks|miraculous|ladybug|reine\s+des\s+neiges|frozen|roi\s+lion|lion\s+king|mufasa|toy\s+story|vice[ -]versa|inside\s+out|vaiana|moana|encanto|ratatouille|zootopie|zootopia|monde\s+de\s+nemo|monde\s+de\s+dory|indestructibles|incredibles|raiponce|tangled|lilo\s*(?:&|et|and)\s*stitch|aladdin|cendrillon|cinderella|blanche[ -]neige|petite\s+sir[eè]ne|livre\s+de\s+la\s+jungle|bambi|dumbo|pinocchio|peter\s+pan|winnie|shrek|chat\s+pott[eé]|puss\s+in\s+boots|madagascar|kung\s+fu\s+panda|how\s+to\s+train\s+your\s+dragon|baby\s+boss|boss\s+baby|trolls|croods|robot\s+sauvage|wild\s+robot|moi,?\s+moche\s+et\s+m[eé]chant|despicable\s+me|minions|comme\s+des\s+b[eê]tes|secret\s+life\s+of\s+pets|tous\s+en\s+sc[eè]ne|grinch|lorax|super\s+mario|[aâ]ge\s+de\s+glace|ice\s+age|h[oô]tel\s+transylvani[ae]|schtroumpfs|smurfs|ast[eé]rix|tintin|lucky\s+luke|kirikou|titeuf|petit\s+nicolas|ducobu|boule\s+et\s+bill|marsupilami|paddington|pierre\s+lapin|peter\s+rabbit|bob\s+l['’][eé]ponge|spongebob|scooby[ -]?doo|tom\s+(?:&|et|and)\s+jerry|looney\s+tunes|bugs\s+bunny|barbie|mon\s+petit\s+poney|my\s+little\s+pony|pok[eé]mon|doraemon|totoro|ponyo|wallace\s+(?:&|et|and)\s+gromit|shaun\s+le\s+mouton|shaun\s+the\s+sheep"""
    )
    private val matureDisqualifiers = wordRegex(
        """horreur|horror|gore|slasher|thriller|[eé]rotique|erotic|adulte?s?|nsfw|xxx|porn|crime|narco|cartel|zombie|sanglant|ultraviolent|seinen|ecchi|hentai|south\s+park|rick\s*(?:&|and|et)\s*morty|sausage\s+party|family\s+guy|american\s+dad|bojack|hazbin|helluva\s+boss|invincible|castlevania|arcane|vox\s+machina|cyberpunk|berserk|goblin\s+slayer|chainsaw\s+man|attaque\s+des\s+titans|attack\s+on\s+titan|deadpool|16\+|18\+|nc-17|tv-ma"""
    )
    private val matureRatingRegex = Regex(
        """(?<![A-Z0-9-])(R|NC-17|TV-MA|TV-14|16\+|18\+|ADULT)(?![A-Z0-9+-])""",
        RegexOption.IGNORE_CASE
    )
    private val teenRatingRegex = Regex(
        """(?<![A-Z0-9-])(PG-13|12\+|13\+|14\+)(?![A-Z0-9+-])""",
        RegexOption.IGNORE_CASE
    )
    private val sportsKeywords = wordRegex(
        """espn|bein(?:\s+sports?)?|sky\s+sports?|tnt\s+sports?|bt\s+sports?|premier\s+sports?|rmc\s+sports?|canal\+?\s*(?:sports?|foot|premier\s+league|formula\s*1|motogp|golf)|foot\+|multisports?|infosport\+?|l['’][eé]quipe|[eé]quipe\s+tv|automoto|equidia|supersport|new\s+world\s+sport|crtv\s+sports?|eurosport|dazn|sport\s*tv|eleven\s+sports?|arena\s+sport|sportklub|movistar\s+(?:laliga|liga|campeones|deportes)|teledeporte|sportitalia|rai\s+sport|ziggo\s+sport|viaplay\s+sport|fox\s+sports?|cbs\s+sports?|nbc\s+sports?|tsn|sportsnet|abu\s+dhabi\s+sports?|dubai\s+sports?|alkass|al\s+kass|arryadia|on\s*time\s+sports?|nba\s+tv|nfl\s+network|mlb\s+network|nhl\s+network|tennis\s+channel|golf\s+channel|wwe\s+network|mutv|lfctv|bar[cç]a\s+tv|real\s+madrid\s+tv|sports?|football|soccer|futsal|ligue\s*1|ligue\s*2|premier\s+league|epl|serie\s+a|bundesliga|laliga|la\s+liga|eredivisie|liga\s+portugal|primeira\s+liga|botola|mls|libertadores|champions\s+league|ligue\s+des\s+champions|ucl|europa\s+league|conference\s+league|uefa|fifa|caf|afcon|can\s+\d{4}|coupe\s+d['’]afrique|coupe\s+du\s+monde|world\s+cup|coupe\s+de\s+france|fa\s+cup|carabao\s+cup|copa\s+del\s+rey|coppa\s+italia|supercoupe|super\s+cup|euro\s+20\d{2}|copa\s+am[eé]rica|nations\s+league|ligue\s+des\s+nations|basket(?:ball)?|nba|wnba|euroleague|euroligue|fiba|tennis|atp|wta|roland[ -]garros|wimbledon|us\s+open|australian\s+open|open\s+d['’]australie|coupe\s+davis|davis\s+cup|rugby|top\s*14|pro\s+d2|six\s+nations|6\s+nations|formula\s*1|formule\s*1|f1|f2|f3|grand\s+prix|motogp|moto2|moto3|superbike|wrc|rallye|rally|nascar|indycar|ufc|mma|bellator|pfl|boxe|boxing|kickboxing|wrestling|wwe|aew|catch|judo|cyclisme|cycling|tour\s+de\s+france|handball|volleyball|athl[eé]tisme|athletics|marathon|jeux\s+olympiques|olympics|nfl|super\s+bowl|nhl|mlb|cricket|ipl|snooker|matchs?|avant[ -]match|multiplex|mercato"""
    )
    private val liveMatchupRegex = Regex(
        """(?:\b(?:vs\.?|contre)\b|\b(?:[01]?\d|2[0-3])[:h][0-5]\d\b.{0,24}[\p{L}]{2,}.*\s[-–—]\s.*[\p{L}]{2,})""",
        RegexOption.IGNORE_CASE
    )
    private val liveEventSectionRegex = wordRegex(
        """[eé]v[eé]nements?|events?|directs?|live\s+events?|matchs?\s+du\s+jour|programme\s+du\s+jour|streams?\s+live"""
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
    private val explicitSeriesTitleRegex = wordRegex(
        """s\d{1,2}\s*e\d{1,3}|s0[1-9]|s[12]\d|\d{1,2}x\d{2}|saisons?\s*\d+|seasons?\s*\d+|ep\.?\s*\d+|mini[ -]?s[eé]ries?|s[eé]rie\s+(?:tv|compl[eè]te)|int[eé]grale\s+saisons?"""
    )
    private val episodeNumberTitleRegex = wordRegex("""[eé]pisodes?\s*\d+""")
    private val seriesHintKeywords = wordRegex(
        """s\d{1,2}\s*e\d{1,3}|s0[1-9]|\d{1,2}x\d{2}|saisons?\s*\d+|seasons?\s*\d+|[eé]pisodes?\s*\d+|s[eé]ries?|dramas?|k[ -]?dramas?|mini[ -]?s[eé]ries?|tv\s+shows?|sitcoms?|telenovelas?|feuilletons?"""
    )
    private val seriesSectionKeywords = wordRegex(
        """s[eé]ries?|dramas?|k[ -]?dramas?|mini[ -]?s[eé]ries?|[eé]pisodes?|saisons?|seasons?|tv\s+shows?|sitcoms?|telenovelas?|feuilletons?"""
    )
    private val movieHintKeywords = wordRegex(
        """films?|movies?|cin[eé]ma|longs?[ -]m[eé]trages?|box[ -]?office"""
    )
    private val seriesUrlPathRegex = Regex(
        """/(?:tv[-_]?series|series?|tv[-_]?shows?|shows?|saisons?|seasons?|episodes?|dramas?|kdramas?)(?:/|-|_|\?|$)""",
        RegexOption.IGNORE_CASE
    )
    private val movieUrlPathRegex = Regex(
        """/(?:films?|movies?|longs?[-_]?metrages?|cinema)(?:/|-|_|\?|$)""",
        RegexOption.IGNORE_CASE
    )
    private val mangaUrlPathRegex = Regex(
        """/(?:animes?|mangas?|donghuas?|manhwas?|oav|ova)(?:/|-|_|\?|$)""",
        RegexOption.IGNORE_CASE
    )
    private val liveUrlRegex = Regex(
        """(?:\.(?:m3u8|ts)(?:\?|$)|/(?:live|iptv|direct)(?:/|-|_|\?|$))""",
        RegexOption.IGNORE_CASE
    )
    private val familyTags = wordRegex(
        """family|familles?|familial(?:e|es)?|children|child|kids?|jeunesse|enfants?|pour\s+enfants?|tous?\s+publics?|jeune\s+public|dessins?\s+anim[eé]s?|films?\s+d['’]animation|contes?|f[eé]erique|disney|pixar|dreamworks|illumination|gulli|tiji|nickelodeon|cartoon\s+network|boomerang|okoo"""
    )
    private val animationGenreRegex = wordRegex(
        """animation|anim[eé]s?|animated|cartoon"""
    )
    private val safeRating = Regex(
        """(?<![A-Z0-9-])(TV-Y7(?:-FV)?|TV-Y|TV-G|TV-PG|PG|G|U|TP|TOUS?\s*PUBLICS?|0\+|3\+|6\+|7\+|8\+|9\+|10\+)(?![A-Z0-9+-])""",
        RegexOption.IGNORE_CASE
    )
    private val durationSuffix = Regex("""(\d{1,3})\s*(min|minutes|mn)\b""", RegexOption.IGNORE_CASE)

    private val noiseTagsRegex = Regex(
        """(\[[^\]]*]|\((?:19|20)\d{2}\)|\b(?:vf|vff|vfq|vostfr|vost|multi|french|truefrench|subbed|dubbed|hd|fhd|uhd|4k|2160p|1080p|720p|480p|webrip|bluray|bdrip|dvdrip|x264|x265|hevc)\b)""",
        RegexOption.IGNORE_CASE
    )
    private val nonAlphaNumRegex = Regex("""[^a-z0-9]+""")

    fun hasExplicitSeriesTitleSignal(title: String): Boolean {
        if (explicitSeriesTitleRegex.containsMatchIn(title)) return true
        return !title.contains("star wars", ignoreCase = true) &&
            episodeNumberTitleRegex.containsMatchIn(title)
    }

    fun isExclusiveSeriesSection(sectionName: String): Boolean {
        if (sectionName.isBlank()) return false
        return seriesSectionKeywords.containsMatchIn(sectionName) &&
            !movieHintKeywords.containsMatchIn(sectionName) &&
            !mangaKeywords.containsMatchIn(sectionName)
    }

    fun isExclusiveMovieSection(sectionName: String): Boolean {
        if (sectionName.isBlank()) return false
        return movieHintKeywords.containsMatchIn(sectionName) &&
            !seriesSectionKeywords.containsMatchIn(sectionName) &&
            !mangaKeywords.containsMatchIn(sectionName)
    }

    fun hasStrongSeriesEvidence(
        rawType: TvType?,
        title: String,
        sectionName: String = "",
        url: String = "",
        episodeCountHint: Int? = null,
        isSeriesLoadResponse: Boolean? = null
    ): Boolean {
        if (isSeriesLoadResponse == true) return true
        if (isSeriesLoadResponse == false) return false
        if (episodeCountHint != null && episodeCountHint > 1) return true
        if (hasExplicitSeriesTitleSignal(title)) return true
        if (seriesUrlPathRegex.containsMatchIn(url) && !movieUrlPathRegex.containsMatchIn(url)) return true
        if (isExclusiveSeriesSection(sectionName) && !movieUrlPathRegex.containsMatchIn(url)) return true
        return rawType in seriesTypes && !movieUrlPathRegex.containsMatchIn(url) && !isExclusiveMovieSection(sectionName)
    }

    fun hasStrongMovieEvidence(
        rawType: TvType?,
        title: String,
        sectionName: String = "",
        url: String = "",
        isSeriesLoadResponse: Boolean? = null
    ): Boolean {
        if (isSeriesLoadResponse == false) return true
        if (isSeriesLoadResponse == true) return false
        if (hasExplicitSeriesTitleSignal(title)) return false
        if (movieUrlPathRegex.containsMatchIn(url) && !seriesUrlPathRegex.containsMatchIn(url)) return true
        if (isExclusiveMovieSection(sectionName) && !seriesUrlPathRegex.containsMatchIn(url)) return true
        return false
    }

    private fun isSportsBroadcastOrEvent(
        effectiveOrRawType: TvType?,
        title: String,
        sectionName: String,
        tags: Collection<String>,
        url: String,
        providerSupportedTypes: Set<TvType>
    ): Boolean {
        val text = "$title $sectionName ${tags.joinToString(" ")}"
        if (podcastKeywords.containsMatchIn(text) || radioKeywords.containsMatchIn(text)) return false
        if (effectiveOrRawType == TvType.Documentary || documentaryKeywords.containsMatchIn(text)) return false

        val isLiveLike = effectiveOrRawType == TvType.Live ||
            liveKeywords.containsMatchIn("$title $sectionName") ||
            liveEventSectionRegex.containsMatchIn(sectionName) ||
            liveUrlRegex.containsMatchIn(url) ||
            (effectiveOrRawType == null || effectiveOrRawType == TvType.Others || effectiveOrRawType == TvType.Video) &&
            (TvType.Live in providerSupportedTypes || liveMatchupRegex.containsMatchIn(title))

        if (!isLiveLike) return false
        return sportsKeywords.containsMatchIn(text) || liveMatchupRegex.containsMatchIn(title)
    }

    /** Checks if a title/metadata contains mature/adult cues (also used by TRUST-01 parental guard). */
    fun hasMatureSignals(
        title: String,
        tags: Collection<String> = emptyList(),
        contentRating: String? = null,
        sectionName: String = ""
    ): Boolean {
        val text = "$title $sectionName ${tags.joinToString(" ")}"
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
        providerSupportedTypes: Set<TvType> = emptySet(),
        url: String = "",
        tags: Collection<String> = emptyList(),
        episodeCountHint: Int? = null,
        isSeriesLoadResponse: Boolean? = null
    ): TvType? {
        if (rawType == TvType.NSFW || rawType == TvType.CustomMedia) return rawType
        val contextText = "$title $sectionName ${tags.joinToString(" ")}".trim()
        val isMangaSignal = rawType in mangaTypes ||
            mangaKeywords.containsMatchIn(contextText) ||
            mangaUrlPathRegex.containsMatchIn(url)
        val isDocumentarySignal = (rawType == TvType.Documentary || documentaryKeywords.containsMatchIn(contextText)) &&
            !liveKeywords.containsMatchIn(sectionName) &&
            rawType != TvType.Live &&
            rawType != TvType.Podcast &&
            rawType !in audioTypes &&
            !podcastKeywords.containsMatchIn(contextText) &&
            !radioKeywords.containsMatchIn(contextText)

        // 1. Concrete LoadResponse / multi-episode structural proof overrides sloppy plugin home-page defaults
        if (isSeriesLoadResponse == true || (episodeCountHint != null && episodeCountHint > 1)) {
            return when {
                isDocumentarySignal -> TvType.Documentary
                isMangaSignal -> TvType.Anime
                rawType == TvType.Cartoon || rawType == TvType.AsianDrama -> rawType
                else -> TvType.TvSeries
            }
        }
        if (isSeriesLoadResponse == false) {
            return when {
                rawType == TvType.Live || isSportsBroadcastOrEvent(rawType, title, sectionName, tags, url, providerSupportedTypes) -> TvType.Live
                isDocumentarySignal -> TvType.Documentary
                isMangaSignal -> TvType.AnimeMovie
                else -> TvType.Movie
            }
        }

        // 2. Domain & structural overrides even when a plugin stamped a default Movie / TvSeries / Video / Others type
        if (rawType == TvType.Podcast) return TvType.Podcast
        if (isSportsBroadcastOrEvent(rawType, title, sectionName, tags, url, providerSupportedTypes)) {
            return TvType.Live
        }
        if (isDocumentarySignal &&
            (rawType == null || rawType == TvType.Others || rawType in movieTypes || rawType == TvType.TvSeries)
        ) {
            return TvType.Documentary
        }
        if (isMangaSignal &&
            (rawType == null || rawType == TvType.Others || rawType == TvType.Movie || rawType == TvType.TvSeries)
        ) {
            return if (hasExplicitSeriesTitleSignal(title) || isExclusiveSeriesSection(sectionName) || seriesUrlPathRegex.containsMatchIn(url)) {
                TvType.Anime
            } else if (isExclusiveMovieSection(sectionName) || movieUrlPathRegex.containsMatchIn(url) || rawType == TvType.Movie) {
                TvType.AnimeMovie
            } else {
                TvType.Anime
            }
        }
        if (rawType in setOf(TvType.Movie, TvType.Torrent, TvType.Video)) {
            if (hasExplicitSeriesTitleSignal(title) ||
                (seriesUrlPathRegex.containsMatchIn(url) && !movieUrlPathRegex.containsMatchIn(url)) ||
                (isExclusiveSeriesSection(sectionName) && !movieUrlPathRegex.containsMatchIn(url))
            ) {
                return TvType.TvSeries
            }
        }
        if (rawType == TvType.TvSeries &&
            !hasExplicitSeriesTitleSignal(title) &&
            !seriesUrlPathRegex.containsMatchIn(url)
        ) {
            if (movieUrlPathRegex.containsMatchIn(url) ||
                (isExclusiveMovieSection(sectionName) && !seriesHintKeywords.containsMatchIn(title))
            ) {
                return TvType.Movie
            }
        }

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
            musicKeywords.containsMatchIn(contextText) &&
                !liveKeywords.containsMatchIn(sectionName) &&
                !movieHintKeywords.containsMatchIn(contextText) &&
                !seriesHintKeywords.containsMatchIn(contextText) &&
                (cleanProviderTypes.isEmpty() || cleanProviderTypes.any { it == TvType.Music || it == TvType.Audio }) -> TvType.Music
            liveKeywords.containsMatchIn(sectionName) ||
                liveEventSectionRegex.containsMatchIn(sectionName) ||
                liveUrlRegex.containsMatchIn(url) ||
                (sportsKeywords.containsMatchIn(contextText) && TvType.Live in cleanProviderTypes) -> TvType.Live
            isMangaSignal -> {
                if (movieHintKeywords.containsMatchIn(contextText) && !seriesHintKeywords.containsMatchIn(contextText)) {
                    TvType.AnimeMovie
                } else {
                    TvType.Anime
                }
            }
            isDocumentarySignal -> TvType.Documentary
            hasExplicitSeriesTitleSignal(title) ||
                seriesUrlPathRegex.containsMatchIn(url) ||
                seriesHintKeywords.containsMatchIn(contextText) -> TvType.TvSeries
            shortKeywords.containsMatchIn(contextText) -> TvType.Movie
            movieUrlPathRegex.containsMatchIn(url) ||
                movieHintKeywords.containsMatchIn(sectionName) -> TvType.Movie
            cleanProviderTypes.size == 1 -> cleanProviderTypes.first()
            cleanProviderTypes.isNotEmpty() && cleanProviderTypes.all { it == TvType.Documentary } -> TvType.Documentary
            cleanProviderTypes.isNotEmpty() && cleanProviderTypes.all { it in movieTypes } -> TvType.Movie
            cleanProviderTypes.isNotEmpty() && cleanProviderTypes.all { it in seriesTypes } -> TvType.TvSeries
            cleanProviderTypes.isNotEmpty() && cleanProviderTypes.all { it in mangaTypes } -> TvType.Anime
            cleanProviderTypes.isNotEmpty() && cleanProviderTypes.all { it == TvType.Live } -> TvType.Live
            cleanProviderTypes.isNotEmpty() && cleanProviderTypes.all { it == TvType.Podcast } -> TvType.Podcast
            cleanProviderTypes.isNotEmpty() && cleanProviderTypes.all { it == TvType.Music } -> TvType.Music
            cleanProviderTypes.isNotEmpty() && cleanProviderTypes.all { it in audioTypes } -> TvType.Audio
            else -> rawType
        }
    }

    fun shouldLoadDetails(
        universeId: String,
        type: TvType?,
        title: String,
        sectionName: String = "",
        url: String = ""
    ): Boolean {
        if (type == TvType.NSFW || type == TvType.CustomMedia) return false
        val effective = inferEffectiveType(type, title, sectionName, url = url)
        val canBeMovie = effective == null || effective == TvType.Others || effective in vodMovieOrDocTypes
        val canBeSeries = effective == null || effective == TvType.Others || effective in seriesTypes
        val context = "$title $sectionName"
        return when (universeId) {
            "shorts" -> canBeMovie && (
                shortKeywords.containsMatchIn(context) ||
                    durationSuffix.containsMatchIn(title) ||
                    effective?.let { it in vodMovieOrDocTypes } == true
                )
            "kids" -> (canBeMovie || canBeSeries) && !matureDisqualifiers.containsMatchIn(context)
            "documentaries" -> (canBeMovie || canBeSeries) && documentaryKeywords.containsMatchIn(context)
            "disambiguate" -> (canBeMovie || canBeSeries) &&
                !isExclusiveMovieSection(sectionName) &&
                !isExclusiveSeriesSection(sectionName) &&
                !movieUrlPathRegex.containsMatchIn(url) &&
                !seriesUrlPathRegex.containsMatchIn(url)
            else -> false
        }
    }

    fun detailPriority(universeId: String, title: String, sectionName: String = "", url: String = ""): Int {
        val context = "$title $sectionName"
        return when (universeId) {
            "shorts" -> (if (shortKeywords.containsMatchIn(context)) 5 else 0) +
                (if (durationSuffix.containsMatchIn(title)) 3 else 0)
            "kids" -> when {
                matureDisqualifiers.containsMatchIn(context) -> 0
                kidsKeywords.containsMatchIn(context) || animationGenreRegex.containsMatchIn(context) -> 5
                else -> 2
            }
            "documentaries" -> (if (documentaryKeywords.containsMatchIn(context)) 4 else 0)
            "disambiguate" -> if (
                !isExclusiveMovieSection(sectionName) &&
                !isExclusiveSeriesSection(sectionName) &&
                !movieUrlPathRegex.containsMatchIn(url) &&
                !seriesUrlPathRegex.containsMatchIn(url)
            ) 3 else 0
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
        providerSupportedTypes: Set<TvType> = emptySet(),
        url: String = "",
        episodeCountHint: Int? = null,
        isSeriesLoadResponse: Boolean? = null
    ): Boolean {
        if (type == TvType.NSFW || type == TvType.CustomMedia) return false

        val effectiveType = inferEffectiveType(
            rawType = type,
            title = title,
            sectionName = sectionName,
            providerSupportedTypes = providerSupportedTypes,
            url = url,
            tags = tags,
            episodeCountHint = episodeCountHint,
            isSeriesLoadResponse = isSeriesLoadResponse
        )
        if (effectiveType == TvType.NSFW || effectiveType == TvType.CustomMedia) return false

        val detailText = buildString {
            if (sectionName.isNotBlank()) append(sectionName).append(' ')
            if (tags.isNotEmpty()) append(tags.joinToString(" "))
        }.trim()
        val classificationText = "$title $detailText"

        val isManga = effectiveType in mangaTypes ||
            mangaKeywords.containsMatchIn(classificationText) ||
            mangaUrlPathRegex.containsMatchIn(url)

        val isDocumentary = !podcastKeywords.containsMatchIn(classificationText) &&
            !radioKeywords.containsMatchIn(classificationText) &&
            (effectiveType == TvType.Documentary ||
                (effectiveType != TvType.Music && documentaryKeywords.containsMatchIn(classificationText)))

        val isSports = isSportsBroadcastOrEvent(
            effectiveOrRawType = effectiveType,
            title = title,
            sectionName = sectionName,
            tags = tags,
            url = url,
            providerSupportedTypes = providerSupportedTypes
        )

        return when (universeId) {
            "home" -> true
            "films" -> effectiveType != TvType.Music &&
                !isSports &&
                !(isDocumentary && effectiveType != TvType.Live) &&
                effectiveType !in seriesTypes &&
                (
                    (effectiveType != null && (effectiveType in movieTypes || effectiveType in mangaTypes)) ||
                        (isManga && effectiveType != TvType.Live && effectiveType != TvType.Podcast && effectiveType !in audioTypes)
                    )
            "series" -> !isSports &&
                !isManga &&
                !(isDocumentary && effectiveType != TvType.Live) &&
                effectiveType != null &&
                effectiveType in seriesTypes
            "documentaries" -> isDocumentary
            "shorts" -> {
                val canBeMovie = (effectiveType == null || effectiveType == TvType.Others || effectiveType in vodMovieOrDocTypes) &&
                    effectiveType !in seriesTypes &&
                    !hasExplicitSeriesTitleSignal(title)
                val titleDuration = durationSuffix.find(title)?.groupValues?.getOrNull(1)?.toIntOrNull()
                canBeMovie && !isSports && (
                    durationMinutes?.let { it in 1..40 } == true ||
                        shortKeywords.containsMatchIn(classificationText) ||
                        (durationMinutes == null && titleDuration != null && titleDuration in 1..40)
                    )
            }
            "kids" -> {
                val supportedType = effectiveType == null ||
                    effectiveType == TvType.Others ||
                    effectiveType in vodMovieOrDocTypes ||
                    effectiveType in seriesTypes ||
                    effectiveType in mangaTypes
                val isMature = hasMatureSignals(title, tags, contentRating, sectionName)
                val isTeenRestricted = teenRatingRegex.containsMatchIn(contentRating.orEmpty())
                val isNonMangaFamilyAnimation = !isManga &&
                    !isTeenRestricted &&
                    animationGenreRegex.containsMatchIn(detailText)
                supportedType && !isSports && !isMature && (
                    effectiveType == TvType.Cartoon ||
                        familyTags.containsMatchIn(detailText) ||
                        isNonMangaFamilyAnimation ||
                        safeRating.containsMatchIn(contentRating.orEmpty()) ||
                        kidsKeywords.containsMatchIn(title)
                    )
            }
            "live_tv" -> effectiveType == TvType.Live &&
                !isSports &&
                !radioKeywords.containsMatchIn(classificationText) &&
                !podcastKeywords.containsMatchIn(classificationText)
            "sports" -> isSports
            "music" -> {
                if (podcastKeywords.containsMatchIn(classificationText) ||
                    radioKeywords.containsMatchIn(classificationText) ||
                    isSports ||
                    isDocumentary ||
                    isManga
                ) {
                    false
                } else {
                    effectiveType == TvType.Music ||
                        (effectiveType == TvType.Audio && musicKeywords.containsMatchIn(classificationText))
                }
            }
            "podcasts" -> effectiveType == TvType.Podcast ||
                (effectiveType in audioTypes && podcastKeywords.containsMatchIn(classificationText))
            "radio" -> {
                val audioOrLive = effectiveType == null ||
                    effectiveType == TvType.Live ||
                    effectiveType in audioTypes
                audioOrLive &&
                    !isSports &&
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
        providerSupportedTypes: Set<TvType> = emptySet(),
        url: String = "",
        episodeCountHint: Int? = null,
        isSeriesLoadResponse: Boolean? = null
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
                    providerSupportedTypes = providerSupportedTypes,
                    url = url,
                    episodeCountHint = episodeCountHint,
                    isSeriesLoadResponse = isSeriesLoadResponse
                )
            ) {
                matched.add(universe)
            }
        }
        // Hard partition invariants: Films & Mangas vs Series, and Sports vs Live TV must never overlap
        if ("films" in matched && "series" in matched) {
            if (hasStrongSeriesEvidence(type, title, sectionName, url, episodeCountHint, isSeriesLoadResponse)) {
                matched.remove("films")
            } else {
                matched.remove("series")
            }
        }
        if ("sports" in matched) {
            matched.remove("live_tv")
            matched.remove("films")
            matched.remove("series")
        }
        if ("radio" in matched || "podcasts" in matched) {
            matched.remove("live_tv")
            matched.remove("films")
            matched.remove("series")
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
                if (effectiveType != TvType.Documentary &&
                    effectiveType !in seriesTypes &&
                    (effectiveType in movieTypes || effectiveType in mangaTypes || mangaKeywords.containsMatchIn(text))
                ) {
                    categories.add(MissaCategory.HOME_MOVIES)
                }
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
                if (effectiveType == TvType.Podcast || effectiveType in audioTypes || musicKeywords.containsMatchIn(text)) {
                    categories.add(MissaCategory.HOME_AUDIO)
                }
                if (categories.isEmpty()) categories.add(MissaCategory.HOME_SELECTION)
            }

            "films" -> {
                if (isRecent || isTrending) categories.add(MissaCategory.FILMS_FEATURED)
                if (effectiveType in mangaTypes || mangaKeywords.containsMatchIn(text)) {
                    categories.add(MissaCategory.FILMS_MANGA)
                }
                if (containsAny(text, "action", "aventure", "adventure")) categories.add(MissaCategory.GENRE_ACTION)
                if (containsAny(text, "comédie", "comedie", "comedy")) categories.add(MissaCategory.GENRE_COMEDY)
                if (containsAny(text, "drame", "drama", "romance")) categories.add(MissaCategory.GENRE_DRAMA)
                if (containsAny(text, "thriller", "policier", "crime", "suspense", "mystère", "mystery")) {
                    categories.add(MissaCategory.GENRE_THRILLER)
                }
                if (containsAny(text, "science-fiction", "sci-fi", "fantastique", "fantasy")) {
                    categories.add(MissaCategory.GENRE_SCIFI)
                }
                if (effectiveType == TvType.Cartoon || containsAny(text, "animation", "pixar", "disney", "dreamworks", "illumination")) {
                    categories.add(MissaCategory.GENRE_ANIMATION)
                }
                if (durationMinutes != null && durationMinutes in 1..40) categories.add(MissaCategory.FILMS_SHORTS)
                if (categories.isEmpty()) categories.add(MissaCategory.FILMS_CATALOG)
            }

            "series" -> {
                if (isRecent || isTrending) categories.add(MissaCategory.SERIES_TRENDING)
                if (effectiveType == TvType.Cartoon || containsAny(text, "animation", "animé", "cartoon", "dessin animé")) {
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

            "documentaries" -> {
                if (isRecent || isTrending) categories.add(MissaCategory.DOCS_FEATURED)
                if (containsAny(
                        text,
                        "nature", "animaux", "animal", "faune", "wildlife", "planète", "planete",
                        "océan", "ocean", "climat", "écologie", "ecologie", "terre", "ushuaïa",
                        "ushuaia", "nat geo wild"
                    )
                ) {
                    categories.add(MissaCategory.DOCS_NATURE)
                }
                if (containsAny(
                        text,
                        "histoire", "history", "historique", "civilisation", "guerre", "war",
                        "antiquit", "archéolog", "archeolog", "biograph", "portrait", "empire",
                        "mémoire", "memoire"
                    )
                ) {
                    categories.add(MissaCategory.DOCS_HISTORY)
                }
                if (containsAny(
                        text,
                        "science", "espace", "space", "univers", "astronomie", "cosmos",
                        "technolog", "ia", "médecine", "medecine", "ingénierie", "ingenierie",
                        "découverte", "decouverte", "discovery"
                    )
                ) {
                    categories.add(MissaCategory.DOCS_SCIENCE)
                }
                if (containsAny(
                        text,
                        "société", "societe", "society", "enquête", "enquete", "reportage",
                        "crime", "justice", "politique", "géopolitique", "geopolitique",
                        "économie", "economie", "afrique", "africa", "cameroun", "culture",
                        "sport", "musique"
                    )
                ) {
                    categories.add(MissaCategory.DOCS_SOCIETY)
                }
                categories.add(MissaCategory.DOCS_ALL)
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
                if (effectiveType in seriesTypes || effectiveType == TvType.Anime || effectiveType == TvType.OVA) {
                    categories.add(MissaCategory.KIDS_CARTOONS)
                }
                if (effectiveType in vodMovieOrDocTypes) {
                    categories.add(MissaCategory.KIDS_MOVIES)
                }
                if (containsAny(text, "aventure", "adventure", "magie", "magic", "conte", "féerique", "fantastique", "fantasy", "disney", "pixar", "dreamworks", "illumination")) {
                    categories.add(MissaCategory.KIDS_MAGIC)
                }
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
                if (containsAny(text, "foot", "soccer", "ligue", "champions", "ucl", "uefa", "fifa", "caf", "afcon", "premier league", "laliga", "la liga", "serie a", "bundesliga", "eredivisie", "botola", "coupe", "world cup", "euro 20", "can ")) {
                    categories.add(MissaCategory.SPORTS_FOOTBALL)
                }
                if (containsAny(text, "basket", "nba", "wnba", "euroleague", "euroligue", "fiba")) {
                    categories.add(MissaCategory.SPORTS_BASKET)
                }
                if (containsAny(text, "ufc", "mma", "bellator", "pfl", "boxe", "boxing", "kickboxing", "wwe", "aew", "catch", "judo", "combat")) {
                    categories.add(MissaCategory.SPORTS_COMBAT)
                }
                if (containsAny(text, "f1", "formula", "formule", "grand prix", "motogp", "wrc", "rally", "auto", "tennis", "atp", "wta", "roland", "wimbledon", "rugby", "top 14", "six nations", "cyclisme", "tour de france", "nfl", "nhl", "golf")) {
                    categories.add(MissaCategory.SPORTS_MOTORS)
                }
                categories.add(MissaCategory.SPORTS_ALL)
            }

            "music" -> {
                if (isTrending || isRecent) {
                    categories.add(MissaCategory.MUSIC_TRENDING)
                }
                if (containsAny(
                        text,
                        "afrique", "africa", "cameroun", "cameroon", "douala", "yaound",
                        "afrobeat", "afropop", "amapiano", "makossa", "bikutsi", "benskin",
                        "mbol", "coupé-décalé", "coupe-decale", "coupe decale", "ndombolo",
                        "rumba", "soukous", "zouglou", "naija", "highlife", "afro"
                    )
                ) {
                    categories.add(MissaCategory.MUSIC_AFRICA)
                }
                if (containsAny(
                        text,
                        "hip-hop", "hip hop", "hiphop", "rap", "r&b", "rnb", "urbain",
                        "urban", "trap", "drill", "freestyle", "soul", "funk", "groove"
                    )
                ) {
                    categories.add(MissaCategory.MUSIC_URBAN)
                }
                if (containsAny(
                        text,
                        "dj", "mix", "set", "remix", "amapiano", "électro", "electro",
                        "electronic", "house", "techno", "dance", "club", "dubstep",
                        "drum & bass", "dnb", "hearthis"
                    )
                ) {
                    categories.add(MissaCategory.MUSIC_DJ_MIXES)
                }
                if (containsAny(
                        text,
                        "pop", "zouk", "kompa", "gospel", "louange", "reggae",
                        "dancehall", "rock", "indie", "world", "monde", "latin",
                        "bossa", "variété", "variete", "chanson"
                    )
                ) {
                    categories.add(MissaCategory.MUSIC_POP_WORLD)
                }
                if (containsAny(
                        text,
                        "jazz", "blues", "classique", "classical", "symphon", "piano",
                        "orchestr", "opera", "opéra", "chill", "chillout", "lounge",
                        "lofi", "lo-fi", "ambient", "acousti", "instrumental"
                    )
                ) {
                    categories.add(MissaCategory.MUSIC_JAZZ_CLASSICAL)
                }
                categories.add(MissaCategory.MUSIC_ALL)
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
                if (containsAny(
                        text,
                        "afrique", "africa", "cameroun", "cameroon", "douala", "yaound",
                        "crtv", "balafon", "equinoxe", "abidjan", "dakar", "kinshasa",
                        "lagos", "naija", "zeno.fm afrique", "cm ", " af",
                        "country:cm", "country:af", "country:ci", "country:sn",
                        "country:cd", "country:cg", "country:ga", "country:bj",
                        "country:tg", "country:ml", "country:bf", "country:gn",
                        "country:ne", "country:td", "country:cf", "country:gq",
                        "country:rw", "country:bi", "country:mg", "country:ma",
                        "country:dz", "country:tn", "country:ng", "country:gh",
                        "country:ke", "country:za", "country:et", "country:ug",
                        "country:tz", "country:ao", "country:mz"
                    )
                ) {
                    categories.add(MissaCategory.RADIO_AFRICA)
                }
                if (containsAny(
                        text,
                        "country:fr", "country:be", "country:ch", "country:ca",
                        "country:mc", "country:lu", "france", "francophon", "rfi",
                        "fip", "mouv", "rtl", "rmc", "europe 1", "nostalgie",
                        "nrj", "skyrock", "cherie", "fun radio", "radio canada",
                        "rtbf", "rts", "couleur 3"
                    )
                ) {
                    categories.add(MissaCategory.RADIO_FRANCOPHONE)
                }
                if (containsAny(
                        text,
                        "info", "actu", "news", "talk", "débat", "debat",
                        "journal", "inter", "rfi", "rtl", "rmc", "bbc",
                        "voa", "france culture", "public radio", "npr"
                    )
                ) {
                    categories.add(MissaCategory.RADIO_NEWS)
                }
                if (containsAny(
                        text,
                        "afrobeat", "afropop", "amapiano", "makossa", "bikutsi",
                        "hip-hop", "hip hop", "rap ", "r&b", "rnb", "urbain",
                        "urban", "gospel", "louange", "chrétien", "christian",
                        "reggae", "dancehall", "zouk", "kompa", "rumba",
                        "mouv", "skyrock", "trace"
                    )
                ) {
                    categories.add(MissaCategory.RADIO_AFRO_URBAN)
                }
                if (containsAny(
                        text,
                        "musique", "music", "hit", "pop", "rock", "indie",
                        "jazz", "classique", "groove", "soul", "funk", "blues",
                        "afro", "makossa", "bikutsi", "fip", "nrj", "skyrock",
                        "mouv", "nostalgie", "bossa", "world", "laut.fm"
                    )
                ) {
                    categories.add(MissaCategory.RADIO_MUSIC)
                }
                if (containsAny(
                        text,
                        "jazz", "classique", "classical", "symphon", "opera",
                        "opéra", "ambient", "chill", "chillout", "lounge",
                        "lofi", "lo-fi", "relax", "zen", "drone", "soma",
                        "groovesalad", "lush", "swiss classic", "swiss jazz",
                        "france musique"
                    )
                ) {
                    categories.add(MissaCategory.RADIO_RELAX)
                }
                categories.add(MissaCategory.RADIO_ALL)
            }
        }

        return categories.sortedBy { it.editorialRank }
    }

    private fun containsAny(haystack: String, vararg needles: String): Boolean =
        needles.any { haystack.contains(it) }
}
