package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class RadioPlaybackState(
    val station: RadioStationPreset? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val hasError: Boolean = false,
    val sleepTimerMinutes: Int = 0
)

/**
 * Direct-listening multi-provider audio stream controller for MISSA TV 360 PLUS (RAD-01..04).
 *
 * - Backed by Media3 [ExoPlayer] + [MissaRadioMediaService] for persistent mini-player,
 *   background listening, Bluetooth controls, and lockscreen media notification.
 * - Aggregates all integrated free providers via [MissaRadioProviders] (MISSA Bouquet Direct,
 *   Radio Browser 45k+, Zeno.FM Afrique, SomaFM, Laut.fm, GitHub M3U Playlists, Icecast Xiph).
 * - Automatic stream failover across [RadioStationPreset.fallbackUrls] and `.pls`/`.m3u` descriptors.
 * - Supports single stream or full `#EXTM3U` playlist import, favorites, and sleep timer.
 */
object DirectRadioPlayerManager {
    private const val CUSTOM_STATIONS_KEY = "missa_custom_radio_stations_v2"
    private const val LEGACY_CUSTOM_STATIONS_KEY = "missa_custom_radio_stations_v1"
    private const val REMOTE_STATIONS_CACHE_KEY = "missa_remote_radio_stations_v3"
    private const val LEGACY_REMOTE_STATIONS_CACHE_KEY = "missa_remote_radio_stations_v1"
    private const val REMOTE_STATIONS_UPDATED_AT_KEY = "missa_remote_radio_updated_at_v3"
    private const val FAVORITE_STATIONS_KEY = "missa_favorite_radio_ids_v1"
    private const val REMOTE_CACHE_TTL_MS = 12 * 60 * 60_000L

    val defaultStations: List<RadioStationPreset>
        get() = MissaRadioProviders.curatedInstantStations

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null
    private var activeSleepMinutes: Int = 0

    @Volatile
    private var remoteRefreshInFlight = false
    private var activeFallbackCandidates: List<String> = emptyList()
    private var currentFallbackIndex: Int = 0
    private var triedPlaylistUnwrapForUrl: String? = null

    private val discoveredSearchStations = ConcurrentHashMap<String, RadioStationPreset>()

    private val _state = MutableLiveData(RadioPlaybackState())
    val state: LiveData<RadioPlaybackState> = _state

    private val _stationsUpdated = MutableLiveData(0L)
    /** Emits after a remote catalogue refresh or online search so visible radio screens re-project. */
    val stationsUpdated: LiveData<Long> = _stationsUpdated

    fun customStations(): List<RadioStationPreset> {
        val v2 = runCatching {
            getKey<Array<RadioStationPreset>>(CUSTOM_STATIONS_KEY)?.toList()
        }.getOrNull()
        if (v2 != null) {
            return v2.map {
                it.copy(
                    isCustom = true,
                    providerName = MissaRadioProviders.PROVIDER_CUSTOM
                )
            }
        }

        return runCatching {
            getKey<Array<RadioStationPreset>>(LEGACY_CUSTOM_STATIONS_KEY)?.toList().orEmpty()
        }.getOrDefault(emptyList()).map {
            it.copy(
                isCustom = true,
                providerName = MissaRadioProviders.PROVIDER_CUSTOM
            )
        }
    }

    fun favoriteIds(): Set<String> = runCatching {
        getKey<Array<String>>(FAVORITE_STATIONS_KEY)?.toSet().orEmpty()
    }.getOrDefault(emptySet())

    fun isFavorite(stationId: String): Boolean = favoriteIds().contains(stationId)

    fun toggleFavorite(stationId: String): Boolean {
        val mutable = favoriteIds().toMutableSet()
        val nowFav = if (mutable.contains(stationId)) {
            mutable.remove(stationId)
            false
        } else {
            mutable.add(stationId)
            true
        }
        runCatching { setKey(FAVORITE_STATIONS_KEY, mutable.toTypedArray()) }
        return nowFav
    }

