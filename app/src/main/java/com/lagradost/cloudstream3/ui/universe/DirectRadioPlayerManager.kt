package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.content.Intent
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
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class RadioPlaybackState(
    val station: RadioStationPreset? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val hasError: Boolean = false,
    val sleepTimerMinutes: Int = 0
)

/**
 * Direct-listening audio stream controller for MISSA TV 360 PLUS (RAD-01..04).
 *
 * - Backed by Media3 [ExoPlayer] + [MissaRadioMediaService] for persistent mini-player,
 *   background listening, Bluetooth controls, and lockscreen media notification.
 * - Includes African, Cameroonian & international presets plus remote JSON station updates.
 * - Supports custom stream add/edit/delete, favorites, and sleep timer.
 */
object DirectRadioPlayerManager {
    private const val CUSTOM_STATIONS_KEY = "missa_custom_radio_stations_v2"
    private const val LEGACY_CUSTOM_STATIONS_KEY = "missa_custom_radio_stations_v1"
    private const val REMOTE_STATIONS_CACHE_KEY = "missa_remote_radio_stations_v1"
    private const val FAVORITE_STATIONS_KEY = "missa_favorite_radio_ids_v1"
    private const val REMOTE_STATIONS_JSON_URL =
        "https://raw.githubusercontent.com/missamedia69-code/MISSA_TV_360_PLUS/branding-miissa-tv-360/missa-radio-presets.json"

    val defaultStations: List<RadioStationPreset> = listOf(
        // African, Cameroonian & Pan-African Direct Presets (RAD-04)
        RadioStationPreset(
            id = "rfi_afrique",
            name = "RFI Afrique",
            genre = "Afrique • Actualités & Magazines",
            country = "AF",
            streamUrl = "https://rfiafrique64k.ice.infomaniak.ch/rfiafrique-64.mp3"
        ),
        RadioStationPreset(
            id = "africa_radio",
            name = "Africa Radio",
            genre = "Panafricaine • Info, Musique & Débats",
            country = "AF",
            streamUrl = "https://african1paris.ice.infomaniak.ch/african1paris-128.mp3"
        ),
        RadioStationPreset(
            id = "radio_balafon_cm",
            name = "Radio Balafon 90.3 Douala",
            genre = "Cameroun • Info, Culture & Afro",
            country = "CM",
            streamUrl = "https://listen.radioking.com/radio/10903/stream/22164"
        ),
        RadioStationPreset(
            id = "bbc_world_africa",
            name = "BBC World Service Africa",
            genre = "Afrique & Monde • News 24/7",
            country = "AF",
            streamUrl = "https://stream.live.vc.bbcmedia.co.uk/bbc_world_service"
        ),
        RadioStationPreset(
            id = "rfi_monde",
            name = "RFI Monde",
            genre = "International & Francophonie",
            country = "INT",
            streamUrl = "https://rfimonde64k.ice.infomaniak.ch/rfimonde-64.mp3"
        ),
        RadioStationPreset(
            id = "mouv",
            name = "Mouv’ Afro & Urbain",
            genre = "Afrobeats, Hip-Hop & Urbain",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/mouv-midfi.mp3"
        ),
        RadioStationPreset(
            id = "france_info",
            name = "franceinfo:",
            genre = "Actualités en continu 24/7",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/franceinfo-midfi.mp3"
        ),
        RadioStationPreset(
            id = "france_inter",
            name = "France Inter",
            genre = "Généraliste & Direct",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/franceinter-midfi.mp3"
        ),
        RadioStationPreset(
            id = "fip",
            name = "FIP Radio",
            genre = "Musique éclectique, Jazz & Groove",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/fip-midfi.mp3"
        ),
        RadioStationPreset(
            id = "france_musique",
            name = "France Musique",
            genre = "Classique & Concerts",
            country = "FR",
            streamUrl = "https://icecast.radiofrance.fr/francemusique-midfi.mp3"
        ),
        RadioStationPreset(
            id = "swiss_jazz",
            name = "Radio Swiss Jazz",
            genre = "Jazz, Soul & Blues",
            country = "CH",
            streamUrl = "https://stream.srg-ssr.ch/m/rsj/mp3_128"
        )
    )

    private var player: ExoPlayer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var sleepRunnable: Runnable? = null
    private var activeSleepMinutes: Int = 0
    private var remoteRefreshAttempted = false

    private val _state = MutableLiveData(RadioPlaybackState())
    val state: LiveData<RadioPlaybackState> = _state

