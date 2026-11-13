package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/**
 * Univers Jeux : jeux **Neo Geo**, catalogue et installation à la demande.
 *
 * Rien de l'émulateur n'est dans l'APK. Le catalogue `game/catalogue.json` du dépôt décrit :
 *  - le **moteur** Neo Geo, un paquet par architecture (Geolith + LibretroDroid, ~0,7 Mo),
 *    téléchargé une seule fois, la première fois qu'un jeu est lancé ;
 *  - le **BIOS** Neo Geo (neogeo.zip en mode MVS, aes.zip en mode AES), une seule fois ;
 *  - les **jeux** (.neo) et leur bannière, affichée avant tout téléchargement.
 * Chaque fichier est vérifié par son SHA-256 avant d'être utilisé ; le moteur est ensuite
 * rendu non modifiable puis chargé par [NeoGeoPlayerActivity].
 *
 * Organisation sur l'appareil (filesDir/missa-games) :
 * ```
 * neogeo/moteur/<abi>/liblibretrodroid.so, libgeolith.so, .version
 * neogeo/bios/neogeo.zip | aes.zip
 * neogeo/saves/<jeu>.srm                 sauvegardes
 * roms/neogeo/<fichier>.neo              jeux
 * catalogue.json                         dernier catalogue lu
 * ```
 */
object MissaGames {

    private const val RAW_MAIN = "raw.githubusercontent.com/missamedia69-code/MISSA_TV_360_PLUS/main/"
    private const val CATALOGUE_ASSET = "game/catalogue.json"
    private const val REPO = "missamedia69-code/MISSA_TV_360_PLUS"
    private const val TAG = "MissaGames"

    /** Branche d'où viennent les contenus (« main » pour les versions publiées). */
    private val rawBranch: String
        get() = RAW_MAIN.removeSuffix("main/") + BuildConfig.MISSA_CONTENT_BRANCH + "/"

    val CATALOGUE_URL: String
        get() = "https://${rawBranch}game/catalogue.json"

    /** Relu à chaque démarrage (MainActivity) et, en ouvrant l'univers, s'il a plus d'une heure. */
    private const val CATALOGUE_MAX_AGE_MS = 3600_000L
    /** Les plus gros jeux Neo Geo font environ 120 Mo au format .neo. */
    private const val MAX_DOWNLOAD_BYTES = 160L * 1024L * 1024L
    private const val USER_AGENT = "MISSA-TV-360"

    const val SYSTEM = "neogeo"
    const val CORE_LIB = "libgeolith.so"
    const val FRONTEND_LIB = "liblibretrodroid.so"

    // ----------------------------------------------------------------------------- Modèle

    data class Package(val url: String, val sha256: String, val bytes: Long)

    data class BiosFile(val fileName: String, val pkg: Package, val licence: String)

    data class Engine(val abi: String, val version: String, val pkg: Package, val licence: String)

    data class Game(
        val id: String,
        val title: String,
        val genre: String,
        val author: String,
        val description: String,
        val fileName: String,
        val pkg: Package,
        val licence: String,
        /** Bannière (HTTPS) affichée avant le téléchargement, ou null. */
        val coverUrl: String? = null,
        val players: String = ""
    ) {
        val system: String get() = SYSTEM
        val platformLabel: String get() = "Neo Geo"
    }

    data class Catalogue(
        /** Moteur par architecture (arm64-v8a, armeabi-v7a, x86_64, x86). */
        val engines: Map<String, Engine>,
        val bios: List<BiosFile>,
        /** « mvs » (arcade, neogeo.zip) ou « aes » (console, aes.zip). */
        val mode: String,
        val games: List<Game>
    ) {
        /** Moteur adapté à cet appareil : la première architecture qu'il sait exécuter. */
        fun engineForDevice(abis: Array<String> = Build.SUPPORTED_ABIS): Engine? =
            abis.firstNotNullOfOrNull { engines[it] }

        /** BIOS attendu par le mode choisi. */
        val requiredBiosName: String get() = if (mode == "aes") "aes.zip" else "neogeo.zip"

        val requiredBios: BiosFile? get() = bios.firstOrNull { it.fileName == requiredBiosName }
    }

    /** Ce qu'il reste à télécharger pour pouvoir lancer un jeu. */
    data class InstallPlan(val engine: Engine?, val bios: BiosFile?, val game: Boolean) {
        val isEmpty: Boolean get() = engine == null && bios == null && !game
        fun bytes(game: Game): Long =
            (engine?.pkg?.bytes ?: 0L) + (bios?.pkg?.bytes ?: 0L) + (if (this.game) game.pkg.bytes else 0L)
    }

    data class Progress(val doneBytes: Long, val totalBytes: Long, val step: Step) {
        val percent: Int
            get() = if (totalBytes > 0) ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
    }

