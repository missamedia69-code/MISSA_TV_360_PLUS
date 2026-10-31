package com.lagradost.cloudstream3.ui.settings

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.edit
import androidx.core.os.ConfigurationCompat
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.APIHolder.allProviders
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.AddRemoveSitesBinding
import com.lagradost.cloudstream3.databinding.AddSiteInputBinding
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showDialog
import com.lagradost.cloudstream3.utils.SingleSelectionHelper.showMultiDialog
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.UIHelper.dismissSafe
import com.lagradost.cloudstream3.utils.USER_PROVIDER_API
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import java.util.Locale

fun getCurrentLocale(context: Context): String {
    val conf = context.resources.configuration
    return ConfigurationCompat.getLocales(conf).get(0)?.toLanguageTag() ?: "en"
}

val appLanguages = arrayListOf(
    /* begin language list */
    Pair("Afrikaans", "af"),
    Pair("Azərbaycan dili", "az"),
    Pair("Bahasa Indonesia", "in"),
    Pair("Bahasa Melayu", "ms"),
    Pair("català", "ca"),
    Pair("Deutsch", "de"),
    Pair("English", "en"),
    Pair("Español", "es"),
    Pair("Esperanto", "eo"),
    Pair("Français", "fr"),
    Pair("Galego", "gl"),
    Pair("hrvatski", "hr"),
    Pair("Italiano", "it"),
    Pair("Latviešu valoda", "lv"),
    Pair("Lietuvių kalba", "lt"),
    Pair("Magyar", "hu"),
    Pair("Malti", "mt"),
    Pair("Nederlands", "nl"),
    Pair("Norsk bokmål", "no"),
    Pair("Norsk nynorsk", "nn"),
    Pair("Polski", "pl"),
    Pair("Português", "pt"),
    Pair("Português (Brasil)", "pt-BR"),
    Pair("Română", "ro"),
    Pair("Shqip мова", "sq"),
    Pair("Slovenčina", "sk"),
    Pair("Soomaaliga", "so"),
    Pair("Svenska", "sv"),
    Pair("Tagalog", "tl"),
    Pair("Tiếng Việt", "vi"),
    Pair("Türkçe", "tr"),
    Pair("Wikang Filipino", "fil"),
    Pair("Čeština", "cs"),
    Pair("Ελληνικά", "el"),
    Pair("беларуская мова", "be"),
    Pair("български", "bg"),
    Pair("македонски", "mk"),
    Pair("русский", "ru"),
    Pair("українська", "uk"),
    Pair("עברית", "iw"),
    Pair("اردو", "ur"),
    Pair("العربية", "ar"),
    Pair("اللهجة النجدية", "ars"),
    Pair("عربي شامي", "apc"),
    Pair("فارسی", "fa"),
    Pair("کوردیی ناوەندی", "ckb"),
    Pair("नेपाली", "ne"),
    Pair("हिन्दी", "hi"),
    Pair("অসমীয়া", "as"),
    Pair("বাংলা", "bn"),
    Pair("ଓଡ଼ିଆ", "or"),
    Pair("தமிழ்", "ta"),
    Pair("ಕನ್ನಡ", "kn"),
    Pair("മലയാളം", "ml"),
    Pair("ພາສາລາວ", "lo"),
    Pair("ဗမာစာ", "my"),
    Pair("ትግርኛ", "ti"),
    Pair("አማርኛ", "am"),
    Pair("中文", "zh"),
    Pair("日本語 (にほんご)", "ja"),
    Pair("正體中文(臺灣)", "zh-TW"),
    Pair("한국어", "ko"),
    /* end language list */
).sortedBy { it.first.lowercase(Locale.ROOT) }

fun Pair<String, String>.nameNextToFlagEmoji(): String {
    val flag = SubtitleHelper.getFlagFromIso(this.second) ?: "\ud83c\udde6\ud83c\udde6"
    return "$flag\u00a0${this.first}"
}

class SettingsGeneral {
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class CustomSite(
        @JsonProperty("parentClassName") @JsonAlias("parentJavaClass")
        @SerialName("parentClassName") @JsonNames("parentJavaClass")
        val parentClassName: String,
        @JsonProperty("name") @SerialName("name") val name: String,
        @JsonProperty("url") @SerialName("url") val url: String,
        @JsonProperty("lang") @SerialName("lang") val lang: String,
    )

    companion object {
        fun Fragment.pickDownloadPath(uri: Uri?, path: String?) {
            if (uri == null) return

            val context = context ?: CloudStreamApp.context ?: return
            val visual = path ?: uri.toString()
            PreferenceManager.getDefaultSharedPreferences(context).edit {
                putString(getString(R.string.download_path_key), uri.toString())
                putString(context.getString(R.string.download_path_key_visual), visual)
            }
        }

        fun getCurrent(): MutableList<CustomSite> {
            return getKey<Array<CustomSite>>(USER_PROVIDER_API)?.toMutableList()
                ?: mutableListOf()
        }

        fun showAdd() {
            val providers = allProviders.distinctBy { it::class }.sortedBy { it.name }
            val context = activity
            context?.showDialog(
                providers.map { "${it.name} (${it.mainUrl})" },
                -1,
                context.getString(R.string.add_site_pref),
                true,
                {}
            ) { selection ->
                val provider = providers.getOrNull(selection) ?: return@showDialog

                val binding: AddSiteInputBinding = AddSiteInputBinding.inflate(
                    LayoutInflater.from(context),
                    null,
                    false
                )

                val builder =
                    AlertDialog.Builder(context, R.style.AlertDialogCustom)
                        .setView(binding.root)

                val dialog = builder.create()
                dialog.show()

                binding.text2.text = provider.name
                binding.applyBtt.setOnClickListener {
                    val name = binding.siteNameInput.text?.toString()
                    val url = binding.siteUrlInput.text?.toString()
                    val lang = binding.siteLangInput.text?.toString()
                    val realLang = if (lang.isNullOrBlank()) provider.lang else lang
                    val simpleName = provider::class.simpleName
                    if (url.isNullOrBlank() || name.isNullOrBlank() || simpleName == null) {
                        showToast(R.string.error_invalid_data, Toast.LENGTH_SHORT)
                        return@setOnClickListener
                    }

                    val current = getCurrent()
                    val newSite = CustomSite(simpleName, name, url, realLang)
                    current.add(newSite)
                    setKey(USER_PROVIDER_API, current.toTypedArray())
                    MainActivity.afterPluginsLoadedEvent.invoke(false)

                    dialog.dismissSafe(activity)
                }
                binding.cancelBtt.setOnClickListener {
                    dialog.dismissSafe(activity)
                }
            }
        }

        fun showDelete() {
            val current = getCurrent()
            val context = activity
            context?.showMultiDialog(
                current.map { it.name },
                listOf(),
                context.getString(R.string.remove_site_pref),
                {}
            ) { indexes ->
                current.removeAll(indexes.map { current[it] })
                setKey(USER_PROVIDER_API, current.toTypedArray())
            }
        }

        fun showAddOrDelete() {
            val context = activity

            val binding: AddRemoveSitesBinding = AddRemoveSitesBinding.inflate(
                LayoutInflater.from(context),
                null,
                false
            )
            val builder =
                AlertDialog.Builder(context ?: return, R.style.AlertDialogCustom)
                    .setView(binding.root)

            val dialog = builder.create()
            dialog.show()

            binding.addSite.setOnClickListener {
                showAdd()
                dialog.dismissSafe(activity)
            }
            binding.removeSite.setOnClickListener {
                showDelete()
                dialog.dismissSafe(activity)
            }
        }
    }
}
