package com.lagradost.cloudstream3.utils

import android.content.Intent
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Bloqueur de pop-up des extensions.
 *
 * Certaines extensions ouvrent d'elles-mêmes des fenêtres (publicités, pages web)
 * pendant qu'elles chargent un catalogue ou une vidéo. On reconnaît ces appels à
 * leur origine : le code d'une extension (.cs3) est chargé par son propre chargeur de classes,
 * que l'application ne connaît pas. Si une demande d'ouverture de fenêtre vient de ce code,
 * elle est refusée, sauf si elle répond directement à un appui de l'utilisateur sur un bouton
 * de l'extension (réglages d'une extension, par exemple).
 *
 * Ce qui est bloqué :
 *  - ouverture du navigateur ou de la page web intégrée (openBrowser, openWebView) ;
 *  - lancement d'une autre application ou d'une page (Intent VIEW).
 *
 * Les boîtes de dialogue ne sont pas filtrées : remplacer le gestionnaire de fenêtres de
 * l'activité (version 4.8.2) cassait le démarrage (écran noir), car une copie ne transmet
 * pas les fonctions « par défaut » d'Android comme la mesure de l'écran.
 * En cas d'erreur, le bloqueur laisse passer (il ne doit jamais gêner l'application).
 */
object MissaPopupBlocker {
    private const val TAG = "MissaPopupBlocker"

    private val appLoader: ClassLoader? = MissaPopupBlocker::class.java.classLoader
    private val known = ConcurrentHashMap<String, Boolean>()

    /** Nombre de fenêtres bloquées depuis le démarrage (journal). */
    @Volatile
    var blockedCount: Int = 0
        private set

    /**
     * Code du système ou du moteur web (WebView, chargé à part) : jamais une extension,
     * même si l'application ne peut pas charger la classe elle-même.
     */
    private val systemPrefixes = listOf(
        "android.", "androidx.", "com.android.", "java.", "javax.", "dalvik.", "libcore.",
        "sun.", "kotlin.", "kotlinx.", "org.chromium.", "com.google.", "org.json.", "org.apache."
    )

    /** Vrai si cette classe appartient à l'application ou au système (et non à une extension). */
    private fun isAppClass(name: String): Boolean = known.getOrPut(name) {
        if (systemPrefixes.any { name.startsWith(it) }) return@getOrPut true
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
    fun isUnwantedPopup(): Boolean = try {
        fromExtensionWithoutClick()
    } catch (_: Throwable) {
        false
    }

    private fun fromExtensionWithoutClick(): Boolean {
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
}
