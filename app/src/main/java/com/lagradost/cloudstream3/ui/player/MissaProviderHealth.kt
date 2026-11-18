package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey

/**
 * Bulletin de santé des extensions, sur CET appareil : combien de lectures ont démarré et
 * combien ont échoué (aucun lien, tous les liens en échec, ou rien après le délai).
 *
 * Sert à classer les extensions : celles qui marchent passent en premier dans le catalogue
 * et pour la recherche d'une source de secours ; celles qui échouent souvent passent en
 * dernier (sans jamais être retirées : un site peut revenir).
 */
object MissaProviderHealth {

    private const val FOLDER = "missa_provider_health_v1"

    /** [ok, échecs] ; les compteurs anciens comptent moins (division par 2 au-delà de 20). */
    private fun read(apiName: String): IntArray =
        runCatching { getKey<IntArray>(FOLDER, apiName) }.getOrNull()?.takeIf { it.size == 2 } ?: intArrayOf(0, 0)

    private fun write(apiName: String, v: IntArray) {
        if (v[0] + v[1] > 20) { v[0] = v[0] / 2; v[1] = v[1] / 2 }
        runCatching { setKey(FOLDER, apiName, v) }
    }

    fun recordSuccess(apiName: String?) {
        if (apiName.isNullOrBlank()) return
        val v = read(apiName); v[0]++; write(apiName, v)
    }

    fun recordFailure(apiName: String?) {
        if (apiName.isNullOrBlank()) return
        val v = read(apiName); v[1]++; write(apiName, v)
    }

    /**
     * Score de 0 à 1 (0,5 si inconnu) : (réussites + 1) / (total + 2). Une extension jamais
     * essayée passe donc avant une extension qui échoue souvent, après une qui marche.
     */
    fun score(apiName: String): Double {
        val v = read(apiName)
        return (v[0] + 1.0) / (v[0] + v[1] + 2.0)
    }
}
