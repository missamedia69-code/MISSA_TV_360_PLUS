package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import dev.kotlinds.libretrokmp.Device
import dev.kotlinds.libretrokmp.JoypadButton
import dev.kotlinds.libretrokmp.LibretroCore
import dev.kotlinds.libretrokmp.LibretroFrontend
import dev.kotlinds.libretrokmp.VideoFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Caps bytes read from the network stream, including when the server omits Content-Length.
 */
private class LimitedInputStream(
    input: InputStream,
    private val maxBytes: Long
) : FilterInputStream(input) {
    private var bytesRead = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) {
            bytesRead++
            check(bytesRead <= maxBytes) { "Core archive exceeded the permitted size" }
        }
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val remainingPlusOne = (maxBytes - bytesRead + 1L).coerceAtLeast(1L)
        val boundedLength = minOf(length.toLong(), remainingPlusOne).toInt()
        val count = super.read(buffer, offset, boundedLength)
        if (count > 0) {
            bytesRead += count
            check(bytesRead <= maxBytes) { "Core archive exceeded the permitted size" }
        }
        return count
    }
}

/**
 * Fullscreen immersive Libretro player for MISSA TV 360 PLUS (GAME-03, GAME-04, GAME-05).
 *
 * Key upgrades:
 * - Fullscreen aspect-preserving display with semi-transparent floating touch overlay
 *   (auto-hidden on Android TV or when a Bluetooth/USB gamepad is connected).
 * - Responsive 8-way multi-touch D-pad ([MissaRetroDpadView]) + >=48dp face/shoulder buttons
 *   with haptic feedback, accessibility `performClick()`, and customizable opacity & scale.
 * - Strict single-thread `LibretroCore` execution via a dedicated [HandlerThread] so
 *   `loadGame`, `run`, `saveState`, `loadState`, `reset`, and `close` always run on the same thread.
 * - Automatic session state persistence (`*.auto.state`) + manual Save/Load State & D-pad Pause Menu
 *   triggered by HUD Pause, `KEYCODE_MENU`, `Start + Select` chord, or `BACK`.
 */
class RetroGamePlayerActivity : AppCompatActivity() {

    data class CoreSpec(
        val systemLabel: String,
        val coreFileName: String,
        val extensions: Set<String>
    ) {
        val coreZipName: String get() = "$coreFileName.zip"
    }

    companion object {
        const val EXTRA_ROM_PATH = "missa_retro_rom_path"
        private const val PREFS_NAME = "missa_retro_player_prefs"
        private const val KEY_OVERLAY_OPACITY_IDX = "overlay_opacity_idx"
        private const val KEY_OVERLAY_SCALE_IDX = "overlay_scale_idx"

        private val OPACITY_LEVELS = floatArrayOf(0.38f, 0.62f, 0.86f)
        private val SCALE_LEVELS = floatArrayOf(0.90f, 1.00f, 1.14f)

        private const val MAX_CORE_BYTES = 24L * 1024L * 1024L
        private const val MAX_CORE_ARCHIVE_BYTES = 32L * 1024L * 1024L
        private const val MIN_CORE_BYTES = 32L * 1024L
        private const val MAX_ROM_BYTES = 64L * 1024L * 1024L
        private const val FALLBACK_FPS = 59.7275

        private val CORE_SPECS = listOf(
            CoreSpec(
                systemLabel = "Game Boy / GBC",
                coreFileName = "sameboy_libretro_android.so",
                extensions = setOf("gb", "gbc", "dmg", "sgb")
            ),
            CoreSpec(
                systemLabel = "NES",
                coreFileName = "fceumm_libretro_android.so",
                extensions = setOf("nes")
            ),
            CoreSpec(
                systemLabel = "Super Nintendo",
                coreFileName = "snes9x_libretro_android.so",
                extensions = setOf("sfc", "smc")
            ),
            CoreSpec(
                systemLabel = "Game Boy Advance",
                coreFileName = "mgba_libretro_android.so",
                extensions = setOf("gba")
            ),
            CoreSpec(
                systemLabel = "Sega Mega Drive / SMS",
                coreFileName = "genesis_plus_gx_libretro_android.so",
                extensions = setOf("md", "gen", "sms", "gg")
            )
        )

        fun resolveCoreSpec(extension: String): CoreSpec? {
            val clean = extension.lowercase(Locale.ROOT)
            return CORE_SPECS.firstOrNull { clean in it.extensions }
        }

        fun supportsExtension(extension: String): Boolean = resolveCoreSpec(extension) != null
    }

