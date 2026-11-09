package com.lagradost.cloudstream3.ui.universe

/**
 * LE CRIBLE — EXT-GROUPS, phase 2.
 *
 * Dire « cette extension alimente Films et Séries » ne doit pas verser **tout** son contenu
 * dans les deux groupes : un film est **un seul titre**, une série est **une suite de vidéos
 * pour un même titre**. Le crible trie donc chaque contenu parmi les groupes que son extension
 * est autorisée à alimenter, au lieu de lui coller l'étiquette de l'extension.
 *
 * Règles structurelles par groupe (toutes dans [MediaUniverseClassifier]) :
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
