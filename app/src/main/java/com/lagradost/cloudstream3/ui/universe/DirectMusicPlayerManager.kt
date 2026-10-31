package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

enum class MusicRepeatMode {
    OFF,
    ALL,
    ONE
}

data class MusicPlaybackState(
    val track: MusicTrackPreset? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val hasError: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val queueSize: Int = 0,
    val queueIndex: Int = 0,
    val shuffleEnabled: Boolean = false,
    val repeatMode: MusicRepeatMode = MusicRepeatMode.ALL,
    val sleepTimerMinutes: Int = 0
)

/**
 * Exclusive YouTube Music playback & style-curated catalog controller for **Musiques**.
 *
 * Performance architecture:
 * - Persists user-selected musical styles (`missa_selected_music_styles_v1`) chosen at 1st launch.
 * - Caches the user's YouTube style catalog in DataStore (`missa_yt_music_catalog_v4`) for 0 ms
 *   display on subsequent launches while progressively refreshing newest YouTube Clips, Singles,
 *   Albums, and Playlists in 2 waves.
 * - Expands YouTube Albums & Playlists (`ytplaylist://`) on click into full playable track queues.
 * - Streams tracks via [DirectRadioPlayerManager.createCachedMusicMediaSource] (OkHttp HTTP/2 +
 *   128 MB disk `SimpleCache` + 600 ms fast-start `DefaultLoadControl`).
 * - Pre-resolves upcoming queue tracks (`idx + 1`, `idx + 2`) and top catalog hits in the
 *   background so track transitions and first clicks start without extraction latency.
 */
object DirectMusicPlayerManager {
    private const val SELECTED_MUSIC_STYLES_KEY = "missa_selected_music_styles_v1"
    private const val HAS_SELECTED_MUSIC_STYLES_KEY = "missa_has_selected_music_styles_v1"
    private const val REMOTE_TRACKS_CACHE_KEY = "missa_yt_music_catalog_v4"
    private const val REMOTE_TRACKS_STYLES_SIG_KEY = "missa_yt_music_styles_sig_v4"
    private const val REMOTE_TRACKS_UPDATED_AT_KEY = "missa_yt_music_updated_at_v4"
    private const val FAVORITE_TRACKS_KEY = "missa_favorite_music_track_ids_v1"
    private const val SHUFFLE_ENABLED_KEY = "missa_music_shuffle_enabled_v1"
    private const val REPEAT_MODE_KEY = "missa_music_repeat_mode_v1"
    private const val REMOTE_CACHE_TTL_MS = 2 * 60 * 60_000L // Refresh newest releases every 2 hours

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isOwningPlayback: Boolean = false

    @Volatile
    private var listenerAttachedPlayer: ExoPlayer? = null

    @Volatile
    private var remoteRefreshInFlight: Boolean = false

    @Volatile
    private var activeAppContext: Context? = null

    @Volatile
    private var inMemoryStyleTracks: List<MusicTrackPreset> = emptyList()

    @Volatile
    var hasPromptedOnboardingThisSession: Boolean = false

    private var resolveJob: Job? = null
    private var prefetchJob: Job? = null
    private var refreshJob: Job? = null
    private var sleepRunnable: Runnable? = null
    private var activeSleepMinutes: Int = 0

    private var activeQueue: List<MusicTrackPreset> = emptyList()
    private var activeQueueIndex: Int = 0
    private var activeCandidateUrls: List<String> = emptyList()
    private var activeCandidateIndex: Int = 0
    private var retriedFreshExtractionForTrackId: String? = null

    private val discoveredSearchTracks = ConcurrentHashMap<String, MusicTrackPreset>()

    private val _state = MutableLiveData(
        MusicPlaybackState(
            shuffleEnabled = getKey<Boolean>(SHUFFLE_ENABLED_KEY, false) == true,
            repeatMode = loadSavedRepeatMode()
        )
    )
    val state: LiveData<MusicPlaybackState> = _state

