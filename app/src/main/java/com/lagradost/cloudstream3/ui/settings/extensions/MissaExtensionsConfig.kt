package com.lagradost.cloudstream3.ui.settings.extensions

import android.app.Activity
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.BackupUtils
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import com.lagradost.cloudstream3.utils.UIHelper.checkWrite
import com.lagradost.cloudstream3.utils.UIHelper.requestRW
import com.lagradost.cloudstream3.utils.txt
import com.lagradost.cloudstream3.CommonActivity.showToast
import java.text.SimpleDateFormat
import java.util.Date
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.ui.universe.MissaContentSource
import com.lagradost.cloudstream3.ui.universe.MissaExtensionGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Configuration des extensions tenue dans le dépôt : `extensions/config.json`.
 *
 * Le responsable de MISSA TV installe et règle ses extensions sur son appareil, puis
 * « Exporter la configuration » (écran Extensions, protégé par code) écrit ce fichier :
 * pour chaque extension, son dépôt, son nom interne et les univers où elle diffuse.
 *
 * À chaque démarrage, l'application lit le fichier sur GitHub (rien n'est téléchargé s'il n'a
 * pas changé) et s'y conforme : dépôts ajoutés, extensions installées, univers appliqués,
 * extensions et dépôts absents du fichier retirés. Le fichier s'impose : l'utilisateur ne peut
 * pas modifier les extensions (écrans verrouillés par [MissaAdminLock]).
 *
 * L'appareil du responsable peut être exclu de la synchronisation (menu de l'écran
 * Extensions), pour essayer de nouvelles extensions sans qu'elles soient retirées.
 */
object MissaExtensionsConfig {

    private const val TAG = "MissaExtConfig"
    private const val CONFIG_PATH = "extensions/config.json"
    private const val LOCAL_DIR = "missa-extensions"
    private const val LOCAL_FILE = "config.json"
    private const val ETAG_KEY = "missa_ext_config_etag_v1"
    private const val SYNC_DISABLED_KEY = "missa_ext_config_sync_disabled_v1"
    private const val MAX_BYTES = 1024 * 1024
    const val EXPORT_FILE_NAME = "missa_extensions_config"

    /** Adresses essayées dans l'ordre : site Cloudflare, puis GitHub. */
    val configUrls: List<String>
        get() = MissaContentSource.urls(CONFIG_PATH)

    data class Entry(
        val repositoryUrl: String,
        val repositoryName: String?,
        val internalName: String,
        val name: String?,
        /** null = Auto, vide = Aucun, sinon les univers (films, series, shorts, sports, podcasts). */
        val universes: Set<String>?
    )

    // ------------------------------------------------------------------ Format du fichier

    fun parse(text: String): List<Entry>? = runCatching {
        val root = JSONObject(text)
        val list = root.optJSONArray("extensions") ?: return@runCatching null
        (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            val repo = o.optString("depot").trim()
            val internal = o.optString("extension").trim()
            if (!repo.startsWith("https://") || internal.isEmpty()) return@mapNotNull null
            val universes = o.optJSONArray("univers")?.let { arr ->
                MissaExtensionGroups.normalize((0 until arr.length()).map { arr.optString(it) })
            }
            Entry(
                repositoryUrl = repo,
                repositoryName = o.optString("nomDepot").takeIf { it.isNotBlank() },
                internalName = internal,
                name = o.optString("nom").takeIf { it.isNotBlank() },
                universes = universes
            )
        }.distinctBy { it.repositoryUrl to it.internalName }
    }.getOrNull()

    fun write(entries: List<Entry>): String {
        val list = JSONArray()
        entries.forEach { e ->
            list.put(JSONObject().apply {
                put("depot", e.repositoryUrl)
                e.repositoryName?.let { put("nomDepot", it) }
                put("extension", e.internalName)
                e.name?.let { put("nom", it) }
                val groups = when {
                    e.universes == null -> emptyList()
                    e.universes.isEmpty() -> listOf(MissaExtensionGroups.GROUP_NONE)
                    else -> e.universes.sorted()
                }
                put("univers", JSONArray(groups))
            })
        }
        return JSONObject().apply {
            put("version", 1)
            put("extensions", list)
        }.toString(2) + "\n"
    }

    // ------------------------------------------------------------------ Export

    /** Extensions installées sur cet appareil, avec leur dépôt et leurs univers. */
    suspend fun collect(context: Context): List<Entry> = withContext(Dispatchers.IO) {
        RepositoryManager.getRepositories().flatMap { repo ->
            val plugins = runCatching { RepositoryManager.getRepoPlugins(repo) }.getOrNull().orEmpty()
            plugins.mapNotNull { wrapper ->
                val plugin = wrapper.plugin
                val file = PluginManager.getPluginPath(context, plugin.internalName, repo.url)
                if (!file.exists()) return@mapNotNull null
                Entry(
                    repositoryUrl = repo.url,
                    repositoryName = repo.name,
                    internalName = plugin.internalName,
                    name = plugin.name,
                    universes = MissaExtensionGroups.getForExtension(file.absolutePath)
                )
            }
        }.distinctBy { it.repositoryUrl to it.internalName }
    }

    /**
     * Écrit la configuration de cet appareil dans le dossier de sauvegarde (Téléchargements
     * par défaut) : `missa_extensions_config_<date>.json`, à déposer dans `extensions/config.json`.
     */
    fun export(activity: Activity) = ioSafe {
        try {
            if (!activity.checkWrite()) {
                showToast(R.string.backup_failed, Toast.LENGTH_LONG)
                activity.requestRW()
                return@ioSafe
            }
            val entries = collect(activity)
            if (entries.isEmpty()) {
                showToast(R.string.missa_ext_config_export_empty, Toast.LENGTH_LONG)
                return@ioSafe
            }
            val date = SimpleDateFormat("yyyy_MM_dd_HH_mm", Locale.ROOT).format(Date())
            BackupUtils.writeToBackupFolder(activity, "${EXPORT_FILE_NAME}_$date", "json", write(entries))
            showToast(txt(R.string.missa_ext_config_export_done, entries.size), Toast.LENGTH_LONG)
        } catch (e: Exception) {
            logError(e)
            showToast(txt(R.string.backup_failed_error_format, e.toString()), Toast.LENGTH_LONG)
        }
    }

