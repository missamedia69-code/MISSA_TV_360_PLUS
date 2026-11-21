package com.lagradost.cloudstream3.ui.setup

import android.view.View
import android.widget.AbsListView
import android.widget.ArrayAdapter
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.navigation.fragment.findNavController
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.databinding.FragmentSetupLanguageBinding
import com.lagradost.cloudstream3.mvvm.safe
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.settings.appLanguages
import com.lagradost.cloudstream3.ui.settings.getCurrentLocale
import com.lagradost.cloudstream3.ui.settings.nameNextToFlagEmoji
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding

const val HAS_DONE_SETUP_KEY = "HAS_DONE_SETUP"

class SetupFragmentLanguage : BaseFragment<FragmentSetupLanguageBinding>(
    BaseFragment.BindingCreator.Inflate(FragmentSetupLanguageBinding::inflate)
) {

    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view)
    }

    override fun onBindingCreated(binding: FragmentSetupLanguageBinding) {
        // We don't want a crash for all users
        safe {
            val ctx = context ?: return@safe
            val settingsManager = PreferenceManager.getDefaultSharedPreferences(ctx)

            val arrayAdapter =
                ArrayAdapter<String>(ctx, R.layout.sort_bottom_single_choice)

            binding.apply {
                // MISSA TV 360 PLUS vector emblem in initial setup
                safe {
                    appIconImage.setImageDrawable(
                        ContextCompat.getDrawable(ctx, R.drawable.ic_missa_launcher_foreground)
                    )
                }

                val tags = appLanguages.map { it.second }
                val current = getCurrentLocale(ctx).replace('_', '-')
                val currentIndex = tags.indexOf(current).takeIf { it >= 0 }
                    ?: tags.indexOf(current.substringBefore('-'))

                arrayAdapter.addAll(appLanguages.map { it.nameNextToFlagEmoji() })
                listview1.adapter = arrayAdapter
                listview1.choiceMode = AbsListView.CHOICE_MODE_SINGLE
                if (currentIndex >= 0) listview1.setItemChecked(currentIndex, true)

                listview1.setOnItemClickListener { _, _, selectedLangIndex, _ ->
                    val langTagIETF = tags[selectedLangIndex]
                    CommonActivity.setLocale(activity, langTagIETF)
                    settingsManager.edit {
                        putString(getString(R.string.locale_key), langTagIETF)
                    }
                }

                // MISSA : premier lancement en une seule page. Les extensions et leurs univers
                // s'installent tout seuls (configuration du site), la disposition téléphone/TV
                // est détectée automatiquement, et langues des sources et types de contenus
                // gardent leurs valeurs par défaut (modifiables dans les Paramètres). Les pages
                // suivantes de l'assistant d'origine n'étaient qu'un détour, et la page
                // « Extensions » laissait croire qu'il fallait ajouter des dépôts soi-même.
                nextBtt.setText(R.string.missa_setup_start)
                nextBtt.setOnClickListener {
                    setKey(HAS_DONE_SETUP_KEY, true)
                    findNavController().navigate(R.id.navigation_home)
                }

                skipBtt.setOnClickListener {
                    setKey(HAS_DONE_SETUP_KEY, true)
                    findNavController().navigate(R.id.navigation_home)
                }
            }
        }
    }
}
