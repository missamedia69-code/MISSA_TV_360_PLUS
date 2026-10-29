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
import com.lagradost.cloudstream3.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

enum class MusicRepeatMode {
    OFF,
    ALL,
    ONE
}

data class MusicPlaybackState(
    val track: MusicTrackPreset? = null,
    val queueSize: Int = 0,
    val queueIndex: Int = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val hasError: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: MusicRepeatMode = MusicRepeatMode.ALL,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val sleepTimerMinutes: Int = 0
)

/**
 * Dedicated 100%-Music queue & playback manager for MISSA TV 360 PLUS (`universeId = "music"`).
 *
 * - Shares the Media3 [ExoPlayer] + [MissaRadioMediaService] session with [DirectRadioPlayerManager]
 *   via strict ownership handoff so background playback, lockscreen controls, and Bluetooth audio
 *   work seamlessly without running duplicate services or overlapping streams.
 * - Full queue auto-advance (`STATE_ENDED`), Previous/Next, Shuffle, Repeat (`ALL` / `ONE` / `OFF`),
 *   seekbar scrubbing, multi-node stream failover, Piped audio-only lazy resolution, Favorites,
 *   OpenSubsonic / Navidrome server sync, and Sleep Timer.
 */
object DirectMusicPlayerManager {
    private const val CUSTOM_TRACKS_KEY = "missa_custom_music_tracks_v1"
    private const val REMOTE_TRACKS_CACHE_KEY = "missa_remote_music_tracks_v2"
    private const val REMOTE_TRACKS_UPDATED_AT_KEY = "missa_remote_music_updated_at_v2"
    private const val FAVORITE_TRACKS_KEY = "missa_favorite_music_ids_v1"
    private const val SUBSONIC_CONFIG_KEY = "missa_subsonic_server_config_v1"
    private const val REMOTE_CACHE_TTL_MS = 12 * 60 * 60_000L

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    internal var isOwningPlayback: Boolean = false

    private var boundExoPlayer: ExoPlayer? = null
    private var appContextRef: Context? = null
    private var resolveStreamJob: Job? = null
    private var sleepRunnable: Runnable? = null
    private var activeSleepMinutes: Int = 0

    @Volatile
    private var remoteRefreshInFlight = false

    private var activeQueue: List<MusicTrackPreset> = emptyList()
    private var activeQueueIndex: Int = 0
    private var shuffleEnabled: Boolean = false
    private var repeatMode: MusicRepeatMode = MusicRepeatMode.ALL

    private var activeFallbackCandidates: List<String> = emptyList()
    private var currentFallbackIndex: Int = 0
    private var consecutiveErrorSkips: Int = 0

    private val discoveredSearchTracks = ConcurrentHashMap<String, MusicTrackPreset>()

    private val _state = MutableLiveData(MusicPlaybackState())
    val state: LiveData<MusicPlaybackState> = _state

    private val _tracksUpdated = MutableLiveData(0L)
    val tracksUpdated: LiveData<Long> = _tracksUpdated

    private val progressRunnable = object : Runnable {
        override fun run() {
            val exo = boundExoPlayer
            if (isOwningPlayback && exo != null) {
                val current = _state.value ?: MusicPlaybackState()
                val rawDur = exo.duration
                val fallbackDurMs = (current.track?.durationSeconds ?: 0) * 1_000L
                val resolvedDur = if (rawDur != C.TIME_UNSET && rawDur > 0L) rawDur else fallbackDurMs
                val resolvedPos = exo.currentPosition.coerceAtLeast(0L)
                if (current.positionMs != resolvedPos || current.durationMs != resolvedDur) {
                    _state.value = current.copy(
                        positionMs = resolvedPos,
                        durationMs = resolvedDur
                    )
                }
                if (exo.isPlaying) {
                    mainHandler.postDelayed(this, 1_000L)
                }
            }
        }
    }