    // ------------------------------------------------------------------ Synchronisation

    fun isSyncDisabled(): Boolean = getKey<Boolean>(SYNC_DISABLED_KEY) == true

    fun setSyncDisabled(disabled: Boolean) = setKey(SYNC_DISABLED_KEY, disabled)

    private val mutex = Mutex()

    private fun localFile(context: Context) = File(File(context.filesDir, LOCAL_DIR), LOCAL_FILE)

    /**
     * Télécharge le fichier s'il a changé, puis applique la configuration. Appelé au démarrage,
     * avant le chargement des extensions. Vrai si au moins une extension a été installée ou retirée.
     */
    suspend fun sync(activity: Activity): Boolean = withContext(Dispatchers.IO) {
        if (isSyncDisabled()) return@withContext false
        mutex.withLock {
            download(activity)
            val text = localFile(activity).takeIf { it.isFile }?.readText() ?: return@withLock false
            val entries = parse(text) ?: return@withLock false
            // Une liste vide (fichier abîmé, mal rempli) ne doit pas tout désinstaller.
            if (entries.isEmpty()) return@withLock false
            apply(activity, entries)
        }
    }

    private fun download(context: Context) {
        for (url in configUrls) {
            if (download(context, url)) return
        }
    }

    /** Vrai si ce serveur a répondu (fichier à jour ou nouveau fichier enregistré). */
    private fun download(context: Context, url: String): Boolean {
        val file = localFile(context)
        val etagKey = ETAG_KEY + ":" + (runCatching { URL(url).host }.getOrNull() ?: "")
        val etag = if (file.exists()) getKey<String>(etagKey) else null
        val conn = runCatching { URL(url).openConnection() as HttpURLConnection }.getOrNull() ?: return false
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "MISSA-TV-360")
            if (etag != null) conn.setRequestProperty("If-None-Match", etag)
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return true
            if (code != HttpURLConnection.HTTP_OK) return false
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > MAX_BYTES) return false
            val text = bytes.toString(Charsets.UTF_8)
            if (parse(text) == null) return false
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$LOCAL_FILE.part")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            conn.getHeaderField("ETag")?.let { setKey(etagKey, it) }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Configuration non téléchargée depuis $url : $e")
            return false
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun apply(activity: Activity, entries: List<Entry>): Boolean {
        var changed = false
        val wantedRepos = entries.map { it.repositoryUrl }.distinct()

        // 1. Dépôts : ajout de ceux du fichier, retrait des autres (et de leurs extensions).
        val current = RepositoryManager.getRepositories()
        current.filter { it.url !in wantedRepos }.forEach { repo ->
            RepositoryManager.removeRepository(activity, repo)
            changed = true
        }
        wantedRepos.forEach { url ->
            if (current.none { it.url == url }) {
                val name = entries.firstOrNull { it.repositoryUrl == url }?.repositoryName
                    ?: runCatching { RepositoryManager.parseRepository(url)?.name }.getOrNull()
                    ?: url
                RepositoryManager.addRepository(RepositoryData(name, url))
            }
        }

        // 2. Extensions : installation de celles qui manquent, retrait des autres.
        wantedRepos.forEach { repoUrl ->
            val wanted = entries.filter { it.repositoryUrl == repoUrl }
            val wantedNames = wanted.map { it.internalName.lowercase(Locale.ROOT) }.toSet()
            val missing = wanted.filter { !PluginManager.getPluginPath(activity, it.internalName, repoUrl).exists() }
            val repoData = RepositoryManager.getRepositories().firstOrNull { it.url == repoUrl } ?: return@forEach
            val online = if (missing.isEmpty()) emptyList()
            else runCatching { RepositoryManager.getRepoPlugins(repoData) }.getOrNull().orEmpty()

            missing.forEach { entry ->
                val plugin = online.firstOrNull { it.plugin.internalName.equals(entry.internalName, true) }?.plugin
                if (plugin == null) {
                    Log.w(TAG, "Extension introuvable dans ${entry.repositoryUrl} : ${entry.internalName}")
                    return@forEach
                }
                val ok = PluginManager.downloadPlugin(
                    activity, plugin.url, plugin.fileHash, plugin.internalName, repoUrl, true
                )
                if (ok) changed = true
            }

            // Fichiers d'extension de ce dépôt qui ne figurent plus dans la configuration.
            val folder = PluginManager.getPluginPath(activity, "x", repoUrl).parentFile
            folder?.listFiles { f -> f.extension == "cs3" }?.forEach { file ->
                val keep = wanted.any {
                    PluginManager.getPluginPath(activity, it.internalName, repoUrl).absolutePath == file.absolutePath
                }
                if (!keep && wantedNames.isNotEmpty()) {
                    if (PluginManager.deletePlugin(file)) changed = true
                }
            }
        }

        // 3. Univers de chaque extension (écrits seulement s'ils diffèrent).
        entries.forEach { entry ->
            val path = PluginManager.getPluginPath(activity, entry.internalName, entry.repositoryUrl).absolutePath
            if (MissaExtensionGroups.getForExtension(path) != entry.universes) {
                MissaExtensionGroups.setForExtension(path, entry.universes)
            }
        }
        return changed
    }
}
