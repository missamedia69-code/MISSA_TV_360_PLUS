package com.lagradost.cloudstream3.ui.universe

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.util.Locale

/**
 * Écran de jeu de l'univers Jeux : une WebView plein écran qui ouvre `lanceur.html`
 * (EmulatorJS) depuis les fichiers installés par [MissaGames], sans connexion.
 *
 * Les fichiers locaux sont servis sous https://appassets.androidplatform.net/ (adresse
 * réservée par Android à ce besoin) : la page a une vraie origine HTTPS, ce qui permet à
 * EmulatorJS de charger ses cœurs WebAssembly et de garder les sauvegardes, sans ouvrir
 * l'accès aux fichiers (`file://`). Aucune autre adresse ne peut être chargée.
 *
 * Commandes :
 *  - télécommande : flèches = croix, OK = A, Retour = B, Retour maintenu = menu pause,
 *    Menu / Lecture = START ;
 *  - manette : boutons et croix transmis tels quels, bouton Home/Mode = menu pause ;
 *  - téléphone et tablette : manette tactile d'EmulatorJS, Retour = menu pause.
 */
class GamePlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SYSTEM = "missa_game_system"
        private const val EXTRA_ROM_PATH = "missa_game_rom_path"
        private const val EXTRA_TITLE = "missa_game_title"
        private const val EXTRA_ID = "missa_game_id"

        internal const val LOCAL_HOST = "appassets.androidplatform.net"
        private const val BACK_HOLD_MS = 550L

        // Index des boutons (convention libretro, voir lanceur.html).
        internal const val BTN_B = 0
        internal const val BTN_Y = 1
        internal const val BTN_SELECT = 2
        internal const val BTN_START = 3
        internal const val BTN_UP = 4
        internal const val BTN_DOWN = 5
        internal const val BTN_LEFT = 6
        internal const val BTN_RIGHT = 7
        internal const val BTN_A = 8
        internal const val BTN_X = 9
        internal const val BTN_L = 10
        internal const val BTN_R = 11

        fun intent(context: Context, game: MissaGames.Game): Intent =
            Intent(context, GamePlayerActivity::class.java)
                .putExtra(EXTRA_SYSTEM, game.system)
                .putExtra(EXTRA_ROM_PATH, MissaGames.gameWebPath(game))
                .putExtra(EXTRA_TITLE, game.title)
                .putExtra(EXTRA_ID, game.id)

        /** Touche Android → bouton de la console, ou null si la touche n'est pas un bouton de jeu. */
        internal fun buttonFor(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> BTN_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> BTN_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> BTN_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> BTN_RIGHT
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> BTN_A
            KeyEvent.KEYCODE_BUTTON_B -> BTN_B
            KeyEvent.KEYCODE_BUTTON_X -> BTN_X
            KeyEvent.KEYCODE_BUTTON_Y -> BTN_Y
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> BTN_START
            KeyEvent.KEYCODE_BUTTON_SELECT -> BTN_SELECT
            KeyEvent.KEYCODE_BUTTON_L1 -> BTN_L
            KeyEvent.KEYCODE_BUTTON_R1 -> BTN_R
            else -> null
        }

        /** Type MIME d'un fichier servi à la WebView. */
        internal fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "html", "htm" -> "text/html"
            "js" -> "application/javascript"
            "css" -> "text/css"
            "json" -> "application/json"
            "wasm" -> "application/wasm"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            else -> "application/octet-stream"
        }

        /**
         * Fichier local correspondant à une adresse demandée par la page, ou null si elle sort
         * des dossiers autorisés (`ejs/` et `roms/`). Fonction pure, testée en JVM.
         */
        internal fun resolveLocalFile(root: File, path: String): File? {
            val decoded = runCatching { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
            val clean = decoded.trimStart('/')
            val base = root.canonicalFile
            val file = File(base, clean).canonicalFile
            // Le fichier final (après résolution des « .. ») doit rester dans ejs/ ou roms/.
            val allowed = listOf(File(base, "ejs"), File(base, "roms")).map { it.path + File.separator }
            return file.takeIf { f -> allowed.any { f.path.startsWith(it) } && f.isFile }
        }
    }

    private lateinit var webView: WebView
    private lateinit var loadingView: TextView
    private lateinit var hintView: TextView
    private var touchMode = false
    private var gameStarted = false
    private var pauseDialog: AlertDialog? = null

    private var backDownAt = 0L
    private var backMenuOpened = false
    private val heldDpad = HashSet<Int>()

    private val phoneBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            showPauseMenu()
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        touchMode = !isLayout(TV or EMULATOR)

        val system = intent.getStringExtra(EXTRA_SYSTEM).orEmpty()
        val romPath = intent.getStringExtra(EXTRA_ROM_PATH).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        if (system.isBlank() || romPath.isBlank()) {
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        webView = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        loadingView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            text = getString(R.string.universe_games_loading, title)
        }
        hintView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setBackgroundColor(0xCC10131A.toInt())
            setPadding(28, 16, 28, 16)
            text = getString(if (touchMode) R.string.universe_games_hint_touch else R.string.universe_games_hint_tv)
            isVisible = false
        }
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(loadingView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(hintView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = 48 })
        setContentView(root)
        enterImmersiveMode()

        val gamesRoot = MissaGames.rootDir(this)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // sauvegardes d'EmulatorJS (IndexedDB / localStorage)
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.addJavascriptInterface(AndroidBridge(), "MissaAndroid")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Rien d'autre que la page du jeu ne s'ouvre dans cet écran.
                return request.url.host != LOCAL_HOST
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != LOCAL_HOST) {
                    // Le jeu tourne hors ligne : aucune requête vers Internet n'est faite.
                    return WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), null)
                }
                val file = resolveLocalFile(gamesRoot, url.path.orEmpty())
                    ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), null)
                return WebResourceResponse(
                    mimeFor(file.name), null, 200, "OK",
                    mapOf("Access-Control-Allow-Origin" to "https://$LOCAL_HOST", "Cache-Control" to "no-store"),
                    FileInputStream(file)
                )
            }
        }

        if (touchMode) onBackPressedDispatcher.addCallback(this, phoneBackCallback)

        val query = Uri.Builder()
            .scheme("https").authority(LOCAL_HOST).path("ejs/lanceur.html")
            .appendQueryParameter("system", system)
            .appendQueryParameter("rom", "/$romPath")
            .appendQueryParameter("name", title)
            .appendQueryParameter("id", id)
            .appendQueryParameter("touch", if (touchMode) "1" else "0")
            .build()
        webView.loadUrl(query.toString())
        webView.requestFocus()
    }

    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    private inner class AndroidBridge {
        @JavascriptInterface
        fun onGameStarted() {
            runOnUiThread {
                gameStarted = true
                loadingView.isVisible = false
                hintView.isVisible = true
                hintView.postDelayed({ hintView.isVisible = false }, 6_000)
            }
        }
    }

    private fun js(code: String) {
        if (::webView.isInitialized) webView.evaluateJavascript("window.MissaGame && $code;", null)
    }

    private fun press(button: Int, down: Boolean) = js("MissaGame.press($button, ${if (down) 1 else 0})")
    private fun tap(button: Int) = js("MissaGame.tap($button)")

    private fun isFromGamepad(event: KeyEvent): Boolean {
        val src = event.source
        return (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    // Sur TV, la touche Retour de la télécommande sert de bouton B (appui court) et ouvre le
    // menu pause (appui maintenu). Les télécommandes envoient bien KEYCODE_BACK : le geste
    // « retour » prédictif ne concerne que les téléphones, traités par OnBackPressedCallback.
    @SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!gameStarted || pauseDialog?.isShowing == true) return super.dispatchKeyEvent(event)
        val code = event.keyCode

        if (code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (event.action == KeyEvent.ACTION_UP) showPauseMenu()
            return true
        }

        if (code == KeyEvent.KEYCODE_BACK && !touchMode) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (event.repeatCount == 0) {
                        backDownAt = SystemClock.uptimeMillis()
                        backMenuOpened = false
                    } else if (!backMenuOpened && SystemClock.uptimeMillis() - backDownAt >= BACK_HOLD_MS) {
                        backMenuOpened = true
                        showPauseMenu()
                    }
                }
                KeyEvent.ACTION_UP -> if (!backMenuOpened) {
                    if (isFromGamepad(event)) press(BTN_B, false) else tap(BTN_B)
                }
            }
            return true
        }

        val button = buttonFor(code) ?: return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) press(button, true)
            KeyEvent.ACTION_UP -> press(button, false)
        }
        return true
    }

    /** Stick analogique ou croix « HAT » d'une manette → croix directionnelle. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val isJoystick = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
        if (!gameStarted || !isJoystick || event.action != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }
        val x = event.getAxisValue(MotionEvent.AXIS_HAT_X).takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_X)
        val y = event.getAxisValue(MotionEvent.AXIS_HAT_Y).takeIf { it != 0f } ?: event.getAxisValue(MotionEvent.AXIS_Y)
        setDpad(BTN_LEFT, x < -0.5f)
        setDpad(BTN_RIGHT, x > 0.5f)
        setDpad(BTN_UP, y < -0.5f)
        setDpad(BTN_DOWN, y > 0.5f)
        return true
    }

    private fun setDpad(button: Int, down: Boolean) {
        if (down && heldDpad.add(button)) press(button, true)
        else if (!down && heldDpad.remove(button)) press(button, false)
    }

    private fun showPauseMenu() {
        if (pauseDialog?.isShowing == true) return
        js("MissaGame.pause()")
        val actions = listOf(
            R.string.universe_games_menu_resume to { },
            R.string.universe_games_menu_save to { js("MissaGame.quickSave()") },
            R.string.universe_games_menu_load to { js("MissaGame.quickLoad()") },
            R.string.universe_games_menu_start to { tap(BTN_START) },
            R.string.universe_games_menu_select to { tap(BTN_SELECT) },
            R.string.universe_games_menu_restart to { js("MissaGame.restart()") },
            R.string.universe_games_menu_quit to { quitGame() }
        )
        var quitting = false
        pauseDialog = AlertDialog.Builder(this)
            .setTitle(R.string.universe_games_menu_title)
            .setItems(actions.map { getString(it.first) }.toTypedArray()) { _, which ->
                if (actions[which].first == R.string.universe_games_menu_quit) quitting = true
                actions[which].second()
            }
            .setOnDismissListener {
                if (!quitting) {
                    js("MissaGame.resume()")
                    webView.requestFocus()
                }
            }
            .show()
    }

    private fun quitGame() {
        js("MissaGame.saveGame()")
        // Laisse le temps à EmulatorJS d'écrire la sauvegarde avant de fermer.
        webView.postDelayed({ finish() }, 400)
    }

    override fun onPause() {
        js("MissaGame.saveGame()")
        js("MissaGame.pause()")
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (pauseDialog?.isShowing != true) js("MissaGame.resume()")
    }

    override fun onDestroy() {
        pauseDialog?.dismiss()
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }
}
