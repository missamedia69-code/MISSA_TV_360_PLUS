package com.lagradost.cloudstream3.ui.player

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.R

/**
 * Version (langue) d'une source vidéo, devinée d'après son nom.
 *
 * Les extensions françaises proposent souvent la même vidéo en plusieurs versions, chacune
 * sur sa propre source : « Uqload VF », « Voe VOSTFR », « Filemoon MULTI »… Le lecteur les
 * mélangeait dans une seule liste de serveurs. Ici, on reconnaît la version pour proposer
 * un choix simple (« Français », « VO sous-titrée »…) et retenir celle que l'utilisateur
 * préfère pour les vidéos suivantes.
 */
enum class MissaLinkVersion(val labelRes: Int) {
    VF(R.string.missa_version_vf),
    VOSTFR(R.string.missa_version_vostfr),
    MULTI(R.string.missa_version_multi),
    VO(R.string.missa_version_vo),
    DUB(R.string.missa_version_dub),
    SUB(R.string.missa_version_sub);

    companion object {
        private const val PREF_KEY = "missa_preferred_link_version_v1"

        // Mots longs : insensibles à la casse. Sigles de 2 lettres (FR, EN, VF, VO) : seulement
        // en majuscules, pour ne pas confondre avec « en », « fr » dans un titre ou une adresse.
        private val vostfr = Regex(
            """(?i)(?<![A-Za-z])(VOSTFR|VOST|SUBFRENCH|SUB[ ._-]?FR|STFR)(?![A-Za-z])"""
        )
        private val multi = Regex("""(?i)(?<![A-Za-z])MULTI(?![A-Za-z])""")
        private val vfWord = Regex(
            """(?i)(?<![A-Za-z])(TRUEFRENCH|FRENCH|FRAN[CcÇç]AIS|VFF|VFQ|VFI)(?![A-Za-z])"""
        )
        private val vfShort = Regex("""(?<![A-Za-z])(VF|FR)(?![A-Za-z])""")
        private val voWord = Regex("""(?i)(?<![A-Za-z])(ENGLISH|ANGLAIS)(?![A-Za-z])""")
        private val voShort = Regex("""(?<![A-Za-z])(VO|EN|ENG)(?![A-Za-z])""")
        private val dub = Regex("""(?i)(?<![A-Za-z])(DUB|DUBBED)(?![A-Za-z])""")
        private val sub = Regex("""(?i)(?<![A-Za-z])(SUB|SUBBED)(?![A-Za-z])""")

        /** Version annoncée dans [texts] (nom de la source, nom du lecteur…), ou null. */
        fun detect(vararg texts: String?): MissaLinkVersion? {
            val text = texts.filterNotNull().joinToString(" ")
            if (text.isBlank()) return null
            return when {
                vostfr.containsMatchIn(text) -> VOSTFR
                multi.containsMatchIn(text) -> MULTI
                vfWord.containsMatchIn(text) || vfShort.containsMatchIn(text) -> VF
                voWord.containsMatchIn(text) || voShort.containsMatchIn(text) -> VO
                dub.containsMatchIn(text) -> DUB
                sub.containsMatchIn(text) -> SUB
                else -> null
            }
        }

        /**
         * Version choisie la dernière fois dans « Langue et qualité ». Tant que l'utilisateur
         * n'a rien choisi : celle de la langue de l'application (VF pour le français).
         */
        var preferred: MissaLinkVersion?
            get() = getKey<String>(PREF_KEY)?.let { saved -> entries.firstOrNull { it.name == saved } }
                ?: runCatching { MissaLanguageDefaults.defaultVersion() }.getOrNull()
            set(value) {
                if (value == null) removeKey(PREF_KEY) else setKey(PREF_KEY, value.name)
            }
    }
}
