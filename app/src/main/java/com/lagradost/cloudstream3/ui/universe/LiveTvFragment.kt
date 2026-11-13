package com.lagradost.cloudstream3.ui.universe

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.player.ExtractorUri
import com.lagradost.cloudstream3.ui.player.GeneratorPlayer
import com.lagradost.cloudstream3.ui.player.NoVideoGenerator
import com.lagradost.cloudstream3.ui.player.SubtitleData
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.UIHelper.navigate
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.launch
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * Univers TV en direct : les chaînes du fichier `tv/chaines.m3u` du dépôt (voir
 * [MissaLiveTv]), en petites tuiles logo.
 *
 * Onglets : toutes les chaînes, favoris, catégories (group-title) et pays (tvg-country).
 * OK lance la chaîne dans le lecteur vidéo ; appui long (ou touche Menu) : favori.
 */
class LiveTvFragment : MissaLogoScreenFragment() {

    private sealed class Drill {
        data class Group(val name: String) : Drill()
        data class Country(val iso: String) : Drill()
        data class Search(val query: String) : Drill()
    }

    override val eyebrowRes = R.string.universe_eyebrow_live_tv
    override val titleRes = R.string.universe_live_tv_title

    private var channels: List<MissaLiveTv.Channel> = emptyList()
    private var drill: Drill? = null
    private var returnFocusKey: String? = null
    /** Chaînes de la liste affichée, dans l'ordre : sert à retrouver la chaîne d'une tuile. */
    private var shown: List<MissaLiveTv.Channel> = emptyList()

    override fun initialTab(): String = TAB_ALL

    override fun tabs(): List<Pair<String, String>> = listOf(
        TAB_ALL to getString(R.string.live_tv_tab_all),
        TAB_FAVORITES to getString(R.string.radio_tab_favorites),
        TAB_GROUPS to getString(R.string.live_tv_tab_groups),
        TAB_COUNTRIES to getString(R.string.radio_tab_countries)
    )

    override fun actions(): List<Pair<String, () -> Unit>> = listOf(
        getString(R.string.radio_action_search) to { askSearch() },
        getString(R.string.live_tv_action_refresh) to { refreshNow() }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        MissaLiveTv.updates.observe(viewLifecycleOwner) { stamp ->
            if (stamp != null && stamp > 0L) {
                channels = MissaLiveTv.cachedChannels()
                onListChanged()
            }
        }
        load()
    }

