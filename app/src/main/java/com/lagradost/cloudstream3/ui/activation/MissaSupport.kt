package com.lagradost.cloudstream3.ui.activation

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.Coroutines.main

/**
 * « Aide et contact » : l'utilisateur écrit au responsable depuis l'application (problème,
 * paiement, contenu, suggestion). Le message part au serveur (/api/support) avec le code de
 * l'appareil, la version et le modèle, et se lit dans la page /admin/ du site.
 * L'e-mail de l'assistance reste proposé (seul moyen de contact public du responsable).
 *
 * Accessible depuis Paramètres et depuis l'écran de fin d'essai (où l'on ne peut rien
 * faire d'autre, et où se posent les questions de paiement).
 */
object MissaSupport {
    private const val SUPPORT_URL = "https://missatv.pages.dev/api/support"
    /** Adresse e-mail publique de l'assistance (page Contact du site). */
    private const val SUPPORT_EMAIL = "missamedia69@gmail.com"

    private val categories = listOf(
        "probleme" to R.string.missa_support_cat_problem,
        "paiement" to R.string.missa_support_cat_payment,
        "contenu" to R.string.missa_support_cat_content,
        "suggestion" to R.string.missa_support_cat_suggestion,
        "autre" to R.string.missa_support_cat_other,
    )

    private fun deviceCode(activity: Activity): String? =
        MissaLicence.snapshot(activity)?.code?.takeIf { it.isNotBlank() }

    private fun model(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { !it.isNullOrBlank() }.distinct().joinToString(" ")

    fun show(activity: Activity) {
        val density = activity.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val code = deviceCode(activity)

        val intro = TextView(activity).apply {
            text = activity.getString(R.string.missa_support_intro) +
                (code?.let { "\n" + activity.getString(R.string.missa_support_your_code, it) } ?: "")
        }
        val group = RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        categories.forEachIndexed { i, (_, label) ->
            group.addView(RadioButton(activity).apply {
                id = 0x5100 + i
                text = activity.getString(label)
                isChecked = i == 0
            })
        }
        val message = EditText(activity).apply {
            hint = activity.getString(R.string.missa_support_message_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP or Gravity.START
        }
        val contact = EditText(activity).apply {
            hint = activity.getString(R.string.missa_support_contact_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(intro)
            addView(group)
            addView(message)
            addView(contact)
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.missa_support_title)
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(R.string.missa_support_send, null)
            .setNeutralButton(R.string.missa_support_email, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                openEmail(activity, code, message.text?.toString().orEmpty())
            }
            val sendButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            sendButton.setOnClickListener {
                val text = message.text?.toString()?.trim().orEmpty()
                if (text.length < 5) {
                    message.error = activity.getString(R.string.missa_support_too_short)
                    return@setOnClickListener
                }
                val index = (group.checkedRadioButtonId - 0x5100).coerceIn(0, categories.lastIndex)
                val category = categories[index].first
                // Champs lus ici, sur le thread de l'interface : jamais depuis l'envoi en arrière-plan.
                val contactText = contact.text?.toString()?.trim().orEmpty()
                sendButton.isEnabled = false
                ioSafe {
                    val result = postMessage(code, category, text, contactText)
                    main {
                        sendButton.isEnabled = true
                        when (result) {
                            200 -> {
                                dialog.dismiss()
                                Toast.makeText(activity, R.string.missa_support_sent, Toast.LENGTH_LONG).show()
                            }
                            429 -> Toast.makeText(activity, R.string.missa_support_too_many, Toast.LENGTH_LONG).show()
                            else -> Toast.makeText(activity, R.string.missa_support_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    /** Code HTTP de la réponse (200 = reçu), ou 0 sans réseau. */
    private suspend fun postMessage(code: String?, category: String, message: String, contact: String): Int = try {
        app.post(
            SUPPORT_URL,
            json = mapOf(
                "code" to code,
                "category" to category,
                "message" to message,
                "contact" to contact,
                "appVersion" to BuildConfig.VERSION_NAME,
                "model" to model(),
                "source" to "app",
            ),
            timeout = 15L
        ).code
    } catch (e: Exception) {
        logError(e)
        0
    }

    private fun openEmail(activity: Activity, code: String?, draft: String) {
        val body = buildString {
            append(draft.trim())
            append("\n\n---\n")
            code?.let { append("Code : $it\n") }
            append("Version : ${BuildConfig.VERSION_NAME}\n")
            append("Appareil : ${model()}\n")
        }
        val uri = "mailto:$SUPPORT_EMAIL?subject=" + Uri.encode("MISSA TV 360 PLUS") +
            "&body=" + Uri.encode(body)
        try {
            activity.startActivity(Intent(Intent.ACTION_SENDTO, uri.toUri()))
        } catch (e: Exception) {
            logError(e)
            Toast.makeText(activity, SUPPORT_EMAIL, Toast.LENGTH_LONG).show()
        }
    }
}
