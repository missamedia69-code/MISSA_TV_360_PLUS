package com.lagradost.cloudstream3.ui.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy

/**
 * Nouvelles tentatives réseau (anti-coupures).
 *
 * Quand un morceau de vidéo n'arrive pas (réseau mobile qui décroche, serveur lent), le
 * lecteur standard réessaie 3 fois, puis abandonne la source : l'utilisateur voit une erreur
 * ou un changement de serveur, alors que la connexion revenait souvent quelques secondes plus
 * tard. Pendant ces nouvelles tentatives, la vidéo continue sur l'avance déjà reçue.
 *
 * Ici : 8 tentatives (12 pour un direct sans fin connue), espacées de 1 s, 2 s… jusqu'à 5 s,
 * soit une trentaine de secondes de patience avant d'abandonner. Les erreurs définitives
 * (fichier introuvable, accès refusé) ne sont pas réessayées, comme avant.
 */
@OptIn(UnstableApi::class)
object MissaLoadErrorPolicy : DefaultLoadErrorHandlingPolicy() {
    const val MIN_RETRIES = 8
    const val MIN_RETRIES_LIVE = 12

    override fun getMinimumLoadableRetryCount(dataType: Int): Int =
        maxOf(
            super.getMinimumLoadableRetryCount(dataType),
            if (dataType == C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE) MIN_RETRIES_LIVE else MIN_RETRIES
        )
}
