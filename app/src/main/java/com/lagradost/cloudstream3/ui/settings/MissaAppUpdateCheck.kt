package com.lagradost.cloudstream3.ui.settings

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import com.lagradost.cloudstream3.ui.activation.MissaSubscription
import com.lagradost.cloudstream3.ui.universe.MissaDataUsage

/**
 * Recherche automatique d'une nouvelle version de l'application (GitHub, dernière version).
 *
 * Au démarrage, au plus toutes les 6 h en Wi-Fi et une fois par jour sur données mobiles :
 * une seule petite requête (quelques Ko). Si une version plus récente existe, la fenêtre
 * « Mettre à jour » s'affiche avec ses nouveautés ; « Ignorer » la fait taire jusqu'à la
 * suivante. Jamais par-dessus une vidéo. Désactivable dans Paramètres › Mises à jour.
 */
object MissaAppUpdateCheck {
    private const val PREFS = "missa_app_update"
    private const val KEY_LAST = "lastCheck"
    private const val WIFI_EVERY_MS = 6L * 60 * 60 * 1000
    private const val METERED_EVERY_MS = 24L * 60 * 60 * 1000

    /** Délai après l'ouverture : laisse l'accueil se charger avant la recherche. */
    const val START_DELAY_MS = 6_000L

    internal fun isDue(now: Long, last: Long, metered: Boolean): Boolean {
        val every = if (metered) METERED_EVERY_MS else WIFI_EVERY_MS
        return last <= 0L || now < last || now - last >= every
    }

    /** Vrai (et noté) si une recherche doit partir maintenant. */
    fun shouldCheckNow(activity: Activity): Boolean {
        if (activity.isFinishing || MissaSubscription.isPlayerVisible(activity)) return false
        val p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!isDue(now, p.getLong(KEY_LAST, 0L), MissaDataUsage.isMetered(activity))) return false
        p.edit { putLong(KEY_LAST, now) }
        return true
    }
}
