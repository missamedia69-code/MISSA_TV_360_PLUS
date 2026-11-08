package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Le catalogue et la lecture de l'univers Podcasts (PODCASTS-01, 02 et 03).
 *
 * Le catalogue suit exactement le dessin de [DirectRadioPlayerManager] : une moisson lancée en
 * arrière-plan, un cache sur le disque ([MissaCatalogueCache]), un instantané en mémoire, des
 * favoris et une recherche en ligne.
 *
 * La lecture, elle, ne réinvente rien : un épisode est un fichier audio **direct**, il part donc
 * dans le lecteur audio de Musiques ([DirectMusicPlayerManager]) — même ExoPlayer, même file
 * d'attente, même télécommande. Il s'y affiche comme un podcast ([MusicItemKind.PODCAST]) et non
 * comme un clip YouTube : aucune recherche de clip vidéo n'est lancée, et c'est le nom de
 * l'émission qui tient la place de l'artiste.
 */
object DirectPodcastPlayerManager {

    private const val REMOTE_SHOWS_CACHE_KEY = "missa_podcast_shows_v1"
    private const val REMOTE_SHOWS_UPDATED_AT_KEY = "missa_podcast_shows_updated_at_v1"
    private const val FAVORITE_SHOWS_KEY = "missa_podcast_favorite_ids_v1"
    private const val REMOTE_CACHE_TTL_MS = 12 * 60 * 60_000L
    private const val MAX_DISCOVERED_SHOWS = 200

    @Volatile
    private var showsSnapshot: List<PodcastShowPreset>? = null

    @Volatile
    private var remoteRefreshInFlight: Boolean = false

    /** Émissions trouvées par une recherche : elles vivent le temps de la session. */
    private val discoveredSearchShows = ConcurrentHashMap<String, PodcastShowPreset>()

    private val _showsUpdated = MutableLiveData<Long>(0L)

    /** Changé chaque fois que le catalogue bouge, pour que l'écran se redessine. */
    val showsUpdated: LiveData<Long> = _showsUpdated

    // --- Catalogue ---------------------------------------------------------------------

    fun snapshotShows(): List<PodcastShowPreset> = showsSnapshot.orEmpty()

    private fun invalidateShowsSnapshot() {
        showsSnapshot = null
    }

    fun favoriteIds(): Set<String> = runCatching {
        getKey<Array<String>>(FAVORITE_SHOWS_KEY)?.toSet().orEmpty()
    }.getOrDefault(emptySet())

    fun isFavorite(showId: String): Boolean = favoriteIds().contains(showId)

    fun toggleFavorite(showId: String): Boolean {
        val mutable = favoriteIds().toMutableSet()
        val nowFavorite = if (mutable.contains(showId)) {
            mutable.remove(showId)
            false
        } else {
            mutable.add(showId)
            true
        }
        runCatching { setKey(FAVORITE_SHOWS_KEY, mutable.toTypedArray()) }
        invalidateShowsSnapshot()
        return nowFavorite
    }

    /** Le catalogue tel qu'il est sur le disque, sans toucher au réseau. */
    suspend fun cachedShows(): List<PodcastShowPreset> = withContext(Dispatchers.IO) {
        runCatching {
            MissaCatalogueCache.read<Array<PodcastShowPreset>>(REMOTE_SHOWS_CACHE_KEY)?.toList()
        }.getOrNull().orEmpty()
    }

    /**
     * Toutes les émissions connues. L'ordre est celui du catalogue : **l'Afrique d'abord**
     * (PODCASTS-02). Les favoris n'écrasent pas cet ordre, ils ont leur rangée à part.
     */
    suspend fun allPresetShows(): List<PodcastShowPreset> {
        val cached = cachedShows()
        val discovered = discoveredSearchShows.values.toList()
        return withContext(Dispatchers.Default) {
            // La fusion trie et dédoublonne : hors du thread appelant.
            val merged = MissaPodcastProviders.mergeShowCatalogues(cached + discovered)
            showsSnapshot = merged
            merged
        }
    }

    /** Retrouve une émission par son identifiant, où qu'elle soit rangée. */
    suspend fun findShow(showId: String): PodcastShowPreset? {
        if (showId.isBlank()) return null
        snapshotShows().firstOrNull { it.id == showId }?.let { return it }
        discoveredSearchShows.values.firstOrNull { it.id == showId }?.let { return it }
        return cachedShows().firstOrNull { it.id == showId }
    }

