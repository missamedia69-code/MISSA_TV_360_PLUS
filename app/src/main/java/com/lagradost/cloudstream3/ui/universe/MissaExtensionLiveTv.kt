package com.lagradost.cloudstream3.ui.universe

import android.util.Log
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Chaînes de télé apportées par les extensions installées, cumulées avec la liste
 * `tv/chaines.m3u` dans l'univers TV en direct.
 *
 * Extensions interrogées (page d'accueil, toutes les sections) :
 *  - réglées sur « Auto » et qui déclarent le type « Live » (seuls leurs directs sont gardés) ;
 *  - réglées explicitement sur le groupe « TV en direct » (tout leur contenu est gardé).
 * Chaque élément devient une chaîne, rangée dans la catégorie de sa section. La lecture passe
 * par l'extension elle-même (comme dans les autres univers). Les extensions réglées sur
 * « Aucun » ou sur d'autres groupes seulement ne sont pas interrogées. Résultat gardé
 * 10 minutes en mémoire.
 */
object MissaExtensionLiveTv : MissaExtensionChannels(universeId = "live_tv", autoLive = true)

/**
 * Stations de radio apportées par les extensions réglées explicitement sur le groupe
 * « Radio » (Paramètres → Extensions → groupe de médias). Affichées dans l'univers Radio,
 * à côté de l'annuaire ; la lecture passe par l'extension.
 */
object MissaExtensionRadio : MissaExtensionChannels(universeId = "radio", autoLive = false)

/**
 * Récolte des « chaînes » (directs, stations) des extensions pour un univers à tuiles.
 * [autoLive] : les extensions en Auto qui déclarent le type « Live » sont aussi interrogées.
 */
open class MissaExtensionChannels(private val universeId: String, private val autoLive: Boolean) {

    private val TAG = "MissaExtChannels/$universeId"
    private val TIMEOUT_MS = 14_000L
    private val TTL_MS = 10 * 60_000L
    private val MAX_PER_PROVIDER = 600

    private val mutex = Mutex()

    @Volatile
    private var cache: List<MissaLiveTv.Channel> = emptyList()

    @Volatile
    private var cacheAt = 0L

    @Volatile
    private var cacheSignature = ""

    /** Dernières chaînes d'extensions connues, sans attendre. */
    fun cached(): List<MissaLiveTv.Channel> = cache

    private fun liveProviders(): List<MainAPI> = apis.withLock {
        apis.filter { api ->
            api.name != "None" && api.name != "Random" &&
                api.hasMainPage &&
                isWanted(api)
        }
    }

    /** Réglage explicite sur ce groupe, ou (TV) extension « Live » laissée en Auto. */
    private fun isWanted(api: MainAPI): Boolean {
        val groups = MissaExtensionGroups.resolveForApi(api)
        return when {
            groups == null -> autoLive && TvType.Live in api.supportedTypes
            else -> universeId in groups
        }
    }

    private fun isExplicit(api: MainAPI): Boolean =
        MissaExtensionGroups.resolveForApi(api)?.contains(universeId) == true

    /**
     * Chaînes de toutes les extensions « Live ». Relit les extensions si le cache a plus de
     * 10 min, si la liste des extensions a changé, ou si [force].
     */
    suspend fun channels(force: Boolean = false): List<MissaLiveTv.Channel> = withContext(Dispatchers.IO) {
        val providers = liveProviders()
        val signature = providers.joinToString("|") { it.name } + MissaExtensionGroups.signature()
        val now = System.currentTimeMillis()
        if (!force && signature == cacheSignature && now - cacheAt < TTL_MS) return@withContext cache
        if (providers.isEmpty()) {
            cache = emptyList(); cacheSignature = signature; cacheAt = now
            return@withContext cache
        }
        mutex.withLock {
            if (!force && signature == cacheSignature && System.currentTimeMillis() - cacheAt < TTL_MS) {
                return@withLock cache
            }
            val semaphore = Semaphore(MissaDeviceProfile.parallelism(normal = 4, light = 2))
            val all = coroutineScope {
                providers.map { api -> async { semaphore.withPermit { harvest(api) } } }.awaitAll().flatten()
            }
            val seen = HashSet<String>()
            val result = all.filter { seen.add(it.url) }
            cache = result
            cacheSignature = signature
            cacheAt = System.currentTimeMillis()
            result
        }
    }

    private suspend fun harvest(api: MainAPI): List<MissaLiveTv.Channel> = try {
        withTimeoutOrNull(TIMEOUT_MS) {
            when (val res = APIRepository(api).getMainPage(1, null)) {
                is Resource.Success -> res.value.filterNotNull()
                    .flatMap { it.items }
                    .flatMap { section -> section.list.map { section.name to it } }
                    .take(MAX_PER_PROVIDER)
                    .mapNotNull { (section, item) -> toChannel(api, section, item) }
                else -> emptyList()
            }
        }.orEmpty()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(TAG, "Extension '${api.name}' : ${e::class.simpleName} ${e.message}")
        emptyList()
    }

    private fun toChannel(api: MainAPI, section: String, item: SearchResponse): MissaLiveTv.Channel? {
        if (item.url.isBlank() || item.name.isBlank()) return null
        // Une extension « Live » en Auto peut aussi proposer des films : on ne garde que le
        // direct. Réglée explicitement sur ce groupe, tout son contenu est gardé.
        val type = item.type
        if (!isExplicit(api) && type != null && type != TvType.Live &&
            api.supportedTypes.any { it != TvType.Live }
        ) return null
        val group = section.trim().ifBlank { api.name }
        return MissaLiveTv.Channel(
            id = "ext|${api.name}|${item.url}",
            name = item.name.trim(),
            url = item.url,
            logo = item.posterUrl?.takeIf { it.startsWith("http", ignoreCase = true) },
            groups = listOf(group),
            country = null,
            source = item,
            provider = api.name
        )
    }
}
