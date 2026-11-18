package com.lagradost.cloudstream3.plugins

import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** Compare local bytes to the repository's declared hash without loading Android plugins.
 * A stale saved version (e.g. a plugin failed to load after download) must not cause the
 * identical binary to be fetched again at every launch.
 */
internal object PluginArtifact {
    fun isCurrent(file: File, expectedHash: String?): Boolean {
        if (!file.isFile || expectedHash.isNullOrBlank()) return false
        fun clean(value: String) = value.trim().lowercase(Locale.ROOT).removePrefix("sha256-")
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            actual == clean(expectedHash)
        }.getOrDefault(false)
    }
}
