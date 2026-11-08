package com.lagradost.cloudstream3.ui.universe

import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Lecture du catalogue distant des jeux homebrew (`retro-catalog.json`, hébergé dans le dépôt
 * MISSA TV sous `jeux/`) et contrôle d'intégrité des ROM téléchargées.
 *
 * Sans dépendance Android pour être testé en JVM pur.
 */
object MissaRetroCatalog {

    /** Garde uniquement un nom de fichier simple : pas de dossier, pas de caractère exotique. */
    fun sanitizeFileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('_', '.')
        return base.ifBlank { "game.gb" }
    }

    /** Une empreinte SHA-256 valide : 64 caractères hexadécimaux, ramenés en minuscules. */
    fun normalizeSha256(raw: String?): String? {
        val clean = raw?.trim()?.lowercase(Locale.ROOT) ?: return null
        return clean.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    fun parse(json: String): List<RetroGamesFragment.CatalogueGame> {
        val arr = JSONArray(json)
        val out = ArrayList<RetroGamesFragment.CatalogueGame>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val downloadUrl = obj.optString("downloadUrl").trim()
            val fileName = sanitizeFileName(obj.optString("fileName").trim())
            if (!downloadUrl.startsWith("https://") || obj.optString("fileName").isBlank()) continue
            if (!seen.add(fileName)) continue
            out.add(
                RetroGamesFragment.CatalogueGame(
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
                    estimatedBytes = obj.optLong("estimatedBytes", 0L).coerceAtLeast(0L),
                    author = obj.optString("author", ""),
                    sha256 = normalizeSha256(obj.optString("sha256", ""))
                )
            )
        }
        return out
    }

    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
