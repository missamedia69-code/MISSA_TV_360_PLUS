package com.lagradost.cloudstream3.ui.universe

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/**
 * Unified, provider-attributed free radio aggregation & playlist parsing engine for MISSA TV 360 PLUS.
 *
 * Integrates coherently and resiliently:
 * 1. [PROVIDER_MISSA_DIRECT] — Public & flagship direct bouquets (RFI, Africa Radio, Radio Balafon,
 *    BBC Africa, VOA, Radio France, 8 thematic FIP channels, Mouv', Radio Swiss Jazz/Pop/Classic).
 * 2. [PROVIDER_SOMAFM] — Commercial-free SomaFM channels (built-in instant presets + live JSON API
 *    with 512px artwork and 4-mirror Icecast failover).
 * 3. [PROVIDER_RADIO_BROWSER] — Open-source 45,000+ worldwide directory with multi-mirror failover
 *    (de1 / fi1 / nl1 / at1), regional priority slices (Cameroon, Africa, Francophonie, Music, World)
 *    and real-time online search.
 * 4. [PROVIDER_ZENO_AFRICA] — Verified active Zeno.FM African & Cameroonian community broadcasts.
 * 5. [PROVIDER_LAUT_FM] — Curated & API-backed Laut.fm genre stations.
 * 6. [PROVIDER_M3U_PLAYLISTS] — Open-source GitHub M3U radio repositories (junguler, mikepierce)
 *    plus full #EXTM3U / .pls parser for user playlist imports.
 * 7. [PROVIDER_ICECAST_XIPH] — Bounded streaming slice of the Xiph.org open Icecast directory.
 */
internal object MissaRadioProviders {
    const val PROVIDER_CUSTOM = "Mes Radios"
    const val PROVIDER_MISSA_DIRECT = DIRECT_RADIO_API_NAME
    const val PROVIDER_RADIO_BROWSER = "Radio Browser"
    const val PROVIDER_ZENO_AFRICA = "Zeno.FM Afrique"
    const val PROVIDER_SOMAFM = "SomaFM"
    const val PROVIDER_LAUT_FM = "Laut.fm"
    const val PROVIDER_M3U_PLAYLISTS = "Playlists M3U"
    const val PROVIDER_ICECAST_XIPH = "Icecast Xiph"

    private const val USER_AGENT = "MISSA-TV-360-PLUS/2.0 (Android; Universal Media Hub)"
    private const val REQUEST_TIMEOUT_MS = 8_500L
    private const val XIPH_MAX_CHARS = 220_000

    private val RADIO_BROWSER_MIRRORS = listOf(
        "https://de1.api.radio-browser.info",
        "https://fi1.api.radio-browser.info",
        "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info"
    )

    @Volatile
    private var preferredMirrorIndex: Int = 0

