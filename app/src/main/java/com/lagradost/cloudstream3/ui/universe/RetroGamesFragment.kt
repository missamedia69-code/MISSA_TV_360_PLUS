package com.lagradost.cloudstream3.ui.universe

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.InputDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Standalone Retro Games universe for MISSA TV 360 PLUS (GAME-01, GAME-02).
 *
 * Uses a single unified [RecyclerView] + [ListAdapter] + [DiffUtil] (2-column grid on TV/tablet,
 * vertical cards on mobile), remote JSON homebrew catalog sync with offline fallback, in-card
 * byte/percentage download progress, per-item cancellation, and HTTP `Range` resume.
 */
class RetroGamesFragment : Fragment() {

    data class CatalogueGame(
        val id: String,
        val title: String,
        val systemId: String,
        val platformLabel: String,
        val genre: String,
        val description: String,
        val fileName: String,
        val downloadUrl: String,
        val sourceUrl: String,
        val licenceNote: String,
        val estimatedBytes: Long = 0L
    )

    data class DownloadProgress(
        val bytesRead: Long,
        val totalBytes: Long
    ) {
        val percent: Int
            get() = if (totalBytes > 0L) ((bytesRead * 100L) / totalBytes).toInt().coerceIn(0, 100) else -1
    }

    sealed class RetroListItem {
        abstract val stableId: String

        data class SectionHeader(
            override val stableId: String,
            val title: String,
            val countText: String,
            val subtitle: String
        ) : RetroListItem()

        data class InstalledCard(
            val filePath: String,
            val title: String,
            val systemId: String,
            val systemLabel: String,
            val sizeBytes: Long,
            val supportsBuiltInCore: Boolean
        ) : RetroListItem() {
            override val stableId: String = "installed:$filePath"
        }

        data class CatalogueCard(
            val game: CatalogueGame,
            val isInstalled: Boolean,
            val progress: DownloadProgress?
        ) : RetroListItem() {
            override val stableId: String = "catalog:${game.fileName}"
        }
    }

    companion object {
        private const val REMOTE_HOMEBREW_CATALOG_URL =
            "https://raw.githubusercontent.com/missamedia69-code/MISSA_TV_360_PLUS/main/retro-catalog.json"
        private const val CATALOG_CACHE_FILE = "retro_homebrew_catalog_v1.json"
        private const val MAX_ROM_DOWNLOAD_BYTES = 64L * 1024L * 1024L
        private const val PAYLOAD_PROGRESS = "retro_download_progress"
    }

