package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import androidx.core.os.ConfigurationCompat
import com.lagradost.cloudstream3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.URLEncoder
import java.text.Collator
import java.util.Locale

/**
 * Univers Radio : accès à **toutes** les stations de l'annuaire libre Radio Browser
 * (plus de 50 000 stations, 225 pays), sans tout garder en mémoire.
 *
 * L'ancien univers chargeait au démarrage un échantillon fixe (~850 stations) et n'en affichait
 * que quelques dizaines par rangée. Ici, rien n'est chargé d'avance : l'utilisateur choisit un
 * pays ou un thème, et ses stations arrivent par pages de [PAGE_SIZE] (les plus écoutées
 * d'abord) au fil du défilement. Seule la page en cours vit en mémoire : c'est ce qui permet
 * de parcourir tout l'annuaire sur un téléviseur qui n'a que 512 Mo de RAM.
 */
object MissaRadioBrowse {

    const val PAGE_SIZE = 120

    /** La liste des pays change peu : on la relit au plus une fois par semaine. */
    private const val COUNTRIES_MAX_AGE_MS = 7L * 24 * 3600_000L
    private const val COUNTRIES_FILE = "missa-radio-countries.json"

    enum class Continent(val labelRes: Int) {
        AFRICA(R.string.radio_continent_africa),
        EUROPE(R.string.radio_continent_europe),
        AMERICAS(R.string.radio_continent_americas),
        ASIA(R.string.radio_continent_asia),
        OCEANIA(R.string.radio_continent_oceania)
    }

    data class Country(
        val iso: String,
        val name: String,
        val stationCount: Int,
        val continent: Continent
    )

    /** Un thème = un mot-clé (tag) de l'annuaire, présenté sous un nom lisible. */
    data class Theme(val id: String, val labelRes: Int, val tag: String)

    val themes: List<Theme> = listOf(
        Theme("news", R.string.radio_theme_news, "news"),
        Theme("talk", R.string.radio_theme_talk, "talk"),
        Theme("african", R.string.radio_theme_african, "african"),
        Theme("afrobeats", R.string.radio_theme_afrobeats, "afrobeats"),
        Theme("gospel", R.string.radio_theme_gospel, "gospel"),
        Theme("christian", R.string.radio_theme_christian, "christian"),
        Theme("islam", R.string.radio_theme_islam, "islam"),
        Theme("zouk", R.string.radio_theme_zouk, "zouk"),
        Theme("reggae", R.string.radio_theme_reggae, "reggae"),
        Theme("hiphop", R.string.radio_theme_hiphop, "hip hop"),
        Theme("rnb", R.string.radio_theme_rnb, "rnb"),
        Theme("jazz", R.string.radio_theme_jazz, "jazz"),
        Theme("blues", R.string.radio_theme_blues, "blues"),
        Theme("pop", R.string.radio_theme_pop, "pop"),
        Theme("rock", R.string.radio_theme_rock, "rock"),
        Theme("dance", R.string.radio_theme_dance, "dance"),
        Theme("electronic", R.string.radio_theme_electronic, "electronic"),
        Theme("chanson", R.string.radio_theme_chanson, "chanson"),
        Theme("latin", R.string.radio_theme_latin, "latin"),
        Theme("classical", R.string.radio_theme_classical, "classical"),
        Theme("oldies", R.string.radio_theme_oldies, "oldies"),
        Theme("chillout", R.string.radio_theme_chillout, "chillout"),
        Theme("sports", R.string.radio_theme_sports, "sports"),
        Theme("kids", R.string.radio_theme_kids, "children")
    )

    // ------------------------------------------------------------------ Continents

