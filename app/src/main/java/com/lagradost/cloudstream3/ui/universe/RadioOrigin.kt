package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.os.Build
import com.lagradost.cloudstream3.R
import java.util.Locale

/**
 * AUDIT H7: where a radio station broadcasts from, resolved in one place.
 *
 * The station list used `country = "AF"` to mean "Africa". Two things went wrong with it:
 *  1. `"AF"` is the ISO-3166 code of Afghanistan, so it is a *country* code that never meant
 *     Africa outside this app;
 *  2. the flag was produced by `SubtitleHelper.getFlagFromIso`, which is built for *language*
 *     codes: it looks up `lang2country["af"]` first, and "af" is afrikaans, whose country is
 *     South Africa. Every panafrican station therefore showed the South African flag (V9).
 *
 * [REGION_AFRICA] and [REGION_WORLD] are explicit continent-level origins, a real country is
 * only recognised when ISO-3166 knows the code, and a region never gets a flag: a continent
 * does not have one.
 *
 * Known limitation: emoji flags are missing on some TV panels. The audit suggests a text
 * fallback (a "CM" chip); detecting a missing glyph needs measurement against the painted
 * typeface, so [displayLabel] is offered instead for the places that print the origin as text.
 */
object RadioOrigin {

    /** Continental panafrican station. Replaces the old `"AF"`. */
    const val REGION_AFRICA = "AFRICA"

    /** Worldwide station. Replaces the old `"INT"`. */
    const val REGION_WORLD = "WORLD"

    private val isoCountries: Set<String> = Locale.getISOCountries().toSet()

    sealed class Origin {
        data class Country(val iso: String) : Origin()
        data class Region(val code: String) : Origin()
        object Unknown : Origin()
    }

    fun from(code: String?): Origin {
        val raw = code?.trim().orEmpty()
        if (raw.isBlank()) return Origin.Unknown
        val upper = raw.uppercase(Locale.ROOT)
        // The legacy "AF" / "INT" keep mapping to their region so a station already saved in
        // the favourites with the old code is not dropped on upgrade.
        if (upper == REGION_AFRICA || upper == "AF") return Origin.Region(REGION_AFRICA)
        if (upper == REGION_WORLD || upper == "INT") return Origin.Region(REGION_WORLD)
        if (upper.length == 2 && upper in isoCountries) return Origin.Country(upper)
        return Origin.Unknown
    }

    /** Emoji flag for a country. A region and an unknown code have no flag: null. */
    fun flagFor(code: String?): String? {
        val iso = (from(code) as? Origin.Country)?.iso ?: return null
        val flagOffset = 0x1F1E6 - 'A'.code
        return buildString {
            for (letter in iso) appendCodePoint(flagOffset + letter.uppercaseChar().code)
        }
    }

    /**
     * Origin as readable text: the country name in the language of the device
     * ("Cameroun"), "Afrique" / "Monde" for a region, and the raw code as a last resort.
     */
    fun displayLabel(code: String?, context: Context? = null): String {
        return when (val origin = from(code)) {
            is Origin.Country -> {
                val locale = context?.currentLocale() ?: Locale.getDefault()
                runCatching { Locale("", origin.iso).getDisplayCountry(locale) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() && !it.equals(origin.iso, ignoreCase = true) }
                    ?: origin.iso
            }

            is Origin.Region -> when (origin.code) {
                REGION_AFRICA -> context?.getString(R.string.radio_origin_africa) ?: "Afrique"
                else -> context?.getString(R.string.radio_origin_world) ?: "Monde"
            }

            Origin.Unknown -> code?.trim().orEmpty()
        }
    }

    private fun Context.currentLocale(): Locale =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            resources.configuration.locale
        }
}
