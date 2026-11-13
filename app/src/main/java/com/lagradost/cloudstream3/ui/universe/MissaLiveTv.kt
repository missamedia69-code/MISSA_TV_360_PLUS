package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Univers TV en direct : les chaînes viennent d'un simple fichier M3U du dépôt,
 * `tv/chaines.m3u`.
 *
 * - À chaque démarrage, l'application demande ce fichier à GitHub. Elle envoie l'empreinte
 *   (ETag) de sa copie : si le fichier n'a pas changé, GitHub ne renvoie rien et rien n'est
 *   téléchargé.
 * - La copie est gardée sur l'appareil (fonctionne hors connexion) ; au tout premier
 *   lancement, la liste rangée dans l'APK sert en attendant.
 * - Modifier la liste des chaînes = modifier `tv/chaines.m3u` sur la branche main. Aucune
 *   nouvelle version de l'application n'est nécessaire.
 */
object MissaLiveTv {

    private const val REPO = "missamedia69-code/MISSA_TV_360_PLUS"
    private const val PLAYLIST_PATH = "tv/chaines.m3u"
    private const val ASSET_PATH = "tv/chaines.m3u"
    private const val LOCAL_DIR = "missa-tv"
    private const val LOCAL_FILE = "chaines.m3u"
    private const val ETAG_KEY = "missa_live_tv_etag_v1"
    private const val FAVORITES_KEY = "missa_live_tv_favorites_v1"
    private const val MAX_CHANNELS = 20_000
    private const val MAX_BYTES = 16L * 1024 * 1024
    private const val USER_AGENT = "MISSA-TV-360"

    val playlistUrl: String
        get() = "https://raw.githubusercontent.com/$REPO/${BuildConfig.MISSA_CONTENT_BRANCH}/$PLAYLIST_PATH"

    data class Channel(
        val id: String,
        val name: String,
        val url: String,
        val logo: String?,
        /** Catégories (group-title, plusieurs possibles séparées par « ; »). */
        val groups: List<String>,
        /** Code pays ISO (tvg-country), en majuscules, ou null. */
        val country: String?,
        val userAgent: String? = null,
        val referer: String? = null
    )

    // ------------------------------------------------------------------ Lecture du M3U

