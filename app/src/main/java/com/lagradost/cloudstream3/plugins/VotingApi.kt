package com.lagradost.cloudstream3.plugins

import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.context
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.Coroutines.main
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest

object VotingApi {
    private fun transformUrl(url: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("${url}#funny-salt".toByteArray())
            .fold("") { str, it -> str + "%02x".format(it) }

    suspend fun SitePlugin.getVotes(): Int = getVotes(url)
    fun SitePlugin.hasVoted(): Boolean = hasVoted(url)
    suspend fun SitePlugin.vote(): Int = vote(url)
    fun SitePlugin.canVote(): Boolean = canVote(this.url)

    private val votesCache = mutableMapOf<String, Int>()

    suspend fun getVotes(pluginUrl: String): Int =
        votesCache[pluginUrl] ?: (if (hasVoted(pluginUrl)) 1 else 0).also {
            votesCache[pluginUrl] = it
        }

    fun hasVoted(pluginUrl: String): Boolean =
        getKey<Boolean>("cs3-votes/${transformUrl(pluginUrl)}") ?: false

    fun canVote(pluginUrl: String): Boolean =
        PluginManager.urlPlugins.contains(pluginUrl)

    private val voteLock = Mutex()

    suspend fun vote(pluginUrl: String): Int {
        voteLock.withLock {
            if (!canVote(pluginUrl)) {
                main {
                    Toast.makeText(
                        context,
                        R.string.extension_install_first,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return getVotes(pluginUrl)
            }

            if (hasVoted(pluginUrl)) {
                main {
                    Toast.makeText(
                        context,
                        R.string.already_voted,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                return getVotes(pluginUrl)
            }

            setKey("cs3-votes/${transformUrl(pluginUrl)}", true)
            votesCache[pluginUrl] = 1
            return getVotes(pluginUrl)
        }
    }
}