    private val AFRICA = setOf(
        "DZ", "AO", "BJ", "BW", "BF", "BI", "CV", "CM", "CF", "TD", "KM", "CG", "CD", "CI", "DJ",
        "EG", "GQ", "ER", "SZ", "ET", "GA", "GM", "GH", "GN", "GW", "KE", "LS", "LR", "LY", "MG",
        "MW", "ML", "MR", "MU", "YT", "MA", "MZ", "NA", "NE", "NG", "RE", "RW", "SH", "ST", "SN",
        "SC", "SL", "SO", "ZA", "SS", "SD", "TZ", "TG", "TN", "UG", "EH", "ZM", "ZW"
    )
    private val EUROPE = setOf(
        "AL", "AD", "AT", "BY", "BE", "BA", "BG", "HR", "CY", "CZ", "DK", "EE", "FO", "FI", "FR",
        "DE", "GI", "GR", "GG", "HU", "IS", "IE", "IM", "IT", "JE", "XK", "LV", "LI", "LT", "LU",
        "MT", "MD", "MC", "ME", "NL", "MK", "NO", "PL", "PT", "RO", "RU", "SM", "RS", "SK", "SI",
        "ES", "SE", "CH", "UA", "GB", "VA", "AX", "SJ"
    )
    private val AMERICAS = setOf(
        "AI", "AG", "AR", "AW", "BS", "BB", "BZ", "BM", "BO", "BQ", "BR", "CA", "KY", "CL", "CO",
        "CR", "CU", "CW", "DM", "DO", "EC", "SV", "FK", "GF", "GL", "GD", "GP", "GT", "GY", "HT",
        "HN", "JM", "MQ", "MX", "MS", "NI", "PA", "PY", "PE", "PR", "BL", "KN", "LC", "MF", "PM",
        "VC", "SX", "SR", "TT", "TC", "US", "UY", "VE", "VG", "VI", "UM"
    )
    private val OCEANIA = setOf(
        "AS", "AU", "CK", "FJ", "PF", "GU", "KI", "MH", "FM", "NR", "NC", "NZ", "NU", "NF", "MP",
        "PW", "PG", "PN", "WS", "SB", "TK", "TO", "TV", "VU", "WF"
    )

    fun continentOf(iso: String): Continent = when (iso.uppercase(Locale.ROOT)) {
        in AFRICA -> Continent.AFRICA
        in EUROPE -> Continent.EUROPE
        in AMERICAS -> Continent.AMERICAS
        in OCEANIA -> Continent.OCEANIA
        else -> Continent.ASIA
    }

    /** Pays mis en avant en tête de leur continent (public principal de l'application). */
    private val PRIORITY = listOf("CM", "CI", "SN", "CD", "CG", "GA", "BJ", "TG", "BF", "ML", "GN", "NE", "TD", "CF", "FR", "BE", "CH", "CA", "HT")

    // ------------------------------------------------------------------ Pays

