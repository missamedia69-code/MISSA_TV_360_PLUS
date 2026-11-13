package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/**
 * Univers Radio : toutes les stations de l'annuaire, rangées par pays et par thème, en petites
 * tuiles logo.
 *
 * - **À la une** : la sélection MISSA (stations africaines et francophones vérifiées), vos
 *   radios ajoutées, puis le catalogue gratuit déjà connu de l'application.
 * - **Favoris** : les stations marquées d'une étoile (appui long ou touche Menu).
 * - **Pays** : les 225 pays de l'annuaire, rangés par continent ; un pays ouvert charge toutes
 *   ses stations, page par page, en défilant.
 * - **Thèmes** : actualités, gospel, afro, jazz... même principe.
 * - **Rechercher** : par nom, dans tout l'annuaire.
 */
class RadioFragment : MissaLogoScreenFragment() {

    private sealed class Drill {
        data class Country(val country: MissaRadioBrowse.Country) : Drill()
        data class Theme(val theme: MissaRadioBrowse.Theme) : Drill()
        data class Search(val query: String) : Drill()
    }

    override val eyebrowRes = R.string.universe_eyebrow_radio
    override val titleRes = R.string.universe_radio_title

    private var drill: Drill? = null
    /** Élément à re-sélectionner en remontant d'un niveau (le pays qu'on vient de quitter). */
    private var returnFocusKey: String? = null

    private var featured: List<RadioStationPreset> = emptyList()
    private var countries: List<MissaRadioBrowse.Country> = emptyList()

    // Liste paginée du pays / thème / recherche ouvert.
    private var stations: List<RadioStationPreset> = emptyList()
    private var nextOffset = 0
    private var endReached = false
    private var pageJob: Job? = null
    private var loadGeneration = 0

    private var playingUrl: String? = null

    override fun initialTab(): String = TAB_FEATURED

    override fun tabs(): List<Pair<String, String>> = listOf(
        TAB_FEATURED to getString(R.string.radio_tab_featured),
        TAB_FAVORITES to getString(R.string.radio_tab_favorites),
        TAB_COUNTRIES to getString(R.string.radio_tab_countries),
        TAB_THEMES to getString(R.string.radio_tab_themes)
    )

