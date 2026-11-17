package com.lagradost.cloudstream3.ui.settings.extensions

import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
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
        val box = FrameLayout(ctx).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
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
