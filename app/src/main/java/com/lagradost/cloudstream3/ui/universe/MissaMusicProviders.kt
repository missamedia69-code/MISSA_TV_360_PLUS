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
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

/**
 * Dedicated 100%-Music server aggregation engine for MISSA TV 360 PLUS (`universeId = "music"`).
 *
 * Strictly serves on-demand music tracks, albums, DJ mixes, and playlists — never radio stations,
 * talk shows, or television channels:
 * 1. [PROVIDER_AUDIUS] — Decentralized 320 kbps music network (+1M tracks, multi-node failover).
 * 2. [PROVIDER_HEARTHIS] — HearThis.at direct MP3 tracks & DJ mixes (Afrobeats, Amapiano, Hip-Hop, Reggae, Jazz).
 * 3. [PROVIDER_FUNKWHALE] — Open federated Funkwhale music pods (`open.audio`).
 * 4. [PROVIDER_CCMIXTER] — ccMixter open Creative Commons Hip-Hop, Soul, Groove & Chill tracks.
 * 5. [PROVIDER_SUBSONIC] — Standard OpenSubsonic / Navidrome server client (MD5 salt+token auth).
 * 6. [PROVIDER_PIPED_MUSIC] — Multi-mirror Piped Music (`filter=music_songs`) audio-only stream resolver.
 * 7. [PROVIDER_CLASSICAL_JAZZ] — Instant zero-latency public-domain & Creative Commons music tracks.
 * 8. [PROVIDER_CUSTOM_MUSIC] — User-imported MP3/FLAC/OGG tracks & `.m3u` music playlists.
 */
internal object MissaMusicProviders {
    const val PROVIDER_CUSTOM_MUSIC = "Mes Musiques"
    const val PROVIDER_AUDIUS = "Audius"
    const val PROVIDER_HEARTHIS = "HearThis.at"
    const val PROVIDER_FUNKWHALE = "Funkwhale"
    const val PROVIDER_CCMIXTER = "ccMixter"
    const val PROVIDER_SUBSONIC = "Navidrome / Subsonic"
    const val PROVIDER_PIPED_MUSIC = "Piped Music"
    const val PROVIDER_CLASSICAL_JAZZ = "Classique & Jazz Public"

    const val PIPED_SCHEME_PREFIX = "pipedmusic://"
    private const val AUDIUS_APP_NAME = "MISSA_TV_360_PLUS"
    private const val USER_AGENT = "MISSA-TV-360-PLUS/2.0 (Android; Dedicated Music Hub)"
    private const val REQUEST_TIMEOUT_MS = 8_500L

    private val FALLBACK_AUDIUS_NODES = listOf(
        "https://discoveryprovider.audius.co",
        "https://discoveryprovider2.audius.co",
        "https://discoveryprovider3.audius.co"
    )

    @Volatile
    private var cachedAudiusNodes: List<String> = FALLBACK_AUDIUS_NODES

    private val FUNKWHALE_PODS = listOf(
        "https://open.audio"
    )