    override fun actions(): List<Pair<String, () -> Unit>> = listOf(
        getString(R.string.radio_action_search) to { askSearch() },
        getString(R.string.radio_action_add) to {
            context?.let { ctx -> MissaUniverseDialogs.showCustomRadioStreamDialog(ctx) { reloadFeatured() } }
            Unit
        },
        getString(R.string.radio_action_sleep) to { askSleepTimer() }
    )

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        DirectRadioPlayerManager.state.observe(viewLifecycleOwner) { state ->
            val url = state?.station?.streamUrl
            if (url != playingUrl) {
                playingUrl = url
                render()
            }
        }
        DirectRadioPlayerManager.stationsUpdated.observe(viewLifecycleOwner) { stamp ->
            // 0 = valeur initiale : le catalogue est déjà en cours de lecture.
            if (stamp != null && stamp > 0L && selectedTab == TAB_FEATURED && drill == null) reloadFeatured()
        }
        // Le compteur « 51 345 stations » se remplit dès que la liste des pays est connue.
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            countries = MissaRadioBrowse.countries(ctx)
            updateCounter()
            if (selectedTab == TAB_COUNTRIES && drill == null) render()
        }
        viewLifecycleOwner.lifecycleScope.launch {
            DirectRadioPlayerManager.refreshRemotePresetsIfNeeded()
        }
    }

    private fun updateCounter() {
        val total = countries.sumOf { it.stationCount }
        setCounter(
            if (total > 0) getString(R.string.radio_counter, NumberFormat.getIntegerInstance().format(total)) else null
        )
    }

    // ------------------------------------------------------------------ Onglets

    override fun onTabSelected(tabId: String) {
        closeDrill()
        when (tabId) {
            TAB_FEATURED -> reloadFeatured()
            TAB_COUNTRIES -> loadCountries()
            else -> render(scrollToTop = true)
        }
    }

    private fun reloadFeatured() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (featured.isEmpty()) setStatus(getString(R.string.radio_loading))
            featured = DirectRadioPlayerManager.allPresetStations()
            if (!isAdded) return@launch
            setStatus(null)
            if (selectedTab == TAB_FEATURED && drill == null) render()
        }
    }

    private fun loadCountries() {
        render(scrollToTop = true)
        if (countries.isNotEmpty()) return
        setStatus(getString(R.string.radio_loading))
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            countries = MissaRadioBrowse.countries(ctx)
            if (!isAdded) return@launch
            updateCounter()
            setStatus(if (countries.isEmpty()) getString(R.string.radio_offline) else null)
            if (selectedTab == TAB_COUNTRIES && drill == null) render()
        }
    }

    // ------------------------------------------------------------------ Sous-niveaux

    private fun openDrill(target: Drill, backLabel: String, fromKey: String?) {
        drill = target
        returnFocusKey = fromKey
        stations = emptyList()
        nextOffset = 0
        endReached = false
        showCrumb(backLabel, drillTitle())
        // La recherche montre d'abord ce que l'application connaît déjà.
        if (target is Drill.Search) {
            stations = MissaRadioBrowse.appendUnique(
                emptyList(),
                (featured + DirectRadioPlayerManager.savedFavoriteStations())
                    .filter { MissaRadioProviders.matchesStationQuery(it, target.query) }
            )
        }
        render(scrollToTop = true)
        loadNextPage()
    }

    private fun closeDrill() {
        pageJob?.cancel()
        pageJob = null
        loadGeneration++
        drill = null
        stations = emptyList()
        hideCrumb()
        setStatus(null)
    }

    override fun onBackInside(): Boolean {
        if (drill == null) return false
        val focusKey = returnFocusKey
        closeDrill()
        render(focusKey = focusKey)
        return true
    }

    private fun drillTitle(): String {
        val count = stations.size
        return when (val d = drill) {
            is Drill.Country -> getString(
                R.string.radio_crumb_count, d.country.name,
                NumberFormat.getIntegerInstance().format(d.country.stationCount)
            )
            is Drill.Theme -> if (count > 0) {
                getString(R.string.radio_crumb_count, getString(d.theme.labelRes), if (endReached) "$count" else "$count+")
            } else {
                getString(d.theme.labelRes)
            }
            is Drill.Search -> getString(R.string.radio_crumb_search, d.query)
            null -> ""
        }
    }

    override fun onNearEnd() {
        if (drill != null) loadNextPage()
    }

    private fun loadNextPage() {
        val target = drill ?: return
        if (endReached || pageJob?.isActive == true) return
        val generation = loadGeneration
        val offset = nextOffset
        pageJob = viewLifecycleOwner.lifecycleScope.launch {
            if (stations.isEmpty()) setStatus(getString(R.string.radio_loading))
            val page = try {
                when (target) {
                    is Drill.Country -> MissaRadioBrowse.stationsOfCountry(target.country.iso, offset)
                    is Drill.Theme -> MissaRadioBrowse.stationsOfTag(target.theme.tag, offset)
                    is Drill.Search -> MissaRadioBrowse.searchByName(target.query, offset)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!isAdded || generation != loadGeneration) return@launch
            if (page == null) {
                // Réseau en panne : on s'arrête ici, un nouvel appui sur le pays relancera.
                endReached = true
                setStatus(if (stations.isEmpty()) getString(R.string.radio_offline) else null)
            } else {
                nextOffset = offset + MissaRadioBrowse.PAGE_SIZE
                if (page.size < MissaRadioBrowse.PAGE_SIZE) endReached = true
                stations = MissaRadioBrowse.appendUnique(stations, page)
                setStatus(if (stations.isEmpty()) getString(R.string.radio_empty) else null)
            }
            updateCrumbTitle(drillTitle())
            render()
        }
    }

    // ------------------------------------------------------------------ Rendu

    private var renderJob: Job? = null

    /**
     * Prépare les tuiles hors du fil principal (nom du pays traduit, favoris... pour des
     * centaines de stations) puis les affiche : l'écran ne se fige pas pendant la préparation,
     * même sur une petite TV.
     */
    private fun render(scrollToTop: Boolean = false, focusKey: String? = null) {
        if (!isAdded) return
        val tab = selectedTab
        val currentDrill = drill
        val currentStations = stations
        val more = !endReached
        val ctx = context ?: return
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val items = withContext(Dispatchers.Default) {
                val favIds = DirectRadioPlayerManager.favoriteIds()
                when {
                    currentDrill != null -> buildList {
                        currentStations.forEach { add(stationItem(ctx, it, favIds)) }
                        if (more) add(LogoGridItem.More())
                    }

                    tab == TAB_FEATURED -> buildFeatured(ctx, favIds)
                    tab == TAB_FAVORITES -> favoriteStations(favIds).map { stationItem(ctx, it, favIds) }
                    tab == TAB_COUNTRIES -> buildCountries(ctx)
                    tab == TAB_THEMES -> MissaRadioBrowse.themes.map { theme ->
                        val label = ctx.getString(theme.labelRes)
                        LogoGridItem.Folder(
                            key = "theme:${theme.id}",
                            title = label,
                            subtitle = "",
                            badge = label.take(1).uppercase(Locale.getDefault())
                        )
                    }

                    else -> emptyList()
                }
            }
            if (!isAdded) return@launch
            if (currentDrill == null && tab == TAB_FAVORITES) {
                setStatus(if (items.isEmpty()) getString(R.string.radio_favorites_empty) else null)
            }
            submit(items, scrollToTop = scrollToTop, focusKey = focusKey)
        }
    }

    private fun buildFeatured(ctx: Context, favIds: Set<String>): List<LogoGridItem> {
        if (featured.isEmpty()) return emptyList()
        val curatedIds = MissaRadioProviders.curatedInstantStations.mapTo(HashSet()) { it.id }
        val (mine, rest) = featured.partition { it.isCustom || it.id in curatedIds }
        return buildList {
            if (mine.isNotEmpty()) {
                add(LogoGridItem.Header("h:missa", ctx.getString(R.string.radio_section_missa)))
                mine.forEach { add(stationItem(ctx, it, favIds)) }
            }
            if (rest.isNotEmpty()) {
                add(LogoGridItem.Header("h:popular", ctx.getString(R.string.radio_section_popular)))
                rest.forEach { add(stationItem(ctx, it, favIds)) }
            }
        }
    }

    private fun favoriteStations(favIds: Set<String>): List<RadioStationPreset> {
        val saved = DirectRadioPlayerManager.savedFavoriteStations()
        val savedIds = saved.mapTo(HashSet()) { it.id }
        // Anciennes favorites (avant la v2) : retrouvées dans le catalogue par leur identifiant.
        val legacy = featured.filter { it.id in favIds && it.id !in savedIds }
        return (saved + legacy).filter { it.id in favIds }
    }

    private fun buildCountries(ctx: Context): List<LogoGridItem> = buildList {
        var continent: MissaRadioBrowse.Continent? = null
        for (c in countries) {
            if (c.continent != continent) {
                continent = c.continent
                add(LogoGridItem.Header("h:${c.continent.name}", ctx.getString(c.continent.labelRes)))
            }
            add(
                LogoGridItem.Folder(
                    key = "country:${c.iso}",
                    title = c.name,
                    subtitle = ctx.resources.getQuantityString(
                        R.plurals.radio_station_count, c.stationCount,
                        NumberFormat.getIntegerInstance().format(c.stationCount)
                    ),
                    badge = c.iso,
                    imageUrl = "https://flagcdn.com/w80/${c.iso.lowercase(Locale.ROOT)}.png"
                )
            )
        }
    }

    private fun stationItem(ctx: Context, st: RadioStationPreset, favIds: Set<String>): LogoGridItem.Logo {
        val logos = (listOfNotNull(st.logoUrl) + st.logoFallbackUrls).filter { it.isNotBlank() }.distinct()
        val origin = RadioOrigin.displayLabel(st.country, ctx)
        return LogoGridItem.Logo(
            key = "st:" + st.streamUrl.lowercase(Locale.ROOT),
            title = st.name,
            logos = logos,
            favorite = st.id in favIds,
            playing = st.streamUrl.equals(playingUrl, ignoreCase = true),
            description = listOf(st.name, origin, st.genre).filter { it.isNotBlank() }.joinToString(", ")
        )
    }

    // ------------------------------------------------------------------ Actions

    private fun currentStationList(): List<RadioStationPreset> = when {
        drill != null -> stations
        selectedTab == TAB_FAVORITES -> favoriteStations(DirectRadioPlayerManager.favoriteIds())
        else -> featured
    }

    private fun stationFor(item: LogoGridItem): RadioStationPreset? {
        val url = item.key.removePrefix("st:")
        return currentStationList().firstOrNull { it.streamUrl.lowercase(Locale.ROOT) == url }
    }

    override fun onItemClick(item: LogoGridItem) {
        val ctx = context ?: return
        when {
            item.key.startsWith("country:") -> {
                val iso = item.key.removePrefix("country:")
                countries.firstOrNull { it.iso == iso }?.let {
                    openDrill(Drill.Country(it), getString(R.string.radio_tab_countries), item.key)
                }
            }

            item.key.startsWith("theme:") -> {
                val id = item.key.removePrefix("theme:")
                MissaRadioBrowse.themes.firstOrNull { it.id == id }?.let {
                    openDrill(Drill.Theme(it), getString(R.string.radio_tab_themes), item.key)
                }
            }

            item is LogoGridItem.Logo -> {
                val st = stationFor(item) ?: return
                DirectRadioPlayerManager.browseQueue = currentStationList()
                DirectRadioPlayerManager.playStation(ctx, st)
            }
        }
    }

    override fun onItemLongClick(item: LogoGridItem) {
        val ctx = context ?: return
        val st = stationFor(item) ?: return
        val isFav = DirectRadioPlayerManager.isFavorite(st.id)
        val options = listOf(
            getString(R.string.radio_option_play) to { onItemClick(item) },
            getString(if (isFav) R.string.radio_option_unfavorite else R.string.radio_option_favorite) to {
                val now = DirectRadioPlayerManager.toggleFavoriteStation(st)
                Toast.makeText(
                    ctx,
                    getString(if (now) R.string.radio_favorite_added else R.string.radio_favorite_removed, st.name),
                    Toast.LENGTH_SHORT
                ).show()
                render()
            }
        )
        val origin = RadioOrigin.displayLabel(st.country, ctx)
        AlertDialog.Builder(ctx)
            .setTitle(listOf(st.name, origin).filter { it.isNotBlank() }.joinToString(" · "))
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    private fun askSearch() {
        val ctx = context ?: return
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setSingleLine()
            hint = getString(R.string.radio_search_hint)
        }
        val box = FrameLayout(ctx).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.radio_action_search)
            .setView(box)
            .setPositiveButton(R.string.radio_action_search) { _, _ -> runSearch(input.text?.toString().orEmpty()) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                dialog.dismiss()
                runSearch(input.text?.toString().orEmpty())
                true
            } else false
        }
        dialog.show()
        input.requestFocus()
    }

    private fun runSearch(raw: String) {
        val q = raw.trim()
        if (q.length < 2) return
        pageJob?.cancel()
        loadGeneration++
        val tabLabel = tabs().firstOrNull { it.first == selectedTab }?.second ?: getString(R.string.radio_tab_featured)
        openDrill(Drill.Search(q), tabLabel, null)
    }

    private fun askSleepTimer() {
        val ctx = context ?: return
        val minutes = listOf(0, 15, 30, 45, 60, 90, 120)
        val labels = minutes.map {
            if (it == 0) getString(R.string.radio_sleep_off) else getString(R.string.radio_sleep_minutes, it)
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.radio_action_sleep)
            .setItems(labels.toTypedArray()) { _, which ->
                DirectRadioPlayerManager.scheduleSleepTimer(minutes[which])
                Toast.makeText(ctx, labels[which], Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    companion object {
        private const val TAB_FEATURED = "featured"
        private const val TAB_FAVORITES = "favorites"
        private const val TAB_COUNTRIES = "countries"
        private const val TAB_THEMES = "themes"
    }
}