    private val httpHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, audio/x-mpegurl, application/xml, text/plain, */*"
    )

    private fun brandFavicon(domain: String): String =
        "https://www.google.com/s2/favicons?domain=$domain&sz=128"

    /**
     * Instant zero-latency curated direct streams across Africa, Cameroon, Radio France/FIP,
     * Radio Swiss, SomaFM and Laut.fm — works offline / before remote providers finish loading.
     */
    val curatedInstantStations: List<RadioStationPreset> = listOf(
        // --- 1. Afrique, Cameroun & Grands Réseaux Internationaux ---
        RadioStationPreset(
            id = "rfi_afrique",
            name = "RFI Afrique",
            genre = "Afrique • Actualités, Débats & Magazines",
            country = "AF",
            streamUrl = "https://rfiafrique64k.ice.infomaniak.ch/rfiafrique-64.mp3",
            logoUrl = brandFavicon("rfi.fr"),
            providerName = PROVIDER_MISSA_DIRECT,
            fallbackUrls = listOf("https://rfiafrique96k.ice.infomaniak.ch/rfiafrique-96k.mp3")
        ),
        RadioStationPreset(
            id = "africa_radio",
            name = "Africa Radio",
            genre = "Panafricaine • Info, Culture, Makossa & Afrobeats",
            country = "AF",
            streamUrl = "https://african1paris.ice.infomaniak.ch/african1paris-128.mp3",
            logoUrl = brandFavicon("africaradio.com"),
            providerName = PROVIDER_MISSA_DIRECT
        ),
        RadioStationPreset(
            id = "radio_balafon_cm",
            name = "Radio Balafon 90.3 Douala",
            genre = "Cameroun • Info, Culture, Makossa & Bikutsi",
            country = "CM",
            streamUrl = "https://listen.radioking.com/radio/10903/stream/22164",
            logoUrl = brandFavicon("radiobalafon.net"),
            providerName = PROVIDER_MISSA_DIRECT
        ),
        RadioStationPreset(
            id = "bbc_world_africa",
            name = "BBC World Service Africa",
            genre = "Afrique & Monde • News 24/7",
            country = "AF",
            streamUrl = "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service",
            logoUrl = brandFavicon("bbc.com"),
            providerName = PROVIDER_MISSA_DIRECT
        ),
        RadioStationPreset(
            id = "bbc_afrique",
            name = "BBC Afrique",
            genre = "Afrique Francophone • Info & Direct",
            country = "AF",
            streamUrl = "https://stream.live.vc.bbcmedia.co.uk/bbc_afrique_radio",
            logoUrl = brandFavicon("bbc.com"),
            providerName = PROVIDER_MISSA_DIRECT
        ),
        RadioStationPreset(
            id = "rfi_musique",
            name = "RFI Musique",
            genre = "Musiques d'Afrique, Monde & Francophonie",
            country = "AF",
            streamUrl = "https://rfimusiquemonde64k.ice.infomaniak.ch/rfimusiquemonde-64.mp3",
            logoUrl = brandFavicon("musique.rfi.fr"),
            providerName = PROVIDER_MISSA_DIRECT
        ),
        RadioStationPreset(
            id = "rfi_monde",
            name = "RFI Monde",
            genre = "International & Francophonie • Info 24/7",
            country = "FR",
            streamUrl = "https://rfimonde64k.ice.infomaniak.ch/rfimonde-64.mp3",
            logoUrl = brandFavicon("rfi.fr"),
            providerName = PROVIDER_MISSA_DIRECT
        ),
        RadioStationPreset(
            id = "monte_carlo_doualiya",
            name = "Monte Carlo Doualiya",
            genre = "International, Méditerranée & Monde",
            country = "INT",
            streamUrl = "https://montecarlodoualiya128k.ice.infomaniak.ch/mc-doualiya.mp3",
            logoUrl = brandFavicon("mc-doualiya.com"),
            providerName = PROVIDER_MISSA_DIRECT
        ),

        // --- 2. Bouquet Radio France, Mouv' & FIP (Zéro Publicité, Multi-Miroirs) ---
        RadioStationPreset(
            id = "mouv",
            name = "Mouv’ Afro & Urbain",
            genre = "Afrobeats, Hip-Hop, Rap & R&B",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/mouv-midfi.mp3",
            logoUrl = brandFavicon("radiofrance.fr"),
            providerName = PROVIDER_MISSA_DIRECT,
            fallbackUrls = listOf("https://icecast.radiofrance.fr/mouv-hifi.aac")
        ),
        RadioStationPreset(
            id = "mouv_100mix",
            name = "Mouv’ 100% Mix",
            genre = "DJ Mix, Hip-Hop, Afrobeats & Club",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/mouv100p100mix-midfi.mp3",
            logoUrl = brandFavicon("radiofrance.fr"),
            providerName = PROVIDER_MISSA_DIRECT,
            fallbackUrls = listOf("https://icecast.radiofrance.fr/mouv100p100mix-hifi.aac")
        ),
        RadioStationPreset(
            id = "france_info",
            name = "franceinfo:",
            genre = "Actualités en continu 24/7",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/franceinfo-midfi.mp3",
            logoUrl = brandFavicon("franceinfo.fr"),
            providerName = PROVIDER_MISSA_DIRECT,
            fallbackUrls = listOf("https://icecast.radiofrance.fr/franceinfo-hifi.aac")
        ),
        RadioStationPreset(
            id = "france_inter",
            name = "France Inter",
            genre = "Généraliste, Débats & Culture",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/franceinter-midfi.mp3",
            logoUrl = brandFavicon("radiofrance.fr"),
            providerName = PROVIDER_MISSA_DIRECT,
            fallbackUrls = listOf("https://icecast.radiofrance.fr/franceinter-hifi.aac")
        ),
        RadioStationPreset(
            id = "france_culture",
            name = "France Culture",
            genre = "Savoirs, Histoire, Documentaires & Idées",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/franceculture-midfi.mp3",
            logoUrl = brandFavicon("radiofrance.fr"),
            providerName = PROVIDER_MISSA_DIRECT,
            fallbackUrls = listOf("https://icecast.radiofrance.fr/franceculture-hifi.aac")
        ),
        RadioStationPreset(
            id = "france_musique",
            name = "France Musique",
            genre = "Classique, Symphonique & Concerts",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/francemusique-midfi.mp3",
            logoUrl = brandFavicon("radiofrance.fr"),
            providerName = PROVIDER_MISSA_DIRECT,
            fallbackUrls = listOf("https://icecast.radiofrance.fr/francemusique-hifi.aac")
        ),
        RadioStationPreset(
            id = "fip",
            name = "FIP Radio",
            genre = "Musique éclectique, Jazz, Soul & Monde",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/fip-midfi.mp3",
            logoUrl = brandFavicon("fip.fr"),
            providerName = PROVIDER_MISSA_DIRECT,
            fallbackUrls = listOf("https://icecast.radiofrance.fr/fip-hifi.aac")
        ),
        RadioStationPreset(
            id = "fip_groove",
            name = "FIP Groove",
            genre = "Soul, Funk, Afrobeat, R&B & Groove",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/fipgroove-midfi.mp3",
            logoUrl = brandFavicon("fip.fr"),
            providerName = PROVIDER_MISSA_DIRECT,
            fallbackUrls = listOf("https://icecast.radiofrance.fr/fipgroove-hifi.aac")
        ),
        RadioStationPreset(
            id = "fip_jazz",
            name = "FIP Jazz",
            genre = "Jazz Contemporain, Légendes & Swing",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/fipjazz-midfi.mp3",
            logoUrl = brandFavicon("fip.fr"),
            providerName = PROVIDER_MISSA_DIRECT,
            fallbackUrls = listOf("https://icecast.radiofrance.fr/fipjazz-hifi.aac")
        ),
        RadioStationPreset(
            id = "fip_reggae",
            name = "FIP Reggae",
            genre = "Reggae, Roots, Dub & Ska",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/fipreggae-midfi.mp3",
            logoUrl = brandFavicon("fip.fr"),
            providerName = PROVIDER_MISSA_DIRECT,
            fallbackUrls = listOf("https://icecast.radiofrance.fr/fipreggae-hifi.aac")
        ),
        RadioStationPreset(
            id = "fip_monde",
            name = "FIP Monde",
            genre = "Musiques du Monde, Afrique, Brésil & Caraïbes",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/fipworld-midfi.mp3",
            logoUrl = brandFavicon("fip.fr"),
            providerName = PROVIDER_MISSA_DIRECT,
            fallbackUrls = listOf("https://icecast.radiofrance.fr/fipworld-hifi.aac")
        ),
        RadioStationPreset(
            id = "fip_electro",
            name = "FIP Electro",
            genre = "Électro, House, Lounge & Chill",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/fipelectro-midfi.mp3",
            logoUrl = brandFavicon("fip.fr"),
            providerName = PROVIDER_MISSA_DIRECT,
            fallbackUrls = listOf("https://icecast.radiofrance.fr/fipelectro-hifi.aac")
        ),
        RadioStationPreset(
            id = "fip_hiphop",
            name = "FIP Hip-Hop",
            genre = "Hip-Hop, Rap, Beats & Soul",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/fiphiphop-midfi.mp3",
            logoUrl = brandFavicon("fip.fr"),
            providerName = PROVIDER_MISSA_DIRECT,
            fallbackUrls = listOf("https://icecast.radiofrance.fr/fiphiphop-hifi.aac")
        ),

        // --- 3. Bouquet Radio Swiss (Zéro Publicité) ---
        RadioStationPreset(
            id = "swiss_jazz",
            name = "Radio Swiss Jazz",
            genre = "Jazz, Soul & Blues",
            country = "CH",
            streamUrl = "https://stream.srg-ssr.ch/m/rsj/mp3_128",
            logoUrl = brandFavicon("radioswissjazz.ch"),
            providerName = PROVIDER_MISSA_DIRECT,
            fallbackUrls = listOf("https://stream.srg-ssr.ch/m/rsj/aacp_96")
        ),
        RadioStationPreset(
            id = "swiss_pop",
            name = "Radio Swiss Pop",
            genre = "Pop Internationale & Hits sans pub",
            country = "CH",
            streamUrl = "https://stream.srg-ssr.ch/m/rsp/mp3_128",
            logoUrl = brandFavicon("radioswisspop.ch"),
            providerName = PROVIDER_MISSA_DIRECT,
            fallbackUrls = listOf("https://stream.srg-ssr.ch/m/rsp/aacp_96")
        ),
        RadioStationPreset(
            id = "swiss_classic",
            name = "Radio Swiss Classic",
            genre = "Musique Classique & Orchestre",
            country = "CH",
            streamUrl = "https://stream.srg-ssr.ch/m/rsc_fr/mp3_128",
            logoUrl = brandFavicon("radioswissclassic.ch"),
            providerName = PROVIDER_MISSA_DIRECT,
            fallbackUrls = listOf("https://stream.srg-ssr.ch/m/rsc_fr/aacp_96")
        ),

        // --- 4. Bouquet SomaFM (Zéro Publicité, 4 Miroirs Icecast) ---
        somaInstant("groovesalad", "SomaFM Groove Salad", "Ambient, Chillout & Downtempo Beats"),
        somaInstant("lush", "SomaFM Lush", "Sensuous Mellow Vocals & Chill"),
        somaInstant("secretagent", "SomaFM Secret Agent", "Lounge, Jazz Espionnage & Bossa"),
        somaInstant("suburbsofgoa", "SomaFM Suburbs of Goa", "World Beats, Asian & Global Groove"),
        somaInstant("indiepop", "SomaFM Indie Pop Rocks!", "Indie Pop, Alternative & Rock"),
        somaInstant("sonicuniverse", "SomaFM Sonic Universe", "Nu-Jazz, Avant-Garde & Afro-Jazz"),
        somaInstant("bossa", "SomaFM Bossa Beyond", "Bossa Nova, Samba & Tropical Jazz"),
        somaInstant("dronezone", "SomaFM Drone Zone", "Atmospheric Ambient & Relaxation"),

        // --- 5. Bouquet Laut.fm (Webradios Thématiques Directes) ---
        lautInstant("afrobeats", "Laut.fm Afrobeats", "Afrobeats, Afropop, Amapiano & Naija", "AF"),
        lautInstant("jazzloft", "Laut.fm Jazzloft", "Smooth Jazz, Lounge & Swing", "DE"),
        lautInstant("reggae", "Laut.fm Reggae", "Reggae, Dancehall & Roots", "INT"),
        lautInstant("gospelradio", "Laut.fm Gospel", "Gospel, Louange & Soul", "INT"),
        lautInstant("lounge", "Laut.fm Lounge", "Lounge, Chillout & Lo-Fi", "DE")
    )

