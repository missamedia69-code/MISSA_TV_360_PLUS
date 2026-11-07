package com.lagradost.cloudstream3.syncproviders

import android.content.Context
import androidx.core.content.edit
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJsonLiteral
import com.lagradost.cloudstream3.utils.PREFERENCES_NAME

/**
 * SECURITE S1 : les jetons de connexion (MAL, Kitsu, AniList, Simkl, OpenSubtitles,
 * AnimeSkip…) étaient rangés dans `rebuild_preference`, le fichier de préférences général.
 * Comme Android sauvegarde ce fichier (allowBackup="true"), les jetons partaient dans le
 * cloud et dans les transferts vers un nouvel appareil, alors que l'export manuel de
 * l'application (BackupUtils) les exclut déjà.
 *
 * Ils vivent maintenant dans leur propre fichier, exclu des règles de sauvegarde
 * (res/xml/backup_descriptor.xml et res/xml/data_extraction_rules.xml). Les réglages,
 * favoris et historique restent sauvegardés normalement.
 */
internal object AccountSecretStore {
    private const val FILE_NAME = "missa_accounts"

    private fun prefs() =
        CloudStreamApp.context?.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private fun legacyPrefs() =
        CloudStreamApp.context?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /**
     * Déplace la valeur depuis `rebuild_preference` la première fois. Sans cela, la mise à
     * jour déconnecterait tout le monde de ses services de synchronisation.
     */
    private fun migrate(key: String) {
        val own = prefs() ?: return
        if (own.contains(key)) return
        val json = legacyPrefs()?.getString(key, null) ?: return
        runCatching {
            own.edit {
                putString(key, json)
            }
            legacyPrefs()?.edit {
                remove(key)
            }
        }
    }

    fun <T : Any> read(key: String, valueType: Class<T>): T? {
        migrate(key)
        val json = prefs()?.getString(key, null) ?: return null
        return runCatching { parseJson(json, valueType.kotlin) }.getOrNull()
    }

    fun readInt(key: String, default: Int): Int {
        migrate(key)
        val json = prefs()?.getString(key, null) ?: return default
        return runCatching { parseJson<Int>(json) }.getOrDefault(default)
    }

    fun write(key: String, value: Any?) {
        runCatching {
            prefs()?.edit {
                putString(key, value?.toJsonLiteral())
            }
        }
    }
}
