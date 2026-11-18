package com.lagradost.cloudstream3.ui.player

import android.util.Log
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.universe.MediaUniverseViewModel
import com.lagradost.cloudstream3.ui.universe.MissaExtensionGroups
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Normalizer
import java.util.Locale

/**
 * Source de secours : quand une extension ne trouve aucun lien lisible pour un titre (site
 * bloqué, extension pas à jour, serveur en panne), on cherche LE MÊME titre dans les autres
 * extensions installées. Même nom (sans accents ni ponctuation), même année si connue, même
 * famille (film / série / direct). Les extensions en meilleure santé passent en premier.
 */
object MissaSourceFallback {

    private const val TAG = "MissaFallback"
    private const val SEARCH_TIMEOUT_MS = 9_000L
    private const val MAX_PROVIDERS = 12

    enum class Family { MOVIE, SERIES, LIVE, OTHER }

    fun family(type: TvType?): Family = when (type) {
        TvType.Movie, TvType.AnimeMovie, TvType.Documentary, TvType.Torrent -> Family.MOVIE
        TvType.TvSeries, TvType.Anime, TvType.OVA, TvType.Cartoon, TvType.AsianDrama -> Family.SERIES
        TvType.Live -> Family.LIVE
        else -> Family.OTHER
    }

    fun normalize(title: String): String =
        Normalizer.normalize(title, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("\\(\\s*\\d{4}\\s*\\)"), " ")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()

    /** Vrai si [candidate] est vraisemblablement le même titre que [title] / [year]. */
    fun sameTitle(title: String, year: Int?, candidate: SearchResponse): Boolean {
        if (normalize(title) != normalize(candidate.name)) return false
        val otherYear = MediaUniverseViewModel.extractYearHint(candidate)
        return year == null || otherYear == null || kotlin.math.abs(year - otherYear) <= 1
    }

    private fun candidates(excludeApi: String, wanted: Family): List<MainAPI> = apis.withLock {
        apis.filter { api ->
            api.name != excludeApi && api.name != "None" && api.name != "Random" &&
                !MissaExtensionGroups.isDisabled(api) &&
                api.supportedTypes.none { it == TvType.NSFW } &&
                (wanted == Family.OTHER || api.supportedTypes.any { family(it) == wanted })
        }
    }.sortedByDescending { MissaProviderHealth.score(it.name) }.take(MAX_PROVIDERS)

    /** Le même titre dans les autres extensions, meilleures en premier. */
    suspend fun findAlternatives(page: LoadResponse): List<SearchResponse> = withContext(Dispatchers.IO) {
        val fam = family(page.type)
        val providers = candidates(page.apiName, fam)
        if (providers.isEmpty()) return@withContext emptyList()
        val semaphore = Semaphore(4)
        val found = coroutineScope {
            providers.map { api ->
                async {
                    semaphore.withPermit {
                        try {
                            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                                when (val r = APIRepository(api).search(page.name, 1)) {
                                    is Resource.Success -> r.value.items.firstOrNull {
                                        sameTitle(page.name, page.year, it) &&
                                            (it.type == null || family(it.type) == fam || fam == Family.OTHER)
                                    }
                                    else -> null
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Log.w(TAG, "${api.name}: ${e.message}")
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        found.sortedByDescending { MissaProviderHealth.score(it.apiName) }
    }
}