    /**
     * Renouvelle le catalogue en arrière-plan s'il est absent, périmé, ou si [force] est
     * demandé. Ne bloque jamais l'écran : on rend d'abord ce qu'on a déjà.
     */
    suspend fun refreshRemotePresetsIfNeeded(force: Boolean = false) {
        if (remoteRefreshInFlight) return
        val now = System.currentTimeMillis()
        val lastUpdated = runCatching {
            getKey<Long>(REMOTE_SHOWS_UPDATED_AT_KEY) ?: 0L
        }.getOrDefault(0L)
        val hasCached = cachedShows().isNotEmpty()
        if (!force && hasCached && now - lastUpdated in 1 until REMOTE_CACHE_TTL_MS) return

        remoteRefreshInFlight = true
        try {
            val harvested = MissaPodcastProviders.harvestAllFreeProviders()
            if (harvested.isNotEmpty()) {
                runCatching {
                    MissaCatalogueCache.write(REMOTE_SHOWS_CACHE_KEY, harvested.toTypedArray())
                    setKey(REMOTE_SHOWS_UPDATED_AT_KEY, System.currentTimeMillis())
                }
                invalidateShowsSnapshot()
                try {
                    allPresetShows()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Le snapshot sera rempli au prochain rendu.
                }
                _showsUpdated.postValue(System.currentTimeMillis())
            }
        } finally {
            remoteRefreshInFlight = false
        }
    }

    /** Recherche en ligne : les émissions trouvées rejoignent le catalogue de la session. */
    suspend fun searchShowsOnline(query: String): List<PodcastShowPreset> {
        val found = MissaPodcastProviders.searchOnlineProviders(query)
        if (found.isNotEmpty()) {
            for (show in found) {
                if (discoveredSearchShows.size < MAX_DISCOVERED_SHOWS ||
                    discoveredSearchShows.containsKey(show.id)
                ) {
                    discoveredSearchShows[show.id] = show
                }
            }
            invalidateShowsSnapshot()
            _showsUpdated.postValue(System.currentTimeMillis())
        }
        return found
    }

    // --- Lecture -----------------------------------------------------------------------

    /** Une url `missapodcast://show/…` désigne une émission, pas un fichier audio. */
    fun isPodcastShowScheme(url: String?): Boolean {
        val value = url?.trim().orEmpty()
        return value.startsWith(PODCAST_SHOW_URL_PREFIX, ignoreCase = true)
    }

    fun showIdFromScheme(url: String?): String? {
        if (!isPodcastShowScheme(url)) return null
        return url!!.trim().removePrefix(PODCAST_SHOW_URL_PREFIX).trim()
            .takeIf { it.isNotBlank() }
    }

    /**
     * Une émission vue comme une piste : c'est ce qui permet d'entrer dedans avec le même
     * mécanisme qu'un album. Le lecteur voit une collection, va chercher les épisodes dans le
     * flux RSS, et les liste comme il liste les titres d'un album.
     */
    fun toShowTrack(show: PodcastShowPreset): MusicTrackPreset = MusicTrackPreset(
        id = show.id,
        title = show.title,
        artist = show.author.ifBlank { show.genre },
        album = show.title,
        genre = show.genre,
        streamUrl = PODCAST_SHOW_URL_PREFIX + show.id,
        artworkUrl = show.artworkUrl,
        durationSeconds = 0,
        providerName = show.providerName,
        isPipedLazy = false,
        itemKind = MusicItemKind.PODCAST,
        trackCount = show.episodeCount.coerceAtLeast(1)
    )

    /** Les épisodes d'une émission, prêts à être mis en file d'attente. */
    suspend fun expandShowEpisodes(showId: String): List<MusicTrackPreset> {
        val show = findShow(showId) ?: return emptyList()
        return MissaPodcastProviders.fetchEpisodes(show).map { it.toMusicTrackPreset() }
    }

    /** Ouvre une émission : ses épisodes arrivent dans le lecteur, en file d'attente. */
    fun playShow(context: Context, show: PodcastShowPreset) {
        DirectMusicPlayerManager.playTrack(context, toShowTrack(show), queue = null)
    }

    /**
     * Joue un épisode. [siblings] sont les autres épisodes de la même émission : ils prennent la
     * file d'attente, pour que la lecture continue tout seul à la fin de l'épisode.
     */
    fun playEpisode(
        context: Context,
        episode: PodcastEpisode,
        siblings: List<PodcastEpisode>? = null
    ) {
        val presets = (siblings?.takeIf { it.isNotEmpty() } ?: listOf(episode))
            .map { it.toMusicTrackPreset() }
        val target = presets.firstOrNull { it.streamUrl == episode.audioUrl }
            ?: episode.toMusicTrackPreset()
        DirectMusicPlayerManager.playTrack(context, target, presets)
    }

    /** Les épisodes encore en mémoire sont oubliés (changement de réseau, réglage…). */
    fun clearEpisodeCache() {
        MissaPodcastProviders.clearEpisodeCache()
    }

    /**
     * Vrai tant que le tout premier catalogue n'est pas encore arrivé : l'écran affiche alors
     * un chargement au lieu d'une carte « univers vide » qui clignoterait au démarrage.
     */
    suspend fun isAwaitingFirstCatalogue(): Boolean =
        cachedShows().isEmpty() && remoteRefreshInFlight

    /** Recherche locale, sans accents, sur le catalogue déjà connu. */
    fun localMatches(query: String): List<PodcastShowPreset> {
        if (query.trim().length < 2) return emptyList()
        return (snapshotShows() + discoveredSearchShows.values)
            .distinctBy { it.id }
            .filter { MissaPodcastProviders.matchesShowQuery(it, query) }
            .sortedBy { it.region.order }
    }
}
