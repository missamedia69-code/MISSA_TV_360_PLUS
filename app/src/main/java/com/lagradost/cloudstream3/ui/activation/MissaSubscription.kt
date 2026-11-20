package com.lagradost.cloudstream3.ui.activation

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.player.AbstractPlayerFragment
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main
import java.text.DateFormat
import java.util.Date

/**
 * « Mon abonnement » (Paramètres) et rappel avant la fin de l'essai ou de l'abonnement.
 *
 * L'utilisateur voit à tout moment où il en est (essai, abonné jusqu'au…, délai de grâce),
 * le code à saisir sur son 2e appareil, et peut renouveler en un geste (page Activer du
 * site, déjà réglée sur cet appareil). Le rappel évite le blocage surprise : moins de 3 h
 * d'essai ou moins de 3 jours d'abonnement, au plus une fois toutes les 10 h, jamais
 * pendant une vidéo.
 */
object MissaSubscription {

    private fun date(ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT).format(Date(ms))

    private fun statusText(ctx: Context, s: MissaLicence.Snapshot?): String {
        if (s == null) return ctx.getString(R.string.missa_sub_unknown)
        val now = System.currentTimeMillis() + s.serverOffset
        return when {
            !s.enforced -> ctx.getString(R.string.missa_sub_free)
            s.expiresAt > now -> ctx.getString(R.string.missa_sub_active, date(s.expiresAt))
            s.graceEndsAt > now -> ctx.getString(R.string.missa_sub_grace, date(s.graceEndsAt))
            s.expiresAt == 0L && s.trialEndsAt > now -> ctx.getString(R.string.missa_sub_trial, date(s.trialEndsAt))
            else -> ctx.getString(R.string.missa_sub_expired)
        }
    }

    /** Ouvre la page Activer du site pour cet appareil (formules, paiement Mobile Money). */
    private fun openPayPage(activity: Activity, s: MissaLicence.Snapshot?) {
        val url = s?.payUrl?.takeIf { it.isNotBlank() }
            ?: ("https://" + MissaLicence.SITE_ACTIVATE + (s?.code?.let { "/?code=$it" } ?: ""))
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            logError(e)
            Toast.makeText(activity, MissaLicence.SITE_ACTIVATE, Toast.LENGTH_LONG).show()
        }
    }

    private fun copyCode(activity: Activity, code: String) {
        runCatching {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("MISSA", code))
            Toast.makeText(activity, activity.getString(R.string.missa_sub_code_copied, code), Toast.LENGTH_SHORT).show()
        }
    }

    /** Écran « Mon abonnement » : état à jour (redemandé au serveur), code, renouvellement. */
    fun show(activity: Activity) {
        ioSafe {
            runCatching { MissaLicence.refresh(activity) }
            main { showNow(activity) }
        }
    }

    private fun showNow(activity: Activity) {
        if (activity.isFinishing) return
        val s = MissaLicence.snapshot(activity)
        val code = s?.code?.takeIf { it.isNotBlank() }
        val message = buildString {
            append(statusText(activity, s))
            if (code != null) {
                append("\n\n")
                append(activity.getString(R.string.missa_sub_code, code))
                append("\n")
                append(activity.getString(R.string.missa_sub_second_device))
            }
        }
        val builder = AlertDialog.Builder(activity)
            .setTitle(R.string.missa_sub_title)
            .setMessage(message)
            .setPositiveButton(R.string.missa_sub_renew) { _, _ -> openPayPage(activity, s) }
            .setNegativeButton(R.string.missa_sub_close, null)
        if (code != null) builder.setNeutralButton(R.string.missa_sub_copy_code) { _, _ -> copyCode(activity, code) }
        builder.show()
    }

    /** Vrai si une vidéo est à l'écran : on ne l'interrompt pas pour un rappel. */
    private fun isPlayerVisible(activity: Activity): Boolean {
        val fa = activity as? FragmentActivity ?: return false
        fun visit(list: List<Fragment>): Boolean = list.any { f ->
            (f is AbstractPlayerFragment<*> && f.isVisible) || visit(f.childFragmentManager.fragments)
        }
        return runCatching { visit(fa.supportFragmentManager.fragments) }.getOrDefault(false)
    }

    /** Appelé par MissaLicenceGate quand l'accès est ouvert. */
    fun maybeRemind(activity: Activity) {
        if (activity.isFinishing || isPlayerVisible(activity)) return
        val endsAt = MissaLicence.takeExpiryReminder(activity) ?: return
        val s = MissaLicence.snapshot(activity)
        val trial = s != null && s.expiresAt == 0L
        AlertDialog.Builder(activity)
            .setTitle(if (trial) R.string.missa_sub_reminder_trial_title else R.string.missa_sub_reminder_title)
            .setMessage(activity.getString(
                if (trial) R.string.missa_sub_reminder_trial else R.string.missa_sub_reminder, date(endsAt)
            ))
            .setPositiveButton(R.string.missa_sub_renew) { _, _ -> openPayPage(activity, s) }
            .setNegativeButton(R.string.missa_sub_later, null)
            .show()
    }
}
