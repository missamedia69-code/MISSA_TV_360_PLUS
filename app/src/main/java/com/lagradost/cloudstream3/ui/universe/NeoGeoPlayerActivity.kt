package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.hardware.input.InputManager
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CommonActivity.updateLocale
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.LibretroDroid
import com.swordfish.libretrodroid.ShaderConfig
import com.swordfish.libretrodroid.Variable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.Date
import java.text.DateFormat
import android.widget.Toast

/**
 * Écran de jeu Neo Geo : moteur natif Geolith (via LibretroDroid), plein écran, hors ligne.
 *
 * Le moteur, le BIOS et le jeu ont été téléchargés et vérifiés par [MissaGames]. Rien ne
 * passe par une WebView : c'est ce qui permet de tenir 60 images/s sur les petites TV.
 *
 * Commandes :
 *  - **manette** (Bluetooth ou à fil) : croix, stick gauche ou croix « HAT » = directions ;
 *    boutons bas / droite / gauche / haut = A / B / C / D ; L1-L2 ou Select = pièce,
 *    R1-R2 ou Start = START ; deux manettes = deux joueurs ;
 *  - **télécommande** : flèches = croix, OK = A, touches 1 2 3 4 = A B C D (ou retour
 *    rapide = B, avance rapide / chaîne + = C, chaîne - = D), 5 = pièce,
 *    0 / Lecture / Menu = START, Retour = menu pause ;
 *  - **téléphone / tablette** : manette tactile ([NeoGeoTouchPad]), Retour = menu pause.
 * Les sauvegardes du jeu (SRAM) sont gardées à la sortie et rechargées au lancement suivant.
 * Menu pause : « Sauvegarder ici » garde l'état exact de la partie (niveau, vies, position),
 * « Reprendre ma sauvegarde » y revient, même après avoir quitté le jeu.
 */
class NeoGeoPlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_ID = "missa_neogeo_game_id"
        private const val EXTRA_TITLE = "missa_neogeo_game_title"

        fun intent(context: Context, game: MissaGames.Game): Intent =
            Intent(context, NeoGeoPlayerActivity::class.java)
                .putExtra(EXTRA_ID, game.id)
                .putExtra(EXTRA_TITLE, game.title)

        /** Touche de télécommande -> touche de manette envoyée au jeu (null : non utilisée). */
        internal fun remoteToPad(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> keyCode
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> KeyEvent.KEYCODE_BUTTON_B // A
            // Télécommandes sans chiffres : touches de lecture et de chaîne pour B, C et D.
            KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> KeyEvent.KEYCODE_BUTTON_A // B
            KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PROG_YELLOW -> KeyEvent.KEYCODE_BUTTON_Y // C
            KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4,
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PROG_BLUE -> KeyEvent.KEYCODE_BUTTON_X // D
            KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_NUMPAD_5,
            KeyEvent.KEYCODE_PROG_RED -> KeyEvent.KEYCODE_BUTTON_SELECT // pièce
            KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_PROG_GREEN -> KeyEvent.KEYCODE_BUTTON_START
            else -> null
        }

        /**
         * Manette Bluetooth ou à fil : boutons physiques -> boutons Neo Geo, à la même place que
         * sur une manette d'arcade. Android nomme A le bouton du bas, B celui de droite, X celui
         * de gauche et Y celui du haut ; le cœur attend la disposition « RetroPad » (A et B, X et
         * Y inversés). Avant, la touche était transmise telle quelle : le bouton du bas donnait B.
         * Gâchettes : L1/L2 = pièce (beaucoup de manettes n'ont pas de Select), R1/R2 = START.
         */
        internal fun gamepadToPad(code: Int): Int? = when (code) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B // bas    -> A
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A // droite -> B
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y // gauche -> C
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X // haut   -> D
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2,
            KeyEvent.KEYCODE_BUTTON_SELECT -> KeyEvent.KEYCODE_BUTTON_SELECT
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_START -> KeyEvent.KEYCODE_BUTTON_START
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> code
            else -> null
        }
    }

    private var retroView: GLRetroView? = null
    private var touchPad: NeoGeoTouchPad? = null
    private lateinit var status: TextView
    private var game: MissaGames.Game? = null
    private var sramFile: File? = null
    /** Sauvegarde rapide (état complet de la partie), une par jeu. */
    private var stateFile: File? = null
    private var started = false

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = showPauseMenu()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Thème et langue choisis dans l'application (sinon, messages dans la langue du système).
        CommonActivity.loadThemes(this)
        updateLocale()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onBackPressedDispatcher.addCallback(this, backCallback)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            text = getString(R.string.neogeo_starting, intent.getStringExtra(EXTRA_TITLE).orEmpty())
        }
        setContentView(root)
        enterImmersiveMode()

        val catalogue = MissaGames.cachedCatalogue(this)
        val g = catalogue?.games?.firstOrNull { it.id == intent.getStringExtra(EXTRA_ID) }
        val engine = catalogue?.engineForDevice()
        val bios = catalogue?.requiredBios
        if (catalogue == null || g == null || engine == null || bios == null ||
            !MissaGames.isGameInstalled(this, catalogue, g)
        ) {
            fail(getString(R.string.neogeo_not_installed))
            root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            return
        }
        game = g

        // Le moteur a été téléchargé et vérifié (SHA-256) par MissaGames, puis mis en lecture seule.
        try {
            LibretroDroid.loadNative(MissaGames.frontendFile(this, engine).absolutePath)
        } catch (e: Throwable) {
            root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            fail(getString(R.string.neogeo_engine_error))
            return
        }

        val lowRam = MissaDeviceProfile.isLowRam(this)
        val saves = MissaGames.savesDir(this)
        sramFile = File(saves, g.id + ".srm")
        stateFile = File(saves, g.id + ".state")
        val data = GLRetroViewData(this).apply {
            coreFilePath = MissaGames.coreFile(this@NeoGeoPlayerActivity, engine).absolutePath
            gameFilePath = MissaGames.gameFile(this@NeoGeoPlayerActivity, g).absolutePath
            systemDirectory = MissaGames.biosDir(this@NeoGeoPlayerActivity).absolutePath
            savesDirectory = saves.absolutePath
            variables = arrayOf(
                Variable("geolith_system_type", catalogue.mode),
                // Mode arcade : parties gratuites, pas besoin d'insérer des pièces.
                Variable("geolith_freeplay", "on"),
                Variable("geolith_memcard", "on")
            )
            saveRAMState = sramFile?.takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
            // Petits appareils : pas de filtre d'image ni de latence audio minimale (moins de CPU).
            shader = if (lowRam) ShaderConfig.Default else ShaderConfig.Sharp
            skipDuplicateFrames = lowRam
            preferLowLatencyAudio = !lowRam
            rumbleEventsEnabled = false
        }
        val view = GLRetroView(this, data)
        retroView = view
        lifecycle.addObserver(view)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        view.requestFocus()

        val touchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        if (touchscreen && !isLayout(TV or EMULATOR)) {
            val pad = NeoGeoTouchPad(this) { key, pressed ->
                retroView?.sendKeyEvent(if (pressed) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, key, 0)
            }
            touchPad = pad
            root.addView(pad, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Manette déjà branchée : pas de manette tactile par-dessus le jeu.
            if (hasGamepad()) pad.isVisible = false
        }
        root.addView(status, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        lifecycleScope.launch {
            view.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.FrameRendered }
            started = true
            status.isVisible = false
        }
        lifecycleScope.launch {
            val code = view.getGLRetroErrors().first()
            fail(
                when (code) {
                    GLRetroView.ERROR_LOAD_GAME -> getString(R.string.neogeo_load_error, bios.fileName)
                    GLRetroView.ERROR_LOAD_LIBRARY -> getString(R.string.neogeo_engine_error)
                    GLRetroView.ERROR_GL_NOT_COMPATIBLE -> getString(R.string.neogeo_gl_error)
                    else -> getString(R.string.neogeo_generic_error, code)
                }
            )
        }
    }

    private fun fail(message: String) {
        // Le message est dans la fenêtre : pas de doublon derrière elle.
        status.isVisible = false
        status.text = message
        AlertDialog.Builder(this)
            .setTitle(R.string.universe_games_title)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .show()
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
        if (hasFocus) enterImmersiveMode() else touchPad?.releaseAll()
    }

    private fun isFromGamepad(event: KeyEvent): Boolean {
        val src = event.source
        return (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val view = retroView ?: return super.dispatchKeyEvent(event)
        val code = event.keyCode
        // Bouton Home / Mode de la manette : menu pause, comme Retour.
        if (code == KeyEvent.KEYCODE_BUTTON_MODE || code == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return super.dispatchKeyEvent(event)
        }
        // Manette : on transmet la touche telle quelle (A/B/X/Y, croix, Start, Select...).
        if (isFromGamepad(event) && (KeyEvent.isGamepadButton(code) || code in DPAD_KEYS)) {
            // Vraie manette utilisée : la manette tactile ne sert plus et cache le jeu.
            hideTouchPad()
            val mapped = gamepadToPad(code)
            if (mapped != null && event.repeatCount == 0) {
                view.sendKeyEvent(event.action, mapped, playerPort(event.device))
            }
            return true
        }
        // Télécommande ou clavier.
        val mapped = remoteToPad(code) ?: return super.dispatchKeyEvent(event)
        if (event.repeatCount == 0) view.sendKeyEvent(event.action, mapped, 0)
        return true
    }

    /** Deux manettes = deux joueurs (Neo Geo : jeux de combat à deux). Tactile et télécommande : joueur 1. */
    private fun playerPort(device: InputDevice?): Int {
        val n = device?.controllerNumber ?: 0
        return if (n >= 2) 1 else 0
    }

    /** Directions envoyées par le stick ou la croix « HAT » de chaque manette (par joueur). */
    private val motionDirections = HashMap<Int, Set<Int>>()

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val view = retroView ?: return super.dispatchGenericMotionEvent(event)
        val src = event.source
        val isJoystick = (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        if (!isJoystick || event.actionMasked != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }
        // Croix « HAT » (beaucoup de manettes Bluetooth) ou stick gauche, au-delà de la moitié
        // de sa course : le Neo Geo n'a qu'une croix, le stick la remplace.
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val stickX = event.getAxisValue(MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(MotionEvent.AXIS_Y)
        val x = if (kotlin.math.abs(hatX) > 0.5f) hatX else stickX
        val y = if (kotlin.math.abs(hatY) > 0.5f) hatY else stickY
        val wanted = buildSet {
            if (x < -0.5f) add(KeyEvent.KEYCODE_DPAD_LEFT)
            if (x > 0.5f) add(KeyEvent.KEYCODE_DPAD_RIGHT)
            if (y < -0.5f) add(KeyEvent.KEYCODE_DPAD_UP)
            if (y > 0.5f) add(KeyEvent.KEYCODE_DPAD_DOWN)
        }
        val port = playerPort(event.device)
        val before = motionDirections[port].orEmpty()
        (before - wanted).forEach { view.sendKeyEvent(KeyEvent.ACTION_UP, it, port) }
        (wanted - before).forEach { view.sendKeyEvent(KeyEvent.ACTION_DOWN, it, port) }
        motionDirections[port] = wanted
        if (wanted.isNotEmpty()) hideTouchPad()
        return true
    }

    private fun hideTouchPad() {
        touchPad?.let { if (it.isVisible) { it.releaseAll(); it.isVisible = false } }
    }

    private fun hasGamepad(): Boolean = InputDevice.getDeviceIds().any { id ->
        val d = InputDevice.getDevice(id) ?: return@any false
        val s = d.sources
        !d.isVirtual && ((s and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (s and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
    }

    /** Manette débranchée ou éteinte : la manette tactile revient. */
    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) {}
        override fun onInputDeviceRemoved(deviceId: Int) {
            if (!hasGamepad()) touchPad?.isVisible = true
        }
    }

    override fun onStart() {
        super.onStart()
        (getSystemService(INPUT_SERVICE) as? InputManager)?.registerInputDeviceListener(inputListener, null)
    }

    override fun onStop() {
        (getSystemService(INPUT_SERVICE) as? InputManager)?.unregisterInputDeviceListener(inputListener)
        super.onStop()
    }

    private val DPAD_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT
    )

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_MODE) {
            showPauseMenu()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun showPauseMenu() {
        val view = retroView
        if (view == null || !started) {
            finish()
            return
        }
        touchPad?.releaseAll()
        // Pause : le jeu avance image par image dans le rendu GL ; arrêter le rendu l'arrête.
        view.audioEnabled = false
        view.onPause()
        val saved = stateFile?.takeIf { it.isFile && it.length() > 0 }
        val options = buildList<Pair<String, () -> Unit>> {
            add(getString(R.string.neogeo_resume) to { })
            // Rendu arrêté (onPause ci-dessus) : l'état se lit et s'écrit directement, sans
            // attendre le fil du jeu (même principe que la SRAM dans saveSram).
            add(getString(R.string.neogeo_save_state) to { saveState() })
            if (saved != null) {
                val whenText = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(saved.lastModified()))
                add(getString(R.string.neogeo_load_state, whenText) to { loadState(saved) })
            }
            // La réinitialisation passe par le fil du jeu : il doit tourner à nouveau.
            add(getString(R.string.neogeo_restart) to {
                view.onResume()
                view.audioEnabled = true
                view.reset()
            })
            add(getString(R.string.neogeo_controls) to { showControls() })
            add(getString(R.string.neogeo_quit) to { quitGame() })
        }
        AlertDialog.Builder(this)
            .setTitle(game?.title)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .setOnDismissListener {
                if (!isFinishing) {
                    view.onResume()
                    view.audioEnabled = true
                    view.requestFocus()
                }
            }
            .show()
    }

    private fun saveState() {
        val view = retroView ?: return
        val file = stateFile ?: return
        val ok = runCatching {
            val bytes = view.serializeState(useEmulationThread = false)
            if (bytes.isEmpty()) return@runCatching false
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file)
        }.getOrDefault(false)
        Toast.makeText(this, if (ok) R.string.neogeo_state_saved else R.string.neogeo_state_error, Toast.LENGTH_SHORT).show()
    }

    private fun loadState(file: File) {
        val view = retroView ?: return
        val ok = runCatching {
            view.unserializeState(file.readBytes(), useEmulationThread = false)
        }.getOrDefault(false)
        Toast.makeText(this, if (ok) R.string.neogeo_state_loaded else R.string.neogeo_state_error, Toast.LENGTH_SHORT).show()
    }

    private fun showControls() {
        AlertDialog.Builder(this)
            .setTitle(R.string.neogeo_controls)
            .setMessage(R.string.neogeo_controls_text)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * Garde la sauvegarde du jeu. Le rendu GL est d'abord arrêté (GLSurfaceView.onPause attend
     * que le fil du jeu soit à l'arrêt) : la lecture de la SRAM se fait alors directement, sans
     * attendre un fil suspendu (ce qui bloquerait l'écran).
     */
    private fun saveSram() {
        val view = retroView ?: return
        val file = sramFile ?: return
        if (!started) return
        view.onPause()
        runCatching {
            val bytes = view.serializeSRAM(useEmulationThread = false)
            if (bytes.isNotEmpty()) {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(file)
            }
        }
    }

    private fun quitGame() {
        saveSram()
        finish()
    }

    override fun onPause() {
        saveSram()
        super.onPause()
    }
}
