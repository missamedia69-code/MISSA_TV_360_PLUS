package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.net.ConnectivityManager
import com.lagradost.cloudstream3.CloudStreamApp

/**
 * Consommation de données : à quelle fréquence l'application peut retourner sur Internet
 * pour remplir ses catalogues.
 *
 * Avant, chaque ouverture relisait les pages d'accueil de toutes les extensions (jusqu'à 40,
 * 5 à 15 pages web chacune), une trentaine de fiches détaillées et des dizaines de recherches
 * YouTube (Musique toutes les 20 min, Enfants 45 min, Documentaires 30 min). Sur données
 * mobiles, cela se comptait en dizaines de mégaoctets par ouverture.
 *
 * Maintenant, ce qui a déjà été téléchargé est gardé sur l'appareil et réutilisé tant qu'il
 * n'est pas trop ancien. Sur une connexion facturée au volume (données mobiles, partage de
 * connexion, ou économiseur activé), les délais sont plus longs. Le geste « tirer pour
 * actualiser » recharge toujours tout immédiatement.
 */
object MissaDataUsage {

    private const val HOUR = 60L * 60L * 1000L

    /** Pages d'accueil des extensions (films, séries…). */
    val CATALOGUE = Policy(wifiMs = 3 * HOUR, meteredMs = 8 * HOUR)

    /** Catalogue Musique (recherches YouTube par style). */
    val MUSIC = Policy(wifiMs = 3 * HOUR, meteredMs = 8 * HOUR)

    /** Vidéos Enfants et Documentaires (recherches YouTube). */
    val VIDEOS = Policy(wifiMs = 6 * HOUR, meteredMs = 12 * HOUR)

    /** Fiches détaillées gardées pour ranger les titres (Enfants, Docs, Courts-métrages). */
    const val DETAILS_MEMO_MS = 7L * 24L * HOUR

    class Policy(val wifiMs: Long, val meteredMs: Long) {
        /** Âge maximal d'un cache avant de retourner sur Internet. */
        fun maxAgeMs(context: Context? = CloudStreamApp.context): Long =
            if (isMetered(context)) meteredMs else wifiMs
    }

    /**
     * Vrai sur une connexion facturée au volume (données mobiles, point d'accès) ou quand
     * l'économiseur de données est activé (MISSA ou Android).
     */
    fun isMetered(context: Context? = CloudStreamApp.context): Boolean {
        val ctx = context ?: return false
        if (runCatching { MissaUiFactory.isDataSaverActive(ctx) }.getOrDefault(false)) return true
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return runCatching { cm.isActiveNetworkMetered }.getOrDefault(false)
    }

    /**
     * Préchargements « confort » (adresse de lecture des premiers titres, pour un démarrage
     * instantané) : seulement en Wi-Fi, ils ne servent que si l'utilisateur lance ces titres.
     */
    fun allowPrefetch(context: Context? = CloudStreamApp.context): Boolean = !isMetered(context)
}
