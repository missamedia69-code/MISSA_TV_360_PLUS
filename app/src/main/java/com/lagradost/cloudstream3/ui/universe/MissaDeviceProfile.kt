package com.lagradost.cloudstream3.ui.universe

import android.app.ActivityManager
import android.content.Context
import com.lagradost.cloudstream3.CloudStreamApp

/**
 * Mode « appareil léger » : activé tout seul sur les téléviseurs et box Android qui ont peu
 * de mémoire (512 Mo ou 1 Go de RAM, très répandus).
 *
 * Sur ces appareils, ce qui fait ramer n'est pas un seul gros calcul mais l'addition de
 * petites dépenses : animations à chaque déplacement du sélecteur, plusieurs catalogues
 * téléchargés en même temps au démarrage, images décodées en couleurs 32 bits, lecteur vidéo
 * qui garde 30 s de vidéo déjà vue en mémoire... Ce profil les réduit toutes :
 *  - pas d'animation (sélecteur, cartes, carrousel immobile) ;
 *  - images en 16 bits (deux fois moins de mémoire) et sans fondu ;
 *  - catalogues mis à jour l'un après l'autre, quelques secondes après l'ouverture ;
 *  - moins de requêtes simultanées et moins de fiches détaillées chargées ;
 *  - lecteur vidéo limité au 720p, avec une mémoire tampon réduite.
 */
object MissaDeviceProfile {

    /** En dessous de cette mémoire totale, l'appareil est considéré comme « léger ». */
    private const val LOW_RAM_TOTAL_BYTES = 1_300L * 1024 * 1024

    @Volatile
    private var lowRam: Boolean? = null

    fun isLowRam(context: Context? = CloudStreamApp.context): Boolean {
        lowRam?.let { return it }
        val ctx = context ?: return false
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val info = ActivityManager.MemoryInfo()
        runCatching { am.getMemoryInfo(info) }
        val result = am.isLowRamDevice ||
            am.memoryClass <= 96 ||
            (info.totalMem in 1 until LOW_RAM_TOTAL_BYTES)
        lowRam = result
        return result
    }

    /** Nombre de tâches réseau simultanées : [normal] d'habitude, [light] sur appareil léger. */
    fun parallelism(normal: Int, light: Int): Int = if (isLowRam()) light else normal
}