    fun customStations(): List<RadioStationPreset> {
        val v2 = runCatching {
            getKey<Array<RadioStationPreset>>(CUSTOM_STATIONS_KEY)?.toList()
        }.getOrNull()
        if (v2 != null) return v2.map { it.copy(isCustom = true) }

        val legacy = runCatching {
            getKey<Array<RadioStationPreset>>(LEGACY_CUSTOM_STATIONS_KEY)?.toList().orEmpty()
        }.getOrDefault(emptyList()).map { it.copy(isCustom = true) }
        return legacy
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

    fun allPresetStations(): List<RadioStationPreset> {
        val custom = customStations()
        val remote = runCatching {
            getKey<Array<RadioStationPreset>>(REMOTE_STATIONS_CACHE_KEY)?.toList().orEmpty()
        }.getOrDefault(emptyList())
        val base = if (remote.isNotEmpty()) {
            (remote + defaultStations).distinctBy { it.streamUrl.lowercase() }
        } else {
            defaultStations
        }
        val favs = favoriteIds()
        val merged = (custom + base).distinctBy { it.streamUrl.lowercase() }
        return merged.sortedWith(
            compareByDescending<RadioStationPreset> { favs.contains(it.id) }
                .thenByDescending { it.isCustom }
        )
    }

    private val musicStationHintRegex = Regex(
        "\\b(musiques?|music|afrobeats?|afro|hip[ -]?hop|urbain|urban|rap|r&b|rnb|jazz|groove|soul|blues|pop|rock|reggae|zouk|gospel|makossa|bikutsi|classique|concerts?|hits?)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Continuous music-focused direct streams surfaced in the dedicated Music universe. */
    fun musicPresetStations(): List<RadioStationPreset> =
        allPresetStations().filter { station ->
            musicStationHintRegex.containsMatchIn("${station.name} ${station.genre}")
        }

    /** Optional remote JSON refresh from GitHub so broken stream URLs can be updated OTA (RAD-04). */
    suspend fun refreshRemotePresetsIfNeeded() {
        if (remoteRefreshAttempted) return
        remoteRefreshAttempted = true
        withContext(Dispatchers.IO) {
            runCatching {
                val text = app.get(REMOTE_STATIONS_JSON_URL).text
                val arr = JSONArray(text)
                val parsed = ArrayList<RadioStationPreset>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name").trim()
                    val url = obj.optString("streamUrl").trim()
                    if (name.isBlank() || !isValidStreamUrl(url)) continue
                    parsed.add(
                        RadioStationPreset(
                            id = obj.optString("id").ifBlank { "remote_${url.hashCode()}" },
                            name = name,
                            genre = obj.optString("genre").ifBlank { "Radio" },
                            country = obj.optString("country").ifBlank { "INT" },
                            streamUrl = url,
                            isCustom = false
                        )
                    )
                }
                if (parsed.isNotEmpty()) {
                    setKey(REMOTE_STATIONS_CACHE_KEY, parsed.toTypedArray())
                }
            }
        }
    }

    fun isValidStreamUrl(url: String): Boolean {
        val trimmed = url.trim()
        return (trimmed.startsWith("https://", ignoreCase = true) ||
            trimmed.startsWith("http://", ignoreCase = true)) &&
            trimmed.length > 10 &&
            !trimmed.contains(' ')
    }

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
        val cleanCountry = country.trim().uppercase().take(4).ifBlank { "WEB" }
        val id = existingId ?: "custom_${trimmedUrl.hashCode()}"
        val preset = RadioStationPreset(
            id = id,
            name = trimmedName,
            genre = genre.trim().ifBlank { fallbackGenre },
            country = cleanCountry,
            streamUrl = trimmedUrl,
            isCustom = true
        )
        val existing = customStations().toMutableList()
        existing.removeAll { it.id == id || it.streamUrl.equals(trimmedUrl, ignoreCase = true) }
        existing.add(0, preset)
        runCatching { setKey(CUSTOM_STATIONS_KEY, existing.take(40).toTypedArray()) }
        return preset
    }

    fun deleteCustomStation(stationIdOrUrl: String) {
        val existing = customStations().toMutableList()
        existing.removeAll {
            it.id == stationIdOrUrl || it.streamUrl.equals(stationIdOrUrl, ignoreCase = true)
        }
        runCatching { setKey(CUSTOM_STATIONS_KEY, existing.toTypedArray()) }
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

    fun playStation(context: Context, preset: RadioStationPreset) {
        val appContext = context.applicationContext
        val exo = getOrCreatePlayer(appContext)
        _state.value = RadioPlaybackState(
            station = preset,
            isPlaying = false,
            isBuffering = true,
            hasError = false,
            sleepTimerMinutes = activeSleepMinutes
        )
        runCatching {
            val metadata = MediaMetadata.Builder()
                .setTitle(preset.name)
                .setArtist(preset.genre)
                .setStation(preset.name)
                .build()
            val mediaItem = MediaItem.Builder()
                .setMediaId(preset.id)
                .setUri(preset.streamUrl)
                .setMediaMetadata(metadata)
                .build()
            exo.stop()
            exo.setMediaItem(mediaItem)
            exo.prepare()
            exo.playWhenReady = true
            // Start Media3 session service so notification & lockscreen transport stay active (RAD-01)
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

    fun playDirectResponse(context: Context, response: DirectRadioSearchResponse) {
        val fallbackGenre = context.getString(R.string.universe_radio_live_genre_default)
        val preset = allPresetStations().firstOrNull { it.streamUrl == response.url }
            ?: RadioStationPreset(
                id = response.url.hashCode().toString(),
                name = response.name,
                genre = response.genre.ifBlank { fallbackGenre },
                country = response.country,
                streamUrl = response.url
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
