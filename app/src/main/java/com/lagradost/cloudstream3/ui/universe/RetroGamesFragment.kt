package com.lagradost.cloudstream3.ui.universe

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.getResourceColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * ROM library and homebrew downloads are deliberately separate from the video/audio player.
 * This screen downloads a small catalogue of official homebrew releases and imports local ROMs.
 * Game Boy and Game Boy Color use the embedded SameBoy libretro core; other systems can use an external emulator.
 */
class RetroGamesFragment : Fragment() {
    private data class CatalogueGame(
        val title: String,
        val platform: String,
        val fileName: String,
        val downloadUrl: String,
        val sourceUrl: String,
        val licenceNote: String
    )

    private val catalogue = listOf(
        CatalogueGame(
            title = "µCity",
            platform = "Game Boy Color",
            fileName = "ucity.gbc",
            downloadUrl = "https://github.com/AntonioND/ucity/releases/download/v1.3/ucity.gbc",
            sourceUrl = "https://github.com/AntonioND/ucity",
            licenceNote = "Homebrew — vérifier licence source"
        ),
        CatalogueGame(
            title = "Big2Small",
            platform = "Game Boy / Game Boy Color",
            fileName = "big2small.gb",
            downloadUrl = "https://github.com/mdsteele/big2small/releases/download/v1.0.0/big2small.gb",
            sourceUrl = "https://github.com/mdsteele/big2small",
            licenceNote = "GPL-3.0 — jeu homebrew libre"
        )
    )

