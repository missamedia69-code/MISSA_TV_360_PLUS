package com.lagradost.cloudstream3.ui.player

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Mémoire tampon « anti-coupures ».
 *
 * Après une coupure, le lecteur standard repart dès quelques secondes de vidéo reçues. Sur une
 * connexion irrégulière, cela donne des coupures à répétition : la vidéo repart, s'arrête deux
 * secondes plus tard, repart… C'est ce que l'on remarque le plus.
 *
 * Ici, chaque nouvelle coupure rapprochée demande un peu plus d'avance avant de repartir :
 * 2 s à la première, puis 5 s, puis 10 s si elles s'enchaînent (fenêtre de 3 minutes). La
 * vidéo s'arrête un peu plus longtemps une fois, mais ne hache plus. Pour un direct, l'attente
 * ne dépasse jamais la moitié du retard voulu sur le direct (règle du lecteur standard).
 *
 * Le reste (quantité gardée d'avance, démarrage) est celui de [DefaultLoadControl].
 */
@OptIn(UnstableApi::class)
class MissaLoadControl(
    minBufferMs: Int,
    maxBufferMs: Int,
    bufferForPlaybackMs: Int,
    targetBufferBytes: Int,
    prioritizeTimeOverSizeThresholds: Boolean,
    backBufferDurationMs: Int,
    retainBackBufferFromKeyframe: Boolean
) : DefaultLoadControl(
    DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    minBufferMs,
    DefaultLoadControl.DEFAULT_MIN_BUFFER_FOR_LOCAL_PLAYBACK_MS,
    maxBufferMs,
    DefaultLoadControl.DEFAULT_MAX_BUFFER_FOR_LOCAL_PLAYBACK_MS,
    bufferForPlaybackMs,
    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_FOR_LOCAL_PLAYBACK_MS,
    REBUFFER_STEPS_MS.first().coerceAtMost(minBufferMs),
    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_FOR_LOCAL_PLAYBACK_MS,
    targetBufferBytes,
    prioritizeTimeOverSizeThresholds,
    DefaultLoadControl.DEFAULT_PRIORITIZE_TIME_OVER_SIZE_THRESHOLDS_FOR_LOCAL_PLAYBACK,
    backBufferDurationMs,
    retainBackBufferFromKeyframe
) {
    private val maxStepUs = Util.msToUs(minBufferMs.toLong())

    /**
     * L'avance supplémentaire n'est demandée que si le chargement continue jusqu'à
     * [minBufferMs] quel que soit le poids de la vidéo (temps prioritaire sur la taille) :
     * sinon, sur un appareil léger, une vidéo très lourde pourrait remplir la mémoire avant
     * d'atteindre l'avance demandée et ne jamais repartir. Appareil léger : règle standard.
     */
    private val escalate = prioritizeTimeOverSizeThresholds

    /** Débuts des coupures récentes (horloge système, ms). */
    private val recentRebuffers = ArrayDeque<Long>()

    /** Vrai pendant une coupure en cours (de son début à la reprise). */
    private var inRebuffer = false

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        val standard = super.shouldStartPlayback(parameters)
        synchronized(recentRebuffers) {
            if (!parameters.rebuffering || !escalate) {
                // Démarrage, saut dans la vidéo : règle standard, ce n'est pas une coupure.
                inRebuffer = false
                return standard
            }
            val now = SystemClock.elapsedRealtime()
            if (!inRebuffer) {
                inRebuffer = true
                recentRebuffers.addLast(now)
            }
            while (recentRebuffers.isNotEmpty() && now - recentRebuffers.first() > WINDOW_MS) {
                recentRebuffers.removeFirst()
            }
            if (!standard) return false
            var neededUs = Util.msToUs(rebufferStepMs(recentRebuffers.size).toLong()).coerceAtMost(maxStepUs)
            if (parameters.targetLiveOffsetUs != C.TIME_UNSET) {
                neededUs = minOf(neededUs, parameters.targetLiveOffsetUs / 2)
            }
            val bufferedUs = Util.getPlayoutDurationForMediaDuration(
                parameters.bufferedDurationUs,
                parameters.playbackSpeed
            )
            val resume = neededUs <= 0 || bufferedUs >= neededUs
            if (resume) inRebuffer = false
            return resume
        }
    }

    companion object {
        /** Avance demandée avant de repartir, selon le nombre de coupures récentes. */
        val REBUFFER_STEPS_MS = intArrayOf(2_000, 5_000, 10_000)
        private const val WINDOW_MS = 3 * 60_000L

        /** Avance demandée pour la [count]-ième coupure des 3 dernières minutes (1 = la première). */
        fun rebufferStepMs(count: Int): Int =
            REBUFFER_STEPS_MS[(count - 1).coerceIn(0, REBUFFER_STEPS_MS.lastIndex)]
    }
}