    private val musicPlayerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isOwningPlayback) return
            val exo = boundExoPlayer ?: return
            mainHandler.removeCallbacks(progressRunnable)
            if (isPlaying) {
                consecutiveErrorSkips = 0
                mainHandler.post(progressRunnable)
            }
            val current = _state.value ?: MusicPlaybackState()
            val rawDur = exo.duration
            val fallbackDur = (current.track?.durationSeconds ?: 0) * 1_000L
            _state.postValue(
                current.copy(
                    isPlaying = isPlaying,
                    isBuffering = exo.playbackState == Player.STATE_BUFFERING,
                    positionMs = exo.currentPosition.coerceAtLeast(0L),
                    durationMs = if (rawDur != C.TIME_UNSET && rawDur > 0L) rawDur else fallbackDur,
                    sleepTimerMinutes = activeSleepMinutes
                )
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (!isOwningPlayback) return
            val exo = boundExoPlayer ?: return
            if (playbackState == Player.STATE_ENDED) {
                val ctx = appContextRef
                if (ctx != null) {
                    handleTrackEnded(ctx)
                }
                return
            }
            val current = _state.value ?: MusicPlaybackState()
            val rawDur = exo.duration
            val fallbackDur = (current.track?.durationSeconds ?: 0) * 1_000L
            _state.postValue(
                current.copy(
                    isPlaying = exo.isPlaying,
                    isBuffering = playbackState == Player.STATE_BUFFERING,
                    hasError = if (playbackState == Player.STATE_READY) false else current.hasError,
                    durationMs = if (rawDur != C.TIME_UNSET && rawDur > 0L) rawDur else fallbackDur,
                    sleepTimerMinutes = activeSleepMinutes
                )
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!isOwningPlayback) return
            val ctx = appContextRef ?: return
            val exo = boundExoPlayer ?: return
            val current = _state.value ?: MusicPlaybackState()
            val track = current.track

            // 1. Try next mirror/fallback URL for the same track
            if (track != null && currentFallbackIndex < activeFallbackCandidates.size) {
                val nextUrl = activeFallbackCandidates[currentFallbackIndex++]
                startExoTrackUri(ctx, exo, track, nextUrl)
                return
            }

            // 2. Auto-skip to next playable track in queue (up to 3 consecutive broken tracks)
            if (activeQueue.size > 1 && consecutiveErrorSkips < 3) {
                consecutiveErrorSkips++
                nextTrack(ctx, autoAdvanced = true)
                return
            }

            _state.postValue(
                current.copy(
                    isPlaying = false,
                    isBuffering = false,
                    hasError = true,
                    sleepTimerMinutes = activeSleepMinutes
                )
            )
        }
    }

    // --- Persistence & Catalogues ---

    fun subsonicConfig(): OpenSubsonicServerConfig? = runCatching {
        getKey<OpenSubsonicServerConfig>(SUBSONIC_CONFIG_KEY)?.takeIf { it.isConfigured() }
    }.getOrNull()

    fun customTracks(): List<MusicTrackPreset> = runCatching {
        getKey<Array<MusicTrackPreset>>(CUSTOM_TRACKS_KEY)?.toList().orEmpty()
    }.getOrDefault(emptyList()).map {
        it.copy(isCustom = true, providerName = MissaMusicProviders.PROVIDER_CUSTOM_MUSIC)
    }

    private fun cachedRemoteTracks(): List<MusicTrackPreset> = runCatching {
        getKey<Array<MusicTrackPreset>>(REMOTE_TRACKS_CACHE_KEY)?.toList().orEmpty()
    }.getOrDefault(emptyList())

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
        return nowFav
    }

    fun allPresetTracks(): List<MusicTrackPreset> {
        val custom = customTracks()
        val remote = cachedRemoteTracks()
        val discovered = discoveredSearchTracks.values.toList()
        val combined = custom + discovered + remote + MissaMusicProviders.curatedStarterTracks
        return MissaMusicProviders.mergeMusicCatalogues(combined, favoriteIds())
    }

    suspend fun refreshRemoteTracksIfNeeded(force: Boolean = false) {
        if (remoteRefreshInFlight) return
        val now = System.currentTimeMillis()
        val lastUpdated = runCatching { getKey<Long>(REMOTE_TRACKS_UPDATED_AT_KEY) ?: 0L }.getOrDefault(0L)
        val hasCached = cachedRemoteTracks().isNotEmpty()
        if (!force && hasCached && now - lastUpdated in 1 until REMOTE_CACHE_TTL_MS) {
            return
        }

        remoteRefreshInFlight = true
        try {
            val harvested = MissaMusicProviders.harvestAllMusicProviders(subsonicConfig())
            if (harvested.isNotEmpty()) {
                runCatching {
                    setKey(REMOTE_TRACKS_CACHE_KEY, harvested.toTypedArray())
                    setKey(REMOTE_TRACKS_UPDATED_AT_KEY, System.currentTimeMillis())
                }
                _tracksUpdated.postValue(System.currentTimeMillis())
            }
        } finally {
            remoteRefreshInFlight = false
        }
    }

    suspend fun searchTracksOnline(query: String): List<MusicTrackPreset> {
        val found = MissaMusicProviders.searchAllMusicProviders(query, subsonicConfig())
        if (found.isNotEmpty()) {
            if (discoveredSearchTracks.size + found.size > 600) {
                discoveredSearchTracks.clear()
            }
            for (tr in found) {
                val key = tr.streamUrl.trim().lowercase(Locale.ROOT)
                discoveredSearchTracks[key] = tr
            }
            _tracksUpdated.postValue(System.currentTimeMillis())
        }
        return found
    }

    suspend fun connectOpenSubsonicServer(config: OpenSubsonicServerConfig): Int {
        val clean = config.copy(
            baseUrl = config.baseUrl.trim().trimEnd('/'),
            username = config.username.trim()
        )
        if (!clean.isConfigured()) return 0
        val songs = MissaMusicProviders.fetchOpenSubsonicCatalog(clean)
        if (songs.isEmpty()) return 0

        runCatching { setKey(SUBSONIC_CONFIG_KEY, clean) }
        val nonSubsonic = cachedRemoteTracks().filterNot {
            it.providerName == MissaMusicProviders.PROVIDER_SUBSONIC
        }
        val merged = MissaMusicProviders.mergeMusicCatalogues(songs + nonSubsonic).take(650)
        runCatching {
            setKey(REMOTE_TRACKS_CACHE_KEY, merged.toTypedArray())
            setKey(REMOTE_TRACKS_UPDATED_AT_KEY, System.currentTimeMillis())
        }
        _tracksUpdated.postValue(System.currentTimeMillis())
        return songs.size
    }

    fun disconnectOpenSubsonicServer() {
        runCatching { removeKey(SUBSONIC_CONFIG_KEY) }
        val remaining = cachedRemoteTracks().filterNot {
            it.providerName == MissaMusicProviders.PROVIDER_SUBSONIC
        }
        runCatching { setKey(REMOTE_TRACKS_CACHE_KEY, remaining.toTypedArray()) }
        _tracksUpdated.postValue(System.currentTimeMillis())
    }

    suspend fun importCustomTrackOrPlaylist(
        context: Context,
        title: String,
        artist: String,
        url: String
    ): List<MusicTrackPreset> {
        val trimmedUrl = url.trim()
        val cleanArtist = artist.trim().ifBlank {
            context.getString(R.string.universe_music_custom_artist_default)
        }
        val lower = trimmedUrl.lowercase(Locale.ROOT).substringBefore('?')
        if (lower.endsWith(".m3u") || lower.endsWith(".m3u8")) {
            val imported = MissaMusicProviders.fetchRemoteMusicPlaylistIfAny(trimmedUrl, cleanArtist)
            if (imported.isNotEmpty()) {
                val existing = customTracks().toMutableList()
                val newUrls = imported.map { it.streamUrl.lowercase(Locale.ROOT) }.toSet()
                existing.removeAll { it.streamUrl.lowercase(Locale.ROOT) in newUrls }
                val updated = (imported + existing).take(200)
                runCatching { setKey(CUSTOM_TRACKS_KEY, updated.toTypedArray()) }
                _tracksUpdated.postValue(System.currentTimeMillis())
                return imported
            }
        }

        val cleanTitle = title.trim().ifBlank {
            context.getString(R.string.universe_music_custom_title_default)
        }
        val preset = MusicTrackPreset(
            id = "custom_music_${System.currentTimeMillis()}",
            title = cleanTitle,
            artist = cleanArtist,
            genre = MissaMusicProviders.PROVIDER_CUSTOM_MUSIC,
            streamUrl = trimmedUrl,
            providerName = MissaMusicProviders.PROVIDER_CUSTOM_MUSIC,
            isCustom = true
        )
        val existing = customTracks().toMutableList()
        existing.removeAll { it.streamUrl.equals(trimmedUrl, ignoreCase = true) }
        existing.add(0, preset)
        runCatching { setKey(CUSTOM_TRACKS_KEY, existing.take(200).toTypedArray()) }
        _tracksUpdated.postValue(System.currentTimeMillis())
        return listOf(preset)
    }

    // --- Shared ExoPlayer Attachment & Handoff ---

    private fun ensurePlayerAttached(context: Context): ExoPlayer {
        val appCtx = context.applicationContext
        appContextRef = appCtx
        val exo = DirectRadioPlayerManager.getOrCreatePlayer(appCtx)
        if (boundExoPlayer !== exo) {
            boundExoPlayer?.removeListener(musicPlayerListener)
            exo.addListener(musicPlayerListener)
            boundExoPlayer = exo
        }
        return exo
    }

    internal fun yieldPlaybackToRadio() {
        resolveStreamJob?.cancel()
        resolveStreamJob = null
        mainHandler.removeCallbacks(progressRunnable)
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
        activeSleepMinutes = 0
        isOwningPlayback = false
        _state.postValue(
            MusicPlaybackState(
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode
            )
        )
    }

    internal fun onSharedPlayerReleased() {
        yieldPlaybackToRadio()
        boundExoPlayer = null
    }

    private fun startExoTrackUri(
        appContext: Context,
        exo: ExoPlayer,
        track: MusicTrackPreset,
        targetStreamUrl: String
    ) {
        runCatching {
            DirectRadioPlayerManager.ensureMediaSessionBound(appContext)
            val subtitle = listOf(track.artist, track.providerName)
                .filter { it.isNotBlank() }
                .joinToString(" • ")
            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(subtitle.ifBlank { track.genre })
                .setAlbumTitle(track.album.ifBlank { track.genre })
            track.artworkUrl?.takeIf { it.isNotBlank() }?.let { art ->
                runCatching { metadataBuilder.setArtworkUri(Uri.parse(art)) }
            }
            val mediaItem = MediaItem.Builder()
                .setMediaId(track.id)
                .setUri(targetStreamUrl)
                .setMediaMetadata(metadataBuilder.build())
                .build()
            exo.stop()
            exo.setMediaItem(mediaItem)
            exo.prepare()
            exo.playWhenReady = true
        }.onFailure {
            _state.postValue(
                (_state.value ?: MusicPlaybackState()).copy(
                    track = track,
                    isPlaying = false,
                    isBuffering = false,
                    hasError = true
                )
            )
        }
    }

    fun playTrack(
        context: Context,
        track: MusicTrackPreset,
        queueContext: List<MusicTrackPreset>? = null
    ) {
        val appContext = context.applicationContext
        DirectRadioPlayerManager.yieldPlaybackToMusic()
        isOwningPlayback = true
        resolveStreamJob?.cancel()

        val resolvedQueue = (queueContext?.takeIf { it.isNotEmpty() } ?: allPresetTracks()).let { base ->
            if (base.any { it.streamUrl.equals(track.streamUrl, ignoreCase = true) }) {
                base
            } else {
                listOf(track) + base
            }
        }
        activeQueue = resolvedQueue
        activeQueueIndex = resolvedQueue.indexOfFirst {
            it.streamUrl.equals(track.streamUrl, ignoreCase = true)
        }.coerceAtLeast(0)

        val exo = ensurePlayerAttached(appContext)
        _state.value = MusicPlaybackState(
            track = track,
            queueSize = activeQueue.size,
            queueIndex = activeQueueIndex,
            isPlaying = false,
            isBuffering = true,
            hasError = false,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            positionMs = 0L,
            durationMs = track.durationSeconds.coerceAtLeast(0) * 1_000L,
            sleepTimerMinutes = activeSleepMinutes
        )

        if (track.isPipedLazy || MissaMusicProviders.isLazyYouTubeAudioScheme(track.streamUrl)) {
            resolveStreamJob = ioScope.launch {
                val audioUrls = MissaMusicProviders.resolveYouTubeOrPipedAudioUrls(track.streamUrl)
                mainHandler.post {
                    if (!isOwningPlayback) return@post
                    if (audioUrls.isEmpty()) {
                        if (activeQueue.size > 1 && consecutiveErrorSkips < 3) {
                            consecutiveErrorSkips++
                            nextTrack(appContext, autoAdvanced = true)
                        } else {
                            _state.value = (_state.value ?: MusicPlaybackState()).copy(
                                isPlaying = false,
                                isBuffering = false,
                                hasError = true
                            )
                        }
                    } else {
                        activeFallbackCandidates = audioUrls.drop(1)
                        currentFallbackIndex = 0
                        startExoTrackUri(appContext, exo, track, audioUrls.first())
                    }
                }
            }
        } else {
            activeFallbackCandidates = track.fallbackUrls.filter {
                MissaMusicProviders.isValidAudioUrl(it) && !it.equals(track.streamUrl, ignoreCase = true)
            }
            currentFallbackIndex = 0
            startExoTrackUri(appContext, exo, track, track.streamUrl)
        }
    }

    fun playDirectResponse(
        context: Context,
        response: DirectMusicSearchResponse,
        queueResponses: List<DirectMusicSearchResponse>? = null
    ) {
        val allPresets = allPresetTracks()
        val presetByUrl = allPresets.associateBy { it.streamUrl.lowercase(Locale.ROOT) }

        fun DirectMusicSearchResponse.toPreset(): MusicTrackPreset =
            presetByUrl[url.lowercase(Locale.ROOT)] ?: MusicTrackPreset(
                id = trackId.ifBlank { "music_${url.hashCode()}" },
                title = title.ifBlank { name },
                artist = artist,
                album = album,
                genre = genre,
                streamUrl = url,
                artworkUrl = artworkUrl ?: posterUrl,
                durationSeconds = durationSeconds,
                providerName = apiName.ifBlank { DIRECT_MUSIC_API_NAME },
                fallbackUrls = fallbackUrls,
                isPipedLazy = isPipedLazy || MissaMusicProviders.isLazyYouTubeAudioScheme(url)
            )

        val targetPreset = response.toPreset()
        val queuePresets = queueResponses?.map { it.toPreset() }
        playTrack(context, targetPreset, queuePresets)
    }

    private fun handleTrackEnded(context: Context) {
        val currentTrack = _state.value?.track ?: return
        if (repeatMode == MusicRepeatMode.ONE) {
            val exo = ensurePlayerAttached(context)
            exo.seekTo(0L)
            exo.playWhenReady = true
            return
        }
        if (activeQueue.isEmpty()) return
        if (!shuffleEnabled && repeatMode == MusicRepeatMode.OFF && activeQueueIndex >= activeQueue.lastIndex) {
            val current = _state.value ?: MusicPlaybackState()
            _state.postValue(current.copy(isPlaying = false, isBuffering = false, positionMs = 0L))
            return
        }
        nextTrack(context, autoAdvanced = true)
    }

    fun togglePlayPause(context: Context) {
        val current = _state.value ?: MusicPlaybackState()
        val track = current.track ?: allPresetTracks().firstOrNull() ?: return
        if (!isOwningPlayback) {
            playTrack(context, track)
            return
        }
        val exo = ensurePlayerAttached(context)
        if (exo.isPlaying) {
            exo.pause()
        } else if (current.hasError || exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_ENDED) {
            playTrack(context, track, activeQueue.takeIf { it.isNotEmpty() })
        } else {
            exo.play()
        }
    }

    fun nextTrack(context: Context, autoAdvanced: Boolean = false) {
        val queue = activeQueue.takeIf { it.isNotEmpty() } ?: allPresetTracks()
        if (queue.isEmpty()) return
        if (!autoAdvanced) {
            consecutiveErrorSkips = 0
        }
        val nextIndex = if (shuffleEnabled && queue.size > 1) {
            var candidate = Random.nextInt(queue.size)
            if (candidate == activeQueueIndex) {
                candidate = (activeQueueIndex + 1) % queue.size
            }
            candidate
        } else {
            (activeQueueIndex + 1).mod(queue.size)
        }
        playTrack(context, queue[nextIndex], queue)
    }

    fun previousTrack(context: Context) {
        val queue = activeQueue.takeIf { it.isNotEmpty() } ?: allPresetTracks()
        if (queue.isEmpty()) return
        consecutiveErrorSkips = 0
        val exo = boundExoPlayer
        if (isOwningPlayback && exo != null && exo.currentPosition > 4_000L) {
            exo.seekTo(0L)
            return
        }
        val prevIndex = (activeQueueIndex - 1).mod(queue.size)
        playTrack(context, queue[prevIndex], queue)
    }

    fun seekTo(positionMs: Long) {
        if (!isOwningPlayback) return
        val exo = boundExoPlayer ?: return
        val target = positionMs.coerceAtLeast(0L)
        runCatching { exo.seekTo(target) }
        val current = _state.value ?: return
        _state.value = current.copy(positionMs = target)
    }

    fun toggleShuffle(): Boolean {
        shuffleEnabled = !shuffleEnabled
        val current = _state.value ?: MusicPlaybackState()
        _state.value = current.copy(shuffleEnabled = shuffleEnabled)
        return shuffleEnabled
    }

    fun cycleRepeatMode(): MusicRepeatMode {
        repeatMode = when (repeatMode) {
            MusicRepeatMode.ALL -> MusicRepeatMode.ONE
            MusicRepeatMode.ONE -> MusicRepeatMode.OFF
            MusicRepeatMode.OFF -> MusicRepeatMode.ALL
        }
        val current = _state.value ?: MusicPlaybackState()
        _state.value = current.copy(repeatMode = repeatMode)
        return repeatMode
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
        resolveStreamJob?.cancel()
        resolveStreamJob = null
        mainHandler.removeCallbacks(progressRunnable)
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
        activeSleepMinutes = 0
        if (isOwningPlayback) {
            isOwningPlayback = false
            runCatching {
                boundExoPlayer?.stop()
                boundExoPlayer?.clearMediaItems()
            }
        }
        _state.value = MusicPlaybackState(
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode
        )
    }
}
