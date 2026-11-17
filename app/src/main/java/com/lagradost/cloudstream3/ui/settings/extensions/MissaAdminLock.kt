package com.lagradost.cloudstream3.ui.settings.extensions

import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.R
import java.security.MessageDigest

/**
 * Verrou des réglages d'extensions : seul le responsable de MISSA TV connaît le code.
 *
 * Les écrans Extensions et Dépôt/Extensions demandent le code à l'ouverture. Code juste :
 * déverrouillé jusqu'à la fermeture de l'application. Annulation ou Retour : l'écran se ferme.
 *
 * Le code n'est pas écrit en clair : on compare son empreinte SHA-256. Un code à 4 chiffres
 * reste une protection légère (10 000 essais suffisent à le retrouver depuis l'APK) : elle
 * empêche un utilisateur de modifier les extensions par mégarde ou par curiosité.
 */
object MissaAdminLock {

    /** Adresse à laquelle les clients écrivent pour acheter le code (5 000 FCFA). */
    const val CONTACT_EMAIL = "missamedia69@gmail.com"

    private const val SALT = "missa-admin:"
    private const val CODE_SHA256 = "ba89c88cc562951442d28e1f429bfcd9cc7784a9c4622274552a57690b801007"

    @Volatile
    private var unlocked = false

    fun isUnlocked(): Boolean = unlocked

    fun check(code: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256").digest((SALT + code.trim()).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) } == CODE_SHA256
    }

    /** À appeler à l'ouverture d'un écran protégé : demande le code, ferme l'écran sinon. */
    fun guard(fragment: Fragment) {
        if (unlocked) return
        val ctx = fragment.context ?: return
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = ctx.getString(R.string.missa_admin_lock_hint)
            setSingleLine()
        }
        // Offre commerciale : le client peut acheter le code pour installer ses propres extensions.
        val buyInfo = TextView(ctx).apply {
            text = ctx.getString(R.string.missa_admin_lock_buy)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        // Adresse e-mail cliquable : ouvre l'application de messagerie avec un e-mail prêt à envoyer.
        // Sans messagerie (fréquent sur Android TV), l'adresse reste lisible à l'écran.
        val emailLink = TextView(ctx).apply {
            text = CONTACT_EMAIL
            textSize = 16f
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            isFocusable = true
            isClickable = true
            setOnClickListener {
                val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(CONTACT_EMAIL))
                    putExtra(Intent.EXTRA_SUBJECT, ctx.getString(R.string.missa_admin_lock_mail_subject))
                    putExtra(Intent.EXTRA_TEXT, ctx.getString(R.string.missa_admin_lock_mail_body))
                }
                runCatching { ctx.startActivity(mail) }.onFailure {
                    Toast.makeText(ctx, CONTACT_EMAIL, Toast.LENGTH_LONG).show()
                }
            }
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
            addView(buyInfo, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 2 })
            addView(emailLink, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = pad / 4 })
        }
        fun leave() {
            if (fragment.isAdded) runCatching { fragment.findNavController().popBackStack() }
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.missa_admin_lock_title)
            .setMessage(R.string.missa_admin_lock_desc)
            .setView(box)
            .setCancelable(true)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel) { _, _ -> leave() }
            .setOnCancelListener { leave() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (check(input.text?.toString().orEmpty())) {
                    unlocked = true
                    dialog.dismiss()
                } else {
                    input.text = null
                    input.error = ctx.getString(R.string.missa_admin_lock_wrong)
                    Toast.makeText(ctx, R.string.missa_admin_lock_wrong, Toast.LENGTH_SHORT).show()
                }
            }
        }
        dialog.show()
        input.post { input.requestFocus() }
    }
}
