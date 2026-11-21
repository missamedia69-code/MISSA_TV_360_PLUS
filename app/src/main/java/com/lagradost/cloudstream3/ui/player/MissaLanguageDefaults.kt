package com.lagradost.cloudstream3.ui.player

import android.content.Context
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.R
import java.util.Locale

/**
 * Réglages par défaut d'un nouvel utilisateur, d'après la langue de l'application.
 *
 * Avant, un utilisateur qui n'avait encore rien choisi avait les sous-titres anglais
 * activés d'office et aucune préférence de version. Maintenant, pour une application en
 * français : VF privilégiée, piste audio française, sous-titres français (sauf sur une
 * source déjà en VF). Dès que l'utilisateur fait un choix dans « Langue et qualité »,
 * c'est son choix qui compte.
 */
object MissaLanguageDefaults {

    /** Code de la langue de l'application (« fr », « en »…), en minuscules. */
    fun appLanguage(context: Context? = CloudStreamApp.context): String {
        val saved = context?.let { ctx ->
            runCatching {
                PreferenceManager.getDefaultSharedPreferences(ctx)
                    .getString(ctx.getString(R.string.locale_key), null)
            }.getOrNull()
        }
        val tag = saved?.takeIf { it.isNotBlank() } ?: Locale.getDefault().toLanguageTag()
        return languageOf(tag)
    }

    /** « fr-CM » → « fr », « pt_BR » → « pt ». */
    fun languageOf(tag: String): String =
        tag.replace('_', '-').substringBefore('-').lowercase(Locale.ROOT).ifBlank { "en" }

    /** Version privilégiée tant que l'utilisateur n'en a pas choisi une. */
    fun defaultVersion(language: String = appLanguage()): MissaLinkVersion? = when (language) {
        "fr" -> MissaLinkVersion.VF
        else -> null
    }

    /**
     * Vrai si une piste audio annoncée [trackLanguage] (« fr », « fre », « fra », « fr-FR »…)
     * est dans la langue [language].
     */
    fun sameLanguage(trackLanguage: String?, language: String): Boolean {
        val code = trackLanguage?.let { languageOf(it) } ?: return false
        if (code == language) return true
        // Codes à 3 lettres (ISO 639-2) des langues courantes de l'application.
        val threeLetters = mapOf(
            "fr" to setOf("fre", "fra"),
            "en" to setOf("eng"),
            "es" to setOf("spa"),
            "pt" to setOf("por"),
            "de" to setOf("ger", "deu"),
            "ar" to setOf("ara"),
            "it" to setOf("ita")
        )
        return threeLetters[language]?.contains(code) == true
    }
}
