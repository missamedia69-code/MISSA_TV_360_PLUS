package com.lagradost.cloudstream3.ui.universe

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R

/**
 * Background & lockscreen Media3 session service for MISSA TV 360 Direct Radio (RAD-01).
 *
 * Connected via [androidx.media3.session.MediaController] (`bindService`) rather than manual
 * `startForegroundService()` so Media3's own notification manager promotes the service to
 * foreground only once the media notification is built — eliminating any risk of
 * `ForegroundServiceDidNotStartInTimeException` on Android 12–16.
 */
class MissaRadioMediaService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_missa_radio)
            }
        )
        setListener(object : Listener {
            override fun onForegroundServiceStartNotAllowedException() {
                // Gracefully keep in-app playback running if OS background restrictions apply.
            }
        })
        val player = DirectRadioPlayerManager.getOrCreatePlayer(this)
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val session = MediaSession.Builder(this, player)
            .setId("missa_tv_360_direct_radio")
            .setSessionActivity(sessionActivity)
            .build()
        mediaSession = session
        addSession(session)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            pauseAllPlayersAndStopSelf()
        }
    }

    @OptIn(UnstableApi::class)
    override fun onDestroy() {
        clearListener()
        mediaSession?.let { session ->
            runCatching { removeSession(session) }
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