    private fun load() {
        setStatus(getString(R.string.radio_loading))
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            channels = MissaLiveTv.channels(ctx)
            if (!isAdded) return@launch
            onListChanged()
        }
    }

    private fun refreshNow() {
        val ctx = context ?: return
        Toast.makeText(ctx, R.string.live_tv_refreshing, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val changed = MissaLiveTv.refresh(ctx)
            if (!isAdded) return@launch
            Toast.makeText(
                ctx,
                if (changed) R.string.live_tv_refreshed else R.string.live_tv_up_to_date,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun onListChanged() {
        setCounter(resources.getQuantityString(R.plurals.live_tv_channel_count, channels.size, channels.size))
        render()
    }

    override fun onTabSelected(tabId: String) {
        closeDrill()
        render(scrollToTop = true)
    }

    private fun closeDrill() {
        drill = null
        hideCrumb()
    }

    override fun onBackInside(): Boolean {
        if (drill == null) return false
        val key = returnFocusKey
        closeDrill()
        render(focusKey = key)
        return true
    }

    // ------------------------------------------------------------------ Rendu

    private fun render(scrollToTop: Boolean = false, focusKey: String? = null) {
        if (!isAdded) return
        val favs = MissaLiveTv.favoriteUrls()
        var emptyMessage: String? = null
        val items: List<LogoGridItem> = when (val d = drill) {
            is Drill.Group -> channelItems(channels.filter { d.name in it.groups }, favs)
            is Drill.Country -> channelItems(channels.filter { it.country == d.iso }, favs)
            is Drill.Search -> channelItems(channels.filter { matches(it, d.query) }, favs).also {
                emptyMessage = getString(R.string.live_tv_search_empty)
            }
            null -> when (selectedTab) {
                TAB_FAVORITES -> channelItems(channels.filter { it.url in favs }, favs).also {
                    emptyMessage = getString(R.string.live_tv_favorites_empty)
                }
                TAB_GROUPS -> {
                    shown = emptyList()
                    groupFolders()
                }
                TAB_COUNTRIES -> {
                    shown = emptyList()
                    emptyMessage = getString(R.string.live_tv_no_countries)
                    countryFolders()
                }
                else -> channelItems(channels, favs)
            }
        }
        setStatus(
            when {
                channels.isEmpty() -> getString(R.string.live_tv_empty)
                items.isEmpty() -> emptyMessage
                else -> null
            }
        )
        submit(items, scrollToTop = scrollToTop, focusKey = focusKey)
    }

    private fun channelItems(list: List<MissaLiveTv.Channel>, favs: Set<String>): List<LogoGridItem> {
        shown = list
        return list.map { ch ->
            LogoGridItem.Logo(
                key = "ch:" + ch.url,
                title = ch.name,
                logos = listOfNotNull(ch.logo),
                favorite = ch.url in favs,
                description = listOfNotNull(ch.name, ch.groups.firstOrNull()).joinToString(", ")
            )
        }
    }

    private fun groupFolders(): List<LogoGridItem> {
        val counts = LinkedHashMap<String, Int>()
        channels.forEach { ch -> ch.groups.ifEmpty { listOf(getString(R.string.live_tv_group_other)) }.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        return counts.entries.sortedWith { a, b -> collator.compare(a.key, b.key) }.map { (name, n) ->
            LogoGridItem.Folder(
                key = "group:$name",
                title = name,
                subtitle = resources.getQuantityString(R.plurals.live_tv_channel_count, n, n),
                badge = name.take(1).uppercase(Locale.getDefault())
            )
        }
    }

    private fun countryFolders(): List<LogoGridItem> {
        val counts = channels.mapNotNull { it.country }.groupingBy { it }.eachCount()
        if (counts.isEmpty()) return emptyList()
        val locale = Locale.getDefault()
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        return counts.entries
            .map { (iso, n) -> Triple(iso, countryName(iso, locale), n) }
            .sortedWith { a, b -> collator.compare(a.second, b.second) }
            .map { (iso, name, n) ->
                LogoGridItem.Folder(
                    key = "country:$iso",
                    title = name,
                    subtitle = resources.getQuantityString(R.plurals.live_tv_channel_count, n, n),
                    badge = iso,
                    imageUrl = "https://flagcdn.com/w80/${iso.lowercase(Locale.ROOT)}.png"
                )
            }
    }

    private fun countryName(iso: String, locale: Locale): String =
        runCatching { Locale("", iso).getDisplayCountry(locale) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: iso

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)

    private fun matches(ch: MissaLiveTv.Channel, query: String): Boolean {
        val q = normalize(query.trim())
        if (q.isEmpty()) return true
        return normalize(ch.name).contains(q) || ch.groups.any { normalize(it).contains(q) } ||
            (ch.country?.let { normalize(countryName(it, Locale.getDefault())).contains(q) } ?: false)
    }

    // ------------------------------------------------------------------ Actions

    override fun onItemClick(item: LogoGridItem) {
        when {
            item.key.startsWith("group:") -> {
                val name = item.key.removePrefix("group:")
                openDrill(Drill.Group(name), getString(R.string.live_tv_tab_groups), name, item.key)
            }

            item.key.startsWith("country:") -> {
                val iso = item.key.removePrefix("country:")
                openDrill(Drill.Country(iso), getString(R.string.radio_tab_countries), countryName(iso, Locale.getDefault()), item.key)
            }

            item is LogoGridItem.Logo -> {
                val ch = shown.firstOrNull { "ch:" + it.url == item.key } ?: return
                play(ch)
            }
        }
    }

    private fun openDrill(target: Drill, backLabel: String, title: String, fromKey: String?) {
        drill = target
        returnFocusKey = fromKey
        showCrumb(backLabel, title)
        render(scrollToTop = true)
        updateCrumbTitle(getString(R.string.live_tv_crumb_count, title, shown.size))
    }

    override fun onItemLongClick(item: LogoGridItem) {
        val ctx = context ?: return
        val ch = shown.firstOrNull { "ch:" + it.url == item.key } ?: return
        val isFav = ch.url in MissaLiveTv.favoriteUrls()
        val options = listOf(
            getString(R.string.live_tv_option_watch) to { play(ch) },
            getString(if (isFav) R.string.radio_option_unfavorite else R.string.radio_option_favorite) to {
                MissaLiveTv.toggleFavorite(ch)
                render()
            }
        )
        AlertDialog.Builder(ctx)
            .setTitle(ch.name)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    private fun play(ch: MissaLiveTv.Channel) {
        val act = activity ?: return
        // La radio s'arrête : on ne veut pas deux sons à la fois.
        DirectRadioPlayerManager.stop()
        act.navigate(
            R.id.global_to_navigation_player,
            GeneratorPlayer.newInstance(LiveChannelGenerator(ch), 0),
            NavOptions.Builder().setPopUpTo(R.id.navigation_player, inclusive = true, saveState = false).build()
        )
    }

    private fun askSearch() {
        val ctx = context ?: return
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
            hint = getString(R.string.live_tv_search_hint)
        }
        val box = FrameLayout(ctx).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        fun go() {
            val q = input.text?.toString()?.trim().orEmpty()
            if (q.isEmpty()) return
            val tabLabel = tabs().firstOrNull { it.first == selectedTab }?.second ?: getString(R.string.live_tv_tab_all)
            drill = Drill.Search(q)
            returnFocusKey = null
            val title = getString(R.string.radio_crumb_search, q)
            showCrumb(tabLabel, title)
            render(scrollToTop = true)
            updateCrumbTitle(getString(R.string.live_tv_crumb_count, title, shown.size))
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.radio_action_search)
            .setView(box)
            .setPositiveButton(R.string.radio_action_search) { _, _ -> go() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                dialog.dismiss()
                go()
                true
            } else false
        }
        dialog.show()
        input.requestFocus()
    }

    companion object {
        private const val TAB_ALL = "all"
        private const val TAB_FAVORITES = "favorites"
        private const val TAB_GROUPS = "groups"
        private const val TAB_COUNTRIES = "countries"
    }
}

/** Donne au lecteur vidéo le flux d'une chaîne, avec son Referer / User-Agent éventuels. */
class LiveChannelGenerator(private val channel: MissaLiveTv.Channel) : NoVideoGenerator(channel.url.hashCode()) {
    override suspend fun generateLinks(
        clearCache: Boolean,
        sourceTypes: Set<ExtractorLinkType>,
        callback: (Pair<ExtractorLink?, ExtractorUri?>) -> Unit,
        subtitleCallback: (SubtitleData) -> Unit,
        offset: Int,
        isCasting: Boolean
    ): Boolean {
        val link = newExtractorLink(source = channel.name, name = channel.name, url = channel.url) {
            referer = channel.referer.orEmpty()
            quality = Qualities.Unknown.value
            channel.userAgent?.let { headers = mapOf("User-Agent" to it) }
        }
        callback(link to null)
        return true
    }
}
