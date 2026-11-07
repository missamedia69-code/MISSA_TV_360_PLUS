package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJsonLiteral

/**
 * PERF A5 : les catalogues Radio / Musique / Enfants sont des JSON de plusieurs centaines de
 * kilo-octets. Ils étaient stockés dans `rebuild_preference`, le fichier de préférences
 * général, or SharedPreferences réécrit le fichier **entier** à chaque `apply()` : la moindre
 * écriture (un favori, une position de lecture, un réglage) réécrivait donc aussi les trois
 * catalogues sur le disque, et inversement.
 *
 * Ils vivent maintenant dans leur propre fichier. `read()` bascule tout seul depuis l'ancien
 * emplacement : l'utilisateur qui met à jour l'application retrouve son catalogue au premier
 * lancement au lieu de le re-télécharger.
 */
object MissaCatalogueCache {
    private const val FILE_NAME = "missa_catalogue_cache"

    // @PublishedApi : read() est inline, il ne peut pas appeler un membre private.
    @PublishedApi
    internal fun prefs(): SharedPreferences? =
        CloudStreamApp.context?.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Lit un catalogue, en le déplaçant depuis `rebuild_preference` s'il y est encore. */
    inline fun <reified T : Any> read(key: String): T? {
        val own = runCatching {
            prefs()?.getString(key, null)?.let { parseJson<T>(it) }
        }.getOrNull()
        if (own != null) return own

        // Migration unique : avant A5 le catalogue était dans rebuild_preference.
        val legacy = getKey<T>(key)
        if (legacy != null) {
            write(key, legacy)
            removeKey(key)
        }
        return legacy
    }

    /** Écrit un catalogue. À appeler depuis Dispatchers.IO : la sérialisation est coûteuse. */
    fun write(key: String, value: Any?) {
        runCatching {
            prefs()?.edit {
                putString(key, value?.toJsonLiteral())
            }
        }
    }

    /** Oublie un catalogue, quel que soit le fichier qui le contient. */
    fun remove(key: String) {
        runCatching {
            prefs()?.edit {
                remove(key)
            }
        }
        removeKey(key)
    }
}