    private val _tracksUpdated = MutableLiveData(0L)
    val tracksUpdated: LiveData<Long> = _tracksUpdated

    private val progressTicker = object : Runnable {
        override fun run() {
            if (!isOwningPlayback) return
            val ctx = activeAppContext ?: return
            val exo = DirectRadioPlayerManager.getOrCreatePlayer(ctx)
            val current = _state.value ?: return
            val dur = exo.duration.takeIf { it != C.TIME_UNSET && it > 0L }
                ?: ((current.track?.durationSeconds ?: 0) * 1000L)
            val pos = exo.currentPosition.coerceAtLeast(0L)
            if (current.positionMs != pos || current.durationMs != dur) {
                _state.postValue(
                    current.copy(
                        positionMs = pos,
                        durationMs = dur,
                        isPlaying = exo.isPlaying,
                        isBuffering = exo.playbackState == Player.STATE_BUFFERING
                    )
                )
            }
            if (isOwningPlayback && (exo.isPlaying || exo.playbackState == Player.STATE_BUFFERING)) {
                mainHandler.postDelayed(this, 900L)
            }
        }
    }

    private fun loadSavedRepeatMode(): MusicRepeatMode {
        val raw = runCatching { getKey<String>(REPEAT_MODE_KEY) }.getOrNull()
        return MusicRepeatMode.entries.firstOrNull { it.name == raw } ?: MusicRepeatMode.ALL
    }

    // -------------------------------------------------------------------------
    // 1st-Launch Musical Style Preferences
    // -------------------------------------------------------------------------

    fun hasSelectedMusicStyles(): Boolean = runCatching {
        getKey<Boolean>(HAS_SELECTED_MUSIC_STYLES_KEY, false) == true &&
            !getKey<Array<String>>(SELECTED_MUSIC_STYLES_KEY).isNullOrEmpty()
    }.getOrDefault(false)

