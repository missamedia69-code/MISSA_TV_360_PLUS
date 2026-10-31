package com.lagradost.cloudstream3.ui.search

import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.download.DOWNLOAD_ACTION_PLAY_FILE
import com.lagradost.cloudstream3.ui.download.DownloadButtonSetup.handleDownloadClick
import com.lagradost.cloudstream3.ui.download.DownloadClickEvent
import com.lagradost.cloudstream3.ui.result.START_ACTION_LOAD_EP
import com.lagradost.cloudstream3.ui.universe.DirectMusicPlayerManager
import com.lagradost.cloudstream3.ui.universe.DirectMusicSearchResponse
import com.lagradost.cloudstream3.ui.universe.DirectRadioPlayerManager
import com.lagradost.cloudstream3.ui.universe.DirectRadioSearchResponse
import com.lagradost.cloudstream3.ui.universe.MissaMusicProviders
import com.lagradost.cloudstream3.ui.universe.MissaUniverses
import com.lagradost.cloudstream3.ui.universe.MusicItemKind
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.downloader.DownloadObjects

object SearchHelper {
    fun handleSearchClickCallback(callback: SearchClickCallback) {
        val card = callback.card
        val ctx = callback.view.context ?: activity

        if (card is DirectMusicSearchResponse) {
            if (callback.action == SEARCH_ACTION_SHOW_METADATA) {
                val trackKey = card.trackId.ifBlank { "music_${card.url.hashCode()}" }
                val nowFav = DirectMusicPlayerManager.toggleFavorite(trackKey)
                showToast(
                    if (nowFav) R.string.universe_radio_favorite_toggle else R.string.universe_retro_deleted,
                    Toast.LENGTH_SHORT
                )
            } else if (ctx != null) {
                DirectMusicPlayerManager.playDirectResponse(ctx, card)
                if (card.itemKind == MusicItemKind.PLAYLIST ||
                    card.itemKind == MusicItemKind.ALBUM ||
                    MissaMusicProviders.isYouTubePlaylistOrAlbumScheme(card.url)
                ) {
                    (activity as? MainActivity)?.navigateToUniverseWithParentalGuard(
                        MissaUniverses.byId("music")
                    )
                }
            }
            return
        }

        if (card is DirectRadioSearchResponse) {
            if (callback.action == SEARCH_ACTION_SHOW_METADATA) {
                val stationKey = DirectRadioPlayerManager.allPresetStations()
                    .firstOrNull { it.streamUrl.equals(card.url, ignoreCase = true) }
                    ?.id
                    ?: "radio_${card.url.hashCode()}"
                val nowFav = DirectRadioPlayerManager.toggleFavorite(stationKey)
                showToast(
                    if (nowFav) R.string.universe_radio_favorite_toggle else R.string.universe_retro_deleted,
                    Toast.LENGTH_SHORT
                )
            } else if (ctx != null) {
                DirectRadioPlayerManager.playDirectResponse(ctx, card)
            }
            return
        }

        when (callback.action) {
            SEARCH_ACTION_LOAD -> {
                loadSearchResult(card)
            }

            SEARCH_ACTION_PLAY_FILE -> {
                if (card is DataStoreHelper.ResumeWatchingResult) {
                    val id = card.id
                    if (id == null) {
                        showToast(R.string.error_invalid_id, Toast.LENGTH_SHORT)
                    } else {
                        if (card.isFromDownload) {
                            handleDownloadClick(
                                DownloadClickEvent(
                                    DOWNLOAD_ACTION_PLAY_FILE,
                                    DownloadObjects.DownloadEpisodeCached(
                                        name = card.name,
                                        poster = card.posterUrl,
                                        episode = card.episode ?: 0,
                                        season = card.season,
                                        id = id,
                                        parentId = card.parentId ?: return,
                                        score = null,
                                        description = null,
                                        cacheTime = System.currentTimeMillis(),
                                    )
                                )
                            )
                        } else {
                            loadSearchResult(card, START_ACTION_LOAD_EP, id)
                        }
                    }
                } else {
                    handleSearchClickCallback(
                        SearchClickCallback(SEARCH_ACTION_LOAD, callback.view, -1, callback.card)
                    )
                }
            }

            SEARCH_ACTION_SHOW_METADATA -> {
                (activity as? MainActivity?)?.apply {
                    loadPopup(callback.card)
                } ?: kotlin.run {
                    showToast(callback.card.name, Toast.LENGTH_SHORT)
                }
            }
        }
    }
}