    /**
     * Lit la liste renvoyée par `/json/countries` : nom anglais, code ISO, nombre de stations.
     * Les noms sont traduits dans la langue de l'appareil ; un code inconnu est ignoré.
     */
    fun parseCountries(json: String, locale: Locale = Locale.getDefault()): List<Country> {
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val isoCountries = Locale.getISOCountries().toSet()
        val byIso = LinkedHashMap<String, Country>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val iso = obj.optString("iso_3166_1").trim().uppercase(Locale.ROOT)
            val count = obj.optInt("stationcount", 0)
            if (iso.length != 2 || iso !in isoCountries || count <= 0) continue
            val name = runCatching { Locale("", iso).getDisplayCountry(locale) }.getOrNull()
                ?.takeIf { it.isNotBlank() && !it.equals(iso, ignoreCase = true) }
                ?: obj.optString("name").trim().ifBlank { iso }
            val previous = byIso[iso]
            byIso[iso] = Country(iso, name, count + (previous?.stationCount ?: 0), continentOf(iso))
        }
        return sortCountries(byIso.values.toList(), locale)
    }

    /** Pays prioritaires d'abord, puis ordre alphabétique de la langue de l'appareil. */
    fun sortCountries(countries: List<Country>, locale: Locale = Locale.getDefault()): List<Country> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        return countries.sortedWith(
            compareBy<Country> { it.continent.ordinal }
                .thenBy { PRIORITY.indexOf(it.iso).let { idx -> if (idx < 0) Int.MAX_VALUE else idx } }
                .thenComparator { a, b -> collator.compare(a.name, b.name) }
        )
    }

    @Volatile
    private var countriesMemo: List<Country>? = null

    /**
     * Tous les pays qui ont au moins une station en état de marche. Lit d'abord le fichier
     * en cache (instantané, fonctionne hors connexion), et ne le rafraîchit qu'une fois par
     * semaine ou sur demande.
     */
    suspend fun countries(context: Context, force: Boolean = false): List<Country> = withContext(Dispatchers.IO) {
        val locale = ConfigurationCompat.getLocales(context.resources.configuration)[0] ?: Locale.getDefault()
        val file = File(context.cacheDir, COUNTRIES_FILE)
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < COUNTRIES_MAX_AGE_MS
        countriesMemo?.takeIf { !force && fresh }?.let { return@withContext it }
        if (!force && fresh) {
            val cached = runCatching { parseCountries(file.readText(), locale) }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) return@withContext cached.also { countriesMemo = it }
        }
        val json = MissaRadioProviders.fetchRadioBrowserJson("?hidebroken=true", endpoint = "countries")
        val parsed = json?.let { parseCountries(it, locale) }.orEmpty()
        if (parsed.isNotEmpty()) {
            runCatching { file.writeText(json!!) }
            return@withContext parsed.also { countriesMemo = it }
        }
        // Hors connexion : l'ancien fichier, même périmé, vaut mieux que rien.
        val stale = runCatching { if (file.exists()) parseCountries(file.readText(), locale) else emptyList() }
            .getOrDefault(emptyList())
        stale.also { if (it.isNotEmpty()) countriesMemo = it }
    }

    // ------------------------------------------------------------------ Stations

    /** Une page des stations d'un pays, les plus écoutées d'abord. */
    suspend fun stationsOfCountry(iso: String, offset: Int, limit: Int = PAGE_SIZE): List<RadioStationPreset>? {
        val code = iso.trim().uppercase(Locale.ROOT).takeIf { it.length == 2 } ?: return emptyList()
        val json = MissaRadioProviders.fetchRadioBrowserJson(
            "bycountrycodeexact/$code?hidebroken=true&order=votes&reverse=true&limit=$limit&offset=$offset"
        ) ?: return null
        return withContext(Dispatchers.Default) { MissaRadioProviders.parseRadioBrowserArray(json) }
    }

    /** Une page des stations d'un thème, les plus écoutées d'abord. */
    suspend fun stationsOfTag(tag: String, offset: Int, limit: Int = PAGE_SIZE): List<RadioStationPreset>? {
        val enc = runCatching { URLEncoder.encode(tag.trim(), "UTF-8").replace("+", "%20") }.getOrDefault(tag)
        val json = MissaRadioProviders.fetchRadioBrowserJson(
            "bytag/$enc?hidebroken=true&order=votes&reverse=true&limit=$limit&offset=$offset"
        ) ?: return null
        return withContext(Dispatchers.Default) { MissaRadioProviders.parseRadioBrowserArray(json) }
    }

    /** Recherche par nom dans tout l'annuaire. */
    suspend fun searchByName(query: String, offset: Int, limit: Int = PAGE_SIZE): List<RadioStationPreset>? {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val enc = runCatching { URLEncoder.encode(q, "UTF-8") }.getOrDefault(q)
        val json = MissaRadioProviders.fetchRadioBrowserJson(
            "search?name=$enc&hidebroken=true&order=votes&reverse=true&limit=$limit&offset=$offset"
        ) ?: return null
        return withContext(Dispatchers.Default) { MissaRadioProviders.parseRadioBrowserArray(json) }
    }

    /**
     * Ajoute une page à une liste déjà affichée sans doublon (même flux ou même nom de station
     * dans le même pays : l'annuaire liste souvent une station deux fois).
     */
    fun appendUnique(current: List<RadioStationPreset>, page: List<RadioStationPreset>): List<RadioStationPreset> {
        if (page.isEmpty()) return current
        val seen = HashSet<String>(current.size * 2 + page.size * 2)
        current.forEach { st ->
            seen += st.streamUrl.lowercase(Locale.ROOT)
            dedupName(st)?.let { seen += it }
        }
        val out = ArrayList<RadioStationPreset>(current.size + page.size)
        out.addAll(current)
        for (st in page) {
            val url = st.streamUrl.lowercase(Locale.ROOT)
            val name = dedupName(st)
            if (url in seen || (name != null && name in seen)) continue
            seen += url
            name?.let { seen += it }
            out += st
        }
        return out
    }

    /** Clé « même station » : nom simplifié + pays. Null si le nom ne dit rien (« Radio FM »). */
    private fun dedupName(st: RadioStationPreset): String? {
        val key = MissaRadioProviders.normalizeStationKey(st.name)
        if (key.length < 2) return null
        return "name:$key|" + st.country.uppercase(Locale.ROOT)
    }
}