    fun selectedMusicStyleIds(): List<String> {
        val saved = runCatching {
            getKey<Array<String>>(SELECTED_MUSIC_STYLES_KEY)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        return MissaMusicProviders.resolveSelectedStyles(saved).map { it.id }
    }

    fun saveSelectedMusicStyles(styleIds: Collection<String>, triggerReload: Boolean = true) {
        val normalized = MissaMusicProviders.resolveSelectedStyles(styleIds).map { it.id }
        val previousSig = stylesSignature(selectedMusicStyleIds())
        val newSig = stylesSignature(normalized)

        runCatching {
            setKey(SELECTED_MUSIC_STYLES_KEY, normalized.toTypedArray())
            setKey(HAS_SELECTED_MUSIC_STYLES_KEY, true)
        }
        hasPromptedOnboardingThisSession = true

        if (previousSig != newSig) {
            inMemoryStyleTracks = emptyList()
            discoveredSearchTracks.clear()
            runCatching {
                removeKey(REMOTE_TRACKS_CACHE_KEY)
                removeKey(REMOTE_TRACKS_UPDATED_AT_KEY)
                setKey(REMOTE_TRACKS_STYLES_SIG_KEY, newSig)
            }
            _tracksUpdated.postValue(System.currentTimeMillis())
        }

        if (triggerReload) {
            ioScope.launch {
                refreshRemoteTracksIfNeeded(force = true)
            }
        }
    }

    private fun stylesSignature(ids: List<String>): String =
        ids.map { it.trim().lowercase() }.sorted().joinToString(",")

    // -------------------------------------------------------------------------
    // Favorites & Catalog State
    // -------------------------------------------------------------------------

    fun favoriteIds(): Set<String> = runCatching {
        getKey<Array<String>>(FAVORITE_TRACKS_KEY)?.toSet().orEmpty()
    }.getOrDefault(emptySet())

    fun isFavorite(trackId: String): Boolean = favoriteIds().contains(trackId)

    fun toggleFavorite(trackId: String): Boolean {
        val mutable = favoriteIds().toMutableSet()
        val nowFav = if (mutable.contains(trackId)) {
            mutable.remove(trackId)
            false
        } else {
            mutable.add(trackId)
            true
        }
        runCatching { setKey(FAVORITE_TRACKS_KEY, mutable.toTypedArray()) }
        _tracksUpdated.postValue(System.currentTimeMillis())
        return nowFav
    }

    private fun cachedRemoteTracks(): List<MusicTrackPreset> {
        val mem = inMemoryStyleTracks
        if (mem.isNotEmpty()) return mem
        val expectedSig = stylesSignature(selectedMusicStyleIds())
        val cachedSig = runCatching { getKey<String>(REMOTE_TRACKS_STYLES_SIG_KEY) }.getOrNull()
        if (!cachedSig.isNullOrBlank() && cachedSig != expectedSig) {
            return emptyList()
        }
        val persisted = runCatching {
            getKey<Array<MusicTrackPreset>>(REMOTE_TRACKS_CACHE_KEY)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        if (persisted.isNotEmpty()) {
            inMemoryStyleTracks = persisted
        }
        return persisted
    }

    /**
     * Returns all YouTube Clips, Studio Singles, Official Albums, and Playlists currently loaded
     * for the user's musical styles plus recent on-demand YouTube searches.
     */
    fun allPresetTracks(): List<MusicTrackPreset> {
        val styleTracks = cachedRemoteTracks()
        val onlineDiscovered = discoveredSearchTracks.values.toList()
        if (styleTracks.isEmpty() && onlineDiscovered.isEmpty()) return emptyList()

        val merged = ArrayList<MusicTrackPreset>(styleTracks.size + onlineDiscovered.size)
        val seenIds = HashSet<String>()
        for (track in styleTracks + onlineDiscovered) {
            if (track.title.isBlank() || track.streamUrl.isBlank()) continue
            if (seenIds.add(track.id)) {
                merged.add(track)
            }
        }
        return merged
    }

    private fun prefetchTopPlayableTracks(tracks: List<MusicTrackPreset>, count: Int = 2) {
        val candidates = tracks
            .filter { MissaMusicProviders.isLazyYouTubeAudioScheme(it.streamUrl) && !MissaMusicProviders.hasCachedAudioUrl(it.streamUrl) }
            .take(count)
        if (candidates.isEmpty()) return
        ioScope.launch {
            for (track in candidates) {
                runCatching {
                    MissaMusicProviders.resolvePlayableAudioUrls(track.streamUrl, track.fallbackUrls)
                }
            }
        }
    }

    suspend fun refreshRemoteTracksIfNeeded(force: Boolean = false) {
        val currentStyleIds = selectedMusicStyleIds()
        val currentSig = stylesSignature(currentStyleIds)
        val cachedSig = runCatching { getKey<String>(REMOTE_TRACKS_STYLES_SIG_KEY) }.getOrNull()
        val lastUpdated = runCatching { getKey<Long>(REMOTE_TRACKS_UPDATED_AT_KEY) ?: 0L }.getOrDefault(0L)
        val now = System.currentTimeMillis()
        val hasCached = cachedRemoteTracks().isNotEmpty() && cachedSig == currentSig

        if (!force && hasCached && (now - lastUpdated) < REMOTE_CACHE_TTL_MS) {
            prefetchTopPlayableTracks(inMemoryStyleTracks, count = 2)
            return
        }

        if (remoteRefreshInFlight && !force) return
        if (force) {
            refreshJob?.cancel()
        }
        remoteRefreshInFlight = true
        try {
            MissaMusicProviders.warmUpYouTubeEngine()
            val fullCatalog = MissaMusicProviders.fetchStyleCatalogProgressive(
                styleIds = currentStyleIds,
                onFirstWaveReady = { firstWave ->
                    if (firstWave.isNotEmpty()) {
                        inMemoryStyleTracks = firstWave
                        _tracksUpdated.postValue(System.currentTimeMillis())
                        prefetchTopPlayableTracks(firstWave, count = 2)
                    }
                }
            )
            if (fullCatalog.isNotEmpty()) {
                inMemoryStyleTracks = fullCatalog
                runCatching {
                    setKey(REMOTE_TRACKS_CACHE_KEY, fullCatalog.toTypedArray())
                    setKey(REMOTE_TRACKS_STYLES_SIG_KEY, currentSig)
                    setKey(REMOTE_TRACKS_UPDATED_AT_KEY, System.currentTimeMillis())
                }
                _tracksUpdated.postValue(System.currentTimeMillis())
                prefetchTopPlayableTracks(fullCatalog, count = 2)
            }
        } finally {
            remoteRefreshInFlight = false
        }
    }

    suspend fun searchTracksOnline(query: String): List<MusicTrackPreset> {
        val cleanQuery = MissaMusicProviders.sanitizeMusicQuery(query)
        if (cleanQuery.length < 2) return emptyList()
        val found = MissaMusicProviders.searchAllMusicServers(cleanQuery)
        if (found.isNotEmpty()) {
            found.forEach { track -> discoveredSearchTracks[track.id] = track }
            _tracksUpdated.postValue(System.currentTimeMillis())
            prefetchTopPlayableTracks(found, count = 2)
        }
        return found
    }

    // -------------------------------------------------------------------------
    // Playback Controls, Album/Playlist Queue Expansion & Next-Track Prefetch
    // -------------------------------------------------------------------------

    internal fun yieldPlaybackToRadio() {
        if (!isOwningPlayback) return
        isOwningPlayback = false
        resolveJob?.cancel()
        prefetchJob?.cancel()
        mainHandler.removeCallbacks(progressTicker)
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
        activeSleepMinutes = 0
        val current = _state.value ?: MusicPlaybackState()
        _state.postValue(
            current.copy(
                track = null,
                isPlaying = false,
                isBuffering = false,
                hasError = false,
                positionMs = 0L,
                durationMs = 0L
            )
        )
    }

    internal fun onSharedPlayerReleased() {
        listenerAttachedPlayer = null
        mainHandler.removeCallbacks(progressTicker)
    }

    private fun ensurePlayerListenerAttached(exo: ExoPlayer) {
        if (listenerAttachedPlayer === exo) return
        listenerAttachedPlayer = exo
        exo.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isOwningPlayback) return
                val current = _state.value ?: MusicPlaybackState()
                _state.postValue(
                    current.copy(
                        isPlaying = isPlaying,
                        isBuffering = exo.playbackState == Player.STATE_BUFFERING,
                        sleepTimerMinutes = activeSleepMinutes
                    )
                )
                mainHandler.removeCallbacks(progressTicker)
                if (isPlaying || exo.playbackState == Player.STATE_BUFFERING) {
                    mainHandler.post(progressTicker)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!isOwningPlayback) return
                val current = _state.value ?: MusicPlaybackState()
                val dur = exo.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: current.durationMs
                _state.postValue(
                    current.copy(
                        isPlaying = exo.isPlaying,
                        isBuffering = playbackState == Player.STATE_BUFFERING,
                        hasError = if (playbackState == Player.STATE_READY) false else current.hasError,
                        durationMs = dur,
                        positionMs = exo.currentPosition.coerceAtLeast(0L),
                        sleepTimerMinutes = activeSleepMinutes
                    )
                )
                if (playbackState == Player.STATE_ENDED) {
                    handleTrackEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!isOwningPlayback) return
                val nextIdx = activeCandidateIndex + 1
                if (nextIdx < activeCandidateUrls.size) {
                    activeCandidateIndex = nextIdx
                    val ctx = activeAppContext ?: return
                    val currentTrack = activeQueue.getOrNull(activeQueueIndex) ?: _state.value?.track ?: return
                    startExoTrackUri(ctx, currentTrack, activeCandidateUrls[nextIdx])
                    return
                }

                // Self-healing retry: if a cached YouTube audio URL expired early (HTTP 403/410),
                // invalidate its cache entry and re-extract fresh YouTube streams once automatically.
                val currentTrack = activeQueue.getOrNull(activeQueueIndex) ?: _state.value?.track
                val ctx = activeAppContext
                if (currentTrack != null &&
                    ctx != null &&
                    MissaMusicProviders.isLazyYouTubeAudioScheme(currentTrack.streamUrl) &&
                    retriedFreshExtractionForTrackId != currentTrack.id
                ) {
                    retriedFreshExtractionForTrackId = currentTrack.id
                    MissaMusicProviders.invalidateCachedAudio(currentTrack.streamUrl)
                    startCurrentQueueTrack(ctx)
                    return
                }

                val current = _state.value ?: MusicPlaybackState()
                _state.postValue(
                    current.copy(
                        isPlaying = false,
                        isBuffering = false,
                        hasError = true,
                        sleepTimerMinutes = activeSleepMinutes
                    )
                )
            }
        })
    }

    private fun handleTrackEnded() {
        val ctx = activeAppContext ?: return
        val current = _state.value ?: MusicPlaybackState()
        when (current.repeatMode) {
            MusicRepeatMode.ONE -> {
                val exo = DirectRadioPlayerManager.getOrCreatePlayer(ctx)
                exo.seekTo(0L)
                exo.playWhenReady = true
            }
            MusicRepeatMode.ALL -> {
                if (activeQueue.size > 1) {
                    advanceQueue(ctx, forward = true, wrap = true)
                } else {
                    val exo = DirectRadioPlayerManager.getOrCreatePlayer(ctx)
                    exo.seekTo(0L)
                    exo.playWhenReady = true
                }
            }
            MusicRepeatMode.OFF -> {
                if (activeQueueIndex + 1 < activeQueue.size) {
                    advanceQueue(ctx, forward = true, wrap = false)
                }
            }
        }
    }

    private fun prefetchUpcomingTracksInQueue() {
        prefetchJob?.cancel()
        val queueSnapshot = activeQueue
        if (queueSnapshot.size <= 1) return
        val baseIdx = activeQueueIndex
        val upcoming = listOf(1, 2).mapNotNull { offset ->
            queueSnapshot.getOrNull((baseIdx + offset) % queueSnapshot.size)
        }.filter {
            MissaMusicProviders.isLazyYouTubeAudioScheme(it.streamUrl) &&
                !MissaMusicProviders.hasCachedAudioUrl(it.streamUrl)
        }
        if (upcoming.isEmpty()) return

        prefetchJob = ioScope.launch {
            for (nextTrack in upcoming) {
                if (!isOwningPlayback) break
                runCatching {
                    MissaMusicProviders.resolvePlayableAudioUrls(nextTrack.streamUrl, nextTrack.fallbackUrls)
                }
            }
        }
    }

    fun playDirectResponse(
        context: Context,
        card: DirectMusicSearchResponse,
        visibleQueue: List<DirectMusicSearchResponse>? = null
    ) {
        val preset = allPresetTracks().firstOrNull {
            (card.trackId.isNotBlank() && it.id == card.trackId) || it.streamUrl == card.url
        } ?: MusicTrackPreset(
            id = card.trackId.ifBlank { "music_${card.url.hashCode()}" },
            title = card.title.ifBlank { card.name },
            artist = card.artist,
            album = card.album,
            genre = card.genre,
            streamUrl = card.url,
            artworkUrl = card.artworkUrl ?: card.posterUrl,
            durationSeconds = card.durationSeconds,
            providerName = card.apiName,
            fallbackUrls = card.fallbackUrls,
            isPipedLazy = card.isPipedLazy,
            itemKind = card.itemKind,
            trackCount = card.trackCount
        )

        if (preset.isAlbumOrPlaylist()) {
            playTrack(context, preset, queue = null)
            return
        }

        val queuePresets = visibleQueue
            ?.filter { !MissaMusicProviders.isYouTubePlaylistOrAlbumScheme(it.url) }
            ?.map { item ->
                allPresetTracks().firstOrNull {
                    (item.trackId.isNotBlank() && it.id == item.trackId) || it.streamUrl == item.url
                } ?: MusicTrackPreset(
                    id = item.trackId.ifBlank { "music_${item.url.hashCode()}" },
                    title = item.title.ifBlank { item.name },
                    artist = item.artist,
                    album = item.album,
                    genre = item.genre,
                    streamUrl = item.url,
                    artworkUrl = item.artworkUrl ?: item.posterUrl,
                    durationSeconds = item.durationSeconds,
                    providerName = item.apiName,
                    fallbackUrls = item.fallbackUrls,
                    isPipedLazy = item.isPipedLazy,
                    itemKind = item.itemKind,
                    trackCount = item.trackCount
                )
            }
            ?.takeIf { it.isNotEmpty() }

        playTrack(context, preset, queuePresets)
    }

    fun playTrack(
        context: Context,
        track: MusicTrackPreset,
        queue: List<MusicTrackPreset>? = null
    ) {
        val appCtx = context.applicationContext
        activeAppContext = appCtx

        // Mutual audio exclusion: stop active Radio stream before starting Music
        if (DirectRadioPlayerManager.isOwningPlayback) {
            DirectRadioPlayerManager.stop()
        }
        DirectRadioPlayerManager.isOwningPlayback = false
        isOwningPlayback = true
        retriedFreshExtractionForTrackId = null

        val exo = DirectRadioPlayerManager.getOrCreatePlayer(appCtx)
        ensurePlayerListenerAttached(exo)

        // Album / Playlist expansion (`ytplaylist://`) -> extract full track queue and play Track 1
        if (track.isAlbumOrPlaylist()) {
            resolveJob?.cancel()
            val prev = _state.value ?: MusicPlaybackState()
            _state.postValue(
                prev.copy(
                    track = track,
                    isPlaying = false,
                    isBuffering = true,
                    hasError = false,
                    positionMs = 0L,
                    durationMs = 0L,
                    queueSize = track.trackCount.coerceAtLeast(1),
                    queueIndex = 0,
                    sleepTimerMinutes = activeSleepMinutes
                )
            )
            resolveJob = ioScope.launch {
                val expandedTracks = MissaMusicProviders.extractYouTubeAlbumOrPlaylistTracks(
                    playlistOrLazyUrl = track.streamUrl,
                    fallbackAlbumTitle = track.title,
                    fallbackArtist = track.artist,
                    fallbackArtworkUrl = track.artworkUrl,
                    fallbackGenre = track.genre
                )
                withContext(Dispatchers.Main) {
                    if (!isOwningPlayback) return@withContext
                    if (expandedTracks.isEmpty()) {
                        val cur = _state.value ?: MusicPlaybackState()
                        _state.value = cur.copy(
                            isPlaying = false,
                            isBuffering = false,
                            hasError = true
                        )
                    } else {
                        activeQueue = expandedTracks
                        activeQueueIndex = 0
                        startCurrentQueueTrack(appCtx)
                    }
                }
            }
            return
        }

        val resolvedQueue = (queue ?: allPresetTracks().filter { !it.isAlbumOrPlaylist() })
            .ifEmpty { listOf(track) }
        val idx = resolvedQueue.indexOfFirst { it.id == track.id || it.streamUrl == track.streamUrl }
        if (idx >= 0) {
            activeQueue = resolvedQueue
            activeQueueIndex = idx
        } else {
            activeQueue = listOf(track) + resolvedQueue
            activeQueueIndex = 0
        }

        startCurrentQueueTrack(appCtx)
    }

    private fun startCurrentQueueTrack(appContext: Context) {
        val track = activeQueue.getOrNull(activeQueueIndex) ?: return
        if (track.isAlbumOrPlaylist()) {
            playTrack(appContext, track, queue = null)
            return
        }

        resolveJob?.cancel()
        val prev = _state.value ?: MusicPlaybackState()
        _state.postValue(
            prev.copy(
                track = track,
                isPlaying = false,
                isBuffering = true,
                hasError = false,
                positionMs = 0L,
                durationMs = (track.durationSeconds * 1000L).coerceAtLeast(0L),
                queueSize = activeQueue.size,
                queueIndex = activeQueueIndex,
                sleepTimerMinutes = activeSleepMinutes
            )
        )

        resolveJob = ioScope.launch {
            val urls = MissaMusicProviders.resolvePlayableAudioUrls(
                streamUrl = track.streamUrl,
                fallbackUrls = track.fallbackUrls
            )
            withContext(Dispatchers.Main) {
                if (!isOwningPlayback) return@withContext
                if (urls.isEmpty()) {
                    val cur = _state.value ?: MusicPlaybackState()
                    _state.value = cur.copy(
                        track = track,
                        isPlaying = false,
                        isBuffering = false,
                        hasError = true,
                        queueSize = activeQueue.size,
                        queueIndex = activeQueueIndex
                    )
                } else {
                    activeCandidateUrls = urls
                    activeCandidateIndex = 0
                    startExoTrackUri(appContext, track, urls.first())
                    prefetchUpcomingTracksInQueue()
                }
            }
        }
    }

    private fun startExoTrackUri(context: Context, track: MusicTrackPreset, uri: String) {
        val appContext = context.applicationContext
        val exo = DirectRadioPlayerManager.getOrCreatePlayer(appContext)
        ensurePlayerListenerAttached(exo)

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist.ifBlank { MissaMusicProviders.PROVIDER_YOUTUBE_MUSIC })
            .setAlbumTitle(track.album.ifBlank { track.genre })
        track.artworkUrl?.takeIf { it.isNotBlank() }?.let { art ->
            runCatching { metadataBuilder.setArtworkUri(Uri.parse(art)) }
        }
        val mediaItem = MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            .setMediaMetadata(metadataBuilder.build())
            .build()

        // Use disk-cached MediaSource for direct progressive/DASH YouTube audio; HLS uses default factory
        val isHlsManifest = uri.contains(".m3u8", ignoreCase = true) || uri.contains("manifest/hls", ignoreCase = true)
        if (isHlsManifest) {
            exo.setMediaItem(mediaItem)
        } else {
            exo.setMediaSource(DirectRadioPlayerManager.createCachedMusicMediaSource(appContext, mediaItem))
        }
        exo.prepare()
        exo.playWhenReady = true
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.post(progressTicker)
    }

    fun togglePlayPause(context: Context) {
        val current = _state.value
        val activeTrack = current?.track
        if (activeTrack == null) {
            val first = allPresetTracks().firstOrNull() ?: return
            playTrack(context, first)
            return
        }
        if (!isOwningPlayback) {
            playTrack(context, activeTrack, activeQueue.takeIf { it.isNotEmpty() })
            return
        }
        val exo = DirectRadioPlayerManager.getOrCreatePlayer(context)
        if (exo.isPlaying) {
            exo.pause()
        } else {
            if (exo.playbackState == Player.STATE_IDLE || current.hasError) {
                retriedFreshExtractionForTrackId = null
                startCurrentQueueTrack(context.applicationContext)
            } else {
                exo.play()
            }
        }
    }

    fun nextTrack(context: Context) {
        if (activeQueue.isEmpty()) {
            val presets = allPresetTracks().filter { !it.isAlbumOrPlaylist() }
            if (presets.isNotEmpty()) playTrack(context, presets.first(), presets)
            return
        }
        activeAppContext = context.applicationContext
        if (!isOwningPlayback) {
            playTrack(context, activeQueue[activeQueueIndex.coerceIn(0, activeQueue.lastIndex)], activeQueue)
            return
        }
        advanceQueue(context.applicationContext, forward = true, wrap = true)
    }

    fun previousTrack(context: Context) {
        if (activeQueue.isEmpty()) return
        val appCtx = context.applicationContext
        activeAppContext = appCtx
        if (isOwningPlayback) {
            val exo = DirectRadioPlayerManager.getOrCreatePlayer(appCtx)
            if (exo.currentPosition > 4_000L) {
                exo.seekTo(0L)
                return
            }
        }
        advanceQueue(appCtx, forward = false, wrap = true)
    }

    private fun advanceQueue(appContext: Context, forward: Boolean, wrap: Boolean) {
        if (activeQueue.isEmpty()) return
        val shuffle = _state.value?.shuffleEnabled == true
        val nextIndex = if (shuffle && activeQueue.size > 2) {
            var candidate = Random.nextInt(activeQueue.size)
            if (candidate == activeQueueIndex) {
                candidate = (candidate + 1) % activeQueue.size
            }
            candidate
        } else if (forward) {
            if (activeQueueIndex + 1 < activeQueue.size) {
                activeQueueIndex + 1
            } else if (wrap) {
                0
            } else {
                return
            }
        } else {
            if (activeQueueIndex - 1 >= 0) {
                activeQueueIndex - 1
            } else if (wrap) {
                activeQueue.lastIndex
            } else {
                0
            }
        }
        activeQueueIndex = nextIndex
        retriedFreshExtractionForTrackId = null
        startCurrentQueueTrack(appContext)
    }

    fun seekTo(positionMs: Long) {
        if (!isOwningPlayback) return
        val ctx = activeAppContext ?: return
        val exo = DirectRadioPlayerManager.getOrCreatePlayer(ctx)
        val safePos = positionMs.coerceAtLeast(0L)
        exo.seekTo(safePos)
        val current = _state.value ?: return
        _state.postValue(current.copy(positionMs = safePos))
    }

    fun toggleShuffle(): Boolean {
        val current = _state.value ?: MusicPlaybackState()
        val nextVal = !current.shuffleEnabled
        runCatching { setKey(SHUFFLE_ENABLED_KEY, nextVal) }
        _state.postValue(current.copy(shuffleEnabled = nextVal))
        return nextVal
    }

    fun cycleRepeatMode(): MusicRepeatMode {
        val current = _state.value ?: MusicPlaybackState()
        val nextMode = when (current.repeatMode) {
            MusicRepeatMode.ALL -> MusicRepeatMode.ONE
            MusicRepeatMode.ONE -> MusicRepeatMode.OFF
            MusicRepeatMode.OFF -> MusicRepeatMode.ALL
        }
        runCatching { setKey(REPEAT_MODE_KEY, nextMode.name) }
        _state.postValue(current.copy(repeatMode = nextMode))
        return nextMode
    }

    fun scheduleSleepTimer(minutes: Int) {
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
        activeSleepMinutes = minutes.coerceAtLeast(0)
        if (activeSleepMinutes > 0) {
            val runnable = Runnable {
                activeSleepMinutes = 0
                stop()
            }
            sleepRunnable = runnable
            mainHandler.postDelayed(runnable, activeSleepMinutes * 60_000L)
        }
        val current = _state.value ?: MusicPlaybackState()
        _state.postValue(current.copy(sleepTimerMinutes = activeSleepMinutes))
    }

    fun stop() {
        resolveJob?.cancel()
        prefetchJob?.cancel()
        mainHandler.removeCallbacks(progressTicker)
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
        activeSleepMinutes = 0
        if (isOwningPlayback) {
            activeAppContext?.let { ctx ->
                DirectRadioPlayerManager.getOrCreatePlayer(ctx).stop()
            }
            isOwningPlayback = false
        }
        val current = _state.value ?: MusicPlaybackState()
        _state.postValue(
            current.copy(
                track = null,
                isPlaying = false,
                isBuffering = false,
                hasError = false,
                positionMs = 0L,
                durationMs = 0L
            )
        )
    }
}
