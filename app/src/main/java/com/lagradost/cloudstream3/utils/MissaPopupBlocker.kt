package com.lagradost.cloudstream3.utils

import android.content.Intent
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Bloqueur de pop-up des extensions.
 *
 * Certaines extensions ouvrent d'elles-mêmes des fenêtres (publicités, pages web, boîtes de
 * dialogue) pendant qu'elles chargent un catalogue ou une vidéo. On reconnaît ces appels à
 * leur origine : le code d'une extension (.cs3) est chargé par son propre chargeur de classes,
 * que l'application ne connaît pas. Si une demande d'ouverture de fenêtre vient de ce code,
 * elle est refusée, sauf si elle répond directement à un appui de l'utilisateur sur un bouton
 * de l'extension (réglages d'une extension, par exemple).
 *
 * Ce qui est bloqué :
 *  - ouverture du navigateur ou de la page web intégrée (openBrowser, openWebView) ;
 *  - lancement d'une autre application ou d'une page (Intent VIEW) ;
 *  - boîtes de dialogue et fenêtres flottantes ajoutées à l'écran.
 */
object MissaPopupBlocker {
    private const val TAG = "MissaPopupBlocker"

    private val appLoader: ClassLoader? = MissaPopupBlocker::class.java.classLoader
    private val known = ConcurrentHashMap<String, Boolean>()

    /** Nombre de fenêtres bloquées depuis le démarrage (journal). */
    @Volatile
    var blockedCount: Int = 0
        private set

    /** Vrai si cette classe appartient à l'application (et non à une extension). */
    private fun isAppClass(name: String): Boolean = known.getOrPut(name) {
        try {
            Class.forName(name, false, appLoader)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Vrai si l'appel en cours vient d'une extension sans appui direct de l'utilisateur
     * (aucun clic en cours dans la pile d'appels).
     */
    fun isUnwantedPopup(): Boolean {
        val stack = Thread.currentThread().stackTrace
        var fromExtension = false
        for (frame in stack) {
            val cls = frame.className
            // Un clic (ou la touche OK de la télécommande) en cours : demande de l'utilisateur.
            if (cls == "android.view.View" && frame.methodName == "performClick") return false
            if (!fromExtension && !isAppClass(cls)) fromExtension = true
        }
        return fromExtension
    }

    /** À appeler avant d'ouvrir une fenêtre : vrai = ne pas l'ouvrir. */
    fun block(what: String): Boolean {
        if (!isUnwantedPopup()) return false
        blockedCount++
        Log.i(TAG, "Fenêtre ouverte par une extension bloquée ($what) — total $blockedCount")
        return true
    }

    /** Intents qui affichent une page ou une autre application (publicités). */
    fun isPopupIntent(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.action != Intent.ACTION_VIEW) return false
        val scheme = intent.data?.scheme?.lowercase() ?: return false
        return scheme == "http" || scheme == "https" || scheme == "intent" || scheme == "market"
    }

    /** Vrai si cet Intent est une pop-up d'extension à refuser. */
    fun blockIntent(intent: Intent?): Boolean =
        isPopupIntent(intent) && block("lien ${intent?.data?.host}")

    /**
     * Enveloppe du gestionnaire de fenêtres d'une activité : les boîtes de dialogue et fenêtres
     * flottantes demandées par une extension ne sont jamais affichées. Les fenêtres refusées
     * sont mémorisées pour que leur fermeture ultérieure ne provoque pas d'erreur.
     */
    fun wrap(base: WindowManager): WindowManager = object : WindowManager by base {
        private val refused: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

        override fun addView(view: View, params: ViewGroup.LayoutParams?) {
            if (block("fenêtre")) {
                synchronized(refused) { refused.add(view) }
                return
            }
            base.addView(view, params)
        }

        override fun updateViewLayout(view: View, params: ViewGroup.LayoutParams?) {
            if (synchronized(refused) { view in refused }) return
            base.updateViewLayout(view, params)
        }

        override fun removeView(view: View) {
            if (synchronized(refused) { refused.remove(view) }) return
            base.removeView(view)
        }

        override fun removeViewImmediate(view: View) {
            if (synchronized(refused) { refused.remove(view) }) return
            base.removeViewImmediate(view)
        }
    }
}
