package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Pages d'accueil des extensions gardées sur l'appareil, une par extension.
 *
 * La page d'accueil d'une extension, c'est 5 à 15 pages web (une par rubrique). Elles étaient
 * relues à chaque ouverture de l'application, et de nouveau à chaque fois que la liste des
 * extensions changeait pendant le démarrage (les extensions se chargent par lots : la récolte
 * était annulée puis relancée plusieurs fois, en retéléchargeant tout).
 *
 * Maintenant, chaque extension qui a répondu est gardée ici avec l'heure de sa réponse :
 *  - en mémoire, pour les relances pendant la même session ;
 *  - sur le disque, pour les ouvertures suivantes.
 * Une extension n'est réinterrogée que si sa copie est plus ancienne que
 * [MissaDataUsage.CATALOGUE], ou quand l'utilisateur tire pour actualiser.
 */
object MissaMainPageCache {
    private const val TAG = "MissaMainPageCache"
    private const val DIR = "missa_mainpages_v1"
    private const val MAX_ITEMS_PER_PROVIDER = 400

    /** Un titre d'une rubrique, tel qu'il est gardé (sans les réglages qui peuvent changer). */
    data class Item(
        val response: SearchResponse,
        val sectionName: String,
        val isHorizontalSection: Boolean
    )

    internal class Entry(val savedAtMs: Long, val items: List<Item>)

    private val memory = ConcurrentHashMap<String, Entry>()

    private fun dir(context: Context) = File(context.cacheDir, DIR)

    private fun fileFor(context: Context, apiName: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(apiName.toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { "%02x".format(it) }.take(24)
        return File(dir(context), "$name.json")
    }

    /** Titres de [apiName] s'ils ont moins de [maxAgeMs], sinon null (à retélécharger). */
    fun get(context: Context?, apiName: String, maxAgeMs: Long): List<Item>? {
        if (maxAgeMs <= 0L) return null
        val now = System.currentTimeMillis()
        memory[apiName]?.let { cached ->
            return if (cached.savedAtMs <= now && now - cached.savedAtMs < maxAgeMs) cached.items else null
        }
        val ctx = context ?: return null
        val loaded = runCatching { readFile(fileFor(ctx, apiName), apiName) }
            .onFailure { Log.w(TAG, "Copie illisible pour $apiName : ${it.message}") }
            .getOrNull() ?: return null
        memory[apiName] = loaded
        return if (loaded.savedAtMs <= now && now - loaded.savedAtMs < maxAgeMs) loaded.items else null
    }

    /** Garde la réponse de [apiName] (rien n'est gardé si elle est vide : on réessaiera). */
    fun put(context: Context?, apiName: String, items: List<Item>) {
        if (items.isEmpty()) return
        val kept = items.take(MAX_ITEMS_PER_PROVIDER)
        val entry = Entry(System.currentTimeMillis(), kept)
        memory[apiName] = entry
        val ctx = context ?: return
        runCatching {
            val file = fileFor(ctx, apiName)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".part")
            tmp.writeText(encode(apiName, entry))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "Copie non enregistrée pour $apiName : ${it.message}") }
    }

    /** Oublie toutes les copies (tirer pour actualiser recharge déjà sans elles). */
    fun clear(context: Context?) {
        memory.clear()
        context?.let { ctx -> runCatching { dir(ctx).deleteRecursively() } }
    }

    internal fun encode(apiName: String, entry: Entry): String {
        val arr = JSONArray()
        for (item in entry.items) {
            val r = item.response
            val obj = JSONObject()
            obj.put("n", r.name)
            obj.put("u", r.url)
            r.type?.let { obj.put("t", it.name) }
            r.posterUrl?.takeIf { it.isNotBlank() }?.let { obj.put("p", it) }
            r.posterHeaders?.takeIf { it.isNotEmpty() }?.let { obj.put("h", JSONObject(it)) }
            yearOf(r)?.let { obj.put("y", it) }
            episodesOf(r)?.let { obj.put("e", it) }
            obj.put("s", item.sectionName)
            if (item.isHorizontalSection) obj.put("w", true)
            arr.put(obj)
        }
        return JSONObject().apply {
            put("api", apiName)
            put("savedAtMs", entry.savedAtMs)
            put("items", arr)
        }.toString()
    }

    private fun readFile(file: File, apiName: String): Entry? =
        if (file.isFile) decode(file.readText(), apiName) else null

    /** Relit une copie ; null si elle appartient à une autre extension. */
    internal fun decode(text: String, apiName: String): Entry? {
        val root = JSONObject(text)
        if (root.optString("api") != apiName) return null
        val arr = root.optJSONArray("items") ?: return null
        val items = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("n")
            val url = obj.optString("u")
            if (name.isBlank() || url.isBlank()) continue
            val headers = obj.optJSONObject("h")?.let { h ->
                h.keys().asSequence().associateWith { k -> h.optString(k) }
            }
            val card = CachedCatalogSearchResponse(
                name = name,
                url = url,
                apiName = apiName,
                type = obj.optString("t").takeIf { it.isNotBlank() }?.let { raw ->
                    runCatching { TvType.valueOf(raw) }.getOrNull()
                },
                posterUrl = obj.optString("p").ifBlank { null },
                posterHeaders = headers,
                year = obj.optInt("y", 0).takeIf { it > 0 },
                episodes = obj.optInt("e", 0).takeIf { it > 0 }
            )
            items.add(Item(card, obj.optString("s"), obj.optBoolean("w", false)))
        }
        return Entry(root.optLong("savedAtMs", 0L), items)
    }

    private fun yearOf(r: SearchResponse): Int? = when (r) {
        is MovieSearchResponse -> r.year
        is TvSeriesSearchResponse -> r.year
        is AnimeSearchResponse -> r.year
        is CachedCatalogSearchResponse -> r.year
        else -> null
    }

    private fun episodesOf(r: SearchResponse): Int? = when (r) {
        is TvSeriesSearchResponse -> r.episodes
        is AnimeSearchResponse -> r.episodes.values.maxOrNull()
        is CachedCatalogSearchResponse -> r.episodes
        else -> null
    }
}