    private fun cachedRemoteStations(): List<RadioStationPreset> {
        val v3 = runCatching {
            getKey<Array<RadioStationPreset>>(REMOTE_STATIONS_CACHE_KEY)?.toList()
        }.getOrNull()
        if (!v3.isNullOrEmpty()) return v3

        return runCatching {
            getKey<Array<RadioStationPreset>>(LEGACY_REMOTE_STATIONS_CACHE_KEY)?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    fun allPresetStations(): List<RadioStationPreset> {
        val custom = customStations()
        val remote = cachedRemoteStations()
        val onlineDiscovered = discoveredSearchStations.values.toList()
        // Curated instant bouquets come first so flagship African/Cameroonian/FIP/SomaFM streams stay prominent,
        // while alternate mirrors discovered in remote providers fold into fallbackUrls automatically.
        val combined = custom + defaultStations + remote + onlineDiscovered
        return MissaRadioProviders.mergeStationCatalogues(combined, favoriteIds())
    }

    private val musicStationHintRegex = Regex(
        "\\b(musiques?|music|afrobeats?|afropop|amapiano|afro|hip[ -]?hop|urbain|urban|rap|r&b|rnb|jazz|groove|soul|blues|pop|rock|reggae|dancehall|zouk|kompa|gospel|louange|makossa|bikutsi|classique|classical|ambient|chill|chillout|lounge|lofi|lo-fi|electro|bossa|concerts?|hits?)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Continuous music-focused direct streams surfaced in the dedicated Music universe. */
    fun musicPresetStations(): List<RadioStationPreset> =
        allPresetStations().filter { station ->
            station.providerName == MissaRadioProviders.PROVIDER_SOMAFM ||
                station.providerName == MissaRadioProviders.PROVIDER_LAUT_FM ||
                musicStationHintRegex.containsMatchIn("${station.name} ${station.genre}")
        }

    /**
     * Refreshes the multi-provider free radio catalogue in the background if the cached snapshot
     * is absent/expired or [force] is requested.
     */
    suspend fun refreshRemotePresetsIfNeeded(force: Boolean = false) {
        if (remoteRefreshInFlight) return
        val now = System.currentTimeMillis()
        val lastUpdated = runCatching { getKey<Long>(REMOTE_STATIONS_UPDATED_AT_KEY) ?: 0L }.getOrDefault(0L)
        val hasCached = cachedRemoteStations().isNotEmpty()
        if (!force && hasCached && now - lastUpdated in 1 until REMOTE_CACHE_TTL_MS) {
            return
        }

        remoteRefreshInFlight = true
        try {
            val harvested = MissaRadioProviders.harvestAllFreeProviders()
            if (harvested.isNotEmpty()) {
                runCatching {
                    setKey(REMOTE_STATIONS_CACHE_KEY, harvested.toTypedArray())
                    setKey(REMOTE_STATIONS_UPDATED_AT_KEY, System.currentTimeMillis())
                }
                _stationsUpdated.postValue(System.currentTimeMillis())
            }
        } finally {
            remoteRefreshInFlight = false
        }
    }

    /**
     * Queries online free radio directories (45,000+ Radio Browser stations by name, tag & country)
     * in real time and merges discovered stations into the active session pool.
     */
    suspend fun searchStationsOnline(query: String): List<RadioStationPreset> {
        val found = MissaRadioProviders.searchOnlineProviders(query)
        if (found.isNotEmpty()) {
            for (st in found) {
                val key = st.streamUrl.trim().lowercase(Locale.ROOT)
                if (discoveredSearchStations.size < 300 || discoveredSearchStations.containsKey(key)) {
                    discoveredSearchStations[key] = st
                }
            }
            _stationsUpdated.postValue(System.currentTimeMillis())
        }
        return found
    }

    fun isValidStreamUrl(url: String): Boolean = MissaRadioProviders.isValidStreamUrl(url)

    fun saveCustomStation(
        context: Context,
        name: String,
        streamUrl: String,
        genre: String = "",
        country: String = "WEB",
        existingId: String? = null
    ): RadioStationPreset {
        val fallbackName = context.getString(R.string.universe_radio_custom_name_default)
        val fallbackGenre = context.getString(R.string.universe_radio_custom_genre_default)
        val trimmedName = name.trim().ifBlank { fallbackName }
        val trimmedUrl = streamUrl.trim()
        val cleanCountry = country.trim().uppercase(Locale.ROOT).take(4).ifBlank { "WEB" }
        val id = existingId ?: "custom_${trimmedUrl.hashCode()}"
        val preset = RadioStationPreset(
            id = id,
            name = trimmedName,
            genre = genre.trim().ifBlank { fallbackGenre },
            country = cleanCountry,
            streamUrl = trimmedUrl,
            isCustom = true,
            providerName = MissaRadioProviders.PROVIDER_CUSTOM
        )
        val existing = customStations().toMutableList()
        existing.removeAll { it.id == id || it.streamUrl.equals(trimmedUrl, ignoreCase = true) }
        existing.add(0, preset)
        runCatching { setKey(CUSTOM_STATIONS_KEY, existing.take(160).toTypedArray()) }
        _stationsUpdated.postValue(System.currentTimeMillis())
        return preset
    }

    /**
     * Smart custom radio importer: if [streamOrPlaylistUrl] points to a multi-station `#EXTM3U` / `.m3u` / `.pls`
     * playlist, imports all stations at once; otherwise saves it as a single direct custom stream.
     */
    suspend fun importCustomStationOrPlaylist(
        context: Context,
        name: String,
        streamOrPlaylistUrl: String,
        country: String = "WEB",
        existingId: String? = null
    ): List<RadioStationPreset> {
        val trimmedUrl = streamOrPlaylistUrl.trim()
        if (existingId == null) {
            val playlistStations = MissaRadioProviders.fetchRemotePlaylistIfAny(
                url = trimmedUrl,
                defaultName = name.trim(),
                defaultCountry = country.trim().uppercase(Locale.ROOT).take(4).ifBlank { "WEB" },
                isCustom = true
            )
            if (playlistStations.isNotEmpty()) {
                val existing = customStations().toMutableList()
                val newUrls = playlistStations.map { it.streamUrl.lowercase(Locale.ROOT) }.toSet()
                existing.removeAll { it.streamUrl.lowercase(Locale.ROOT) in newUrls }
                val updated = (playlistStations + existing).take(160)
                runCatching { setKey(CUSTOM_STATIONS_KEY, updated.toTypedArray()) }
                _stationsUpdated.postValue(System.currentTimeMillis())
                return playlistStations
            }
        }
        val single = withContext(Dispatchers.Main) {
            saveCustomStation(
                context = context,
                name = name,
                streamUrl = trimmedUrl,
                country = country,
                existingId = existingId
            )
        }
        return listOf(single)
    }

    fun deleteCustomStation(stationIdOrUrl: String) {
        val existing = customStations().toMutableList()
        existing.removeAll {
            it.id == stationIdOrUrl || it.streamUrl.equals(stationIdOrUrl, ignoreCase = true)
        }
        runCatching { setKey(CUSTOM_STATIONS_KEY, existing.toTypedArray()) }
        _stationsUpdated.postValue(System.currentTimeMillis())
        if (_state.value?.station?.let { it.id == stationIdOrUrl || it.streamUrl == stationIdOrUrl } == true) {
            stop()
        }
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
        val current = _state.value ?: RadioPlaybackState()
        _state.postValue(current.copy(sleepTimerMinutes = activeSleepMinutes))
    }

    fun getOrCreatePlayer(context: Context): ExoPlayer {
        player?.let { return it }
        val appContext = context.applicationContext
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        return ExoPlayer.Builder(appContext)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { exo ->
                exo.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        val current = _state.value ?: RadioPlaybackState()
                        _state.postValue(
                            current.copy(
                                isPlaying = isPlaying,
                                isBuffering = exo.playbackState == Player.STATE_BUFFERING,
                                sleepTimerMinutes = activeSleepMinutes
                            )
                        )
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val current = _state.value ?: RadioPlaybackState()
                        _state.postValue(
                            current.copy(
                                isPlaying = exo.isPlaying,
                                isBuffering = playbackState == Player.STATE_BUFFERING,
                                hasError = if (playbackState == Player.STATE_READY) false else current.hasError,
                                sleepTimerMinutes = activeSleepMinutes
                            )
                        )
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val current = _state.value ?: RadioPlaybackState()
                        val station = current.station
                        // 1. Try next mirror URL automatically if available
                        if (station != null && currentFallbackIndex < activeFallbackCandidates.size) {
                            val nextMirrorUrl = activeFallbackCandidates[currentFallbackIndex++]
                            startExoStreamUri(appContext, exo, station, nextMirrorUrl)
                            return
                        }
                        // 2. If the primary URL was a .pls / .m3u descriptor wrapper, unwrap it once asynchronously
                        val failedUrl = station?.streamUrl
                        if (station != null &&
                            failedUrl != null &&
                            triedPlaylistUnwrapForUrl != failedUrl &&
                            (failedUrl.contains(".pls", ignoreCase = true) || failedUrl.contains(".m3u", ignoreCase = true))
                        ) {
                            triedPlaylistUnwrapForUrl = failedUrl
                            ioScope.launch {
                                val unwrapped = MissaRadioProviders.fetchRemotePlaylistIfAny(failedUrl)
                                if (unwrapped.isNotEmpty()) {
                                    val first = unwrapped.first()
                                    mainHandler.post {
                                        activeFallbackCandidates = first.fallbackUrls
                                        currentFallbackIndex = 0
                                        startExoStreamUri(appContext, exo, station, first.streamUrl)
                                    }
                                } else {
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
                })
                player = exo
            }
    }

    private fun startExoStreamUri(
        appContext: Context,
        exo: ExoPlayer,
        preset: RadioStationPreset,
        targetStreamUrl: String
    ) {
        runCatching {
            val metadataBuilder = MediaMetadata.Builder()
                .setTitle(preset.name)
                .setArtist("${preset.providerName} • ${preset.genre}")
                .setStation(preset.name)
            preset.logoUrl?.takeIf { it.isNotBlank() }?.let { logo ->
                runCatching { metadataBuilder.setArtworkUri(Uri.parse(logo)) }
            }
            val mediaItem = MediaItem.Builder()
                .setMediaId(preset.id)
                .setUri(targetStreamUrl)
                .setMediaMetadata(metadataBuilder.build())
                .build()
            exo.stop()
            exo.setMediaItem(mediaItem)
            exo.prepare()
            exo.playWhenReady = true
            runCatching {
                val serviceIntent = Intent(appContext, MissaRadioMediaService::class.java)
                ContextCompat.startForegroundService(appContext, serviceIntent)
            }
        }.onFailure {
            _state.value = RadioPlaybackState(
                station = preset,
                isPlaying = false,
                isBuffering = false,
                hasError = true,
                sleepTimerMinutes = activeSleepMinutes
            )
        }
    }

    fun playStation(context: Context, preset: RadioStationPreset) {
        val appContext = context.applicationContext
        val exo = getOrCreatePlayer(appContext)
        activeFallbackCandidates = preset.fallbackUrls.filter {
            isValidStreamUrl(it) && !it.equals(preset.streamUrl, ignoreCase = true)
        }
        currentFallbackIndex = 0
        triedPlaylistUnwrapForUrl = null
        _state.value = RadioPlaybackState(
            station = preset,
            isPlaying = false,
            isBuffering = true,
            hasError = false,
            sleepTimerMinutes = activeSleepMinutes
        )
        startExoStreamUri(appContext, exo, preset, preset.streamUrl)
        ioScope.launch {
            MissaRadioProviders.pingRadioBrowserClickIfApplicable(preset.id)
        }
    }

    fun playDirectResponse(context: Context, response: DirectRadioSearchResponse) {
        val fallbackGenre = context.getString(R.string.universe_radio_live_genre_default)
        val preset = allPresetStations().firstOrNull { it.streamUrl.equals(response.url, ignoreCase = true) }
            ?: RadioStationPreset(
                id = response.url.hashCode().toString(),
                name = response.name,
                genre = response.genre.ifBlank { fallbackGenre },
                country = response.country,
                streamUrl = response.url,
                logoUrl = response.logoUrl ?: response.posterUrl,
                providerName = response.apiName.ifBlank { DIRECT_RADIO_API_NAME }
            )
        playStation(context, preset)
    }

    fun togglePlayPause(context: Context) {
        val current = _state.value ?: return
        val station = current.station ?: allPresetStations().firstOrNull() ?: return
        val exo = getOrCreatePlayer(context)
        if (exo.isPlaying) {
            exo.pause()
        } else if (current.hasError || exo.playbackState == Player.STATE_IDLE || exo.playbackState == Player.STATE_ENDED) {
            playStation(context, station)
        } else {
            exo.play()
        }
    }

    fun nextStation(context: Context) {
        val stations = allPresetStations()
        if (stations.isEmpty()) return
        val currentUrl = _state.value?.station?.streamUrl
        val currentIndex = stations.indexOfFirst { it.streamUrl == currentUrl }
        val next = stations[(currentIndex + 1).mod(stations.size)]
        playStation(context, next)
    }

    fun previousStation(context: Context) {
        val stations = allPresetStations()
        if (stations.isEmpty()) return
        val currentUrl = _state.value?.station?.streamUrl
        val currentIndex = stations.indexOfFirst { it.streamUrl == currentUrl }.let { if (it < 0) 0 else it }
        val prev = stations[(currentIndex - 1).mod(stations.size)]
        playStation(context, prev)
    }

    fun stop() {
        sleepRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepRunnable = null
        activeSleepMinutes = 0
        runCatching {
            player?.stop()
            player?.clearMediaItems()
        }
        _state.value = RadioPlaybackState()
    }

    fun release() {
        stop()
        runCatching {
            player?.release()
        }
        player = null
    }
}
