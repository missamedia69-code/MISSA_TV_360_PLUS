package com.lagradost.cloudstream3.ui.player

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat.getString
import androidx.navigation.NavOptions
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.actions.temp.CloudStreamPackage
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.safefile.SafeFile

object OfflinePlaybackHelper {
    /**
     * Pop any existing player off the nav back stack before pushing the new one,
     * keeping the stack flat (at most one player at a time). This prevents an
     * OOM when many files are opened in sequence via DownloadedPlayerActivity.
     */
    private val replacePlayerNavOptions = NavOptions.Builder()
        .setPopUpTo(R.id.navigation_player, inclusive = true, saveState = false)
        .build()

    fun playLink(activity: Activity, url: String) {
        activity.navigate(
            R.id.global_to_navigation_player, GeneratorPlayer.newInstance(
                LinkGenerator(
                    listOf(
                        BasicLink(url)
                    ), id = url.hashCode()
                ), 0
            ),
            replacePlayerNavOptions
        )
    }

    /**
     * Opens an **already resolved** direct stream in the standard video player.
     *
     * Used by the Kids universe (KIDS-02): YouTube Kids videos are played as videos, with a
     * title, instead of going through the Musiques audio player. `extract = false` keeps the
     * resolved YouTube url untouched (no extractor round-trip).
     */
    fun playMedia(activity: Activity, url: String, name: String? = null) {
        activity.navigate(
            R.id.global_to_navigation_player, GeneratorPlayer.newInstance(
                LinkGenerator(
                    listOf(
                        BasicLink(url, name)
                    ),
                    extract = false,
                    id = url.hashCode()
                ), 0
            ),
            replacePlayerNavOptions
        )
    }

    // See CloudStreamPackage
    fun playIntent(activity: Activity, intent: Intent?): Boolean {
        if (intent == null) return false
        val links = intent.getStringArrayExtra(CloudStreamPackage.LINKS_EXTRA)
            ?.mapNotNull { tryParseJson<CloudStreamPackage.MinimalVideoLink>(it) } ?: emptyList()
        if (links.isEmpty()) return false
        val subs = intent.getStringArrayExtra(CloudStreamPackage.SUBTITLE_EXTRA)
            ?.mapNotNull { tryParseJson<CloudStreamPackage.MinimalSubtitleLink>(it) } ?: emptyList()

        val id = intent.getIntExtra(CloudStreamPackage.ID_EXTRA, -1)
        //val title = intent.getStringExtra(CloudStreamPackage.TITLE_EXTRA) // unused
        val pos = intent.getLongExtra(CloudStreamPackage.POSITION_EXTRA, -1L)
        val dur = intent.getLongExtra(CloudStreamPackage.DURATION_EXTRA, -1L)

        if (id != -1 && pos != -1L) {
            val duration = if (dur != -1L) {
                dur
            } else DataStoreHelper.getViewPos(id)?.duration ?: pos
            DataStoreHelper.setViewPos(id, pos, duration)
        }

        activity.navigate(
            R.id.global_to_navigation_player, GeneratorPlayer.newInstance(
                MinimalLinkGenerator(
                    links,
                    subs,
                    if (id != -1) id else null,
                ), 0
            ),
            replacePlayerNavOptions
        )
        return true
    }

    fun playUri(activity: Activity, uri: Uri) {
        if (uri.scheme == "magnet") {
            playLink(activity, uri.toString())
            return
        }
        val name = SafeFile.fromUri(activity, uri)?.name()
        activity.navigate(
            R.id.global_to_navigation_player, GeneratorPlayer.newInstance(
                DownloadFileGenerator(
                    listOf(
                        ExtractorUri(
                            uri = uri,
                            name = name ?: getString(activity, R.string.downloaded_file),
                            // well not the same as a normal id, but we take it as users may want to
                            // play downloaded files and save the location
                            id = uri.lastPathSegment?.toLongOrNull()?.hashCode() ?: uri.lastPathSegment?.hashCode()
                        )
                    )
                ), 0
            ),
            replacePlayerNavOptions
        )
    }
}