    private val attrRegex = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    /**
     * Lit un M3U ligne par ligne (jamais tout le fichier en mémoire d'un coup). Comprend :
     * `#EXTINF` (tvg-id, tvg-name, tvg-logo, tvg-country, group-title, http-referrer,
     * http-user-agent), `#EXTGRP`, `#EXTVLCOPT:http-referrer` / `http-user-agent`.
     */
    fun parse(reader: BufferedReader): List<Channel> {
        val out = ArrayList<Channel>()
        val seenUrls = HashSet<String>()
        var name: String? = null
        var attrs: Map<String, String> = emptyMap()
        var extGroup: String? = null
        var vlcReferer: String? = null
        var vlcAgent: String? = null
        while (true) {
            val raw = reader.readLine() ?: break
            val line = raw.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val comma = findTitleComma(line)
                    val head = if (comma >= 0) line.substring(0, comma) else line
                    name = if (comma >= 0) line.substring(comma + 1).trim() else null
                    attrs = attrRegex.findAll(head).associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2].trim() }
                    extGroup = null
                    vlcReferer = null
                    vlcAgent = null
                }

                line.startsWith("#EXTGRP:", ignoreCase = true) -> extGroup = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase(Locale.ROOT)
                    val value = opt.substringAfter('=', "").trim()
                    when (key) {
                        "http-referrer", "http-referer" -> vlcReferer = value
                        "http-user-agent" -> vlcAgent = value
                    }
                }

                line.startsWith("#") -> Unit
                else -> {
                    val url = line
                    val title = name?.takeIf { it.isNotBlank() } ?: attrs["tvg-name"]
                    if (title != null && isPlayableUrl(url) && seenUrls.add(url)) {
                        val groups = (attrs["group-title"] ?: extGroup).orEmpty()
                            .split(';')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                        val country = attrs["tvg-country"]?.split(';', ',')?.firstOrNull()
                            ?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 }
                        out += Channel(
                            id = attrs["tvg-id"]?.takeIf { it.isNotBlank() }?.let { "$it|${url.hashCode()}" } ?: url,
                            name = title,
                            url = url,
                            logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http", ignoreCase = true) },
                            groups = groups,
                            country = country,
                            userAgent = vlcAgent ?: attrs["http-user-agent"]?.takeIf { it.isNotBlank() },
                            referer = vlcReferer ?: attrs["http-referrer"]?.takeIf { it.isNotBlank() }
                        )
                        if (out.size >= MAX_CHANNELS) break
                    }
                    name = null
                    attrs = emptyMap()
                    extGroup = null
                    vlcReferer = null
                    vlcAgent = null
                }
            }
        }
        return out
    }

    fun parse(text: String): List<Channel> = parse(BufferedReader(text.reader()))

    /** La virgule qui sépare les attributs du nom : la première hors guillemets. */
    private fun findTitleComma(line: String): Int {
        var inQuotes = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }

    private fun isPlayableUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("rtmp://") || lower.startsWith("rtsp://")) && !url.contains(' ')
    }

    // ------------------------------------------------------------------ Chargement

    private val mutex = Mutex()

    @Volatile
    private var memo: List<Channel>? = null

    private val _updates = MutableLiveData(0L)
    /** Émet quand une nouvelle liste a été téléchargée : l'écran ouvert se met à jour. */
    val updates: LiveData<Long> = _updates

    /** Dernière liste chargée, sans attendre (vide tant qu'elle n'a pas été lue). */
    fun cachedChannels(): List<Channel> = memo.orEmpty()

    private fun localFile(context: Context) = File(File(context.filesDir, LOCAL_DIR), LOCAL_FILE)

    /** La liste à afficher : copie téléchargée, sinon celle rangée dans l'APK. */
    suspend fun channels(context: Context): List<Channel> = withContext(Dispatchers.IO) {
        memo?.let { return@withContext it }
        mutex.withLock {
            memo?.let { return@withLock it }
            val file = localFile(context)
            val fromDisk = if (file.exists()) {
                runCatching { file.bufferedReader().use { parse(it) } }.getOrNull()
            } else null
            val list = fromDisk?.takeIf { it.isNotEmpty() } ?: runCatching {
                context.assets.open(ASSET_PATH).use { parse(BufferedReader(InputStreamReader(it, Charsets.UTF_8))) }
            }.getOrDefault(emptyList())
            memo = list
            list
        }
    }

    /**
     * Télécharge la liste de GitHub si elle a changé. Appelé au démarrage de l'application.
     * Vrai si une nouvelle liste a été enregistrée.
     */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val file = localFile(appContext)
        val etag = if (file.exists()) getKey<String>(ETAG_KEY) else null
        val conn = runCatching { URL(playlistUrl).openConnection() as HttpURLConnection }.getOrNull()
            ?: return@withContext false
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            if (etag != null) conn.setRequestProperty("If-None-Match", etag)
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED || code != HttpURLConnection.HTTP_OK) return@withContext false
            // getContentLengthLong() n'existe qu'à partir d'Android 7 : on lit l'en-tête.
            val length = conn.getHeaderField("Content-Length")?.trim()?.toLongOrNull() ?: -1L
            if (length > MAX_BYTES) return@withContext false
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$LOCAL_FILE.part")
            var total = 0L
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) throw IllegalStateException("liste trop grande")
                        output.write(buf, 0, n)
                    }
                }
            }
            // On n'adopte la nouvelle liste que si elle contient au moins une chaîne.
            val parsed = tmp.bufferedReader().use { parse(it) }
            if (parsed.isEmpty()) {
                tmp.delete()
                return@withContext false
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            conn.getHeaderField("ETag")?.let { setKey(ETAG_KEY, it) }
            memo = parsed
            _updates.postValue(System.currentTimeMillis())
            true
        } catch (_: Exception) {
            false
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ Favoris

    fun favoriteUrls(): Set<String> = runCatching {
        getKey<Array<String>>(FAVORITES_KEY)?.toSet().orEmpty()
    }.getOrDefault(emptySet())

    fun toggleFavorite(channel: Channel): Boolean {
        val set = favoriteUrls().toMutableSet()
        val now = if (set.remove(channel.url)) false else set.add(channel.url)
        runCatching { setKey(FAVORITES_KEY, set.toTypedArray()) }
        return now
    }
}