    private lateinit var romFile: File
    private lateinit var coreSpec: CoreSpec

    private lateinit var centerOverlay: View
    private lateinit var titleText: TextView
    private lateinit var gameNameText: TextView
    private lateinit var engineButton: MaterialButton
    private lateinit var pauseButton: MaterialButton
    private lateinit var exitButton: MaterialButton
    private lateinit var statusText: TextView
    private lateinit var gameFrame: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var touchControls: View
    private lateinit var dpadView: MissaRetroDpadView
    private lateinit var actionDiamond: View

    private var retroThread: HandlerThread? = null
    private var retroHandler: Handler? = null
    private var core: LibretroCore? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var emulatorRunning = false
    @Volatile private var destroyed = false
    private var frameIntervalMs = 16L
    private var frameIntervalNs = 16_742_706L
    private var nextFrameDeadlineNs = 0L

    private var currentBitmap: Bitmap? = null
    private var resumeAfterForeground = false
    private var activePauseDialog: AlertDialog? = null

    private var opacityIndex = 1
    private var scaleIndex = 1
    private var touchOverlayUserVisible = true

    private val touchDpadButtons: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())
    private val pressedButtons: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())
    private val axisPressedButtons: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

    private val frameTickRunnable = object : Runnable {
        override fun run() {
            val activeCore = core ?: return
            if (!emulatorRunning || destroyed) return
            try {
                activeCore.run()
            } catch (error: Throwable) {
                android.util.Log.e("MissaRetroEngine", "Emulation stopped", error)
                emulatorRunning = false
                runOnUiThread {
                    if (!destroyed) {
                        centerOverlay.isVisible = true
                        statusText.setText(R.string.universe_retro_player_failed)
                    }
                }
                return
            }
            val now = System.nanoTime()
            if (nextFrameDeadlineNs <= 0L) nextFrameDeadlineNs = now
            nextFrameDeadlineNs += frameIntervalNs
            val delayMs = ((nextFrameDeadlineNs - now) / 1_000_000L).coerceIn(1L, frameIntervalMs)
            if (now - nextFrameDeadlineNs > frameIntervalNs * 3) {
                nextFrameDeadlineNs = now
            }
            retroHandler?.postDelayed(this, delayMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        DirectRadioPlayerManager.stop()
        setContentView(R.layout.activity_retro_game_player)
        enterImmersiveFullscreen()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        opacityIndex = prefs.getInt(KEY_OVERLAY_OPACITY_IDX, 1).coerceIn(0, OPACITY_LEVELS.lastIndex)
        scaleIndex = prefs.getInt(KEY_OVERLAY_SCALE_IDX, 1).coerceIn(0, SCALE_LEVELS.lastIndex)

        centerOverlay = findViewById(R.id.retro_center_overlay)
        titleText = findViewById(R.id.retro_player_title)
        gameNameText = findViewById(R.id.retro_player_game_name)
        engineButton = findViewById(R.id.retro_player_engine_button)
        pauseButton = findViewById(R.id.retro_player_pause_button)
        exitButton = findViewById(R.id.retro_player_exit_button)
        statusText = findViewById(R.id.retro_player_status)
        gameFrame = findViewById(R.id.retro_game_frame)
        progress = findViewById(R.id.retro_player_progress)
        touchControls = findViewById(R.id.retro_touch_controls)
        dpadView = findViewById(R.id.retro_dpad_view)
        actionDiamond = findViewById(R.id.retro_action_diamond)

        val shouldShowTouchInitially = !isLayout(TV or EMULATOR) &&
            packageManager.hasSystemFeature("android.hardware.touchscreen") &&
            !hasPhysicalGamepad()
        touchOverlayUserVisible = shouldShowTouchInitially
        applyOverlayAppearance()

        pauseButton.setOnClickListener { showPauseDialog() }
        exitButton.setOnClickListener { confirmQuitOrFinish() }
        engineButton.setOnClickListener {
            if (coreFile().isFile) startGame() else downloadEngine()
        }
        wireTouchControls()

        // BACK opens the Pause Menu instead of abruptly killing an active game (GAME-05)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (core != null) {
                    showPauseDialog()
                } else {
                    finish()
                }
            }
        })

        val requestedPath = intent.getStringExtra(EXTRA_ROM_PATH)
        if (requestedPath.isNullOrBlank()) {
            statusText.setText(R.string.universe_retro_player_failed)
            return
        }
        romFile = File(requestedPath)
        val libraryDir = File(filesDir, "retro-games")
        val validPath = runCatching {
            romFile.isFile &&
                romFile.canonicalPath.startsWith(libraryDir.canonicalPath + File.separator) &&
                romFile.length() in 1L..MAX_ROM_BYTES
        }.getOrDefault(false)

        val resolvedSpec = resolveCoreSpec(romFile.extension)
        if (!validPath || resolvedSpec == null) {
            statusText.setText(R.string.universe_retro_player_failed)
            return
        }
        coreSpec = resolvedSpec
        titleText.text = getString(R.string.universe_retro_player_title_fmt, coreSpec.systemLabel)
        gameNameText.text = romFile.nameWithoutExtension.replace('_', ' ')

        if (Build.VERSION.SDK_INT < 24 || coreDownloadUrl() == null) {
            statusText.setText(R.string.universe_retro_engine_incompatible)
            return
        }

        if (coreFile().isFile && coreFile().length() >= MIN_CORE_BYTES) {
            startGame()
        } else {
            centerOverlay.isVisible = true
            statusText.text = getString(R.string.universe_retro_engine_required_fmt, coreSpec.systemLabel)
            engineButton.text = getString(R.string.universe_retro_install_engine_fmt, coreSpec.systemLabel)
            engineButton.isVisible = true
            engineButton.requestFocus()
        }
    }

    private fun enterImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun hasPhysicalGamepad(): Boolean {
        for (deviceId in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(deviceId) ?: continue
            if (!dev.isVirtual && (
                    (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                        (dev.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun applyOverlayAppearance() {
        touchControls.isVisible = touchOverlayUserVisible
        touchControls.alpha = OPACITY_LEVELS[opacityIndex]
        val scale = SCALE_LEVELS[scaleIndex]
        dpadView.scaleX = scale
        dpadView.scaleY = scale
        dpadView.pivotX = 0f
        dpadView.pivotY = dpadView.height.toFloat().takeIf { it > 0f } ?: 400f
        actionDiamond.scaleX = scale
        actionDiamond.scaleY = scale
        actionDiamond.pivotX = actionDiamond.width.toFloat().takeIf { it > 0f } ?: 400f
        actionDiamond.pivotY = actionDiamond.height.toFloat().takeIf { it > 0f } ?: 400f
    }

    private fun coreFile(): File =
        File(File(filesDir, "retro-engine").apply { mkdirs() }, coreSpec.coreFileName)

    private fun statesDir(): File = File(filesDir, "retro-states").apply { mkdirs() }

    private fun autoStateFile(): File = File(statesDir(), "${romFile.name}.auto.state")

    private fun slotStateFile(): File = File(statesDir(), "${romFile.name}.slot1.state")

    private fun coreDownloadUrl(): String? {
        val supportedAbi = Build.SUPPORTED_ABIS.firstOrNull {
            it == "arm64-v8a" || it == "armeabi-v7a" || it == "x86_64"
        } ?: return null
        return "https://buildbot.libretro.com/nightly/android/latest/$supportedAbi/${coreSpec.coreZipName}"
    }

    private fun downloadEngine() {
        val url = coreDownloadUrl() ?: run {
            statusText.setText(R.string.universe_retro_engine_incompatible)
            return
        }
        centerOverlay.isVisible = true
        engineButton.isEnabled = false
        progress.isVisible = true
        statusText.setText(R.string.universe_retro_engine_downloading)

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { downloadAndExtractCore(url) }
            }
            progress.isVisible = false
            engineButton.isEnabled = true
            result.onSuccess {
                statusText.setText(R.string.universe_retro_engine_ready)
                engineButton.isVisible = false
                startGame()
            }.onFailure {
                statusText.setText(R.string.universe_retro_engine_failed)
                engineButton.setText(R.string.universe_retro_download_engine)
                engineButton.isVisible = true
                Toast.makeText(this@RetroGamePlayerActivity, R.string.universe_retro_engine_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadAndExtractCore(downloadUrl: String): File {
        require(downloadUrl.startsWith("https://buildbot.libretro.com/")) {
            "Core download host is not trusted"
        }
        val destination = coreFile()
        val expectedFileName = coreSpec.coreFileName
        val temporary = File(destination.parentFile, "$expectedFileName.part")
        temporary.delete()

        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 35_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "MISSA-TV-360")
        }
        try {
            require(connection.responseCode in 200..299) {
                "Core download returned HTTP ${connection.responseCode}"
            }
            val finalUrl = connection.url
            require(
                finalUrl.protocol.equals("https", ignoreCase = true) &&
                    finalUrl.host.equals("buildbot.libretro.com", ignoreCase = true)
            ) {
                "Core download redirected to an untrusted host"
            }
            val declaredLength = connection.contentLengthLong
            require(declaredLength <= MAX_CORE_ARCHIVE_BYTES) {
                "Core archive exceeded the permitted size"
            }
            var extracted = false
            ZipInputStream(
                BufferedInputStream(LimitedInputStream(connection.inputStream, MAX_CORE_ARCHIVE_BYTES))
            ).use { archive ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val entry = archive.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && name == expectedFileName) {
                        FileOutputStream(temporary).use { fileOutput ->
                            BufferedOutputStream(fileOutput).use { output ->
                                var total = 0L
                                while (true) {
                                    val count = archive.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= MAX_CORE_BYTES) { "Core exceeded the permitted size" }
                                    output.write(buffer, 0, count)
                                }
                            }
                        }
                        extracted = true
                        break
                    }
                    while (archive.read(buffer) >= 0) {
                        // drain unused zip entry
                    }
                    archive.closeEntry()
                }
            }
            require(extracted && temporary.isFile && temporary.length() >= MIN_CORE_BYTES) {
                "Official archive did not contain the expected core"
            }
            val backup = File(destination.parentFile, "$expectedFileName.backup")
            backup.delete()
            if (destination.exists()) {
                require(destination.renameTo(backup)) { "Could not preserve previous core file" }
            }
            if (!temporary.renameTo(destination)) {
                if (backup.exists()) backup.renameTo(destination)
                error("Could not install core file")
            }
            backup.delete()
            return destination
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureRetroWorker(): Handler {
        retroHandler?.let { return it }
        val thread = HandlerThread("MISSA-TV-RetroCore").apply { start() }
        val handler = Handler(thread.looper)
        retroThread = thread
        retroHandler = handler
        return handler
    }

    private fun startGame() {
        if (core != null) {
            resumeEmulation()
            return
        }
        if (!coreFile().isFile || coreFile().length() < MIN_CORE_BYTES) {
            centerOverlay.isVisible = true
            engineButton.isVisible = true
            return
        }

        centerOverlay.isVisible = true
        engineButton.isVisible = false
        progress.isVisible = true
        statusText.setText(R.string.universe_retro_loading_game)

        val worker = ensureRetroWorker()
        worker.post {
            var openedCore: LibretroCore? = null
            val openResult = runCatching {
                val frontend = createFrontend()
                val newCore = LibretroCore(coreFile().absolutePath, frontend)
                openedCore = newCore
                newCore.loadGame(romFile.absolutePath)
                newCore.setControllerPortDevice(0, Device.JOYPAD)

                val fps = newCore.avInfo.timing.fps
                    .takeIf { it.isFinite() && it in 1.0..240.0 } ?: FALLBACK_FPS
                frameIntervalNs = (1_000_000_000.0 / fps).toLong()
                frameIntervalMs = max(4L, (1000.0 / fps).toLong())

                val audio = createAudioTrack(newCore.avInfo.timing.sampleRate)

                // Auto-resume previous session state if present (GAME-04)
                val autoFile = autoStateFile()
                var autoRestored = false
                if (autoFile.isFile && autoFile.length() > 0L) {
                    autoRestored = runCatching {
                        newCore.loadState(autoFile.readBytes())
                    }.getOrDefault(false)
                }
                Triple(newCore, audio, autoRestored)
            }.onFailure {
                runCatching { openedCore?.close() }
            }

            runOnUiThread {
                if (destroyed || isFinishing) return@runOnUiThread
                progress.isVisible = false
                openResult.onSuccess { (newCore, audio, autoRestored) ->
                    core = newCore
                    audioTrack = audio
                    centerOverlay.isVisible = false
                    if (autoRestored) {
                        Toast.makeText(this, R.string.universe_retro_auto_resumed, Toast.LENGTH_SHORT).show()
                    }
                    resumeEmulation()
                }.onFailure {
                    centerOverlay.isVisible = true
                    statusText.setText(R.string.universe_retro_player_failed)
                    Toast.makeText(this, R.string.universe_retro_player_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun resumeEmulation() {
        if (core == null || emulatorRunning || destroyed) return
        emulatorRunning = true
        nextFrameDeadlineNs = System.nanoTime()
        runCatching { audioTrack?.play() }
        retroHandler?.removeCallbacks(frameTickRunnable)
        retroHandler?.post(frameTickRunnable)
    }

    private fun pauseEmulation(saveAutoSnapshot: Boolean = false) {
        emulatorRunning = false
        retroHandler?.removeCallbacks(frameTickRunnable)
        runCatching { audioTrack?.pause() }
        if (saveAutoSnapshot && core != null) {
            retroHandler?.post {
                val activeCore = core ?: return@post
                runCatching {
                    val bytes = activeCore.saveState()
                    if (bytes != null && bytes.isNotEmpty()) {
                        autoStateFile().writeBytes(bytes)
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pause Menu & State Management on Single Emulation Thread (GAME-04, GAME-05)
    // -------------------------------------------------------------------------

    private fun showPauseDialog() {
        if (activePauseDialog?.isShowing == true) return
        val wasRunning = emulatorRunning
        pauseEmulation(saveAutoSnapshot = false)

        val dialogView = layoutInflater.inflate(R.layout.dialog_retro_pause, null, false)
        val resumeBtn = dialogView.findViewById<MaterialButton>(R.id.pause_btn_resume)
        val saveBtn = dialogView.findViewById<MaterialButton>(R.id.pause_btn_save_state)
        val loadBtn = dialogView.findViewById<MaterialButton>(R.id.pause_btn_load_state)
        val resetBtn = dialogView.findViewById<MaterialButton>(R.id.pause_btn_reset)
        val toggleTouchBtn = dialogView.findViewById<MaterialButton>(R.id.pause_btn_toggle_touch)
        val opacityBtn = dialogView.findViewById<MaterialButton>(R.id.pause_btn_opacity)
        val scaleBtn = dialogView.findViewById<MaterialButton>(R.id.pause_btn_scale)
        val quitBtn = dialogView.findViewById<MaterialButton>(R.id.pause_btn_quit)

        fun refreshOverlayLabels() {
            val pct = (OPACITY_LEVELS[opacityIndex] * 100f).toInt()
            opacityBtn.text = getString(R.string.universe_retro_touch_opacity_fmt, pct)
            val scaleStr = String.format(Locale.US, "%.2fx", SCALE_LEVELS[scaleIndex])
            scaleBtn.text = getString(R.string.universe_retro_touch_scale_fmt, scaleStr)
        }
        refreshOverlayLabels()

        var resumeOnDismiss = wasRunning
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setOnDismissListener {
                activePauseDialog = null
                enterImmersiveFullscreen()
                if (resumeOnDismiss && !destroyed && !isFinishing) {
                    resumeEmulation()
                }
            }
            .create()

        resumeBtn.setOnClickListener {
            resumeOnDismiss = true
            dialog.dismiss()
        }

        saveBtn.setOnClickListener {
            retroHandler?.post {
                val saved = runCatching {
                    val bytes = core?.saveState()
                    if (bytes != null && bytes.isNotEmpty()) {
                        slotStateFile().writeBytes(bytes)
                        autoStateFile().writeBytes(bytes)
                        true
                    } else false
                }.getOrDefault(false)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (saved) R.string.universe_retro_state_saved else R.string.universe_retro_state_unavailable,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        loadBtn.setOnClickListener {
            val slot = slotStateFile().takeIf { it.isFile } ?: autoStateFile().takeIf { it.isFile }
            if (slot == null) {
                Toast.makeText(this, R.string.universe_retro_state_unavailable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            retroHandler?.post {
                val restored = runCatching { core?.loadState(slot.readBytes()) == true }.getOrDefault(false)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (restored) R.string.universe_retro_state_loaded else R.string.universe_retro_state_unavailable,
                        Toast.LENGTH_SHORT
                    ).show()
                    if (restored) {
                        resumeOnDismiss = true
                        dialog.dismiss()
                    }
                }
            }
        }

        resetBtn.setOnClickListener {
            retroHandler?.post {
                runCatching { core?.reset() }
                runOnUiThread {
                    resumeOnDismiss = true
                    dialog.dismiss()
                }
            }
        }

        toggleTouchBtn.setOnClickListener {
            touchOverlayUserVisible = !touchOverlayUserVisible
            applyOverlayAppearance()
        }

        opacityBtn.setOnClickListener {
            opacityIndex = (opacityIndex + 1) % OPACITY_LEVELS.size
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_OVERLAY_OPACITY_IDX, opacityIndex).apply()
            applyOverlayAppearance()
            refreshOverlayLabels()
        }

        scaleBtn.setOnClickListener {
            scaleIndex = (scaleIndex + 1) % SCALE_LEVELS.size
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt(KEY_OVERLAY_SCALE_IDX, scaleIndex).apply()
            applyOverlayAppearance()
            refreshOverlayLabels()
        }

        quitBtn.setOnClickListener {
            resumeOnDismiss = false
            dialog.dismiss()
            saveAutoAndFinish()
        }

        activePauseDialog = dialog
        dialog.show()
        resumeBtn.post { resumeBtn.requestFocus() }
    }

    private fun confirmQuitOrFinish() {
        if (core == null) {
            finish()
            return
        }
        val wasRunning = emulatorRunning
        pauseEmulation(saveAutoSnapshot = false)
        AlertDialog.Builder(this)
            .setTitle(R.string.universe_retro_exit)
            .setMessage(R.string.universe_retro_quit_confirm)
            .setPositiveButton(R.string.universe_retro_exit) { _, _ ->
                saveAutoAndFinish()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                enterImmersiveFullscreen()
                if (wasRunning) resumeEmulation()
            }
            .show()
    }

    private fun saveAutoAndFinish() {
        val handler = retroHandler
        if (handler != null && core != null) {
            val latch = CountDownLatch(1)
            handler.post {
                runCatching {
                    val bytes = core?.saveState()
                    if (bytes != null && bytes.isNotEmpty()) {
                        autoStateFile().writeBytes(bytes)
                    }
                }
                latch.countDown()
            }
            runCatching { latch.await(400, TimeUnit.MILLISECONDS) }
        }
        finish()
    }

    // -------------------------------------------------------------------------
    // Libretro Frontend & Audio
    // -------------------------------------------------------------------------

    private fun createFrontend(): LibretroFrontend {
        val systemDir = File(filesDir, "retro-system").apply { mkdirs() }
        val saveDir = File(filesDir, "retro-saves").apply { mkdirs() }
        return object : LibretroFrontend {
            override val systemDirectory: String = systemDir.absolutePath
            override val saveDirectory: String = saveDir.absolutePath

            override fun onVideoFrame(frame: VideoFrame) {
                if (destroyed || frame.width <= 0 || frame.height <= 0) return
                val pixels = frame.pixels.copyOf()
                runOnUiThread {
                    if (destroyed || isFinishing || isDestroyed) return@runOnUiThread
                    runCatching {
                        val bitmap = currentBitmap?.takeIf {
                            !it.isRecycled && it.width == frame.width && it.height == frame.height
                        } ?: Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888).also {
                            val oldBitmap = currentBitmap
                            currentBitmap = it
                            gameFrame.setImageBitmap(it)
                            if (oldBitmap != null && !oldBitmap.isRecycled) oldBitmap.recycle()
                        }
                        bitmap.setPixels(pixels, 0, frame.width, 0, 0, frame.width, frame.height)
                    }
                }
            }

            override fun onAudio(samples: ShortArray, frames: Int) {
                val track = audioTrack ?: return
                if (frames <= 0 || samples.isEmpty()) return
                val amount = min(samples.size, frames * 2)
                if (amount <= 0) return
                runCatching { track.write(samples, 0, amount, AudioTrack.WRITE_NON_BLOCKING) }
            }

            override fun inputState(port: Int, device: Int, index: Int, id: Int): Short {
                if (port != 0 || device != Device.JOYPAD) return 0
                val isDown = touchDpadButtons.contains(id) ||
                    pressedButtons.contains(id) ||
                    axisPressedButtons.contains(id)
                return if (isDown) 1.toShort() else 0.toShort()
            }

            override fun onLog(level: dev.kotlinds.libretrokmp.LogLevel, message: String) {
                if (level == dev.kotlinds.libretrokmp.LogLevel.ERROR) {
                    android.util.Log.e("MissaRetroEngine", message)
                }
            }
        }
    }

    private fun createAudioTrack(rate: Double): AudioTrack {
        val sampleRate = rate.toInt().coerceIn(8_000, 192_000)
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBufferSize > 0) { "Android audio output is unavailable" }
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(max(minBufferSize * 2, sampleRate / 5 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    // -------------------------------------------------------------------------
    // Touch, Gamepad & Remote Input (GAME-03, GAME-05)
    // -------------------------------------------------------------------------

    private fun wireTouchControls() {
        dpadView.onDirectionsChanged = { dirs ->
            touchDpadButtons.clear()
            touchDpadButtons.addAll(dirs)
        }

        val controls = listOf(
            R.id.retro_pad_a to JoypadButton.A,
            R.id.retro_pad_b to JoypadButton.B,
            R.id.retro_pad_x to JoypadButton.X,
            R.id.retro_pad_y to JoypadButton.Y,
            R.id.retro_pad_l to JoypadButton.L,
            R.id.retro_pad_r to JoypadButton.R,
            R.id.retro_pad_select to JoypadButton.SELECT,
            R.id.retro_pad_start to JoypadButton.START
        )
        controls.forEach { (viewId, buttonId) ->
            findViewById<View?>(viewId)?.setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        pressedButtons.add(buttonId)
                        v.isPressed = true
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        checkStartSelectPauseChord()
                    }
                    MotionEvent.ACTION_UP -> {
                        pressedButtons.remove(buttonId)
                        v.isPressed = false
                        v.performClick()
                    }
                    MotionEvent.ACTION_POINTER_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        pressedButtons.remove(buttonId)
                        v.isPressed = false
                    }
                }
                true
            }
        }
    }

    private fun checkStartSelectPauseChord(): Boolean {
        if (core != null &&
            pressedButtons.contains(JoypadButton.START) &&
            pressedButtons.contains(JoypadButton.SELECT)
        ) {
            pressedButtons.remove(JoypadButton.START)
            pressedButtons.remove(JoypadButton.SELECT)
            showPauseDialog()
            return true
        }
        return false
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (core == null || activePauseDialog?.isShowing == true) {
            return super.onGenericMotionEvent(event)
        }
        val isJoystick = (event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK ||
            (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        if (!isJoystick || event.action != MotionEvent.ACTION_MOVE) {
            return super.onGenericMotionEvent(event)
        }

        // Auto-hide touch overlay when physical controller input arrives
        if (touchOverlayUserVisible && touchControls.isVisible) {
            touchOverlayUserVisible = false
            touchControls.isVisible = false
        }

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val stickX = event.getAxisValue(MotionEvent.AXIS_X)
        val stickY = event.getAxisValue(MotionEvent.AXIS_Y)

        val x = if (abs(hatX) > 0.2f) hatX else stickX
        val y = if (abs(hatY) > 0.2f) hatY else stickY

        axisPressedButtons.clear()
        if (x < -0.42f) axisPressedButtons.add(JoypadButton.LEFT)
        if (x > 0.42f) axisPressedButtons.add(JoypadButton.RIGHT)
        if (y < -0.42f) axisPressedButtons.add(JoypadButton.UP)
        if (y > 0.42f) axisPressedButtons.add(JoypadButton.DOWN)
        return true
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // When Pause dialog is open or core isn't loaded yet, let normal Android D-pad focus work!
        if (activePauseDialog?.isShowing == true || core == null) {
            return super.dispatchKeyEvent(event)
        }

        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                showPauseDialog()
            }
            return true
        }

        val button = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> JoypadButton.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> JoypadButton.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> JoypadButton.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> JoypadButton.RIGHT
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> JoypadButton.A
            KeyEvent.KEYCODE_BUTTON_B -> JoypadButton.B
            KeyEvent.KEYCODE_BUTTON_X -> JoypadButton.X
            KeyEvent.KEYCODE_BUTTON_Y -> JoypadButton.Y
            KeyEvent.KEYCODE_BUTTON_START -> JoypadButton.START
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BUTTON_MODE -> JoypadButton.SELECT
            KeyEvent.KEYCODE_BUTTON_L1 -> JoypadButton.L
            KeyEvent.KEYCODE_BUTTON_R1 -> JoypadButton.R
            else -> null
        } ?: return super.dispatchKeyEvent(event)

        if (!isLayout(TV or EMULATOR) && touchOverlayUserVisible &&
            (event.source and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
        ) {
            touchOverlayUserVisible = false
            touchControls.isVisible = false
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                pressedButtons.add(button)
                checkStartSelectPauseChord()
            }
            KeyEvent.ACTION_UP -> pressedButtons.remove(button)
        }
        return true
    }

    override fun onPause() {
        resumeAfterForeground = emulatorRunning
        pauseEmulation(saveAutoSnapshot = true)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveFullscreen()
        if (resumeAfterForeground && core != null && activePauseDialog?.isShowing != true) {
            resumeAfterForeground = false
            resumeEmulation()
        }
    }

    override fun onDestroy() {
        destroyed = true
        emulatorRunning = false
        activePauseDialog?.dismiss()
        activePauseDialog = null

        val handler = retroHandler
        if (handler != null) {
            handler.removeCallbacksAndMessages(null)
            val closeLatch = CountDownLatch(1)
            handler.post {
                runCatching {
                    val bytes = core?.saveState()
                    if (bytes != null && bytes.isNotEmpty()) {
                        autoStateFile().writeBytes(bytes)
                    }
                }
                runCatching { core?.close() }
                core = null
                closeLatch.countDown()
            }
            runCatching { closeLatch.await(600, TimeUnit.MILLISECONDS) }
        }
        retroThread?.quitSafely()
        retroThread = null
        retroHandler = null

        audioTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
        audioTrack = null
        currentBitmap?.let { bmp -> runCatching { bmp.recycle() } }
        currentBitmap = null
        super.onDestroy()
    }
}