    private val pickRom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importRom(uri)
    }

    private var selectedSystemId: String = "all"
    private val filterButtons = LinkedHashMap<String, MaterialButton>()
    private val activeDownloadJobs = HashMap<String, Job>()
    private val activeProgress = HashMap<String, DownloadProgress>()
    private var remoteExtraGames: List<CatalogueGame> = emptyList()

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var controllerBadge: TextView
    private lateinit var filtersContainer: LinearLayout
    private lateinit var importButton: MaterialButton
    private lateinit var refreshButton: MaterialButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var listAdapter: RetroGamesAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_retro_games, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swipeRefresh = view.findViewById(R.id.retro_swipe_refresh)
        controllerBadge = view.findViewById(R.id.retro_controller_badge)
        filtersContainer = view.findViewById(R.id.retro_system_filters)
        importButton = view.findViewById(R.id.retro_import_button)
        refreshButton = view.findViewById(R.id.retro_refresh_button)
        recyclerView = view.findViewById(R.id.retro_games_recycler)

        listAdapter = RetroGamesAdapter(
            onPlayFile = { path -> playRomFile(File(path)) },
            onDeleteFile = { path, title -> confirmDeleteRom(File(path), title) },
            onDownloadCatalogue = { game -> startCatalogueDownload(game) },
            onCancelDownload = { fileName -> cancelDownload(fileName) },
            onOpenSource = { url -> openUrl(url) }
        )

        val isTvOrTablet = isLayout(TV or EMULATOR) || resources.configuration.smallestScreenWidthDp >= 600
        recyclerView.layoutManager = if (isTvOrTablet) {
            GridLayoutManager(requireContext(), 2).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int {
                        return if (listAdapter.isSectionHeader(position)) 2 else 1
                    }
                }
            }
        } else {
            LinearLayoutManager(requireContext())
        }
        recyclerView.itemAnimator = null
        recyclerView.adapter = listAdapter

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                (activity as? MainActivity)?.onMissaContentScrolled(dy, !rv.canScrollVertically(-1))
            }
        })

        importButton.setOnClickListener {
            pickRom.launch(arrayOf("*/*"))
        }
        refreshButton.setOnClickListener {
            syncRemoteCatalogue(forceNetwork = true)
        }
        view.findViewById<View>(R.id.retro_homebrew_button).setOnClickListener {
            openUrl("https://hh.gbdev.io/")
        }

        swipeRefresh.isEnabled = !isLayout(TV or EMULATOR)
        swipeRefresh.setOnRefreshListener {
            syncRemoteCatalogue(forceNetwork = true)
        }

        buildSystemFilterPills()
        updateControllerStatus()
        loadCachedRemoteCatalogue()
        rebuildList()
        syncRemoteCatalogue(forceNetwork = false)
    }

    override fun onResume() {
        super.onResume()
        updateControllerStatus()
        rebuildList()
    }

    override fun onDestroyView() {
        activeDownloadJobs.values.forEach { it.cancel() }
        activeDownloadJobs.clear()
        super.onDestroyView()
    }

    fun requestPrimaryContentFocus(): Boolean {
        if (!isAdded) return false
        val firstCardChild = recyclerView.getChildAt(1) ?: recyclerView.getChildAt(0)
        if (firstCardChild?.requestFocus() == true) return true
        return if (::importButton.isInitialized) importButton.requestFocus() else false
    }

    private fun systemFilters(): List<Pair<String, String>> = listOf(
        "all" to getString(R.string.universe_retro_filter_all),
        "gb" to getString(R.string.universe_retro_filter_gb),
        "nes" to getString(R.string.universe_retro_filter_nes),
        "snes" to getString(R.string.universe_retro_filter_snes),
        "gba" to getString(R.string.universe_retro_filter_gba),
        "sega" to getString(R.string.universe_retro_filter_sega)
    )

    private fun builtInCatalogue(): List<CatalogueGame> = listOf(
        CatalogueGame(
            id = "ucity",
            title = "µCity",
            systemId = "gb",
            platformLabel = "Game Boy Color",
            genre = getString(R.string.universe_retro_game_ucity_genre),
            description = getString(R.string.universe_retro_game_ucity_desc),
            fileName = "ucity.gbc",
            downloadUrl = "https://github.com/AntonioND/ucity/releases/download/v1.3/ucity.gbc",
            sourceUrl = "https://github.com/AntonioND/ucity",
            licenceNote = "GPL-3.0",
            estimatedBytes = 131_072L
        ),
        CatalogueGame(
            id = "big2small",
            title = "Big2Small",
            systemId = "gb",
            platformLabel = "Game Boy / GBC",
            genre = getString(R.string.universe_retro_game_big2small_genre),
            description = getString(R.string.universe_retro_game_big2small_desc),
            fileName = "big2small.gb",
            downloadUrl = "https://github.com/mdsteele/big2small/releases/download/v1.0.0/big2small.gb",
            sourceUrl = "https://github.com/mdsteele/big2small",
            licenceNote = "GPL-3.0",
            estimatedBytes = 65_536L
        ),
        CatalogueGame(
            id = "roborun_nes",
            title = "RoboRun NES",
            systemId = "nes",
            platformLabel = "NES",
            genre = getString(R.string.universe_retro_game_roborun_genre),
            description = getString(R.string.universe_retro_game_roborun_desc),
            fileName = "RoboRun-NES_1.1.nes",
            downloadUrl = "https://github.com/heaven-hm/roborun-nes/releases/download/1.1/RoboRun-NES_1.1.nes",
            sourceUrl = "https://github.com/heaven-hm/roborun-nes",
            licenceNote = "GPL-3.0",
            estimatedBytes = 40_976L
        ),
        CatalogueGame(
            id = "libbet",
            title = "Libbet & the Magic Floor",
            systemId = "gb",
            platformLabel = "Game Boy",
            genre = getString(R.string.universe_retro_game_libbet_genre),
            description = getString(R.string.universe_retro_game_libbet_desc),
            fileName = "libbet.gb",
            downloadUrl = "https://github.com/pinobatch/libbet/releases/download/v0.08/libbet.gb",
            sourceUrl = "https://github.com/pinobatch/libbet",
            licenceNote = "Zlib",
            estimatedBytes = 32_768L
        ),
        CatalogueGame(
            id = "240p_gba",
            title = "240p Suite Mini",
            systemId = "gba",
            platformLabel = "Game Boy Advance",
            genre = getString(R.string.universe_retro_game_240p_genre),
            description = getString(R.string.universe_retro_game_240p_desc),
            fileName = "240pee_mb.gba",
            downloadUrl = "https://github.com/pinobatch/240p-test-mini/releases/download/v0.23/240pee_mb.gba",
            sourceUrl = "https://github.com/pinobatch/240p-test-mini",
            licenceNote = "Zlib / GPL",
            estimatedBytes = 98_304L
        )
    )

    private fun buildSystemFilterPills() {
        val ctx = context ?: return
        filtersContainer.removeAllViews()
        filterButtons.clear()

        systemFilters().forEach { (sysId, label) ->
            val pill = MissaUiFactory.createPillButton(
                context = ctx,
                label = label,
                selected = sysId == selectedSystemId,
                marginEndDp = 6
            ) {
                selectedSystemId = sysId
                updateFilterPillSelection()
                rebuildList()
            }
            filtersContainer.addView(pill)
            filterButtons[sysId] = pill
        }
    }

    private fun updateFilterPillSelection() {
        filterButtons.forEach { (id, button) ->
            MissaUiFactory.stylePillSelection(button, id == selectedSystemId)
        }
    }

    private fun updateControllerStatus() {
        if (!isAdded) return
        var gamepadName: String? = null
        for (deviceId in InputDevice.getDeviceIds()) {
            val dev = InputDevice.getDevice(deviceId) ?: continue
            val isPad = !dev.isVirtual && (
                (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                    (dev.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                )
            if (isPad) {
                gamepadName = dev.name
                break
            }
        }

        controllerBadge.text = when {
            !gamepadName.isNullOrBlank() -> getString(R.string.universe_retro_controller_gamepad, gamepadName)
            isLayout(TV or EMULATOR) -> getString(R.string.universe_retro_controller_tv)
            else -> getString(R.string.universe_retro_controller_touch)
        }
    }

    private fun libraryDir(): File = File(requireContext().filesDir, "retro-games").apply { mkdirs() }

    private fun classifyExtensionSystem(ext: String): Pair<String, String> {
        return when (ext.lowercase(Locale.ROOT)) {
            "gb", "gbc", "dmg", "sgb" -> "gb" to getString(R.string.universe_retro_filter_gb)
            "nes" -> "nes" to getString(R.string.universe_retro_filter_nes)
            "sfc", "smc" -> "snes" to getString(R.string.universe_retro_filter_snes)
            "gba" -> "gba" to getString(R.string.universe_retro_filter_gba)
            "md", "gen", "sms", "gg" -> "sega" to getString(R.string.universe_retro_filter_sega)
            else -> "other" to getString(R.string.universe_retro_console_other)
        }
    }

    private fun rebuildList() {
        if (!isAdded || !::listAdapter.isInitialized) return
        val dir = libraryDir()
        val installedFiles = (dir.listFiles()?.toList() ?: emptyList())
            .filter { it.isFile && !it.name.endsWith(".part") && it.length() > 0L }
            .sortedBy { it.name.lowercase(Locale.ROOT) }

        val installedByName = installedFiles.associateBy { it.name }

        val filteredInstalled = installedFiles.mapNotNull { file ->
            val (sysId, sysLabel) = classifyExtensionSystem(file.extension)
            if (selectedSystemId != "all" && sysId != selectedSystemId) return@mapNotNull null
            RetroListItem.InstalledCard(
                filePath = file.absolutePath,
                title = file.nameWithoutExtension.replace('_', ' '),
                systemId = sysId,
                systemLabel = sysLabel,
                sizeBytes = file.length(),
                supportsBuiltInCore = RetroGamePlayerActivity.supportsExtension(file.extension)
            )
        }

        val combinedCatalogue = LinkedHashMap<String, CatalogueGame>()
        builtInCatalogue().forEach { combinedCatalogue[it.fileName] = it }
        remoteExtraGames.forEach { combinedCatalogue.putIfAbsent(it.fileName, it) }

        val filteredCatalogue = combinedCatalogue.values
            .filter { selectedSystemId == "all" || it.systemId == selectedSystemId }
            .map { game ->
                RetroListItem.CatalogueCard(
                    game = game,
                    isInstalled = installedByName.containsKey(game.fileName),
                    progress = activeProgress[game.fileName]
                )
            }

        val items = ArrayList<RetroListItem>(filteredInstalled.size + filteredCatalogue.size + 2)

        items.add(
            RetroListItem.SectionHeader(
                stableId = "header:installed",
                title = getString(R.string.universe_retro_installed_section),
                countText = resources.getQuantityString(
                    R.plurals.missa_games_count,
                    filteredInstalled.size,
                    filteredInstalled.size
                ),
                subtitle = if (filteredInstalled.isEmpty()) {
                    getString(R.string.universe_retro_empty)
                } else {
                    getString(R.string.universe_retro_engine_missing)
                }
            )
        )
        items.addAll(filteredInstalled)

        items.add(
            RetroListItem.SectionHeader(
                stableId = "header:catalog",
                title = getString(R.string.universe_retro_catalog_section),
                countText = resources.getQuantityString(
                    R.plurals.missa_games_count,
                    filteredCatalogue.size,
                    filteredCatalogue.size
                ),
                subtitle = getString(R.string.universe_retro_catalogue_notice)
            )
        )
        items.addAll(filteredCatalogue)

        listAdapter.submitList(items)
    }

    private fun loadCachedRemoteCatalogue() {
        val ctx = context ?: return
        val cache = File(ctx.filesDir, CATALOG_CACHE_FILE)
        if (!cache.isFile) return
        remoteExtraGames = runCatching { parseRemoteCatalogueJson(cache.readText()) }.getOrDefault(emptyList())
    }

    private fun syncRemoteCatalogue(forceNetwork: Boolean) {
        val ctx = context ?: return
        val cache = File(ctx.filesDir, CATALOG_CACHE_FILE)
        if (!forceNetwork && cache.isFile && System.currentTimeMillis() - cache.lastModified() < 12 * 3600_000L) {
            swipeRefresh.isRefreshing = false
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) {
                runCatching {
                    val conn = (URL(REMOTE_HOMEBREW_CATALOG_URL).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8_000
                        readTimeout = 10_000
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "MISSA-TV-360")
                    }
                    try {
                        if (conn.responseCode in 200..299) {
                            val body = conn.inputStream.bufferedReader().use { it.readText() }
                            val parsed = parseRemoteCatalogueJson(body)
                            if (parsed.isNotEmpty()) cache.writeText(body)
                            parsed
                        } else null
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()
            }
            if (!isAdded) return@launch
            swipeRefresh.isRefreshing = false
            if (fetched != null) {
                remoteExtraGames = fetched
            }
            rebuildList()
        }
    }

    private fun parseRemoteCatalogueJson(json: String): List<CatalogueGame> {
        val arr = JSONArray(json)
        val out = ArrayList<CatalogueGame>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val downloadUrl = obj.optString("downloadUrl").trim()
            val fileName = sanitizeFileName(obj.optString("fileName").trim())
            if (!downloadUrl.startsWith("https://") || fileName.isBlank()) continue
            out.add(
                CatalogueGame(
                    id = obj.optString("id", fileName),
                    title = obj.optString("title", fileName.substringBeforeLast('.')),
                    systemId = obj.optString("systemId", "gb"),
                    platformLabel = obj.optString("platformLabel", "Retro"),
                    genre = obj.optString("genre", "Homebrew"),
                    description = obj.optString("description", ""),
                    fileName = fileName,
                    downloadUrl = downloadUrl,
                    sourceUrl = obj.optString("sourceUrl", ""),
                    licenceNote = obj.optString("licenceNote", "Open-source"),
                    estimatedBytes = obj.optLong("estimatedBytes", 0L)
                )
            )
        }
        return out
    }

    /**
     * Downloads a homebrew ROM with HTTP `Range` resume on `.part` files, real-time progress
     * updates via DiffUtil payloads, and instant cancellation (GAME-02).
     */
    private fun startCatalogueDownload(game: CatalogueGame) {
        val destFile = File(libraryDir(), sanitizeFileName(game.fileName))
        if (destFile.isFile && destFile.length() > 0L) {
            playRomFile(destFile)
            return
        }
        if (activeDownloadJobs.containsKey(game.fileName)) return

        activeProgress[game.fileName] = DownloadProgress(0L, game.estimatedBytes)
        rebuildList()

        val job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    downloadRomWithRangeResume(game, destFile) { readBytes, totalBytes ->
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main.immediate) {
                            if (isAdded && activeDownloadJobs.containsKey(game.fileName)) {
                                activeProgress[game.fileName] = DownloadProgress(readBytes, totalBytes)
                                rebuildList()
                            }
                        }
                    }
                }
                if (!isAdded) return@launch
                activeDownloadJobs.remove(game.fileName)
                activeProgress.remove(game.fileName)
                rebuildList()
                Toast.makeText(requireContext(), R.string.universe_retro_file_imported, Toast.LENGTH_SHORT).show()
            } catch (_: CancellationException) {
                activeDownloadJobs.remove(game.fileName)
                activeProgress.remove(game.fileName)
                if (isAdded) rebuildList()
            } catch (_: Throwable) {
                activeDownloadJobs.remove(game.fileName)
                activeProgress.remove(game.fileName)
                if (isAdded) {
                    rebuildList()
                    Toast.makeText(requireContext(), R.string.universe_retro_import_error, Toast.LENGTH_LONG).show()
                }
            }
        }
        activeDownloadJobs[game.fileName] = job
    }

    private fun cancelDownload(fileName: String) {
        activeDownloadJobs.remove(fileName)?.cancel()
        activeProgress.remove(fileName)
        rebuildList()
    }

    private suspend fun downloadRomWithRangeResume(
        game: CatalogueGame,
        destination: File,
        onProgress: (Long, Long) -> Unit
    ) {
        require(game.downloadUrl.startsWith("https://")) { "Untrusted URL scheme" }
        val partFile = File(destination.parentFile, "${destination.name}.part")
        val existingBytes = if (partFile.isFile) partFile.length().coerceAtLeast(0L) else 0L

        val conn = (URL(game.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "MISSA-TV-360")
            if (existingBytes > 0L) {
                setRequestProperty("Range", "bytes=$existingBytes-")
            }
        }

        try {
            val code = conn.responseCode
            val isPartial = code == HttpURLConnection.HTTP_PARTIAL && existingBytes > 0L
            require(code in 200..299) { "HTTP $code" }

            val contentLen = conn.contentLengthLong
            val totalExpected = when {
                isPartial && contentLen > 0L -> existingBytes + contentLen
                contentLen > 0L -> contentLen
                else -> game.estimatedBytes
            }
            require(totalExpected <= MAX_ROM_DOWNLOAD_BYTES) { "ROM exceeds maximum permitted size" }

            var totalWritten = if (isPartial) existingBytes else 0L
            onProgress(totalWritten, totalExpected)

            conn.inputStream.buffered().use { input ->
                FileOutputStream(partFile, isPartial).buffered().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var lastUiTick = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        totalWritten += read
                        require(totalWritten <= MAX_ROM_DOWNLOAD_BYTES) { "ROM exceeds maximum permitted size" }
                        output.write(buffer, 0, read)
                        val now = System.currentTimeMillis()
                        if (now - lastUiTick >= 120L) {
                            lastUiTick = now
                            onProgress(totalWritten, totalExpected)
                        }
                    }
                    output.flush()
                }
            }

            require(partFile.isFile && partFile.length() > 0L) { "Empty ROM file" }
            if (destination.exists()) destination.delete()
            check(partFile.renameTo(destination)) { "Could not finalize ROM file" }
        } finally {
            conn.disconnect()
        }
    }

    private fun importRom(uri: Uri) {
        val ctx = context ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val imported = withContext(Dispatchers.IO) {
                runCatching {
                    val rawName = queryDisplayName(uri) ?: "imported_rom.gb"
                    val cleanName = sanitizeFileName(rawName)
                    val dest = File(libraryDir(), cleanName)
                    val part = File(libraryDir(), "$cleanName.part")
                    var total = 0L
                    ctx.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(part).buffered().use { output ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                require(total <= MAX_ROM_DOWNLOAD_BYTES) { "ROM too large" }
                                output.write(buf, 0, n)
                            }
                        }
                    } ?: error("Cannot read selected URI")
                    require(total > 0L) { "Empty file" }
                    if (dest.exists()) dest.delete()
                    check(part.renameTo(dest)) { "Rename failed" }
                    dest
                }
            }
            if (!isAdded) return@launch
            imported.onSuccess {
                rebuildList()
                Toast.makeText(ctx, R.string.universe_retro_file_imported, Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(ctx, R.string.universe_retro_import_error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val ctx = context ?: return null
        return ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun sanitizeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_')
        return base.ifBlank { "game.gb" }
    }

    private fun playRomFile(file: File) {
        val ctx = context ?: return
        if (!file.isFile) return
        if (RetroGamePlayerActivity.supportsExtension(file.extension)) {
            startActivity(
                Intent(ctx, RetroGamePlayerActivity::class.java).apply {
                    putExtra(RetroGamePlayerActivity.EXTRA_ROM_PATH, file.absolutePath)
                }
            )
            return
        }
        // Fallback to external emulator via FileProvider
        runCatching {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/octet-stream")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.universe_retro_external_engine)))
        }.onFailure {
            Toast.makeText(ctx, R.string.universe_retro_engine_missing, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDeleteRom(file: File, title: String) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setMessage(getString(R.string.universe_retro_delete_confirm, title))
            .setPositiveButton(R.string.universe_retro_delete) { _, _ ->
                file.delete()
                rebuildList()
                Toast.makeText(ctx, R.string.universe_retro_deleted, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
        }
    }

    // -------------------------------------------------------------------------
    // Unified RecyclerView ListAdapter + DiffUtil (GAME-01, GAME-02)
    // -------------------------------------------------------------------------

    private class RetroGamesAdapter(
        private val onPlayFile: (String) -> Unit,
        private val onDeleteFile: (String, String) -> Unit,
        private val onDownloadCatalogue: (CatalogueGame) -> Unit,
        private val onCancelDownload: (String) -> Unit,
        private val onOpenSource: (String) -> Unit
    ) : ListAdapter<RetroListItem, RecyclerView.ViewHolder>(DIFF) {

        companion object {
            private const val TYPE_HEADER = 1
            private const val TYPE_CARD = 2

            private val DIFF = object : DiffUtil.ItemCallback<RetroListItem>() {
                override fun areItemsTheSame(oldItem: RetroListItem, newItem: RetroListItem): Boolean =
                    oldItem.stableId == newItem.stableId

                override fun areContentsTheSame(oldItem: RetroListItem, newItem: RetroListItem): Boolean =
                    oldItem == newItem

                override fun getChangePayload(oldItem: RetroListItem, newItem: RetroListItem): Any? {
                    if (oldItem is RetroListItem.CatalogueCard && newItem is RetroListItem.CatalogueCard &&
                        oldItem.game == newItem.game && oldItem.isInstalled == newItem.isInstalled &&
                        oldItem.progress != newItem.progress
                    ) {
                        return PAYLOAD_PROGRESS
                    }
                    return null
                }
            }

            fun formatBytes(bytes: Long): String {
                if (bytes <= 0L) return ""
                val kb = bytes / 1024.0
                return if (kb < 1024.0) {
                    String.format(Locale.US, "%.0f KB", kb.coerceAtLeast(1.0))
                } else {
                    String.format(Locale.US, "%.1f MB", kb / 1024.0)
                }
            }
        }

        fun isSectionHeader(position: Int): Boolean =
            position in 0 until itemCount && getItem(position) is RetroListItem.SectionHeader

        override fun getItemViewType(position: Int): Int = when (getItem(position)) {
            is RetroListItem.SectionHeader -> TYPE_HEADER
            is RetroListItem.InstalledCard, is RetroListItem.CatalogueCard -> TYPE_CARD
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_HEADER -> SectionHeaderVH(inflater.inflate(R.layout.item_retro_section_header, parent, false))
                else -> GameCardVH(inflater.inflate(R.layout.item_retro_game_card, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            onBindViewHolder(holder, position, emptyList())
        }

        override fun onBindViewHolder(
            holder: RecyclerView.ViewHolder,
            position: Int,
            payloads: MutableList<Any>
        ) {
            when (val item = getItem(position)) {
                is RetroListItem.SectionHeader -> (holder as SectionHeaderVH).bind(item)
                is RetroListItem.InstalledCard -> (holder as GameCardVH).bindInstalled(item, onPlayFile, onDeleteFile)
                is RetroListItem.CatalogueCard -> {
                    val cardVh = holder as GameCardVH
                    if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_PROGRESS }) {
                        cardVh.bindProgressOnly(item, onDownloadCatalogue, onCancelDownload)
                    } else {
                        cardVh.bindCatalogue(item, onPlayFile, onDownloadCatalogue, onCancelDownload, onOpenSource)
                    }
                }
            }
        }

        private class SectionHeaderVH(view: View) : RecyclerView.ViewHolder(view) {
            private val title: TextView = view.findViewById(R.id.retro_section_title)
            private val count: TextView = view.findViewById(R.id.retro_section_count)
            private val subtitle: TextView = view.findViewById(R.id.retro_section_subtitle)

            fun bind(item: RetroListItem.SectionHeader) {
                title.text = item.title
                count.text = item.countText
                subtitle.text = item.subtitle
                subtitle.isVisible = item.subtitle.isNotBlank()
            }
        }

        private class GameCardVH(view: View) : RecyclerView.ViewHolder(view) {
            private val artwork: ImageView = view.findViewById(R.id.retro_card_artwork)
            private val title: TextView = view.findViewById(R.id.retro_card_title)
            private val systemBadge: TextView = view.findViewById(R.id.retro_card_system_badge)
            private val metaBadge: TextView = view.findViewById(R.id.retro_card_meta_badge)
            private val description: TextView = view.findViewById(R.id.retro_card_description)
            private val progressGroup: View = view.findViewById(R.id.retro_card_progress_group)
            private val progressBar: LinearProgressIndicator = view.findViewById(R.id.retro_card_progress_bar)
            private val progressText: TextView = view.findViewById(R.id.retro_card_progress_text)
            private val primaryBtn: MaterialButton = view.findViewById(R.id.retro_card_primary_btn)
            private val secondaryBtn: MaterialButton = view.findViewById(R.id.retro_card_secondary_btn)

            init {
                MissaUiFactory.attachPressFeedback(primaryBtn)
                MissaUiFactory.attachPressFeedback(secondaryBtn)
            }

            fun bindInstalled(
                item: RetroListItem.InstalledCard,
                onPlayFile: (String) -> Unit,
                onDeleteFile: (String, String) -> Unit
            ) {
                val ctx = itemView.context
                artwork.setImageResource(R.drawable.ic_missa_games)
                title.text = item.title
                systemBadge.text = item.systemLabel
                val engineBadge = ctx.getString(
                    if (item.supportsBuiltInCore) R.string.universe_retro_builtin_engine_badge
                    else R.string.universe_retro_external_engine_badge
                )
                metaBadge.text = listOf(engineBadge, formatBytes(item.sizeBytes))
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                description.isVisible = false
                progressGroup.isVisible = false

                primaryBtn.isEnabled = true
                primaryBtn.setText(R.string.universe_retro_open)
                primaryBtn.setOnClickListener { onPlayFile(item.filePath) }

                secondaryBtn.isVisible = true
                secondaryBtn.setText(R.string.universe_retro_delete)
                secondaryBtn.setOnClickListener { onDeleteFile(item.filePath, item.title) }
            }

            fun bindCatalogue(
                item: RetroListItem.CatalogueCard,
                onPlayFile: (String) -> Unit,
                onDownloadCatalogue: (CatalogueGame) -> Unit,
                onCancelDownload: (String) -> Unit,
                onOpenSource: (String) -> Unit
            ) {
                val game = item.game
                artwork.setImageResource(R.drawable.ic_missa_games)
                title.text = game.title
                systemBadge.text = game.platformLabel
                metaBadge.text = listOf(game.licenceNote, game.genre, formatBytes(game.estimatedBytes))
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                description.text = game.description
                description.isVisible = game.description.isNotBlank()

                secondaryBtn.isVisible = game.sourceUrl.isNotBlank()
                secondaryBtn.setText(R.string.universe_retro_source_code)
                secondaryBtn.setOnClickListener { onOpenSource(game.sourceUrl) }

                bindCatalogueActionState(item, onPlayFile, onDownloadCatalogue, onCancelDownload)
            }

            fun bindProgressOnly(
                item: RetroListItem.CatalogueCard,
                onDownloadCatalogue: (CatalogueGame) -> Unit,
                onCancelDownload: (String) -> Unit
            ) {
                bindCatalogueActionState(item, onPlayFile = {}, onDownloadCatalogue, onCancelDownload)
            }

            private fun bindCatalogueActionState(
                item: RetroListItem.CatalogueCard,
                onPlayFile: (String) -> Unit,
                onDownloadCatalogue: (CatalogueGame) -> Unit,
                onCancelDownload: (String) -> Unit
            ) {
                val ctx = itemView.context
                val prog = item.progress
                if (prog != null) {
                    progressGroup.isVisible = true
                    if (prog.percent >= 0) {
                        progressBar.isIndeterminate = false
                        progressBar.progress = prog.percent
                        progressText.text = ctx.getString(
                            R.string.universe_retro_downloading_pct,
                            prog.percent,
                            formatBytes(prog.bytesRead)
                        )
                    } else {
                        progressBar.isIndeterminate = true
                        progressText.text = ctx.getString(
                            R.string.universe_retro_downloading_bytes,
                            formatBytes(prog.bytesRead)
                        )
                    }
                    primaryBtn.isEnabled = true
                    primaryBtn.setText(R.string.universe_retro_cancel_download)
                    primaryBtn.setOnClickListener { onCancelDownload(item.game.fileName) }
                } else {
                    progressGroup.isVisible = false
                    primaryBtn.isEnabled = true
                    if (item.isInstalled) {
                        primaryBtn.setText(R.string.universe_retro_open)
                        primaryBtn.setOnClickListener {
                            val file = File(File(ctx.filesDir, "retro-games"), item.game.fileName)
                            onPlayFile(file.absolutePath)
                        }
                    } else {
                        primaryBtn.setText(R.string.universe_retro_download)
                        primaryBtn.setOnClickListener { onDownloadCatalogue(item.game) }
                    }
                }
            }
        }
    }
}
