package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.Prerelease

/** Sources explicitement retirées de MISSA TV, même si un dépôt tiers les annonce encore.
 * Filtrer lors de l'enregistrement évite aussi qu'elles réapparaissent dans la recherche
 * et les sélecteurs de sources après chaque redémarrage.
 */
@Prerelease
object ExcludedSources {
    fun contains(name: String): Boolean {
        val compact = name.lowercase().filter { it.isLetterOrDigit() }
        return compact == "allforu" || compact == "allforuprovider" ||
            compact == "allforyou" || compact == "allforyouprovider"
    }
}
