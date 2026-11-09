package com.lagradost.cloudstream3.ui.universe

import android.content.Context
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
 * Univers Jeux : catalogue et installation à la demande.
 *
 * Rien n'est embarqué dans l'APK. Le catalogue `game/catalogue.json` du dépôt décrit :
 *  - le **moteur** (EmulatorJS + lanceur.html), téléchargé une seule fois ;
 *  - un **émulateur** par console, téléchargé la première fois qu'un jeu de cette console
 *    est installé ;
 *  - les **jeux**.
 * Chaque fichier est vérifié par son SHA-256 avant d'être utilisé, puis le jeu tourne hors
 * ligne dans [GamePlayerActivity].
 *
 * Organisation sur l'appareil (filesDir/missa-games) :
 * ```
 * ejs/lanceur.html, ejs/data/...        moteur + cœurs dézippés (servis à la WebView)
 * ejs/.moteur, ejs/.emu-<console>       empreinte du paquet installé
 * roms/<console>/<fichier>              jeux
 * catalogue.json                        dernier catalogue lu
 * ```
 */
object MissaGames {

    const val CATALOGUE_URL =
        "https://raw.githubusercontent.com/missamedia69-code/MISSA_TV_360_PLUS/main/game/catalogue.json"

    /** Le catalogue est relu au plus toutes les 12 heures, ou à la demande. */
    private const val CATALOGUE_MAX_AGE_MS = 12L * 3600_000L
    private const val MAX_DOWNLOAD_BYTES = 64L * 1024L * 1024L
    private const val USER_AGENT = "MISSA-TV-360"

    // ----------------------------------------------------------------------------- Modèle

    data class Package(
        val url: String,
        val sha256: String,
        val bytes: Long
    )

    data class Emulator(
        val system: String,
        val label: String,
        val core: String,
        val pkg: Package,
        val licence: String
    )

    data class Game(
        val id: String,
        val title: String,
        val system: String,
        val platformLabel: String,
        val genre: String,
        val author: String,
        val description: String,
        val fileName: String,
        val pkg: Package,
        val licence: String,
        val sourceUrl: String
    )

    data class Catalogue(
        val engineVersion: String,
        val engine: Package,
        val emulators: Map<String, Emulator>,
        val games: List<Game>
    ) {
        fun emulatorFor(game: Game): Emulator? = emulators[game.system]
    }

    /** Ce qu'il reste à télécharger pour pouvoir lancer un jeu. */
    data class InstallPlan(
        val engine: Boolean,
        val emulator: Emulator?,
        val game: Boolean
    ) {
        val isEmpty: Boolean get() = !engine && emulator == null && !game
    }

    data class Progress(val doneBytes: Long, val totalBytes: Long, val step: Step) {
        val percent: Int
            get() = if (totalBytes > 0) ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
    }

    enum class Step { ENGINE, EMULATOR, GAME }

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
     * Lit le catalogue. Toute entrée incomplète, sans empreinte ou dont l'adresse n'est pas en
     * HTTPS est ignorée : mieux vaut un jeu absent qu'un fichier impossible à vérifier.
     */
    fun parseCatalogue(json: String): Catalogue? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val engineObj = root.optJSONObject("moteur")
        val engine = parsePackage(engineObj) ?: return null

        val emulators = LinkedHashMap<String, Emulator>()
        val emuArr = root.optJSONArray("emulateurs")
        if (emuArr != null) {
            for (i in 0 until emuArr.length()) {
                val o = emuArr.optJSONObject(i) ?: continue
                val system = o.optString("system").trim().lowercase(Locale.ROOT)
                val core = o.optString("core").trim()
                val pkg = parsePackage(o) ?: continue
                if (system.isBlank() || !core.matches(Regex("[a-z0-9_]+"))) continue
                emulators[system] = Emulator(
                    system = system,
                    label = o.optString("label", system.uppercase(Locale.ROOT)),
                    core = core,
                    pkg = pkg,
                    licence = o.optString("licence")
                )
            }
        }