/**
 * Fiches détaillées déjà lues, en version légère (genres, classement, durée, film ou série).
 *
 * Elles servent à ranger les titres dans Enfants, Documentaires et Courts-métrages. Une
 * trentaine étaient retéléchargées à chaque ouverture ; elles sont maintenant gardées
 * [MissaDataUsage.DETAILS_MEMO_MS] et seules les nouvelles sont lues.
 */
object MissaDetailsMemo {
    private const val TAG = "MissaDetailsMemo"
    private const val FILE = "missa_details_memo_v1.json"
    private const val MAX_ENTRIES = 1500

    data class Lite(
        val type: TvType?,
        val tags: List<String>,
        val contentRating: String?,
        val duration: Int?,
        val isSeries: Boolean?,
        val savedAtMs: Long = System.currentTimeMillis()
    )

    @Volatile
    private var loaded: ConcurrentHashMap<String, Lite>? = null
    private val lock = Any()

    private fun file(context: Context) = File(context.cacheDir, FILE)

    private fun all(context: Context?): ConcurrentHashMap<String, Lite> {
        loaded?.let { return it }
        synchronized(lock) {
            loaded?.let { return it }
            val map = ConcurrentHashMap<String, Lite>()
            val ctx = context
            if (ctx != null) runCatching {
                val f = file(ctx)
                if (f.isFile) {
                    val root = JSONObject(f.readText())
                    val now = System.currentTimeMillis()
                    for (key in root.keys()) {
                        val o = root.optJSONObject(key) ?: continue
                        val at = o.optLong("at", 0L)
                        if (at > now || now - at > MissaDataUsage.DETAILS_MEMO_MS) continue
                        val tags = ArrayList<String>()
                        o.optJSONArray("g")?.let { g -> for (i in 0 until g.length()) g.optString(i).takeIf { it.isNotBlank() }?.let(tags::add) }
                        map[key] = Lite(
                            type = o.optString("t").takeIf { it.isNotBlank() }?.let { runCatching { TvType.valueOf(it) }.getOrNull() },
                            tags = tags,
                            contentRating = o.optString("r").ifBlank { null },
                            duration = o.optInt("d", 0).takeIf { it > 0 },
                            isSeries = if (o.has("s")) o.optBoolean("s") else null,
                            savedAtMs = at
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "Mémo illisible : ${it.message}") }
            loaded = map
            return map
        }
    }

    fun get(context: Context?, identity: String): Lite? = all(context)[identity]

    fun putAll(context: Context?, values: Map<String, Lite>) {
        if (values.isEmpty()) return
        val map = all(context)
        map.putAll(values)
        if (map.size > MAX_ENTRIES) {
            map.entries.sortedBy { it.value.savedAtMs }
                .take(map.size - MAX_ENTRIES)
                .forEach { map.remove(it.key) }
        }
        val ctx = context ?: return
        runCatching {
            val root = JSONObject()
            for ((key, lite) in map) {
                root.put(key, JSONObject().apply {
                    lite.type?.let { put("t", it.name) }
                    if (lite.tags.isNotEmpty()) put("g", JSONArray(lite.tags.take(12)))
                    lite.contentRating?.let { put("r", it) }
                    lite.duration?.let { put("d", it) }
                    lite.isSeries?.let { put("s", it) }
                    put("at", lite.savedAtMs)
                })
            }
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE.part")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "Mémo non enregistré : ${it.message}") }
    }
}
