package com.lagradost.cloudstream3.ui.universe

import com.lagradost.cloudstream3.TvType

/**
 * LE CRIBLE — EXT-GROUPS, phase 2.
 *
 * Dire « cette extension alimente Films et Séries » ne doit pas verser **tout** son contenu
 * dans les deux groupes : un film est **un seul titre**, une série est **une suite de vidéos
 * pour un même titre**. Le crible trie donc chaque contenu parmi les groupes que son extension
 * est autorisée à alimenter, au lieu de lui coller l'étiquette de l'extension.
 *
 * Règles structurelles par groupe (toutes déjà dans [MediaUniverseClassifier], sauf la dernière) :
 *
 * | Groupe          | Ce qui le caractérise structurellement |
 * |-----------------|----------------------------------------|
 * | `films`         | un titre unique : type Film / Torrent / AnimeMovie / OVA, durée longue, aucune preuve de série |
 * | `series`        | plusieurs vidéos pour un titre : LoadResponse à épisodes, `episodeCountHint > 1`, « S01E02 », « saison 2 », URL `/series/`, `/episode/`, type TvSeries / Cartoon / AsianDrama / Anime |
 * | `shorts`        | durée courte (≤ 40 min) + « court-métrage », « short » |
 * | `documentaries` | type Documentary ou mots-clés documentaire ; un flux `TvType.Live` va dans la rangée Live |
 * | `live_tv`       | type Live, URL HLS/`live`, plateforme (Twitch, YouTube Live, Kick), « en direct », « 24/7 » |
 * | `sports`        | signal live + mots-clés sport ou « équipe A vs équipe B » |
 * | `podcasts`      | type Podcast / AudioBook / Audio + mots-clés podcast |
 * | `radio`         | une station : type Audio / Music + mots-clés radio |
 * | `retro_games`   | **ajouté ici** : le classifieur n'a aucune règle pour ce groupe (ROM, homebrew, émulation) |
 *
 * Trois garanties :
 *  - le crible ne peut jamais **ajouter** un groupe que l'extension n'a pas coché : il ne
 *    fait que choisir parmi eux ;
 *  - « Aucun » reste absolu : rien n'est chargé, quoi que dise la structure ;
 *  - quand le contenu n'a la structure d'**aucun** des groupes cochés, la partition
 *    films / séries tranche (c'est la seule que le classifieur traite comme exclusive :
 *    une série d'une extension réglée sur Films seul part dans Séries). Partout ailleurs,
 *    le réglage garde la main, sinon il ne servirait plus à corriger la devinette.
 */
object MissaContentSieve {

    /** ROM / homebrew / émulation : extensions de fichier et machines reconnues. */
    private val romFileRegex = Regex(
        """\.(nes|snes|sfc|smc|gb|gbc|gba|nds|n64|gen|md|sms|gg|pce|iso|rom|bin)(?:[?#/]|$)""",
        RegexOption.IGNORE_CASE
    )
    private val consoleRegex = Regex(
        """\b(nes|snes|super nintendo|nintendo 64|n64|game ?boy|gba|mega ?drive|genesis|""" +
            """sega|master system|playstation|psx|ps2|arcade|homebrew|emulator|émulateur|""" +
            """romset|rom ?set|retro ?gaming|jeux? rétro)\b""",
        RegexOption.IGNORE_CASE
    )
    /** Une section entièrement dédiée aux jeux (« Roms », « Homebrew », « Jeux rétro »). */
    private val gameSectionRegex = Regex(
        """\b(roms?|homebrew|jeux? rétro|retro ?games?|emulation|émulation|consoles?)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Groupes dont ce contenu a la structure : la classification automatique, complétée par
     * les règles que le classifieur ne connaît pas. Fonction PURE (testable sans Android).
     */
    fun structuralGroups(
        auto: Set<String>,
        title: String,
        url: String = "",
        sectionName: String = "",
        tags: Collection<String> = emptyList(),
        type: TvType? = null
    ): Set<String> {
        if (isRetroGame(title, url, sectionName, tags, type)) {
            // Un jeu n'est ni un film ni une série : on remplace, on n'ajoute pas.
            return setOf("retro_games")
        }
        return auto
    }

    /** ROM, homebrew ou émulation : le seul signal que le classifieur ne voit pas. */
    internal fun isRetroGame(
        title: String,
        url: String,
        sectionName: String,
        tags: Collection<String>,
        type: TvType?
    ): Boolean {
        if (type == TvType.NSFW || type == TvType.CustomMedia) return false
        if (romFileRegex.containsMatchIn(url)) return true
        val text = "$title $sectionName ${tags.joinToString(" ")}"
        if (gameSectionRegex.containsMatchIn(sectionName)) return true
        // Un nom de machine seul ne suffit pas (« Arcade » apparaît dans des titres de films) :
        // il faut que le contenu ne soit pas une vidéo déjà identifiée comme film ou série.
        return consoleRegex.containsMatchIn(text) && !isVideoType(type)
    }

    private val videoTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon,
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.AsianDrama,
        TvType.Documentary,
        TvType.Live
    )

    private fun isVideoType(type: TvType?): Boolean = type != null && type in videoTypes

    /** La seule partition que le classifieur traite comme exclusive : un titre est un film OU une série. */
    private val filmsOrSeries = setOf("films", "series")

    /**
     * Le crible proprement dit : parmi les groupes que l'extension est autorisée à alimenter
     * ([allowed]), garde ceux dont le contenu a la structure ([structural]).
     *
     * - `allowed` vide (« Aucun ») → rien, ce contenu n'est chargé nulle part ;
     * - recouvrement non vide → on trie : chaque groupe reçoit son propre contenu ;
     * - aucun recouvrement → la partition films/séries tranche ; ailleurs, [allowed] garde la main.
     */
    fun sift(allowed: Set<String>, structural: Set<String>): Set<String> {
        if (allowed.isEmpty()) return emptySet()
        val kept = structural.filter { it in allowed }
        if (kept.isNotEmpty()) return kept.toCollection(LinkedHashSet())

        val structuralPartition = structural.filter { it in filmsOrSeries }
        if (structuralPartition.isNotEmpty() && allowed.any { it in filmsOrSeries }) {
            return structuralPartition.toCollection(LinkedHashSet())
        }
        return LinkedHashSet(allowed)
    }
}
