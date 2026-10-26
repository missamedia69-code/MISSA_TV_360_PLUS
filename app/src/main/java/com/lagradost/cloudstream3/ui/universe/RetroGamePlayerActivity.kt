package com.lagradost.cloudstream3.ui.universe

import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.R
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
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.zip.ZipInputStream
import java.util.concurrent.locks.LockSupport
import kotlin.math.max
import kotlin.math.min

/**
 * Integrated first-party gameplay surface for Game Boy and Game Boy Color.
 * The frontend is bundled; the SameBoy core is obtained on demand from the official Libretro
 * buildbot and stored privately. ROM images are never bundled by the application.
 */
class RetroGamePlayerActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ROM_PATH = "missa_retro_rom_path"
        private const val CORE_FILE_NAME = "sameboy_libretro_android.so"
        private const val CORE_ZIP_NAME = "sameboy_libretro_android.so.zip"
        private const val MAX_CORE_BYTES = 16L * 1024L * 1024L
        private const val MIN_CORE_BYTES = 32L * 1024L
        private const val MAX_ROM_BYTES = 64L * 1024L * 1024L
        private const val FALLBACK_FPS = 59.7275
    }

    private data class OpenedSession(val core: LibretroCore, val audio: AudioTrack)

    private lateinit var romFile: File
    private lateinit var engineButton: Button
    private lateinit var pauseButton: Button
    private lateinit var exitButton: Button
    private lateinit var statusText: TextView
    private lateinit var gameFrame: ImageView
    private lateinit var progress: ProgressBar
    private lateinit var touchControls: View

    @Volatile private var emulatorRunning = false
    @Volatile private var destroyed = false
    @Volatile private var audioTrack: AudioTrack? = null
    private var gameThread: Thread? = null
    private var core: LibretroCore? = null
    private var currentBitmap: Bitmap? = null
    private var resumeAfterForeground = false
    private val pressedButtons: MutableSet<Int> = Collections.synchronizedSet(mutableSetOf())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_retro_game_player)

        engineButton = findViewById(R.id.retro_player_engine_button)
        pauseButton = findViewById(R.id.retro_player_pause_button)
        exitButton = findViewById(R.id.retro_player_exit_button)
        statusText = findViewById(R.id.retro_player_status)
        gameFrame = findViewById(R.id.retro_game_frame)
        progress = findViewById(R.id.retro_player_progress)
        touchControls = findViewById(R.id.retro_touch_controls)
        touchControls.isVisible = packageManager.hasSystemFeature("android.hardware.touchscreen")

        exitButton.setOnClickListener { finish() }
        engineButton.setOnClickListener {
            if (coreFile().isFile) startGame() else downloadEngine()
        }
        pauseButton.setOnClickListener { togglePause() }
        wireTouchControls()

        val requestedPath = intent.getStringExtra(EXTRA_ROM_PATH)
        if (requestedPath.isNullOrBlank()) {
            statusText.setText(R.string.universe_retro_player_failed)
            engineButton.isEnabled = false
            return
        }
        romFile = File(requestedPath)
        val libraryDir = File(filesDir, "retro-games")
        val validPath = runCatching {
            romFile.isFile &&
                romFile.canonicalPath.startsWith(libraryDir.canonicalPath + File.separator) &&
                romFile.length() in 1L..MAX_ROM_BYTES
        }.getOrDefault(false)
        if (!validPath || romFile.extension.lowercase() !in setOf("gb", "gbc", "dmg", "sgb")) {
            statusText.setText(R.string.universe_retro_player_failed)
            engineButton.isEnabled = false
            return
        }

        if (Build.VERSION.SDK_INT < 24 || coreDownloadUrl() == null) {
            statusText.setText(R.string.universe_retro_engine_incompatible)
            engineButton.isEnabled = false
            return
        }

        if (coreFile().isFile && coreFile().length() >= MIN_CORE_BYTES) {
            startGame()
        } else {
            statusText.setText(R.string.universe_retro_engine_missing)
            engineButton.setText(R.string.universe_retro_download_engine)
            engineButton.requestFocus()
        }
    }

    private fun coreFile(): File =
        File(File(filesDir, "retro-engine").apply { mkdirs() }, CORE_FILE_NAME)

    private fun coreDownloadUrl(): String? {
        val supportedAbi = Build.SUPPORTED_ABIS.firstOrNull {
            it == "arm64-v8a" || it == "armeabi-v7a" || it == "x86_64"
        } ?: return null
        return "https://buildbot.libretro.com/nightly/android/latest/$supportedAbi/$CORE_ZIP_NAME"
    }

    private fun downloadEngine() {
        if (Build.VERSION.SDK_INT < 24) {
            statusText.setText(R.string.universe_retro_engine_incompatible)
            return
        }
        val url = coreDownloadUrl()
        if (url == null) {
            statusText.setText(R.string.universe_retro_engine_incompatible)
            return
        }
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
                Toast.makeText(
                    this@RetroGamePlayerActivity,
                    getString(R.string.universe_retro_engine_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun downloadAndExtractCore(downloadUrl: String): File {
        require(downloadUrl.startsWith("https://buildbot.libretro.com/")) {
            "Core download host is not trusted"
        }
        val destination = coreFile()
        val temporary = File(destination.parentFile, "$CORE_FILE_NAME.part")
        temporary.delete()

        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "MISSA-TV-360")
        }
        try {
            require(connection.responseCode in 200..299) {
                "Core download returned HTTP ${connection.responseCode}"
            }
            var extracted = false
            ZipInputStream(BufferedInputStream(connection.inputStream)).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (!entry.isDirectory && name == CORE_FILE_NAME) {
                        FileOutputStream(temporary).use { fileOutput ->
                            BufferedOutputStream(fileOutput).use { output ->
                                val buffer = ByteArray(16 * 1024)
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
                    archive.closeEntry()
                }
            }
            require(extracted && temporary.isFile && temporary.length() >= MIN_CORE_BYTES) {
                "Official archive did not contain the expected core"
            }
            if (destination.exists()) destination.delete()
            require(temporary.renameTo(destination)) { "Could not install core file" }
            return destination
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun startGame() {
        if (core != null) {
            if (!emulatorRunning) resumeGameLoop()
            return
        }
        if (!coreFile().isFile || coreFile().length() < MIN_CORE_BYTES) {
            engineButton.isVisible = true
            return
        }
        engineButton.isEnabled = false
        progress.isVisible = true
        statusText.setText(R.string.universe_retro_loading_game)

        lifecycleScope.launch {
            val opened = withContext(Dispatchers.IO) {
                var createdCore: LibretroCore? = null
                runCatching {
                    val gameFrontend = createFrontend()
                    val newCore = LibretroCore(coreFile().absolutePath, gameFrontend)
                    createdCore = newCore
                    newCore.loadGame(romFile.absolutePath)
                    newCore.setControllerPortDevice(0, Device.JOYPAD)
                    val audio = createAudioTrack(newCore.avInfo.timing.sampleRate)
                    OpenedSession(newCore, audio)
                }.onFailure {
                    runCatching { createdCore?.close() }
                }
            }
            progress.isVisible = false
            engineButton.isEnabled = true
            opened.onSuccess { session ->
                core = session.core
                audioTrack = session.audio
                engineButton.isVisible = false
                pauseButton.isVisible = true
                statusText.text = romFile.name
                runCatching { audioTrack?.play() }
                resumeGameLoop()
            }.onFailure {
                statusText.setText(R.string.universe_retro_player_failed)
                Toast.makeText(
                    this@RetroGamePlayerActivity,
                    getString(R.string.universe_retro_player_failed),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun createFrontend(): LibretroFrontend {
        val systemDirectory = File(filesDir, "retro-system").apply { mkdirs() }
        val saveDirectory = File(filesDir, "retro-saves").apply { mkdirs() }
        return object : LibretroFrontend {
            override val systemDirectory: String = systemDirectory.absolutePath
            override val saveDirectory: String = saveDirectory.absolutePath

            override fun onVideoFrame(frame: VideoFrame) {
                if (destroyed || frame.width <= 0 || frame.height <= 0) return
                val pixels = frame.pixels.copyOf()
                runOnUiThread {
                    if (destroyed || isFinishing || isDestroyed) return@runOnUiThread
                    runCatching {
                        val bitmap = Bitmap.createBitmap(
                            pixels,
                            frame.width,
                            frame.height,
                            Bitmap.Config.ARGB_8888
                        )
                        gameFrame.setImageBitmap(bitmap)
                        currentBitmap?.recycle()
                        currentBitmap = bitmap
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
                return if (pressedButtons.contains(id)) 1.toShort() else 0.toShort()
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
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
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

    private fun resumeGameLoop() {
        val activeCore = core ?: return
        if (gameThread?.isAlive == true) return
        emulatorRunning = true
        pauseButton.setText(R.string.universe_retro_pause)
        audioTrack?.let { track -> runCatching { track.play() } }
        val fps = activeCore.avInfo.timing.fps
            .takeIf { it.isFinite() && it in 1.0..240.0 } ?: FALLBACK_FPS
        val frameIntervalNs = (1_000_000_000.0 / fps).toLong()
        gameThread = Thread({
            var nextFrame = System.nanoTime()
            while (emulatorRunning && !destroyed) {
                try {
                    activeCore.run()
                } catch (error: Throwable) {
                    android.util.Log.e("MissaRetroEngine", "Emulation stopped", error)
                    emulatorRunning = false
                    runOnUiThread {
                        if (!destroyed) statusText.setText(R.string.universe_retro_player_failed)
                    }
                    break
                }
                nextFrame += frameIntervalNs
                val remaining = nextFrame - System.nanoTime()
                if (remaining > 0) LockSupport.parkNanos(remaining) else nextFrame = System.nanoTime()
            }
        }, "MISSA-TV-GameBoy").apply {
            isDaemon = true
            start()
        }
    }

    private fun togglePause() {
        if (core == null) return
        if (emulatorRunning) {
            stopGameLoop()
            audioTrack?.let { track -> runCatching { track.pause() } }
            pauseButton.setText(R.string.universe_retro_resume)
            statusText.setText(R.string.universe_retro_pause)
        } else {
            resumeGameLoop()
            pauseButton.setText(R.string.universe_retro_pause)
        }
    }

    private fun stopGameLoop() {
        emulatorRunning = false
        gameThread?.let { thread ->
            if (thread !== Thread.currentThread()) runCatching { thread.join(500) }
        }
        gameThread = null
    }

    private fun wireTouchControls() {
        val controls = listOf(
            R.id.retro_pad_up to JoypadButton.UP,
            R.id.retro_pad_down to JoypadButton.DOWN,
            R.id.retro_pad_left to JoypadButton.LEFT,
            R.id.retro_pad_right to JoypadButton.RIGHT,
            R.id.retro_pad_a to JoypadButton.A,
            R.id.retro_pad_b to JoypadButton.B,
            R.id.retro_pad_select to JoypadButton.SELECT,
            R.id.retro_pad_start to JoypadButton.START
        )
        controls.forEach { (viewId, buttonId) ->
            findViewById<Button>(viewId).setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_POINTER_DOWN -> pressedButtons.add(buttonId)
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_POINTER_UP,
                    MotionEvent.ACTION_CANCEL -> pressedButtons.remove(buttonId)
                }
                true
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && core != null) {
                togglePause()
                return true
            }
        }
        if (core == null) return super.dispatchKeyEvent(event)

        val button = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_BUTTON_DPAD_UP -> JoypadButton.UP
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_BUTTON_DPAD_DOWN -> JoypadButton.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_BUTTON_DPAD_LEFT -> JoypadButton.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_BUTTON_DPAD_RIGHT -> JoypadButton.RIGHT
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A -> JoypadButton.A
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> JoypadButton.B
            KeyEvent.KEYCODE_BUTTON_X -> JoypadButton.SELECT
            KeyEvent.KEYCODE_BUTTON_Y -> JoypadButton.START
            KeyEvent.KEYCODE_BUTTON_START -> JoypadButton.START
            KeyEvent.KEYCODE_BUTTON_SELECT -> JoypadButton.SELECT
            KeyEvent.KEYCODE_BUTTON_L1 -> JoypadButton.L
            KeyEvent.KEYCODE_BUTTON_R1 -> JoypadButton.R
            else -> null
        } ?: return super.dispatchKeyEvent(event)

        when (event.action) {
            KeyEvent.ACTION_DOWN -> pressedButtons.add(button)
            KeyEvent.ACTION_UP -> pressedButtons.remove(button)
        }
        return true
    }

    override fun onPause() {
        resumeAfterForeground = emulatorRunning
        stopGameLoop()
        audioTrack?.let { track ->
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) runCatching { track.pause() }
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (resumeAfterForeground && core != null) {
            resumeAfterForeground = false
            resumeGameLoop()
        }
    }

    override fun onDestroy() {
        destroyed = true
        stopGameLoop()
        runCatching { core?.close() }
        core = null
        audioTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
        audioTrack = null
        currentBitmap?.let { bitmap -> runCatching { bitmap.recycle() } }
        currentBitmap = null
        super.onDestroy()
    }
}