    private val pickRom = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importRom(uri)
    }

    private lateinit var catalogueContainer: LinearLayout
    private lateinit var libraryContainer: LinearLayout
    private lateinit var libraryEmpty: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_retro_games, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        catalogueContainer = view.findViewById(R.id.retro_catalogue_list)
        libraryContainer = view.findViewById(R.id.retro_library_list)
        libraryEmpty = view.findViewById(R.id.retro_library_empty)

        view.findViewById<Button>(R.id.retro_import_button).setOnClickListener {
            pickRom.launch(arrayOf("*/*"))
        }
        view.findViewById<Button>(R.id.retro_homebrew_button).setOnClickListener {
            openUrl("https://hh.gbdev.io/")
        }
        renderCatalogue()
        refreshLibrary()
    }

    private fun getRetroDirectory(): File {
        return File(requireContext().filesDir, "retro-games").apply { mkdirs() }
    }

    private fun renderCatalogue() {
        catalogueContainer.removeAllViews()
        catalogue.forEach { game ->
            val card = createCard()
            val column = createColumn(card)
            column.addView(createText("${game.title}", 20, true))
            column.addView(createText("${game.platform} • ${game.licenceNote}", 13, false).apply {
                setPadding(0, dp(5), 0, dp(8))
            })

            val row = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            val destination = File(getRetroDirectory(), game.fileName)
            row.addView(createButton(if (destination.exists()) getString(R.string.universe_retro_open) else "Télécharger") {
                if (destination.exists()) {
                    launchGame(destination)
                } else {
                    downloadGame(game)
                }
            }, LinearLayout.LayoutParams(0, dp(44), 1f))
            row.addView(createButton("Source / licence") {
                openUrl(game.sourceUrl)
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(8)
            })
            column.addView(row)
            catalogueContainer.addView(card, verticalCardParams())
        }
    }

    private fun downloadGame(game: CatalogueGame) {
        viewLifecycleOwner.lifecycleScope.launch {
            val destination = File(getRetroDirectory(), game.fileName)
            Toast.makeText(requireContext(), "Téléchargement de ${game.title}…", Toast.LENGTH_SHORT).show()
            val result = withContext(Dispatchers.IO) {
                val temporary = File(getRetroDirectory(), "${game.fileName}.part")
                runCatching {
                    require(game.downloadUrl.startsWith("https://github.com/")) {
                        "URL de catalogue non autorisée"
                    }
                    temporary.delete()
                    val connection = (URL(game.downloadUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 25_000
                        instanceFollowRedirects = true
                        requestMethod = "GET"
                    }
                    try {
                        val responseCode = connection.responseCode
                        require(responseCode in 200..299) { "Téléchargement HTTP $responseCode" }
                        val finalUrl = connection.url
                        require(finalUrl.protocol.equals("https", ignoreCase = true) &&
                            (finalUrl.host.equals("github.com", ignoreCase = true) ||
                                finalUrl.host.endsWith(".github.com", ignoreCase = true) ||
                                finalUrl.host.equals("githubusercontent.com", ignoreCase = true) ||
                                finalUrl.host.endsWith(".githubusercontent.com", ignoreCase = true))) {
                            "Redirection de téléchargement non autorisée"
                        }
                        connection.inputStream.buffered().use { input ->
                            temporary.outputStream().buffered().use { sink ->
                                val buffer = ByteArray(16 * 1024)
                                var total = 0L
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    require(total <= MAX_ROM_SIZE) { "Fichier trop volumineux" }
                                    sink.write(buffer, 0, count)
                                }
                            }
                        }
                        require(temporary.length() > 1024L) { "Fichier téléchargé invalide" }
                        require(!destination.exists() || destination.delete()) {
                            "Impossible de remplacer le fichier existant"
                        }
                        require(temporary.renameTo(destination)) { "Impossible de finaliser le téléchargement" }
                        destination
                    } finally {
                        connection.disconnect()
                    }
                }.onFailure {
                    temporary.delete()
                }
            }
            if (!isAdded) return@launch
            result.onSuccess {
                Toast.makeText(requireContext(), getString(R.string.universe_retro_file_imported), Toast.LENGTH_SHORT).show()
                renderCatalogue()
                refreshLibrary()
            }.onFailure {
                destination.delete()
                Toast.makeText(requireContext(), getString(R.string.universe_retro_import_error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importRom(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                var temporary: File? = null
                runCatching {
                    val displayName = requireContext().contentResolver.query(
                        uri,
                        arrayOf(OpenableColumns.DISPLAY_NAME),
                        null,
                        null,
                        null
                    )?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                        } else null
                    } ?: uri.lastPathSegment ?: "imported-rom"
                    val safeName = displayName.substringAfterLast('/')
                        .replace(Regex("[^A-Za-z0-9._ -]"), "_")
                        .trim()
                        .ifBlank { "imported-rom" }
                    val extension = safeName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    require(extension in SUPPORTED_ROM_EXTENSIONS) {
                        "Format de jeu non pris en charge"
                    }
                    val destination = uniqueDestination(safeName)
                    temporary = File(destination.parentFile, "${destination.name}.part")
                    val input = requireContext().contentResolver.openInputStream(uri)
                        ?: error("Impossible de lire le fichier")
                    input.buffered().use { source ->
                        temporary!!.outputStream().buffered().use { target ->
                            val buffer = ByteArray(16 * 1024)
                            var total = 0L
                            while (true) {
                                val count = source.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_ROM_SIZE) { "Fichier trop volumineux" }
                                target.write(buffer, 0, count)
                            }
                        }
                    }
                    require(temporary!!.length() > 0L) { "Le fichier est vide" }
                    require(temporary!!.renameTo(destination)) { "Impossible de finaliser l'import" }
                    destination
                }.onFailure {
                    temporary?.delete()
                }
            }
            if (!isAdded) return@launch
            result.onSuccess {
                Toast.makeText(requireContext(), getString(R.string.universe_retro_file_imported), Toast.LENGTH_SHORT).show()
                refreshLibrary()
                renderCatalogue()
            }.onFailure {
                Toast.makeText(requireContext(), getString(R.string.universe_retro_import_error), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun uniqueDestination(name: String): File {
        val directory = getRetroDirectory()
        val requested = File(directory, name)
        if (!requested.exists()) return requested
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var counter = 2
        while (true) {
            val suffix = if (extension.isBlank()) "" else ".$extension"
            val candidate = File(directory, "$base ($counter)$suffix")
            if (!candidate.exists()) return candidate
            counter++
        }
    }

    private fun refreshLibrary() {
        libraryContainer.removeAllViews()
        val files = getRetroDirectory().listFiles()
            ?.filter { it.isFile && it.extension.lowercase(Locale.ROOT) in SUPPORTED_ROM_EXTENSIONS }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            .orEmpty()
        libraryEmpty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
        files.forEach { file ->
            val card = createCard()
            val column = createColumn(card)
            column.addView(createText(file.name, 17, true))
            column.addView(createText(formatBytes(file.length()), 13, false).apply {
                setPadding(0, dp(4), 0, dp(8))
            })
            column.addView(createButton(getString(R.string.universe_retro_open)) {
                launchGame(file)
            })
            libraryContainer.addView(card, verticalCardParams())
        }
    }

    private fun launchGame(file: File) {
        val gameBoyExtensions = setOf("gb", "gbc", "dmg", "sgb")
        if (Build.VERSION.SDK_INT >= 24 && file.extension.lowercase(Locale.ROOT) in gameBoyExtensions) {
            startActivity(Intent(requireContext(), RetroGamePlayerActivity::class.java).apply {
                putExtra(RetroGamePlayerActivity.EXTRA_ROM_PATH, file.absolutePath)
            })
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.provider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/octet-stream")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, getString(R.string.universe_retro_open)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), getString(R.string.universe_retro_engine_missing), Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(requireContext(), getString(R.string.universe_retro_engine_missing), Toast.LENGTH_LONG).show()
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(requireContext(), url, Toast.LENGTH_LONG).show()
        }
    }

    private fun createCard(): MaterialCardView = MaterialCardView(requireContext()).apply {
        radius = dp(14).toFloat()
        cardElevation = dp(2).toFloat()
        setCardBackgroundColor(requireContext().getResourceColor(R.attr.iconGrayBackground))
        isFocusable = false
    }

    private fun createColumn(card: MaterialCardView): LinearLayout {
        val column = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        card.addView(column, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return column
    }

    private fun createText(text: String, sizeSp: Int, bold: Boolean): TextView =
        TextView(requireContext()).apply {
            this.text = text
            textSize = sizeSp.toFloat()
            setTextColor(requireContext().getResourceColor(R.attr.textColor))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun createButton(label: String, onClick: () -> Unit): Button =
        Button(requireContext()).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
        }

    private fun verticalCardParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply {
        bottomMargin = dp(10)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> "%.1f MB".format(Locale.ROOT, bytes / (1024f * 1024f))
        bytes >= 1024L -> "%.1f KB".format(Locale.ROOT, bytes / 1024f)
        else -> "$bytes B"
    }

    companion object {
        private const val MAX_ROM_SIZE = 64L * 1024L * 1024L
        private val SUPPORTED_ROM_EXTENSIONS = setOf(
            "nes", "gb", "gbc", "gba", "sfc", "smc", "n64", "z64", "v64", "nds", "zip", "7z", "bin", "cue"
        )
    }
}
