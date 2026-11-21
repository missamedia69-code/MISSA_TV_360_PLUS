package com.lagradost.cloudstream3.ui.activation

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.net.toUri
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import org.json.JSONObject

/**
 * Annonce du responsable (page /admin/ du site) : maintenance, nouveautés, promotion.
 *
 * Demandée au serveur au plus toutes les 3 heures, affichée une seule fois par appareil
 * (on retient le numéro de la dernière annonce vue), jamais pendant une vidéo. Sans réseau
 * ou sans annonce en cours : rien ne s'affiche.
 */
object MissaAnnouncements {
    private const val URL = "https://missatv.pages.dev/api/announcement"
    private const val PREFS = "missa_announcements"
    private const val CHECK_EVERY_MS = 3L * 60 * 60 * 1000

    data class Announcement(val id: Long, val title: String, val message: String, val link: String?)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var checking = false

    /** Appelé régulièrement par [MissaLicenceGate] quand l'accès est ouvert. */
    fun maybeShow(activity: Activity) {
        if (checking || activity.isFinishing) return
        val p = prefs(activity)
        val now = System.currentTimeMillis()
        val last = p.getLong("lastCheck", 0L)
        if (now - last in 0 until CHECK_EVERY_MS) return
        checking = true
        ioSafe {
            try {
                val found = fetch()
                // Annonce ou pas (ou réseau absent) : prochaine demande dans 3 h, pas avant.
                p.edit { putLong("lastCheck", System.currentTimeMillis()) }
                if (found == null) return@ioSafe
                if (found.id <= p.getLong("seenId", 0L)) return@ioSafe
                main {
                    if (activity.isFinishing || MissaSubscription.isPlayerVisible(activity)) {
                        // On réessaiera à la prochaine vérification, hors vidéo.
                        p.edit { putLong("lastCheck", 0L) }
                        return@main
                    }
                    p.edit { putLong("seenId", found.id) }
                    show(activity, found)
                }
            } finally {
                checking = false
            }
        }
    }

    /** Annonce en cours, null s'il n'y en a pas (ou en cas d'erreur réseau). */
    private suspend fun fetch(): Announcement? = try {
        val j = JSONObject(app.get(URL, timeout = 10L).text)
        val a = j.optJSONObject("announcement")
        if (a == null) {
            null
        } else {
            Announcement(
                id = a.optLong("id"),
                title = a.optString("title"),
                message = a.optString("message"),
                link = a.optString("link").takeIf { it.startsWith("https://") }
            ).takeIf { it.id > 0 && it.title.isNotBlank() }
        }
    } catch (e: Exception) {
        logError(e)
        null
    }

    private fun show(activity: Activity, a: Announcement) {
        val builder = AlertDialog.Builder(activity)
            .setTitle(a.title)
            .setMessage(a.message)
            .setNegativeButton(R.string.missa_announcement_ok, null)
        a.link?.let { link ->
            builder.setPositiveButton(R.string.missa_announcement_open) { _, _ ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, link.toUri())) }
                    .onFailure { logError(it) }
            }
        }
        runCatching { builder.show() }.onFailure { logError(it) }
    }
}