        val games = ArrayList<Game>()
        val seen = HashSet<String>()
        val gameArr = root.optJSONArray("jeux")
        if (gameArr != null) {
            for (i in 0 until gameArr.length()) {
                val o = gameArr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val system = o.optString("system").trim().lowercase(Locale.ROOT)
                val pkg = parsePackage(o) ?: continue
                val fileName = safeFileName(o.optString("fileName")) ?: continue
                if (id.isBlank() || !seen.add(id) || system !in emulators) continue
                games.add(
                    Game(
                        id = id,
                        title = o.optString("title", fileName),
                        system = system,
                        platformLabel = o.optString("platformLabel", emulators[system]?.label ?: system),
                        genre = o.optString("genre"),
                        author = o.optString("author"),
                        description = o.optString("description"),
                        fileName = fileName,
                        pkg = pkg,
                        licence = o.optString("licence"),
                        sourceUrl = o.optString("sourceUrl")
                    )
                )
            }
        }
        return Catalogue(
            engineVersion = engineObj?.optString("version").orEmpty(),
            engine = engine,
            emulators = emulators,
            games = games
        )
    }

    // ------------------------------------------------------------------ Fichiers locaux

    fun rootDir(context: Context): File = File(context.filesDir, "missa-games").apply { mkdirs() }
    fun engineDir(context: Context): File = File(rootDir(context), "ejs")
    private fun romsDir(context: Context, system: String): File =
        File(File(rootDir(context), "roms"), system).apply { mkdirs() }
    private fun catalogueCache(context: Context): File = File(rootDir(context), "catalogue.json")
    private fun engineMarker(context: Context): File = File(engineDir(context), ".moteur")
    private fun emulatorMarker(context: Context, system: String): File =
        File(engineDir(context), ".emu-$system")

    fun gameFile(context: Context, game: Game): File = File(romsDir(context, game.system), game.fileName)

    /** Chemin du jeu tel que la WebView le demande, relatif à la racine servie. */
    fun gameWebPath(game: Game): String = "roms/${game.system}/${game.fileName}"

    private fun markerMatches(marker: File, sha: String): Boolean =
        marker.isFile && runCatching { marker.readText().trim() }.getOrNull() == sha

    fun isEngineInstalled(context: Context, catalogue: Catalogue): Boolean =
        markerMatches(engineMarker(context), catalogue.engine.sha256) &&
            File(engineDir(context), "lanceur.html").isFile

    fun isEmulatorInstalled(context: Context, emulator: Emulator): Boolean =
        markerMatches(emulatorMarker(context, emulator.system), emulator.pkg.sha256)

    /** Un jeu est prêt s'il est présent, entier, et que son moteur et son émulateur le sont. */
    fun isGameInstalled(context: Context, catalogue: Catalogue, game: Game): Boolean {
        val file = gameFile(context, game)
        val emulator = catalogue.emulatorFor(game) ?: return false
        return file.isFile && file.length() == game.pkg.bytes &&
            isEmulatorInstalled(context, emulator) && isEngineInstalled(context, catalogue)
    }

    fun planInstall(context: Context, catalogue: Catalogue, game: Game): InstallPlan {
        val emulator = catalogue.emulatorFor(game)
        val file = gameFile(context, game)
        val engineNeeded = !isEngineInstalled(context, catalogue)
        return InstallPlan(
            engine = engineNeeded,
            // Un nouveau moteur remplace le dossier entier : l'émulateur doit être redéployé.
            emulator = emulator?.takeIf { engineNeeded || !isEmulatorInstalled(context, it) },
            game = !(file.isFile && file.length() == game.pkg.bytes)
        )
    }

    fun installedBytes(context: Context, catalogue: Catalogue): Long =
        catalogue.games.filter { isGameInstalled(context, catalogue, it) }.sumOf { it.pkg.bytes }

    fun deleteGame(context: Context, game: Game): Boolean = gameFile(context, game).delete()

    // --------------------------------------------------------------------- Catalogue

    @Volatile
    private var memo: Pair<Long, Catalogue>? = null

    /** Dernier catalogue lu, gardé en mémoire tant que le fichier ne change pas. */
    fun cachedCatalogue(context: Context): Catalogue? {
        val cache = catalogueCache(context)
        if (!cache.isFile) return null
        val stamp = cache.lastModified()
        memo?.let { (time, cat) -> if (time == stamp) return cat }
        val parsed = runCatching { parseCatalogue(cache.readText()) }.getOrNull() ?: return null
        memo = stamp to parsed
        return parsed
    }

    /** Relit le catalogue en ligne s'il est trop ancien (ou si [force]) ; garde le cache sinon. */
    suspend fun refreshCatalogue(context: Context, force: Boolean): Catalogue? = withContext(Dispatchers.IO) {
        val cache = catalogueCache(context)
        val fresh = cache.isFile && System.currentTimeMillis() - cache.lastModified() < CATALOGUE_MAX_AGE_MS
        if (!force && fresh) return@withContext cachedCatalogue(context)
        val body = runCatching { httpGetText(CATALOGUE_URL) }.getOrNull()
        val parsed = body?.let { parseCatalogue(it) }
        if (parsed != null && body != null) {
            cache.writeText(body)
            parsed
        } else {
            cachedCatalogue(context)
        }
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
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------- Installation

    /**
     * Installe tout ce qu'il manque pour [game], dans l'ordre : moteur, émulateur, jeu.
     * [onProgress] reçoit l'avancement global (octets cumulés des trois étapes).
     * Lève une exception si un téléchargement échoue ou si une empreinte ne correspond pas ;
     * rien de partiel n'est alors considéré comme installé.
     */
    suspend fun install(
        context: Context,
        catalogue: Catalogue,
        game: Game,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val plan = planInstall(context, catalogue, game)
        val total = (if (plan.engine) catalogue.engine.bytes else 0L) +
            (plan.emulator?.pkg?.bytes ?: 0L) +
            (if (plan.game) game.pkg.bytes else 0L)
        var done = 0L
        val tmp = File(rootDir(context), "tmp").apply { mkdirs() }

        if (plan.engine) {
            val zip = File(tmp, "moteur.zip")
            download(catalogue.engine, zip) { n -> onProgress(Progress(done + n, total, Step.ENGINE)) }
            // Le moteur remplace l'ancien : les cœurs devront être redéployés par-dessus.
            val dir = engineDir(context)
            dir.deleteRecursively()
            dir.mkdirs()
            unzipSafely(zip, dir)
            zip.delete()
            engineMarker(context).writeText(catalogue.engine.sha256)
            done += catalogue.engine.bytes
        }
        val emulator = catalogue.emulatorFor(game) ?: throw IOException("No emulator for ${game.system}")
        if (plan.emulator != null) {
            val zip = File(tmp, "emu-${emulator.system}.zip")
            download(emulator.pkg, zip) { n -> onProgress(Progress(done + n, total, Step.EMULATOR)) }
            unzipSafely(zip, engineDir(context))
            zip.delete()
            emulatorMarker(context, emulator.system).writeText(emulator.pkg.sha256)
            done += emulator.pkg.bytes
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