    enum class Step { ENGINE, BIOS, GAME }

    // -------------------------------------------------------------------- Lecture (pure)

    /** Une empreinte SHA-256 valide (64 caractères hexadécimaux), ramenée en minuscules. */
    internal fun normalizeSha256(raw: String?): String? {
        val clean = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
        return clean.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /** Garde un nom de fichier simple : pas de dossier, pas de caractère exotique. */
    internal fun safeFileName(raw: String): String? {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_', '.')
        return base.takeIf { it.isNotBlank() }
    }

    private fun parsePackage(obj: JSONObject?): Package? {
        obj ?: return null
        val url = obj.optString("url").trim()
        val sha = normalizeSha256(obj.optString("sha256")) ?: return null
        val bytes = obj.optLong("bytes", 0L)
        if (!url.startsWith("https://") || bytes <= 0L || bytes > MAX_DOWNLOAD_BYTES) return null
        return Package(url, sha, bytes)
    }

    /**
     * Lit le catalogue (version 2, Neo Geo). Toute entrée incomplète, sans empreinte ou dont
     * l'adresse n'est pas en HTTPS est ignorée : mieux vaut un jeu absent qu'un fichier
     * impossible à vérifier.
     */
    fun parseCatalogue(json: String): Catalogue? {
        // Version d'essai lue sur une autre branche : les fichiers viennent de cette branche.
        val text = if (rawBranch != RAW_MAIN) json.replace(RAW_MAIN, rawBranch) else json
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (root.optString("console", SYSTEM) != SYSTEM) return null

        val engineObj = root.optJSONObject("moteur") ?: return null
        val version = engineObj.optString("version")
        val licence = engineObj.optString("licence")
        val engines = LinkedHashMap<String, Engine>()
        engineObj.optJSONObject("abis")?.let { abis ->
            for (abi in abis.keys()) {
                val pkg = parsePackage(abis.optJSONObject(abi)) ?: continue
                engines[abi] = Engine(abi, version, pkg, licence)
            }
        }
        if (engines.isEmpty()) return null

        val bios = ArrayList<BiosFile>()
        root.optJSONArray("bios")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = safeFileName(o.optString("fileName")) ?: continue
                if (name != "neogeo.zip" && name != "aes.zip") continue
                val pkg = parsePackage(o) ?: continue
                bios.add(BiosFile(name, pkg, o.optString("licence")))
            }
        }