    private val PIPED_API_MIRRORS = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://pipedapi.tokhmi.xyz",
        "https://piped-api.lunar.icu",
        "https://pipedapi.moomoo.me"
    )

    @Volatile
    private var preferredPipedMirrorIndex: Int = 0

    private val httpHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "application/json, audio/x-mpegurl, text/plain, */*"
    )

    /**
     * Zero-latency on-demand starter tracks (100% finite music compositions, never radio) so the
     * Music universe is playable immediately on first launch even before remote APIs respond.
     */
    val curatedStarterTracks: List<MusicTrackPreset> = listOf(
        MusicTrackPreset(
            id = "starter_afro_groove_1",
            title = "Sunmakossa & Afro Percussions",
            artist = "Kevin MacLeod",
            album = "African & World Rhythms",
            genre = "Afrique • Afrobeat & Percussions",
            streamUrl = "https://archive.org/download/Kevin_MacLeod_-_Arroz_Con_Pollo/Kevin_MacLeod_-_Arroz_Con_Pollo.mp3",
            durationSeconds = 192,
            providerName = PROVIDER_CLASSICAL_JAZZ,
            isTrending = true
        ),
        MusicTrackPreset(
            id = "starter_jazz_vibes_1",
            title = "Backed Vibes Clean (Smooth Jazz)",
            artist = "Kevin MacLeod",
            album = "Jazz & Blues Sessions",
            genre = "Jazz • Smooth & Saxophone",
            streamUrl = "https://archive.org/download/Kevin_MacLeod_-_Backed_Vibes_Clean/Kevin_MacLeod_-_Backed_Vibes_Clean.mp3",
            durationSeconds = 229,
            providerName = PROVIDER_CLASSICAL_JAZZ
        ),
        MusicTrackPreset(
            id = "starter_lofi_chill_1",
            title = "Bossa Antigua",
            artist = "Kevin MacLeod",
            album = "Lounge & Chillout",
            genre = "Jazz • Bossa, Chill & Lo-Fi",
            streamUrl = "https://archive.org/download/Kevin_MacLeod_-_Bossa_Antigua/Kevin_MacLeod_-_Bossa_Antigua.mp3",
            durationSeconds = 285,
            providerName = PROVIDER_CLASSICAL_JAZZ
        ),
        MusicTrackPreset(
            id = "starter_classical_1",
            title = "Clair de Lune (Suite bergamasque)",
            artist = "Claude Debussy",
            album = "Chefs-d'œuvre du Piano",
            genre = "Classique • Piano",
            streamUrl = "https://upload.wikimedia.org/wikipedia/commons/transcoded/b/be/Clair_de_lune_%28Claude_Debussy%29_Suite_bergamasque.ogg/Clair_de_lune_%28Claude_Debussy%29_Suite_bergamasque.ogg.mp3",
            durationSeconds = 304,
            providerName = PROVIDER_CLASSICAL_JAZZ
        ),
        MusicTrackPreset(
            id = "starter_classical_2",
            title = "Gymnopédie No. 1",
            artist = "Erik Satie",
            album = "Classique & Détente",
            genre = "Classique • Piano & Ambient",
            streamUrl = "https://upload.wikimedia.org/wikipedia/commons/transcoded/9/98/Gymnopedie_No_1.ogg/Gymnopedie_No_1.ogg.mp3",
            durationSeconds = 188,
            providerName = PROVIDER_CLASSICAL_JAZZ
        ),
        MusicTrackPreset(
            id = "starter_classical_3",
            title = "Nocturne Op. 9 No. 2",
            artist = "Frédéric Chopin",
            album = "Romantisme & Piano",
            genre = "Classique • Piano",
            streamUrl = "https://upload.wikimedia.org/wikipedia/commons/transcoded/4/4c/Chopin_-_Nocturne_Op._9_No._2_%28E_Flat_Major%29.ogg/Chopin_-_Nocturne_Op._9_No._2_%28E_Flat_Major%29.ogg.mp3",
            durationSeconds = 268,
            providerName = PROVIDER_CLASSICAL_JAZZ
        )
    )

    fun isValidAudioUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (trimmed.startsWith(PIPED_SCHEME_PREFIX)) {
            return trimmed.removePrefix(PIPED_SCHEME_PREFIX).isNotBlank()
        }
        return (trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)) &&
            trimmed.length > 10 &&
            !trimmed.contains(' ')
    }

    // --- 1. Audius Open Music Network Parser & Harvester ---

    fun parseAudiusHostListJson(jsonText: String): List<String> {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            val host = arr.optString(i).trim().trimEnd('/')
            if (host.startsWith("https://", ignoreCase = true)) {
                out.add(host)
            }
        }
        return out
    }

    fun parseAudiusTracksJson(
        jsonText: String,
        nodes: List<String> = FALLBACK_AUDIUS_NODES,
        markTrending: Boolean = false,
        extraGenreHint: String = ""
    ): List<MusicTrackPreset> {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val data = root.optJSONArray("data") ?: return emptyList()
        val activeNodes = nodes.ifEmpty { FALLBACK_AUDIUS_NODES }
        val primaryNode = activeNodes.first().trimEnd('/')
        val backupNodes = activeNodes.drop(1).take(2).map { it.trimEnd('/') }

        val out = ArrayList<MusicTrackPreset>(data.length())
        for (i in 0 until data.length()) {
            val obj = data.optJSONObject(i) ?: continue
            if (obj.optBoolean("is_delete", false) || obj.optBoolean("is_stream_gated", false)) continue
            val trackId = obj.optString("id").trim()
            val title = obj.optString("title").trim()
            if (trackId.isBlank() || title.isBlank()) continue

            val userObj = obj.optJSONObject("user")
            val artist = userObj?.optString("name")?.trim()
                ?.ifBlank { userObj.optString("handle").trim() }
                .orEmpty()

            val duration = obj.optInt("duration", 0)
            if (duration in 1..20) continue // Skip tiny sound effects

            val rawGenre = obj.optString("genre").trim()
            val mood = obj.optString("mood").trim()
            val tags = obj.optString("tags")
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() && it.length <= 24 }
                .take(3)
                .joinToString(" • ")
            val genre = listOf(rawGenre, extraGenreHint, mood, tags)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" • ")
                .ifBlank { "Musique" }

            val artworkObj = obj.optJSONObject("artwork")
            val artwork = artworkObj?.optString("480x480")?.trim()?.takeIf { isValidAudioUrl(it) }
                ?: artworkObj?.optString("1000x1000")?.trim()?.takeIf { isValidAudioUrl(it) }
                ?: artworkObj?.optString("150x150")?.trim()?.takeIf { isValidAudioUrl(it) }

            val encId = runCatching { URLEncoder.encode(trackId, "UTF-8") }.getOrDefault(trackId)
            val primaryStream = "$primaryNode/v1/tracks/$encId/stream?app_name=$AUDIUS_APP_NAME"
            val fallbackStreams = backupNodes.map { node ->
                "$node/v1/tracks/$encId/stream?app_name=$AUDIUS_APP_NAME"
            }

            out.add(
                MusicTrackPreset(
                    id = "audius_$trackId",
                    title = title,
                    artist = artist,
                    genre = genre,
                    streamUrl = primaryStream,
                    artworkUrl = artwork,
                    durationSeconds = duration.coerceAtLeast(0),
                    providerName = PROVIDER_AUDIUS,
                    fallbackUrls = fallbackStreams,
                    isTrending = markTrending
                )
            )
        }
        return out
    }

    // --- 2. HearThis.at Direct MP3 Music & DJ Mixes Parser ---

    fun parseHearThisJson(
        jsonText: String,
        markTrending: Boolean = false,
        extraGenreHint: String = ""
    ): List<MusicTrackPreset> {
        val arr = runCatching { JSONArray(jsonText) }.getOrNull() ?: return emptyList()
        val out = ArrayList<MusicTrackPreset>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            if (obj.optString("private") == "1") continue
            val id = obj.optString("id").trim()
            val title = obj.optString("title").trim()
            val streamUrl = obj.optString("stream_url").trim()
                .ifBlank { obj.optString("preview_url").trim() }
            if (id.isBlank() || title.isBlank() || !isValidAudioUrl(streamUrl)) continue

            val userObj = obj.optJSONObject("user")
            val artist = userObj?.optString("username")?.trim().orEmpty()
            val rawGenre = obj.optString("genre").trim()
            val genre = listOf(rawGenre, extraGenreHint)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" • ")
                .ifBlank { "DJ Mix & Musique" }
            val duration = obj.optString("duration").trim().toIntOrNull()
                ?: obj.optInt("duration", 0)
            val artwork = obj.optString("artwork_url_retina").trim().takeIf { isValidAudioUrl(it) }
                ?: obj.optString("artwork_url").trim().takeIf { isValidAudioUrl(it) }
                ?: obj.optString("thumb").trim().takeIf { isValidAudioUrl(it) }

            val fallbacks = obj.optString("preview_url").trim()
                .takeIf { isValidAudioUrl(it) && !it.equals(streamUrl, ignoreCase = true) }
                ?.let { listOf(it) }
                .orEmpty()

            out.add(
                MusicTrackPreset(
                    id = "hearthis_$id",
                    title = title,
                    artist = artist,
                    genre = genre,
                    streamUrl = streamUrl,
                    artworkUrl = artwork,
                    durationSeconds = duration.coerceAtLeast(0),
                    providerName = PROVIDER_HEARTHIS,
                    fallbackUrls = fallbacks,
                    isTrending = markTrending
                )
            )
        }
        return out
    }

    // --- 3. Funkwhale Federated Open Music Pod Parser ---

    fun parseFunkwhaleTracksJson(
        jsonText: String,
        podBaseUrl: String = "https://open.audio"
    ): List<MusicTrackPreset> {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val results = root.optJSONArray("results") ?: return emptyList()
        val cleanBase = podBaseUrl.trim().trimEnd('/')
        val out = ArrayList<MusicTrackPreset>(results.length())

        for (i in 0 until results.length()) {
            val obj = results.optJSONObject(i) ?: continue
            if (!obj.optBoolean("is_playable", true)) continue
            val id = obj.optString("id").trim().ifBlank { obj.optInt("id", 0).toString() }
            val title = obj.optString("title").trim()
            val rawListen = obj.optString("listen_url").trim()
            if (id.isBlank() || id == "0" || title.isBlank() || rawListen.isBlank()) continue

            val streamUrl = if (rawListen.startsWith("http", ignoreCase = true)) {
                rawListen
            } else {
                "$cleanBase/${rawListen.trimStart('/')}"
            }
            if (!isValidAudioUrl(streamUrl)) continue

            val artist = obj.optJSONObject("artist")?.optString("name")?.trim().orEmpty()
            val albumObj = obj.optJSONObject("album")
            val albumTitle = albumObj?.optString("title")?.trim().orEmpty()
            val coverUrls = albumObj?.optJSONObject("cover")?.optJSONObject("urls")
                ?: obj.optJSONObject("cover")?.optJSONObject("urls")
            val rawCover = coverUrls?.optString("medium_square_crop")?.trim()
                ?.ifBlank { coverUrls.optString("original").trim() }
            val artwork = rawCover?.takeIf { it.isNotBlank() }?.let { c ->
                if (c.startsWith("http", ignoreCase = true)) c else "$cleanBase/${c.trimStart('/')}"
            }

            val uploads = obj.optJSONArray("uploads")
            val duration = uploads?.optJSONObject(0)?.optInt("duration", 0) ?: 0

            val tagsArr = obj.optJSONArray("tags")
            val tags = buildList {
                if (tagsArr != null) {
                    for (t in 0 until minOf(tagsArr.length(), 4)) {
                        tagsArr.optString(t).trim().takeIf { it.isNotBlank() }?.let { add(it) }
                    }
                }
            }.joinToString(" • ").ifBlank { "Album • Funkwhale" }

            out.add(
                MusicTrackPreset(
                    id = "fw_$id",
                    title = title,
                    artist = artist,
                    album = albumTitle,
                    genre = tags,
                    streamUrl = streamUrl,
                    artworkUrl = artwork,
                    durationSeconds = duration.coerceAtLeast(0),
                    providerName = PROVIDER_FUNKWHALE
                )
            )
        }
        return out
    }

    // --- 4. ccMixter Creative Commons Music API Parser ---

    fun parseCcMixterJson(jsonText: String): List<MusicTrackPreset> {
        val arr = runCatching { JSONArray(jsonText) }.getOrNull() ?: return emptyList()
        val out = ArrayList<MusicTrackPreset>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("upload_id").trim()
            val title = obj.optString("upload_name").trim()
            if (id.isBlank() || title.isBlank()) continue

            val artist = obj.optString("user_real_name").trim()
                .ifBlank { obj.optString("user_name").trim() }
            val files = obj.optJSONArray("files") ?: continue
            var mp3Url: String? = null
            var durationSecs = 0
            for (f in 0 until files.length()) {
                val fileObj = files.optJSONObject(f) ?: continue
                val dl = fileObj.optString("download_url").trim()
                val lower = dl.lowercase(Locale.ROOT)
                if (isValidAudioUrl(dl) && (lower.endsWith(".mp3") || lower.endsWith(".ogg") || lower.endsWith(".flac"))) {
                    mp3Url = dl
                    val rawTime = fileObj.optJSONObject("file_format_info")?.optString("ps")?.trim().orEmpty()
                    durationSecs = parseColonDuration(rawTime)
                    break
                }
            }
            val validUrl = mp3Url ?: continue

            val extra = obj.optJSONObject("upload_extra")
            val tags = extra?.optString("usertags")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() && !it.startsWith("editorial") }
                ?.take(4)
                ?.joinToString(" • ")
                .orEmpty()
                .ifBlank { "Instrumental & Chill • ccMixter" }

            out.add(
                MusicTrackPreset(
                    id = "ccmixter_$id",
                    title = title,
                    artist = artist,
                    genre = tags,
                    streamUrl = validUrl,
                    durationSeconds = durationSecs,
                    providerName = PROVIDER_CCMIXTER
                )
            )
        }
        return out
    }

    private fun parseColonDuration(text: String): Int {
        if (text.isBlank()) return 0
        val parts = text.split(':').mapNotNull { it.trim().toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> 0
        }
    }

    // --- 5. OpenSubsonic / Navidrome Standard Server Client ---

    fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            sb.append("%02x".format(b))
        }
        return sb.toString()
    }

    fun buildSubsonicAuthQuery(username: String, password: String, salt: String = "missa360"): String {
        val cleanSalt = salt.ifBlank { "missa360" }
        val token = md5Hex(password + cleanSalt)
        val encUser = runCatching { URLEncoder.encode(username.trim(), "UTF-8") }.getOrDefault(username.trim())
        return "u=$encUser&t=$token&s=$cleanSalt&v=1.16.1&c=MISSA_TV_360_PLUS&f=json"
    }

    fun parseOpenSubsonicSongsJson(
        jsonText: String,
        config: OpenSubsonicServerConfig,
        salt: String = "missa360"
    ): List<MusicTrackPreset> {
        if (!config.isConfigured()) return emptyList()
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val resp = root.optJSONObject("subsonic-response") ?: return emptyList()
        if (!resp.optString("status").equals("ok", ignoreCase = true)) return emptyList()

        val songsArray = resp.optJSONObject("randomSongs")?.optJSONArray("song")
            ?: resp.optJSONObject("searchResult3")?.optJSONArray("song")
            ?: resp.optJSONObject("songsByGenre")?.optJSONArray("song")
            ?: resp.optJSONObject("topSongs")?.optJSONArray("song")
            ?: return emptyList()

        val cleanBase = config.baseUrl.trim().trimEnd('/')
        val authQuery = buildSubsonicAuthQuery(config.username, config.password, salt)
        val out = ArrayList<MusicTrackPreset>(songsArray.length())

        for (i in 0 until songsArray.length()) {
            val song = songsArray.optJSONObject(i) ?: continue
            if (song.optBoolean("isVideo", false)) continue
            val songId = song.optString("id").trim()
            val title = song.optString("title").trim()
            if (songId.isBlank() || title.isBlank()) continue

            val encSongId = runCatching { URLEncoder.encode(songId, "UTF-8") }.getOrDefault(songId)
            val streamUrl = "$cleanBase/rest/stream.view?id=$encSongId&maxBitRate=320&$authQuery"
            val coverId = song.optString("coverArt").trim()
            val artwork = if (coverId.isNotBlank()) {
                val encCover = runCatching { URLEncoder.encode(coverId, "UTF-8") }.getOrDefault(coverId)
                "$cleanBase/rest/getCoverArt.view?id=$encCover&size=480&$authQuery"
            } else {
                null
            }

            out.add(
                MusicTrackPreset(
                    id = "subsonic_$songId",
                    title = title,
                    artist = song.optString("artist").trim(),
                    album = song.optString("album").trim(),
                    genre = song.optString("genre").trim().ifBlank { "Bibliothèque Subsonic" },
                    streamUrl = streamUrl,
                    artworkUrl = artwork,
                    durationSeconds = song.optInt("duration", 0).coerceAtLeast(0),
                    providerName = PROVIDER_SUBSONIC
                )
            )
        }
        return out
    }

    // --- 6. Piped Music (`filter=music_songs` Audio-Only Search & Stream Resolver) ---

    fun parsePipedMusicSearchJson(jsonText: String): List<MusicTrackPreset> {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val items = root.optJSONArray("items") ?: return emptyList()
        val out = ArrayList<MusicTrackPreset>(items.length())

        for (i in 0 until items.length()) {
            val obj = items.optJSONObject(i) ?: continue
            if (obj.optString("type").lowercase(Locale.ROOT) != "stream") continue
            if (obj.optBoolean("isShort", false)) continue
            val duration = obj.optInt("duration", 0)
            // Keep genuine songs/tracks (35s .. 25m), skip 24/7 live radio streams (duration <= 0)
            if (duration !in 35..1500) continue

            val rawUrl = obj.optString("url").trim()
            val videoId = extractWatchVideoId(rawUrl) ?: continue
            val title = obj.optString("title").trim()
            if (title.isBlank()) continue
            val artist = obj.optString("uploaderName")
                .replace(Regex("\\s*-\\s*Topic$", RegexOption.IGNORE_CASE), "")
                .trim()
            val thumb = obj.optString("thumbnail").trim().takeIf { isValidAudioUrl(it) }

            out.add(
                MusicTrackPreset(
                    id = "piped_$videoId",
                    title = title,
                    artist = artist,
                    genre = "Musique • Piped",
                    streamUrl = "$PIPED_SCHEME_PREFIX$videoId",
                    artworkUrl = thumb,
                    durationSeconds = duration,
                    providerName = PROVIDER_PIPED_MUSIC,
                    isPipedLazy = true
                )
            )
        }
        return out
    }

    fun parsePipedAudioStreamsJson(jsonText: String): List<String> {
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return emptyList()
        val audioStreams = root.optJSONArray("audioStreams") ?: return emptyList()
        data class Candidate(val url: String, val bitrate: Int, val isM4a: Boolean)
        val candidates = ArrayList<Candidate>(audioStreams.length())
        for (i in 0 until audioStreams.length()) {
            val s = audioStreams.optJSONObject(i) ?: continue
            val url = s.optString("url").trim()
            if (!isValidAudioUrl(url)) continue
            val mime = s.optString("mimeType").lowercase(Locale.ROOT)
            val bitrate = s.optInt("bitrate", 0)
            candidates.add(Candidate(url, bitrate, mime.contains("mp4") || mime.contains("m4a")))
        }
        return candidates
            .sortedWith(compareByDescending<Candidate> { it.isM4a }.thenByDescending { it.bitrate })
            .map { it.url }
            .distinct()
    }

    private fun extractWatchVideoId(rawUrl: String): String? {
        if (rawUrl.isBlank()) return null
        val qIdx = rawUrl.indexOf("v=")
        if (qIdx >= 0) {
            return rawUrl.substring(qIdx + 2).substringBefore('&').trim().takeIf { it.length >= 6 }
        }
        return rawUrl.trim('/').substringAfterLast('/').substringBefore('?').takeIf { it.length >= 6 }
    }

    suspend fun resolvePipedMusicTrackUrls(videoIdOrScheme: String): List<String> = withContext(Dispatchers.IO) {
        val cleanId = videoIdOrScheme.removePrefix(PIPED_SCHEME_PREFIX).trim()
        if (cleanId.isBlank()) return@withContext emptyList()
        val startIdx = preferredPipedMirrorIndex
        for (offset in PIPED_API_MIRRORS.indices) {
            val idx = (startIdx + offset) % PIPED_API_MIRRORS.size
            val base = PIPED_API_MIRRORS[idx]
            val urls = withTimeoutOrNull(6_500L) {
                try {
                    val text = app.get("$base/streams/$cleanId", headers = httpHeaders).text
                    parsePipedAudioStreamsJson(text)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }.orEmpty()
            if (urls.isNotEmpty()) {
                preferredPipedMirrorIndex = idx
                return@withContext urls
            }
        }
        emptyList()
    }

    // --- 7. Music M3U Playlist Parser (Custom MP3 / FLAC / OGG / M4A Tracks) ---

    private val extInfRegex = Regex("""#EXTINF:\s*(-?\d+)[^,]*,(.*)""", RegexOption.IGNORE_CASE)
    private val extAttrRegex = Regex("""([a-zA-Z0-9_-]+)\s*=\s*"([^"]*)"""")

    fun parseMusicPlaylistContent(
        content: String,
        defaultArtist: String = "",
        isCustom: Boolean = true,
        maxTracks: Int = 200
    ): List<MusicTrackPreset> {
        if (content.isBlank() || MissaRadioProviders.isSingleHlsStreamManifest(content)) return emptyList()
        val results = ArrayList<MusicTrackPreset>()
        var pendingTitle: String? = null
        var pendingArtist: String? = null
        var pendingLogo: String? = null
        var pendingGenre: String? = null
        var pendingDuration = 0

        for (rawLine in content.lineSequence()) {
            if (results.size >= maxTracks) break
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val attrs = HashMap<String, String>()
                for (m in extAttrRegex.findAll(line)) {
                    attrs[m.groupValues[1].lowercase(Locale.ROOT)] = m.groupValues[2].trim()
                }
                val match = extInfRegex.find(line)
                pendingDuration = match?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                val rawLabel = match?.groupValues?.getOrNull(2)?.trim().orEmpty()
                if (rawLabel.contains(" - ")) {
                    pendingArtist = rawLabel.substringBefore(" - ").trim()
                    pendingTitle = rawLabel.substringAfter(" - ").trim()
                } else {
                    pendingArtist = defaultArtist
                    pendingTitle = rawLabel.ifBlank { null }
                }
                pendingLogo = attrs["tvg-logo"]?.takeIf { isValidAudioUrl(it) }
                pendingGenre = attrs["group-title"]?.replace(";", " • ")?.trim()
            } else if (line.startsWith("#")) {
                continue
            } else if (isValidAudioUrl(line)) {
                val title = pendingTitle ?: deriveTrackTitleFromUrl(line)
                results.add(
                    MusicTrackPreset(
                        id = "custom_music_${line.hashCode()}",
                        title = title,
                        artist = pendingArtist ?: defaultArtist,
                        genre = pendingGenre ?: "Playlist Musicale",
                        streamUrl = line,
                        artworkUrl = pendingLogo,
                        durationSeconds = pendingDuration,
                        providerName = PROVIDER_CUSTOM_MUSIC,
                        isCustom = isCustom
                    )
                )
                pendingTitle = null
                pendingArtist = null
                pendingLogo = null
                pendingGenre = null
                pendingDuration = 0
            }
        }
        return results
    }

    private fun deriveTrackTitleFromUrl(url: String): String {
        val seg = runCatching {
            URI(url).path?.trim('/')?.substringAfterLast('/')?.substringBeforeLast('.')
        }.getOrNull()?.replace(Regex("[-_]+"), " ")?.trim()
        return if (!seg.isNullOrBlank() && seg.length >= 2) seg else "Piste Audio"
    }

    // --- 8. Deduplication & Accent-Insensitive Search Matcher ---

    fun normalizeTrackIdentity(artist: String, title: String): String {
        val raw = "${artist.trim()} ${title.trim()}"
        val decomposed = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)
        return decomposed
            .replace(Regex("\\b(official|audio|lyrics|hd|hq|320kbps|mp3)\\b"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
    }

    fun mergeMusicCatalogues(
        tracks: List<MusicTrackPreset>,
        favoriteIds: Set<String> = emptySet()
    ): List<MusicTrackPreset> {
        val byUrl = LinkedHashMap<String, MusicTrackPreset>(tracks.size)
        val canonicalByIdentity = HashMap<String, String>()

        for (track in tracks) {
            if (!isValidAudioUrl(track.streamUrl)) continue
            val normUrl = track.streamUrl.trim().lowercase(Locale.ROOT)
            val existingUrl = byUrl[normUrl]
            if (existingUrl != null) {
                if (existingUrl.artworkUrl.isNullOrBlank() && !track.artworkUrl.isNullOrBlank()) {
                    byUrl[normUrl] = existingUrl.copy(artworkUrl = track.artworkUrl)
                }
                continue
            }

            val identity = if (!track.isCustom) normalizeTrackIdentity(track.artist, track.title) else ""
            val existingCanonical = if (identity.length >= 5) canonicalByIdentity[identity] else null
            if (existingCanonical != null) {
                val primary = byUrl[existingCanonical]
                if (primary != null && !track.isPipedLazy) {
                    val combinedFallbacks = (primary.fallbackUrls + track.streamUrl + track.fallbackUrls)
                        .filter { !it.equals(primary.streamUrl, ignoreCase = true) && isValidAudioUrl(it) }
                        .distinctBy { it.lowercase(Locale.ROOT) }
                        .take(4)
                    byUrl[existingCanonical] = primary.copy(
                        artworkUrl = primary.artworkUrl ?: track.artworkUrl,
                        fallbackUrls = combinedFallbacks
                    )
                    continue
                }
            }

            if (identity.length >= 5) {
                canonicalByIdentity[identity] = normUrl
            }
            byUrl[normUrl] = track
        }

        return byUrl.values.sortedWith(
            compareByDescending<MusicTrackPreset> { favoriteIds.contains(it.id) }
                .thenByDescending { it.isCustom }
        )
    }

    fun matchesTrackQuery(track: MusicTrackPreset, rawQuery: String): Boolean {
        val q = Normalizer.normalize(rawQuery, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)
            .trim()
        if (q.isEmpty()) return true
        val haystack = Normalizer.normalize(
            "${track.title} ${track.artist} ${track.album} ${track.genre} ${track.providerName}",
            Normalizer.Form.NFD
        )
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .lowercase(Locale.ROOT)
        return haystack.contains(q)
    }

    // --- 9. Live Network Harvesters ---

    private suspend fun resolveAudiusNodes(): List<String> = withContext(Dispatchers.IO) {
        val discovered = withTimeoutOrNull(5_500L) {
            runCatching {
                val text = app.get("https://api.audius.co", headers = httpHeaders).text
                parseAudiusHostListJson(text)
            }.getOrDefault(emptyList())
        }.orEmpty()
        if (discovered.isNotEmpty()) {
            cachedAudiusNodes = discovered.take(5)
        }
        cachedAudiusNodes
    }

    private suspend fun fetchAudiusEndpoint(pathAndQuery: String, nodes: List<String>): String? {
        for (node in nodes.take(3)) {
            val clean = node.trimEnd('/')
            val body = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                try {
                    val text = app.get("$clean/$pathAndQuery", headers = httpHeaders).text
                    text.takeIf { it.trimStart().startsWith("{") }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
            if (!body.isNullOrBlank()) return body
        }
        return null
    }

    suspend fun fetchOpenSubsonicCatalog(config: OpenSubsonicServerConfig): List<MusicTrackPreset> =
        withContext(Dispatchers.IO) {
            if (!config.isConfigured()) return@withContext emptyList()
            val cleanBase = config.baseUrl.trim().trimEnd('/')
            val salt = "m360" + (System.currentTimeMillis() % 99999)
            val authQuery = buildSubsonicAuthQuery(config.username, config.password, salt)
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                runCatching {
                    val text = app.get("$cleanBase/rest/getRandomSongs.view?size=80&$authQuery", headers = httpHeaders).text
                    parseOpenSubsonicSongsJson(text, config, salt)
                }.getOrDefault(emptyList())
            }.orEmpty()
        }

    /**
     * Harvests all dedicated free music servers in parallel with bounded concurrency:
     * Audius, HearThis.at, Funkwhale (`open.audio`), ccMixter, plus user's OpenSubsonic server if configured.
     */
    suspend fun harvestAllMusicProviders(
        subsonicConfig: OpenSubsonicServerConfig? = null
    ): List<MusicTrackPreset> = coroutineScope {
        val semaphore = Semaphore(4)
        val audiusNodes = resolveAudiusNodes()

        val audiusSlices = listOf(
            Triple("v1/tracks/trending?limit=40&app_name=$AUDIUS_APP_NAME", true, "Trending"),
            Triple("v1/tracks/search?query=Afrobeats&limit=35&app_name=$AUDIUS_APP_NAME", false, "Afrique • Afrobeats"),
            Triple("v1/tracks/search?query=Amapiano&limit=25&app_name=$AUDIUS_APP_NAME", false, "Afrique • Amapiano"),
            Triple("v1/tracks/search?query=Makossa&limit=20&app_name=$AUDIUS_APP_NAME", false, "Cameroun • Makossa"),
            Triple("v1/tracks/trending?genre=Hip-Hop%2FRap&limit=35&app_name=$AUDIUS_APP_NAME", false, "Hip-Hop & Rap"),
            Triple("v1/tracks/trending?genre=R%26B%2FSoul&limit=30&app_name=$AUDIUS_APP_NAME", false, "R&B & Soul"),
            Triple("v1/tracks/trending?genre=Electronic&limit=25&app_name=$AUDIUS_APP_NAME", false, "Électro & Club"),
            Triple("v1/tracks/trending?genre=Jazz&limit=25&app_name=$AUDIUS_APP_NAME", false, "Jazz"),
            Triple("v1/tracks/trending?genre=Reggae&limit=25&app_name=$AUDIUS_APP_NAME", false, "Reggae & Dancehall")
        )
        val audiusDeferred = audiusSlices.map { (endpoint, isTrending, genreHint) ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val json = fetchAudiusEndpoint(endpoint, audiusNodes) ?: return@withPermit emptyList()
                    parseAudiusTracksJson(json, audiusNodes, markTrending = isTrending, extraGenreHint = genreHint)
                }
            }
        }

        val hearThisSlices = listOf(
            Triple("https://api-v2.hearthis.at/feed/?type=popular&page=1&count=30", true, "Trending • DJ Mix"),
            Triple("https://api-v2.hearthis.at/search?t=afrobeats&page=1&count=25", false, "Afrique • Afrobeats"),
            Triple("https://api-v2.hearthis.at/search?t=amapiano&page=1&count=20", false, "Afrique • Amapiano"),
            Triple("https://api-v2.hearthis.at/categories/hiphop/?page=1&count=25", false, "Hip-Hop & Rap"),
            Triple("https://api-v2.hearthis.at/categories/rnb/?page=1&count=20", false, "R&B & Soul"),
            Triple("https://api-v2.hearthis.at/categories/reggae/?page=1&count=20", false, "Reggae"),
            Triple("https://api-v2.hearthis.at/categories/dancehall/?page=1&count=20", false, "Dancehall"),
            Triple("https://api-v2.hearthis.at/categories/jazz/?page=1&count=20", false, "Jazz"),
            Triple("https://api-v2.hearthis.at/categories/chillout/?page=1&count=20", false, "Chillout & Lounge")
        )
        val hearThisDeferred = hearThisSlices.map { (url, isTrending, genreHint) ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                        runCatching {
                            val text = app.get(url, headers = httpHeaders).text
                            parseHearThisJson(text, markTrending = isTrending, extraGenreHint = genreHint)
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }
        }

        val funkwhaleDeferred = FUNKWHALE_PODS.map { pod ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                        runCatching {
                            val text = app.get(
                                "$pod/api/v1/tracks/?playable=true&ordering=-creation_date&page_size=40",
                                headers = httpHeaders
                            ).text
                            parseFunkwhaleTracksJson(text, pod)
                        }.getOrDefault(emptyList())
                    }.orEmpty()
                }
            }
        }

        val ccMixterDeferred = async(Dispatchers.IO) {
            semaphore.withPermit {
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    runCatching {
                        val text = app.get(
                            "https://ccmixter.org/api/query?f=json&limit=35&tags=hip_hop,soul,jazz,chill,funk&sort=rank",
                            headers = httpHeaders
                        ).text
                        parseCcMixterJson(text)
                    }.getOrDefault(emptyList())
                }.orEmpty()
            }
        }

        val subsonicDeferred = async(Dispatchers.IO) {
            if (subsonicConfig?.isConfigured() == true) {
                semaphore.withPermit { fetchOpenSubsonicCatalog(subsonicConfig) }
            } else {
                emptyList()
            }
        }

        val combined = ArrayList<MusicTrackPreset>(500)
        combined.addAll(subsonicDeferred.await())
        combined.addAll(audiusDeferred.awaitAll().flatten())
        combined.addAll(hearThisDeferred.awaitAll().flatten())
        combined.addAll(funkwhaleDeferred.awaitAll().flatten())
        combined.addAll(ccMixterDeferred.await())

        mergeMusicCatalogues(combined).take(650)
    }

    /**
     * Real-time search across all dedicated music servers:
     * Audius, HearThis.at, Funkwhale (`open.audio`), ccMixter, OpenSubsonic (if configured),
     * and Piped Music (`filter=music_songs` audio-only).
     */
    suspend fun searchAllMusicProviders(
        query: String,
        subsonicConfig: OpenSubsonicServerConfig? = null
    ): List<MusicTrackPreset> = coroutineScope {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@coroutineScope emptyList()
        val encoded = runCatching { URLEncoder.encode(trimmed, "UTF-8") }.getOrDefault(trimmed)
        val audiusNodes = cachedAudiusNodes

        val audiusJob = async(Dispatchers.IO) {
            val json = fetchAudiusEndpoint("v1/tracks/search?query=$encoded&limit=40&app_name=$AUDIUS_APP_NAME", audiusNodes)
                ?: return@async emptyList()
            parseAudiusTracksJson(json, audiusNodes)
        }

        val hearThisJob = async(Dispatchers.IO) {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                runCatching {
                    val text = app.get("https://api-v2.hearthis.at/search?t=$encoded&page=1&count=30", headers = httpHeaders).text
                    parseHearThisJson(text)
                }.getOrDefault(emptyList())
            }.orEmpty()
        }

        val funkwhaleJob = async(Dispatchers.IO) {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                runCatching {
                    val text = app.get(
                        "https://open.audio/api/v1/tracks/?playable=true&q=$encoded&page_size=25",
                        headers = httpHeaders
                    ).text
                    parseFunkwhaleTracksJson(text, "https://open.audio")
                }.getOrDefault(emptyList())
            }.orEmpty()
        }

        val ccMixterJob = async(Dispatchers.IO) {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                runCatching {
                    val text = app.get(
                        "https://ccmixter.org/api/query?f=json&limit=25&search=$encoded&search_type=any",
                        headers = httpHeaders
                    ).text
                    parseCcMixterJson(text)
                }.getOrDefault(emptyList())
            }.orEmpty()
        }

        val pipedJob = async(Dispatchers.IO) {
            val startIdx = preferredPipedMirrorIndex
            for (offset in PIPED_API_MIRRORS.indices) {
                val idx = (startIdx + offset) % PIPED_API_MIRRORS.size
                val base = PIPED_API_MIRRORS[idx]
                val hits = withTimeoutOrNull(6_000L) {
                    runCatching {
                        val text = app.get("$base/search?q=$encoded&filter=music_songs", headers = httpHeaders).text
                        parsePipedMusicSearchJson(text)
                    }.getOrDefault(emptyList())
                }.orEmpty()
                if (hits.isNotEmpty()) {
                    preferredPipedMirrorIndex = idx
                    return@async hits
                }
            }
            emptyList()
        }

        val subsonicJob = async(Dispatchers.IO) {
            if (subsonicConfig?.isConfigured() == true) {
                val cleanBase = subsonicConfig.baseUrl.trim().trimEnd('/')
                val salt = "m360s"
                val authQuery = buildSubsonicAuthQuery(subsonicConfig.username, subsonicConfig.password, salt)
                withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    runCatching {
                        val text = app.get(
                            "$cleanBase/rest/search3.view?query=$encoded&songCount=40&$authQuery",
                            headers = httpHeaders
                        ).text
                        parseOpenSubsonicSongsJson(text, subsonicConfig, salt)
                    }.getOrDefault(emptyList())
                }.orEmpty()
            } else {
                emptyList()
            }
        }

        val rawHits = subsonicJob.await() +
            audiusJob.await() +
            hearThisJob.await() +
            pipedJob.await() +
            funkwhaleJob.await() +
            ccMixterJob.await()

        val tagged = rawHits.map { tr ->
            if (matchesTrackQuery(tr, trimmed)) tr else tr.copy(genre = "${tr.genre} • $trimmed")
        }
        mergeMusicCatalogues(tagged).take(140)
    }

    suspend fun fetchRemoteMusicPlaylistIfAny(
        url: String,
        defaultArtist: String = ""
    ): List<MusicTrackPreset> = withContext(Dispatchers.IO) {
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
            runCatching {
                val lower = url.lowercase(Locale.ROOT).substringBefore('?')
                if (!lower.endsWith(".m3u") && !lower.endsWith(".m3u8")) return@withTimeoutOrNull emptyList()
                val text = app.get(url, headers = httpHeaders).text
                if (MissaRadioProviders.isSingleHlsStreamManifest(text)) return@withTimeoutOrNull emptyList()
                parseMusicPlaylistContent(text, defaultArtist = defaultArtist, isCustom = true)
            }.getOrDefault(emptyList())
        }.orEmpty()
    }
}
