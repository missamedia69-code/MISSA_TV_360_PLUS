package com.lagradost.cloudstream3.ui.universe

import com.lagradost.cloudstream3.BuildConfig

/**
 * D'où l'application télécharge ses fichiers de démarrage (liste des chaînes, catalogue des
 * jeux, configuration des extensions).
 *
 * 1. Le site Cloudflare (missatv.pages.dev) : serveurs en Afrique, jamais bloqué par les
 *    opérateurs. Le workflow site-cloudflare.yml y recopie ces fichiers à chaque changement
 *    sur main.
 * 2. GitHub (raw.githubusercontent.com), en secours si Cloudflare ne répond pas.
 *
 * Une version d'essai construite sur une autre branche lit uniquement cette branche sur GitHub.
 */
object MissaContentSource {

    const val SITE = "https://missatv.pages.dev/"
    private const val REPO = "missamedia69-code/MISSA_TV_360_PLUS"

    fun githubUrl(path: String): String =
        "https://raw.githubusercontent.com/$REPO/${BuildConfig.MISSA_CONTENT_BRANCH}/$path"

    /** Adresses à essayer dans l'ordre pour [path] (chemin dans le dépôt, ex. tv/chaines.m3u). */
    fun urls(path: String): List<String> =
        if (BuildConfig.MISSA_CONTENT_BRANCH == "main") listOf(SITE + path, githubUrl(path))
        else listOf(githubUrl(path))
}