        val games = ArrayList<Game>()
        val seen = HashSet<String>()
        root.optJSONArray("jeux")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val pkg = parsePackage(o) ?: continue
                val fileName = safeFileName(o.optString("fileName")) ?: continue
                if (id.isBlank() || !seen.add(id) || !fileName.endsWith(".neo", ignoreCase = true)) continue
                games.add(
                    Game(
                        id = id,
                        title = o.optString("title", fileName),
                        genre = o.optString("genre"),
                        author = o.optString("author"),
                        description = o.optString("description"),
                        fileName = fileName,
                        pkg = pkg,
                        licence = o.optString("licence"),
                        coverUrl = (o.optString("bannerUrl").ifBlank { o.optString("coverUrl") })
                            .trim().takeIf { it.startsWith("https://") },
                        players = o.optString("players")
                    )
                )
            }
        }
        val mode = root.optString("mode", "mvs").lowercase(Locale.ROOT).takeIf { it == "aes" } ?: "mvs"
        return Catalogue(engines, bios, mode, games)
    }

    // ------------------------------------------------------------------ Fichiers locaux

    fun rootDir(context: Context): File = File(context.filesDir, "missa-games").apply { mkdirs() }
    private fun neoGeoDir(context: Context): File = File(rootDir(context), "neogeo")
    fun engineDir(context: Context, abi: String): File = File(File(neoGeoDir(context), "moteur"), abi)
    fun biosDir(context: Context): File = File(neoGeoDir(context), "bios").apply { mkdirs() }
    fun savesDir(context: Context): File = File(neoGeoDir(context), "saves").apply { mkdirs() }
    private fun romsDir(context: Context): File = File(File(rootDir(context), "roms"), SYSTEM).apply { mkdirs() }
    private fun catalogueCache(context: Context): File = File(rootDir(context), "catalogue.json")
    private fun engineMarker(context: Context, abi: String): File = File(engineDir(context, abi), ".version")

    fun gameFile(context: Context, game: Game): File = File(romsDir(context), game.fileName)
    fun biosFile(context: Context, bios: BiosFile): File = File(biosDir(context), bios.fileName)
    fun coreFile(context: Context, engine: Engine): File = File(engineDir(context, engine.abi), CORE_LIB)
    fun frontendFile(context: Context, engine: Engine): File = File(engineDir(context, engine.abi), FRONTEND_LIB)

    private fun markerMatches(marker: File, sha: String): Boolean =
        marker.isFile && runCatching { marker.readText().trim() }.getOrNull() == sha

    fun isEngineInstalled(context: Context, engine: Engine): Boolean =
        markerMatches(engineMarker(context, engine.abi), engine.pkg.sha256) &&
            coreFile(context, engine).isFile && frontendFile(context, engine).isFile

    fun isBiosInstalled(context: Context, bios: BiosFile): Boolean {
        val file = biosFile(context, bios)
        return file.isFile && file.length() == bios.pkg.bytes
    }

    private fun isGameFilePresent(context: Context, game: Game): Boolean {
        val file = gameFile(context, game)
        return file.isFile && file.length() == game.pkg.bytes
    }

    /** Un jeu est prêt si lui, le moteur de l'appareil et le BIOS sont présents et entiers. */
    fun isGameInstalled(context: Context, catalogue: Catalogue, game: Game): Boolean {
        val engine = catalogue.engineForDevice() ?: return false
        val bios = catalogue.requiredBios ?: return false
        return isGameFilePresent(context, game) && isEngineInstalled(context, engine) &&
            isBiosInstalled(context, bios)
    }

    fun planInstall(context: Context, catalogue: Catalogue, game: Game): InstallPlan {
        val engine = catalogue.engineForDevice()
        val bios = catalogue.requiredBios
        return InstallPlan(
            engine = engine?.takeIf { !isEngineInstalled(context, it) },
            bios = bios?.takeIf { !isBiosInstalled(context, it) },
            game = !isGameFilePresent(context, game)
        )
    }

    fun installedBytes(context: Context, catalogue: Catalogue): Long =
        catalogue.games.filter { isGameFilePresent(context, it) }.sumOf { it.pkg.bytes }

    fun deleteGame(context: Context, game: Game): Boolean = gameFile(context, game).delete()

    // --------------------------------------------------------------------- Catalogue

    @Volatile
    private var memo: Pair<Long, Catalogue>? = null

    /** Le catalogue livré avec l'APK évite qu'une ancienne copie vide masque les jeux publiés. */
    private fun bundledCatalogue(context: Context): Catalogue? {
        if (BuildConfig.MISSA_CONTENT_BRANCH != "main") return null
        return runCatching {
            context.assets.open(CATALOGUE_ASSET).bufferedReader().use { parseCatalogue(it.readText()) }
        }.getOrNull()
    }

    /** Dernier catalogue téléchargé, ou copie embarquée si le cache est absent ou encore vide. */
    fun cachedCatalogue(context: Context): Catalogue? {
        val cache = catalogueCache(context)
        if (!cache.isFile) return bundledCatalogue(context)
        val stamp = cache.lastModified()
        val cached = memo?.takeIf { it.first == stamp }?.second ?: runCatching {
            parseCatalogue(cache.readText())
        }.getOrNull()?.also { memo = stamp to it }
        return cached?.takeIf { it.games.isNotEmpty() } ?: bundledCatalogue(context) ?: cached
    }

    /**
     * Ne fait pas confiance à un ancien catalogue vide : sur certains réseaux l'adresse raw
     * renvoie encore cette version après publication. L'API GitHub sert de second accès.
     */
    private fun fetchCatalogue(): Pair<String, Catalogue>? {
        val branch = BuildConfig.MISSA_CONTENT_BRANCH
        val rawUrl = "$CATALOGUE_URL?t=${System.currentTimeMillis()}"
        val apiUrl = "https://api.github.com/repos/$REPO/contents/game/catalogue.json?ref=$branch"
        var empty: Pair<String, Catalogue>? = null
        for ((url, isApi) in listOf(rawUrl to false, apiUrl to true)) {
            try {
                val response = httpGetText(url)
                val body = if (isApi) {
                    val content = JSONObject(response).getString("content")
                    String(Base64.decode(content, Base64.DEFAULT), Charsets.UTF_8)
                } else response
                val catalogue = parseCatalogue(body) ?: throw IOException("Invalid catalogue from $url")
                if (catalogue.games.isNotEmpty()) return body to catalogue
                empty = body to catalogue
            } catch (e: Exception) {
                Log.w(TAG, "Catalogue unavailable from $url", e)
            }
        }
        return empty
    }

    /** Relit le catalogue en ligne s'il est trop ancien (ou si [force]) ; garde le cache sinon. */
    suspend fun refreshCatalogue(context: Context, force: Boolean): Catalogue? = withContext(Dispatchers.IO) {
        val cache = catalogueCache(context)
        val fresh = cache.isFile && System.currentTimeMillis() - cache.lastModified() < CATALOGUE_MAX_AGE_MS
        val current = cachedCatalogue(context)
        val diskHasGames = cache.isFile && runCatching {
            parseCatalogue(cache.readText())?.games?.isNotEmpty() == true
        }.getOrDefault(false)
        if (!force && fresh && diskHasGames) return@withContext current
        val fetched = fetchCatalogue()
        if (fetched != null) {
            cache.writeText(fetched.first)
            memo = cache.lastModified() to fetched.second
        }
        cachedCatalogue(context)
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private fun httpGetText(url: String): String {
        val conn = openConnection(url)
        conn.useCaches = false
        conn.setRequestProperty("Cache-Control", "no-cache")
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------- Installation

    /**
     * Installe tout ce qu'il manque pour [game], dans l'ordre : moteur, BIOS, jeu.
     * [onProgress] reçoit l'avancement global. Lève une exception si un téléchargement échoue
     * ou si une empreinte ne correspond pas : rien de partiel n'est considéré comme installé.
     */
    suspend fun install(
        context: Context,
        catalogue: Catalogue,
        game: Game,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        if (catalogue.engineForDevice() == null) throw IOException("No Neo Geo engine for ${Build.SUPPORTED_ABIS.joinToString()}")
        if (catalogue.requiredBios == null) throw IOException("Neo Geo BIOS missing from catalogue")
        val plan = planInstall(context, catalogue, game)
        val total = plan.bytes(game)
        var done = 0L
        val tmp = File(rootDir(context), "tmp").apply { mkdirs() }

        plan.engine?.let { engine ->
            val zip = File(tmp, "moteur-${engine.abi}.zip")
            download(engine.pkg, zip) { n -> onProgress(Progress(done + n, total, Step.ENGINE)) }
            val dir = engineDir(context, engine.abi)
            // Un fichier non modifiable ne peut pas être écrasé : on repart d'un dossier vide.
            dir.listFiles()?.forEach { it.setWritable(true); it.delete() }
            dir.mkdirs()
            unzipSafely(zip, dir)
            zip.delete()
            // Android 14 et plus : un code chargé dynamiquement doit être en lecture seule.
            listOf(coreFile(context, engine), frontendFile(context, engine)).forEach {
                if (!it.isFile) throw IOException("Engine package incomplete: ${it.name}")
                it.setReadOnly()
            }
            engineMarker(context, engine.abi).writeText(engine.pkg.sha256)
            done += engine.pkg.bytes
        }
        plan.bios?.let { bios ->
            val target = biosFile(context, bios)
            val part = File(target.parentFile, target.name + ".part")
            download(bios.pkg, part) { n -> onProgress(Progress(done + n, total, Step.BIOS)) }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Could not finalize ${target.name}")
            done += bios.pkg.bytes
        }
        if (plan.game) {
            val target = gameFile(context, game)
            val part = File(target.parentFile, target.name + ".part")
            download(game.pkg, part) { n -> onProgress(Progress(done + n, total, Step.GAME)) }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) throw IOException("Could not finalize ${target.name}")
            done += game.pkg.bytes
        }
        onProgress(Progress(total, total, Step.GAME))
    }

    /** Télécharge [pkg] dans [dest] et vérifie taille et SHA-256 ; supprime le fichier sinon. */
    private suspend fun download(pkg: Package, dest: File, onBytes: (Long) -> Unit) {
        val conn = openConnection(pkg.url)
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.buffered().use { input ->
                FileOutputStream(dest).buffered().use { out ->
                    val buffer = ByteArray(32 * 1024)
                    var lastTick = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        if (written > pkg.bytes || written > MAX_DOWNLOAD_BYTES) throw IOException("Unexpected size")
                        digest.update(buffer, 0, read)
                        out.write(buffer, 0, read)
                        val now = System.currentTimeMillis()
                        if (now - lastTick > 120) {
                            lastTick = now
                            onBytes(written)
                        }
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (written != pkg.bytes || sha != pkg.sha256) throw IOException("Checksum mismatch")
            onBytes(written)
        } catch (e: Throwable) {
            dest.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /** Dézippe en refusant tout chemin qui sortirait de [destDir] (« zip slip »). */
    internal fun unzipSafely(zip: File, destDir: File) {
        zip.inputStream().use { unzipSafely(it, destDir) }
    }

    internal fun unzipSafely(input: InputStream, destDir: File) {
        val root = destDir.canonicalFile
        ZipInputStream(input.buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                val out = File(root, entry.name).canonicalFile
                if (!out.path.startsWith(root.path + File.separator)) {
                    throw IOException("Unsafe zip entry: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zin.copyTo(it) }
                }
                zin.closeEntry()
            }
        }
    }
}