    private fun somaInstant(channelId: String, title: String, genre: String): RadioStationPreset =
        RadioStationPreset(
            id = "somafm_$channelId",
            name = title,
            genre = genre,
            country = "US",
            streamUrl = "https://ice1.somafm.com/$channelId-128-mp3",
            logoUrl = "https://api.somafm.com/logos/512/${channelId}512.png",
            providerName = PROVIDER_SOMAFM,
            fallbackUrls = listOf(
                "https://ice2.somafm.com/$channelId-128-mp3",
                "https://ice4.somafm.com/$channelId-128-mp3",
                "https://ice6.somafm.com/$channelId-128-mp3"
            )
        )

    private fun lautInstant(slug: String, title: String, genre: String, country: String): RadioStationPreset =
        RadioStationPreset(
            id = "lautfm_$slug",
            name = title,
            genre = genre,
            country = country,
            streamUrl = "https://stream.laut.fm/$slug",
            logoUrl = brandFavicon("laut.fm"),
            providerName = PROVIDER_LAUT_FM
        )

    fun isValidStreamUrl(url: String): Boolean {
        val trimmed = url.trim()
        return (trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)) &&
            trimmed.length > 10 &&
            !trimmed.contains(' ')
    }

    fun isZenoStreamUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("zeno.fm/") || lower.contains("zenomedia.com/")
    }

    /**
     * Parses a JSON array returned by any Radio Browser `/json/stations/...` endpoint.
     * Automatically classifies `zeno.fm` streams under [PROVIDER_ZENO_AFRICA] so local African
     * & Cameroonian community radios are surfaced coherently.
     */
    fun parseRadioBrowserArray(
        jsonText: String,
        defaultProvider: String = PROVIDER_RADIO_BROWSER
    ): List<RadioStationPreset> {
        val arr = runCatching { JSONArray(jsonText) }.getOrNull() ?: return emptyList()
        val out = ArrayList<RadioStationPreset>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim().replace(Regex("\\s+"), " ")
            val resolvedUrl = obj.optString("url_resolved").trim()
            val rawUrl = obj.optString("url").trim()
            val primaryUrl = resolvedUrl.ifBlank { rawUrl }
            if (name.isBlank() || !isValidStreamUrl(primaryUrl)) continue

            val fallback = if (rawUrl.isNotBlank() &&
                !rawUrl.equals(primaryUrl, ignoreCase = true) &&
                isValidStreamUrl(rawUrl)
            ) {
                listOf(rawUrl)
            } else {
                emptyList()
            }

            val uuid = obj.optString("stationuuid").trim()
            val id = "rb_" + uuid.ifBlank { primaryUrl.hashCode().toString() }
            val tags = obj.optString("tags")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length <= 28 }
                .distinct()
                .take(4)
                .joinToString(" • ")
            val country = obj.optString("countrycode").trim()
                .ifBlank { obj.optString("country").trim().take(4) }
                .uppercase(Locale.ROOT)
                .ifBlank { "INT" }

            val favicon = obj.optString("favicon").trim().takeIf { isValidStreamUrl(it) }
                ?: obj.optString("homepage").trim().takeIf { isValidStreamUrl(it) }?.let { homepage ->
                    val host = runCatching { URI(homepage).host }.getOrNull()?.takeIf { it.isNotBlank() }
                    host?.let {
                        val enc = runCatching { URLEncoder.encode(it, "UTF-8") }.getOrDefault(it)
                        "https://www.google.com/s2/favicons?domain=$enc&sz=128"
                    }
                }

            val provider = if (isZenoStreamUrl(primaryUrl) || isZenoStreamUrl(rawUrl)) {
                PROVIDER_ZENO_AFRICA
            } else {
                defaultProvider
            }

            out.add(
                RadioStationPreset(
                    id = id,
                    name = name,
                    genre = tags.ifBlank { "Radio • Direct" },
                    country = country,
                    streamUrl = primaryUrl,
                    isCustom = false,
                    logoUrl = favicon,
                    providerName = provider,
                    fallbackUrls = fallback
                )
            )
        }
        return out
    }

    /** Parses SomaFM's official `https://api.somafm.com/channels.json` directory. */
    fun parseSomaFmChannelsJson(jsonText: String): List<RadioStationPreset> {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val channels = root.optJSONArray("channels") ?: return emptyList()
        val out = ArrayList<RadioStationPreset>(channels.length())
        for (i in 0 until channels.length()) {
            val ch = channels.optJSONObject(i) ?: continue
            val id = ch.optString("id").trim()
            val title = ch.optString("title").trim()
            if (id.isBlank() || title.isBlank()) continue
            val rawGenre = ch.optString("genre").replace("|", " • ").trim()
            val desc = ch.optString("description").trim()
            val genreLabel = listOf(rawGenre, desc)
                .filter { it.isNotBlank() }
                .joinToString(" — ")
                .take(80)
                .ifBlank { "Music • SomaFM" }
            val logo = ch.optString("xlimage").trim().takeIf { isValidStreamUrl(it) }
                ?: ch.optString("largeimage").trim().takeIf { isValidStreamUrl(it) }
                ?: ch.optString("image").trim().takeIf { isValidStreamUrl(it) }

            val primary = "https://ice1.somafm.com/$id-128-mp3"
            val fallbacks = listOf(
                "https://ice2.somafm.com/$id-128-mp3",
                "https://ice4.somafm.com/$id-128-mp3",
                "https://ice6.somafm.com/$id-128-mp3"
            )
            out.add(
                RadioStationPreset(
                    id = "somafm_$id",
                    name = if (title.startsWith("SomaFM", ignoreCase = true)) title else "SomaFM $title",
                    genre = genreLabel,
                    country = "US",
                    streamUrl = primary,
                    logoUrl = logo,
                    providerName = PROVIDER_SOMAFM,
                    fallbackUrls = fallbacks
                )
            )
        }
        return out
    }

    /** Parses Laut.fm's public JSON station lists (`https://api.laut.fm/stations/...`). */
    fun parseLautFmStationsJson(jsonText: String): List<RadioStationPreset> {
        val trimmed = jsonText.trim()
        val arr = runCatching {
            if (trimmed.startsWith("{")) {
                JSONObject(trimmed).optJSONArray("items") ?: return emptyList()
            } else {
                JSONArray(trimmed)
            }
        }.getOrNull() ?: return emptyList()

        val out = ArrayList<RadioStationPreset>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val slug = obj.optString("name").trim()
            if (slug.isBlank()) continue
            val displayName = obj.optString("display_name").trim().ifBlank { slug }
            val streamUrl = obj.optString("stream_url").trim()
                .takeIf { isValidStreamUrl(it) }
                ?: "https://stream.laut.fm/$slug"

            val genresArr = obj.optJSONArray("genres")
            val genres = buildList {
                if (genresArr != null) {
                    for (g in 0 until minOf(genresArr.length(), 4)) {
                        genresArr.optString(g).trim().takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
                obj.optString("format").trim().takeIf { it.isNotBlank() }?.let { add(it) }
            }.distinct().take(4).joinToString(" • ").ifBlank { "Webradio • Laut.fm" }

            val images = obj.optJSONObject("images")
            val logo = images?.optString("station_120x120")?.trim()?.takeIf { isValidStreamUrl(it) }
                ?: images?.optString("station")?.trim()?.takeIf { isValidStreamUrl(it) }

            out.add(
                RadioStationPreset(
                    id = "lautfm_$slug",
                    name = displayName,
                    genre = genres,
                    country = "DE",
                    streamUrl = streamUrl,
                    logoUrl = logo,
                    providerName = PROVIDER_LAUT_FM
                )
            )
        }
        return out
    }

    private val icecastEntryRegex = Regex("""<entry>(.*?)</entry>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private fun extractXmlTag(block: String, tag: String): String {
        val m = Regex("""<$tag>(.*?)</$tag>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(block)
            ?: return ""
        return m.groupValues[1]
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    /** Parses a bounded XML slice from Xiph.org's open Icecast directory (`dir.xiph.org/yp.xml`). */
    fun parseIcecastXiphXml(xmlSlice: String, maxItems: Int = 60): List<RadioStationPreset> {
        if (xmlSlice.isBlank()) return emptyList()
        val byName = LinkedHashMap<String, RadioStationPreset>()
        for (match in icecastEntryRegex.findAll(xmlSlice)) {
            if (byName.size >= maxItems) break
            val block = match.groupValues[1]
            val name = extractXmlTag(block, "server_name")
            val listenUrl = extractXmlTag(block, "listen_url")
            if (name.length < 3 ||
                name.equals("Unspecified name", ignoreCase = true) ||
                !isValidStreamUrl(listenUrl)
            ) {
                continue
            }
            val bitrate = extractXmlTag(block, "bitrate").toIntOrNull() ?: 128
            if (bitrate in 1..47) continue
            val genre = extractXmlTag(block, "genre").ifBlank { "Icecast • Direct" }
            val normKey = normalizeStationKey(name)
            if (normKey.isBlank() || byName.containsKey(normKey)) continue

            byName[normKey] = RadioStationPreset(
                id = "xiph_${listenUrl.hashCode()}",
                name = name,
                genre = genre,
                country = "INT",
                streamUrl = listenUrl,
                providerName = PROVIDER_ICECAST_XIPH
            )
        }
        return byName.values.toList()
    }

    private val extInfAttrRegex = Regex("""([a-zA-Z0-9_-]+)\s*=\s*"([^"]*)"""")

    /**
     * Returns true when [text] is an HLS live media/master stream (`.m3u8` `#EXT-X-...`) rather
     * than a multi-station `#EXTM3U` radio directory playlist.
     */
    fun isSingleHlsStreamManifest(text: String): Boolean {
        return text.contains("#EXT-X-TARGETDURATION", ignoreCase = true) ||
            text.contains("#EXT-X-STREAM-INF", ignoreCase = true) ||
            text.contains("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true)
    }

    /**
     * Parses `#EXTM3U` / `.m3u` / `.pls` radio playlists (GitHub open-source playlists or user-imported URLs).
     */
    fun parsePlaylistContent(
        content: String,
        defaultProvider: String = PROVIDER_M3U_PLAYLISTS,
        defaultCountry: String = "INT",
        defaultGenre: String = "Radio • M3U",
        isCustom: Boolean = false,
        maxStations: Int = 120
    ): List<RadioStationPreset> {
        if (content.isBlank() || isSingleHlsStreamManifest(content)) return emptyList()
        val trimmed = content.trimStart()

        // Support Shoutcast/SomaFM .pls playlists ([playlist] File1=... Title1=...)
        if (trimmed.startsWith("[playlist]", ignoreCase = true)) {
            return parsePlsContent(trimmed, defaultProvider, defaultCountry, defaultGenre, isCustom, maxStations)
        }

        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val results = ArrayList<RadioStationPreset>()
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var pendingCountry: String? = null

        for (line in lines) {
            if (results.size >= maxStations) break
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val attrs = HashMap<String, String>()
                for (m in extInfAttrRegex.findAll(line)) {
                    attrs[m.groupValues[1].lowercase(Locale.ROOT)] = m.groupValues[2].trim()
                }
                val commaIdx = line.lastIndexOf(',')
                val rawTitle = if (commaIdx >= 0 && commaIdx + 1 < line.length) {
                    line.substring(commaIdx + 1).trim()
                } else {
                    attrs["tvg-name"].orEmpty()
                }
                pendingName = rawTitle.ifBlank { null }
                pendingLogo = attrs["tvg-logo"]?.takeIf { isValidStreamUrl(it) }
                pendingGroup = attrs["group-title"]?.replace(";", " • ")?.trim()?.ifBlank { null }
                pendingCountry = (attrs["tvg-country"] ?: attrs["country"])
                    ?.trim()
                    ?.take(4)
                    ?.uppercase(Locale.ROOT)
                    ?.ifBlank { null }
            } else if (line.startsWith("#")) {
                continue
            } else if (isValidStreamUrl(line)) {
                val stationName = pendingName
                    ?: deriveNameFromStreamUrl(line)
                val provider = if (!isCustom && isZenoStreamUrl(line)) {
                    PROVIDER_ZENO_AFRICA
                } else {
                    defaultProvider
                }
                results.add(
                    RadioStationPreset(
                        id = "${if (isCustom) "custom" else "m3u"}_${line.hashCode()}",
                        name = stationName,
                        genre = pendingGroup ?: defaultGenre,
                        country = pendingCountry ?: defaultCountry,
                        streamUrl = line,
                        isCustom = isCustom,
                        logoUrl = pendingLogo,
                        providerName = provider
                    )
                )
                pendingName = null
                pendingLogo = null
                pendingGroup = null
                pendingCountry = null
            }
        }
        return results
    }

    private fun parsePlsContent(
        content: String,
        defaultProvider: String,
        defaultCountry: String,
        defaultGenre: String,
        isCustom: Boolean,
        maxStations: Int
    ): List<RadioStationPreset> {
        val files = LinkedHashMap<String, String>()
        val titles = HashMap<String, String>()
        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val key = line.substring(0, eq).trim().lowercase(Locale.ROOT)
            val value = line.substring(eq + 1).trim()
            when {
                key.startsWith("file") && isValidStreamUrl(value) -> {
                    val idx = key.removePrefix("file")
                    files[idx] = value
                }
                key.startsWith("title") && value.isNotBlank() -> {
                    val idx = key.removePrefix("title")
                    titles[idx] = value
                }
            }
        }
        if (files.isEmpty()) return emptyList()
        val urls = files.values.toList()
        // If all entries in the .pls are mirrors of a single channel (typical SomaFM/Shoutcast .pls), merge into 1 preset with fallbacks
        val distinctTitles = titles.values.map { it.replace(Regex("\\(#\\d+.*\\)"), "").trim() }.distinct()
        if (distinctTitles.size <= 1 && urls.size > 1 && !isCustom) {
            val primary = urls.first()
            return listOf(
                RadioStationPreset(
                    id = "pls_${primary.hashCode()}",
                    name = distinctTitles.firstOrNull()?.ifBlank { null } ?: deriveNameFromStreamUrl(primary),
                    genre = defaultGenre,
                    country = defaultCountry,
                    streamUrl = primary,
                    isCustom = false,
                    providerName = defaultProvider,
                    fallbackUrls = urls.drop(1).take(4)
                )
            )
        }
        return files.entries.take(maxStations).map { (idx, url) ->
            RadioStationPreset(
                id = "${if (isCustom) "custom" else "pls"}_${url.hashCode()}",
                name = titles[idx]?.ifBlank { null } ?: deriveNameFromStreamUrl(url),
                genre = defaultGenre,
                country = defaultCountry,
                streamUrl = url,
                isCustom = isCustom,
                providerName = defaultProvider
            )
        }
    }

    private fun deriveNameFromStreamUrl(url: String): String {
        val pathSeg = runCatching {
            URI(url).path?.trim('/')?.substringAfterLast('/')?.substringBefore('.')
        }.getOrNull()?.replace(Regex("[-_]+"), " ")?.trim()
        if (!pathSeg.isNullOrBlank() && pathSeg.length >= 3 && !pathSeg.equals("stream", ignoreCase = true)) {
            return pathSeg.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
        val host = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.") ?: "Radio Web"
        return host
    }

    fun normalizeStationKey(name: String): String {
        val decomposed = Normalizer.normalize(name, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)
        return decomposed
            .replace(Regex("\\b(radio|fm|hd|aac|mp3|128k|64k|stream|live|online)\\b"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    /**
     * Merges stations across all providers, eliminating duplicate URLs and folding alternate URLs
     * of the same `(normalizedName, country)` station into [RadioStationPreset.fallbackUrls] for
     * seamless automatic stream failover.
     */
    fun mergeStationCatalogues(
        stations: List<RadioStationPreset>,
        favoriteIds: Set<String> = emptySet()
    ): List<RadioStationPreset> {
        val byNormalizedUrl = LinkedHashMap<String, RadioStationPreset>(stations.size)
        val canonicalKeyByIdentity = HashMap<String, String>()

        for (station in stations) {
            if (!isValidStreamUrl(station.streamUrl)) continue
            val normUrl = station.streamUrl.trim().lowercase(Locale.ROOT).trimEnd('/')
            val existingByUrl = byNormalizedUrl[normUrl]
            if (existingByUrl != null) {
                if (existingByUrl.logoUrl.isNullOrBlank() && !station.logoUrl.isNullOrBlank()) {
                    byNormalizedUrl[normUrl] = existingByUrl.copy(logoUrl = station.logoUrl)
                }
                continue
            }

            val normName = normalizeStationKey(station.name)
            val identityKey = if (normName.length >= 4 && !station.isCustom) {
                "$normName|${station.country.uppercase(Locale.ROOT)}"
            } else {
                ""
            }

            val existingCanonicalUrl = if (identityKey.isNotEmpty()) canonicalKeyByIdentity[identityKey] else null
            if (existingCanonicalUrl != null) {
                val primary = byNormalizedUrl[existingCanonicalUrl]
                if (primary != null) {
                    val combinedFallbacks = (primary.fallbackUrls + station.streamUrl + station.fallbackUrls)
                        .filter { !it.equals(primary.streamUrl, ignoreCase = true) && isValidStreamUrl(it) }
                        .distinctBy { it.lowercase(Locale.ROOT) }
                        .take(4)
                    byNormalizedUrl[existingCanonicalUrl] = primary.copy(
                        logoUrl = primary.logoUrl ?: station.logoUrl,
                        fallbackUrls = combinedFallbacks
                    )
                    continue
                }
            }

            if (identityKey.isNotEmpty()) {
                canonicalKeyByIdentity[identityKey] = normUrl
            }
            byNormalizedUrl[normUrl] = station
        }

        return byNormalizedUrl.values.sortedWith(
            compareByDescending<RadioStationPreset> { favoriteIds.contains(it.id) }
                .thenByDescending { it.isCustom }
        )
    }

    // --- Resilient Network Harvesters ---

    private suspend fun fetchRadioBrowserJson(queryAndParams: String): String? {
        val startIdx = preferredMirrorIndex
        for (offset in RADIO_BROWSER_MIRRORS.indices) {
            val mirrorIdx = (startIdx + offset) % RADIO_BROWSER_MIRRORS.size
            val base = RADIO_BROWSER_MIRRORS[mirrorIdx]
            val url = "$base/json/stations/$queryAndParams"
            val body = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                try {
                    val resp = app.get(url, headers = httpHeaders)
                    resp.text.takeIf { it.trimStart().startsWith("[") }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            if (!body.isNullOrBlank()) {
                preferredMirrorIndex = mirrorIdx
                return body
            }
        }
        return null
    }

    /** Non-blocking Radio Browser community click ping when a station starts playing. */
    suspend fun pingRadioBrowserClickIfApplicable(stationId: String) {
        if (!stationId.startsWith("rb_")) return
        val uuid = stationId.removePrefix("rb_").trim()
        if (uuid.length < 16 || !uuid.contains('-')) return
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(4_000L) {
                runCatching {
                    val base = RADIO_BROWSER_MIRRORS[preferredMirrorIndex % RADIO_BROWSER_MIRRORS.size]
                    app.get("$base/json/url/$uuid", headers = httpHeaders)
                }
            }
        }
    }

    /**
     * Harvests all free external radio providers in parallel with strict timeouts and bounded memory:
     * - Radio Browser (Cameroon, Francophone/West/Central Africa, Francophonie, Music genres, World top)
     * - Zeno.FM Afrique (verified active `zeno.fm` community stations)
     * - SomaFM (`api.somafm.com/channels.json`)
     * - Laut.fm (`api.laut.fm/stations/genre/...`)
     * - GitHub Open M3U Radio Playlists (`junguler/m3u-radio-music-playlists`)
     * - Xiph.org Open Icecast Directory (`dir.xiph.org/yp.xml` bounded slice)
     */
    suspend fun harvestAllFreeProviders(): List<RadioStationPreset> = coroutineScope {
        val semaphore = Semaphore(4)

        val radioBrowserQueries = listOf(
            // 1. Cameroon & Central/West/Francophone Africa first
            "search?limit=80&hidebroken=true&countrycode=CM&order=votes&reverse=true" to PROVIDER_RADIO_BROWSER,
            "search?limit=60&hidebroken=true&countrycode=CI&order=votes&reverse=true" to PROVIDER_RADIO_BROWSER,
            "search?limit=60&hidebroken=true&countrycode=SN&order=votes&reverse=true" to PROVIDER_RADIO_BROWSER,
            "search?limit=50&hidebroken=true&countrycode=CD&order=votes&reverse=true" to PROVIDER_RADIO_BROWSER,
            "search?limit=40&hidebroken=true&countrycode=GA&order=votes&reverse=true" to PROVIDER_RADIO_BROWSER,
            "search?limit=60&hidebroken=true&countrycode=NG&order=votes&reverse=true" to PROVIDER_RADIO_BROWSER,
            "search?limit=45&hidebroken=true&countrycode=GH&order=votes&reverse=true" to PROVIDER_RADIO_BROWSER,
            "search?limit=45&hidebroken=true&countrycode=MA&order=votes&reverse=true" to PROVIDER_RADIO_BROWSER,
            "search?limit=40&hidebroken=true&countrycode=ZA&order=votes&reverse=true" to PROVIDER_RADIO_BROWSER,
            // 2. Zeno.FM African & Community Stations
            "search?limit=80&hidebroken=true&url=zeno.fm&order=votes&reverse=true" to PROVIDER_ZENO_AFRICA,
            // 3. Francophonie & News/Culture
            "search?limit=70&hidebroken=true&countrycode=FR&order=votes&reverse=true" to PROVIDER_RADIO_BROWSER,
            "search?limit=35&hidebroken=true&countrycode=BE&order=votes&reverse=true" to PROVIDER_RADIO_BROWSER,
            "search?limit=35&hidebroken=true&countrycode=CA&order=votes&reverse=true" to PROVIDER_RADIO_BROWSER,
            // 4. Thematic Music Slices (also enrich the Music universe!)
            "search?limit=45&hidebroken=true&tag=afrobeats&order=votes&reverse=true" to PROVIDER_RADIO_BROWSER,
            "search?limit=40&hidebroken=true&tag=gospel&order=votes&reverse=true" to PROVIDER_RADIO_BROWSER,
            "search?limit=40&hidebroken=true&tag=jazz&order=votes&reverse=true" to PROVIDER_RADIO_BROWSER,
            "search?limit=35&hidebroken=true&tag=reggae&order=votes&reverse=true" to PROVIDER_RADIO_BROWSER,
            // 5. Global Top Voted
            "search?limit=90&hidebroken=true&order=votes&reverse=true" to PROVIDER_RADIO_BROWSER
        )

        val rbDeferred = radioBrowserQueries.map { (query, defaultProvider) ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val json = fetchRadioBrowserJson(query) ?: return@withPermit emptyList()
                    parseRadioBrowserArray(json, defaultProvider)
                }
            }
        }

        val somaDeferred = async(Dispatchers.IO) {
            semaphore.withPermit {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    runCatching {
                        val text = app.get("https://api.somafm.com/channels.json", headers = httpHeaders).text
                        parseSomaFmChannelsJson(text)
                    }.getOrDefault(emptyList())
                }.orEmpty()
            }
        }

        val lautGenres = listOf("Jazz", "Reggae", "Hip-Hop", "Soul", "Lounge", "World")
        val lautDeferred = lautGenres.map { genre ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                        runCatching {
                            val enc = URLEncoder.encode(genre, "UTF-8")
                            val text = app.get("https://api.laut.fm/stations/genre/$enc?limit=20", headers = httpHeaders).text
                            parseLautFmStationsJson(text)
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }
        }

        val githubM3uSources = listOf(
            Triple("https://raw.githubusercontent.com/junguler/m3u-radio-music-playlists/main/top_radio.m3u", "INT", "Top Radio • M3U"),
            Triple("https://raw.githubusercontent.com/junguler/m3u-radio-music-playlists/main/jazz.m3u", "INT", "Jazz • M3U"),
            Triple("https://raw.githubusercontent.com/junguler/m3u-radio-music-playlists/main/hip_hop.m3u", "INT", "Hip-Hop & Urbain • M3U"),
            Triple("https://raw.githubusercontent.com/junguler/m3u-radio-music-playlists/main/reggae.m3u", "INT", "Reggae • M3U"),
            Triple("https://raw.githubusercontent.com/mikepierce/internet-radio-streams/main/streams.m3u", "INT", "Public Radio • M3U")
        )
        val m3uDeferred = githubM3uSources.map { (m3uUrl, country, genre) ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                        runCatching {
                            val text = app.get(m3uUrl, headers = httpHeaders).text
                            parsePlaylistContent(
                                content = text,
                                defaultProvider = PROVIDER_M3U_PLAYLISTS,
                                defaultCountry = country,
                                defaultGenre = genre,
                                maxStations = 45
                            )
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }
        }

        val xiphDeferred = async(Dispatchers.IO) {
            semaphore.withPermit {
                fetchIcecastXiphBoundedSlice()
            }
        }

        val combined = ArrayList<RadioStationPreset>(600)
        // Put curated bouquets first, then regional/thematic Radio Browser & Zeno, SomaFM, Laut.fm, M3U, Icecast
        combined.addAll(rbDeferred.awaitAll().flatten())
        combined.addAll(somaDeferred.await())
        combined.addAll(lautDeferred.awaitAll().flatten())
        combined.addAll(m3uDeferred.awaitAll().flatten())
        combined.addAll(xiphDeferred.await())

        mergeStationCatalogues(combined).take(850)
    }

    private val africanIsoCodes = setOf(
        "AF", "CM", "CI", "SN", "CD", "CG", "GA", "BJ", "TG", "ML", "BF", "GN", "NE", "TD",
        "CF", "GQ", "RW", "BI", "MG", "MA", "DZ", "TN", "NG", "GH", "KE", "ZA", "ET", "UG",
        "TZ", "AO", "MZ"
    )

    private val countryQueryAliases: Map<String, Set<String>> = mapOf(
        "cameroun" to setOf("CM"),
        "cameroon" to setOf("CM"),
        "douala" to setOf("CM"),
        "yaounde" to setOf("CM"),
        "ivoire" to setOf("CI"),
        "abidjan" to setOf("CI"),
        "senegal" to setOf("SN"),
        "dakar" to setOf("SN"),
        "congo" to setOf("CD", "CG"),
        "kinshasa" to setOf("CD"),
        "gabon" to setOf("GA"),
        "benin" to setOf("BJ"),
        "togo" to setOf("TG"),
        "mali" to setOf("ML"),
        "burkina" to setOf("BF"),
        "maroc" to setOf("MA"),
        "morocco" to setOf("MA"),
        "algerie" to setOf("DZ"),
        "tunisie" to setOf("TN"),
        "nigeria" to setOf("NG"),
        "ghana" to setOf("GH"),
        "afrique" to africanIsoCodes,
        "africa" to africanIsoCodes,
        "france" to setOf("FR"),
        "suisse" to setOf("CH"),
        "switzerland" to setOf("CH"),
        "belgique" to setOf("BE"),
        "belgium" to setOf("BE"),
        "canada" to setOf("CA")
    )

    private fun stripAccentsLower(input: String): String =
        Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)

    /**
     * Accent-insensitive & country-alias-aware station search matcher so queries like
     * `"Cameroun"`, `"Sénégal"`, `"Côte d'Ivoire"`, `"Afrique"`, `"Suisse"`, `"SomaFM"`, or `"Zeno"`
     * match ISO country codes (`CM`, `SN`, `CI`, `CH`) and provider names immediately.
     */
    fun matchesStationQuery(station: RadioStationPreset, rawQuery: String): Boolean {
        val q = stripAccentsLower(rawQuery).trim()
        if (q.isEmpty()) return true
        val haystack = stripAccentsLower("${station.name} ${station.genre} ${station.country} ${station.providerName}")
        if (haystack.contains(q)) return true
        val iso = station.country.uppercase(Locale.ROOT)
        for ((alias, codes) in countryQueryAliases) {
            if (q.contains(alias) && iso in codes) return true
        }
        return false
    }

    /**
     * Live real-time search across Radio Browser's 45,000+ free stations (by name, tag, and country)
     * plus Laut.fm's station search.
     */
    suspend fun searchOnlineProviders(query: String): List<RadioStationPreset> = coroutineScope {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@coroutineScope emptyList()
        val encoded = runCatching { URLEncoder.encode(trimmed, "UTF-8") }.getOrDefault(trimmed)
        val frenchToIso = countryQueryAliases[stripAccentsLower(trimmed)]?.firstOrNull()

        val byName = async(Dispatchers.IO) {
            val json = fetchRadioBrowserJson("search?name=$encoded&limit=60&hidebroken=true&order=votes&reverse=true")
                ?: return@async emptyList()
            parseRadioBrowserArray(json)
        }
        val byTag = async(Dispatchers.IO) {
            val json = fetchRadioBrowserJson("search?tag=$encoded&limit=40&hidebroken=true&order=votes&reverse=true")
                ?: return@async emptyList()
            parseRadioBrowserArray(json)
        }
        val byCountry = async(Dispatchers.IO) {
            val endpoint = if (frenchToIso != null && frenchToIso != "AF") {
                "search?countrycode=$frenchToIso&limit=60&hidebroken=true&order=votes&reverse=true"
            } else {
                "search?country=$encoded&limit=40&hidebroken=true&order=votes&reverse=true"
            }
            val json = fetchRadioBrowserJson(endpoint) ?: return@async emptyList()
            parseRadioBrowserArray(json)
        }
        val byLaut = async(Dispatchers.IO) {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                runCatching {
                    val text = app.get("https://api.laut.fm/stations/search?query=$encoded&limit=25", headers = httpHeaders).text
                    parseLautFmStationsJson(text)
                }.getOrDefault(emptyList())
            }.orEmpty()
        }

        val rawHits = byName.await() + byTag.await() + byCountry.await() + byLaut.await()
        val taggedHits = rawHits.map { st ->
            if (matchesStationQuery(st, trimmed)) st else st.copy(genre = "${st.genre} • $trimmed")
        }
        mergeStationCatalogues(taggedHits).take(120)
    }

    /**
     * Fetches and resolves an external `.m3u` or `.pls` URL (either returning multiple stations
     * for playlist import, or resolving the inner stream URL when ExoPlayer encounters a `.pls`/`.m3u` wrapper).
     */
    suspend fun fetchRemotePlaylistIfAny(
        url: String,
        defaultName: String = "",
        defaultCountry: String = "WEB",
        isCustom: Boolean = false
    ): List<RadioStationPreset> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                val lower = url.lowercase(Locale.ROOT).substringBefore('?')
                val looksLikePlaylist = lower.endsWith(".m3u") || lower.endsWith(".pls") || lower.endsWith(".m3u8")
                if (!looksLikePlaylist) return@withTimeoutOrNull emptyList()
                val text = app.get(url, headers = httpHeaders).text
                if (isSingleHlsStreamManifest(text)) return@withTimeoutOrNull emptyList()
                parsePlaylistContent(
                    content = text,
                    defaultProvider = if (isCustom) PROVIDER_CUSTOM else PROVIDER_M3U_PLAYLISTS,
                    defaultCountry = defaultCountry.ifBlank { "WEB" },
                    defaultGenre = defaultName.ifBlank { "Playlist M3U" },
                    isCustom = isCustom,
                    maxStations = 150
                )
            }.getOrDefault(emptyList())
        }.orEmpty()
    }

    /**
     * Reads at most [XIPH_MAX_CHARS] characters from `dir.xiph.org/yp.xml` over a streaming
     * [HttpURLConnection] so the full multi-megabyte XML file is never buffered in memory.
     */
    private suspend fun fetchIcecastXiphBoundedSlice(): List<RadioStationPreset> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(6_500L) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("https://dir.xiph.org/yp.xml").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4_500
                    readTimeout = 4_500
                    setRequestProperty("User-Agent", USER_AGENT)
                }
                val sb = StringBuilder(XIPH_MAX_CHARS)
                BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                    val buf = CharArray(4096)
                    while (sb.length < XIPH_MAX_CHARS) {
                        val read = reader.read(buf)
                        if (read <= 0) break
                        sb.append(buf, 0, read)
                    }
                }
                parseIcecastXiphXml(sb.toString(), maxItems = 50)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            } finally {
                runCatching { conn?.disconnect() }
            }
        }.orEmpty()
    }
}
