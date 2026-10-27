package com.lagradost.cloudstream3.ui.universe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.result.START_ACTION_RESUME_LATEST
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_FOCUSED
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_SHOW_METADATA
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.search.SearchResultBuilder
import com.lagradost.cloudstream3.utils.AppContextUtils.loadSearchResult
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.hideKeyboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Multi-universe cinematic discovery screen for MISSA TV 360 PLUS.
 *
 * Implements:
 * - HOME-01 / HOME-02: Split ListAdapter + DiffUtil items, sticky compact filter bar, capped Hero height.
 * - HOME-05 / HOME-06: Shimmer skeleton + provider progress & 4 distinct empty/error states.
 * - HOME-07 / NAV-04: Scoped in-universe filter/search, vertical grid results, recent chips & TV voice search.
 * - HOME-08 / I18N-01: Short 1–2 word category pills (max 8 + More…), multi-source checkbox sheet.
 * - HOME-10 / HOME-11 / HOME-12 / HOME-13: Editorial rows + See All sort sheet, Undo Snackbar, 1-tap details, SwipeRefreshLayout.
 * - NAV-02 / NAV-03 / NAV-05: Globals.isLayout(TV or EMULATOR), per-universe focus memory, quick-return mobile top shell.
 * - TRUST-01 / FLOW-01 / FLOW-02: Kids Parental PIN lock, 3-step Onboarding Wizard, Live channel favorites & zapping.
 */
class MediaUniverseFragment : Fragment(), UniverseScreenAdapter.Callbacks {
    companion object {
        const val ARG_UNIVERSE_ID = "universe_id"
        private const val MAX_INLINE_CATEGORY_PILLS = 8
        private val focusMemoryByUniverse = ConcurrentHashMap<String, String>()

        /** Contextual filter strip is hidden on Home and shown inside dedicated universes (Films, Series, …). */
        fun shouldShowContextualFilterBar(universeId: String): Boolean =
            universeId.lowercase(Locale.ROOT) != "home"
    }

    private val viewModel: MediaUniverseViewModel by activityViewModels()

    private val universeId: String
        get() = arguments?.getString(ARG_UNIVERSE_ID)?.lowercase(Locale.ROOT) ?: "home"

    /** Contextual category/source/search strip appears inside dedicated universes (Films, Series, …), not on Home. */
    private val showContextualFilterBar: Boolean
        get() = shouldShowContextualFilterBar(universeId)

    private var stickyFilterBar: View? = null
    private var swipeRefresh: SwipeRefreshLayout? = null
    private var masterRecycler: RecyclerView? = null
    private var loadingBar: ProgressBar? = null
    private var categoryPillsRecycler: RecyclerView? = null
    private var kidsPinBtn: MaterialButton? = null
    private var sourceFilterBtn: MaterialButton? = null
    private var toggleFilterBtn: MaterialButton? = null
    private var inlineFilterPanel: View? = null
    private var searchInput: EditText? = null
    private var voiceSearchBtn: ImageButton? = null
    private var searchClearBtn: ImageButton? = null
    private var searchSubmitBtn: MaterialButton? = null
    private var searchProgressText: TextView? = null
    private var recentSearchesScroll: HorizontalScrollView? = null
    private var recentSearchesContainer: LinearLayout? = null

    private var screenAdapter: UniverseScreenAdapter? = null
    private var categoryPillAdapter: CategoryFilterPillAdapter? = null

    private var currentSearchQuery: String = ""
    private var searchOverrideEntries: List<UnifiedCatalogEntry>? = null
    private var selectedCategoryKey: String? = null
    private var selectedSources: Set<String> = emptySet()
    private var showAllRows: Boolean = false
    private var searchJob: Job? = null
    private var lastProjection: UniverseScreenProjection? = null
    private var didRestoreUniverseFocus = false

    private val voiceRecognizerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (spoken.isNotBlank()) {
                inlineFilterPanel?.isVisible = true
                searchInput?.setText(spoken)
                submitInUniverseSearch(spoken)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_media_universe, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val ctx = requireContext()
        HomeChildItemAdapter.updatePosterSize(ctx)
        SearchResultBuilder.updateCache(ctx)

        stickyFilterBar = view.findViewById(R.id.universe_sticky_filter_bar)
        swipeRefresh = view.findViewById(R.id.universe_swipe_refresh)
        masterRecycler = view.findViewById(R.id.universe_master_recycler)
        loadingBar = view.findViewById(R.id.universe_loading_bar)
        categoryPillsRecycler = view.findViewById(R.id.universe_category_pills_recycler)
        kidsPinBtn = view.findViewById(R.id.universe_kids_pin_btn)
        sourceFilterBtn = view.findViewById(R.id.universe_source_filter_btn)
        toggleFilterBtn = view.findViewById(R.id.universe_toggle_filter_btn)
        inlineFilterPanel = view.findViewById(R.id.universe_inline_filter_panel)
        searchInput = view.findViewById(R.id.universe_search_input)
        voiceSearchBtn = view.findViewById(R.id.universe_voice_search_btn)
        searchClearBtn = view.findViewById(R.id.universe_search_clear)
        searchSubmitBtn = view.findViewById(R.id.universe_search_submit)
        searchProgressText = view.findViewById(R.id.universe_search_progress_text)
        recentSearchesScroll = view.findViewById(R.id.universe_recent_searches_scroll)
        recentSearchesContainer = view.findViewById(R.id.universe_recent_searches_container)

        setupStickyFilterBar()
        setupMasterRecycler()

        viewModel.catalogSnapshot.observe(viewLifecycleOwner) { snapshot ->
            val activelyLoading = snapshot.isLoading || searchJob?.isActive == true
            loadingBar?.isVisible = activelyLoading
            if (!activelyLoading) {
                swipeRefresh?.isRefreshing = false
            }
            renderScreen()
        }

        DirectRadioPlayerManager.state.observe(viewLifecycleOwner) {
            if (universeId == "radio" || universeId == "music") {
                renderScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        didRestoreUniverseFocus = false
        viewModel.ensureCatalogLoaded(forceReload = false)
        viewModel.refreshResumeWatching()
    }

    override fun onDestroyView() {
        searchJob?.cancel()
        stickyFilterBar = null
        swipeRefresh = null
        masterRecycler = null
        loadingBar = null
        categoryPillsRecycler = null
        kidsPinBtn = null
        sourceFilterBtn = null
        toggleFilterBtn = null
        inlineFilterPanel = null
        searchInput = null
        voiceSearchBtn = null
        searchClearBtn = null
        searchSubmitBtn = null
        searchProgressText = null
        recentSearchesScroll = null
        recentSearchesContainer = null
        screenAdapter = null
        categoryPillAdapter = null
        super.onDestroyView()
    }

    private fun setupStickyFilterBar() {
        stickyFilterBar?.isVisible = showContextualFilterBar
        if (!showContextualFilterBar) {
            inlineFilterPanel?.isVisible = false
            return
        }

        val ctx = requireContext()
        val def = MissaUniverses.byId(universeId)
        val isTv = MissaUiFactory.isTvLayoutMode()

        // Scoped hint explicitly labels this as in-universe filtering (NAV-04)
        searchInput?.hint = ctx.getString(
            R.string.universe_filter_in_universe_hint,
            ctx.getString(def.titleRes)
        )

        val pillAdapter = CategoryFilterPillAdapter { pill ->
            if (pill.isOverflowMore) {
                val cats = lastProjection?.availableCategories.orEmpty()
                MissaUniverseDialogs.showCategoryOverflowSheet(
                    context = requireContext(),
                    categories = cats,
                    selectedKey = selectedCategoryKey
                ) { chosen ->
                    selectedCategoryKey = chosen
                    renderScreen()
                }
            } else {
                selectedCategoryKey = if (pill.key == selectedCategoryKey) null else pill.key
                renderScreen()
            }
        }
        categoryPillAdapter = pillAdapter
        categoryPillsRecycler?.apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.HORIZONTAL, false)
            itemAnimator = null
            adapter = pillAdapter
        }

        // Kids Parental PIN button (TRUST-01)
        kidsPinBtn?.isVisible = universeId == "kids"
        updateKidsPinButtonLabel()
        kidsPinBtn?.setOnClickListener {
            MissaUniverseDialogs.showKidsPinDialog(
                context = requireContext(),
                unlockOnly = false
            ) {
                updateKidsPinButtonLabel()
                renderScreen()
            }
        }

        // Multi-source filter BottomSheet button (HOME-08)
        sourceFilterBtn?.setOnClickListener {
            val sources = lastProjection?.availableSources.orEmpty()
            MissaUniverseDialogs.showSourceFilterSheet(
                context = requireContext(),
                availableSources = sources,
                initiallySelected = selectedSources
            ) { chosenSet ->
                selectedSources = chosenSet
                renderScreen()
            }
        }

        toggleFilterBtn?.setOnClickListener {
            val panel = inlineFilterPanel ?: return@setOnClickListener
            panel.isVisible = !panel.isVisible
            MissaUiFactory.stylePillSelection(toggleFilterBtn!!, panel.isVisible || currentSearchQuery.isNotBlank())
            if (panel.isVisible) {
                renderRecentSearchChips()
                searchInput?.requestFocus()
            } else {
                hideKeyboard()
            }
        }

        // TV Voice Search Intent (HOME-07)
        val speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        }
        val canHandleVoice = speechIntent.resolveActivity(ctx.packageManager) != null
        voiceSearchBtn?.isVisible = isTv && canHandleVoice
        voiceSearchBtn?.setOnClickListener {
            runCatching { voiceRecognizerLauncher.launch(speechIntent) }
        }

        searchSubmitBtn?.setOnClickListener {
            submitInUniverseSearch(searchInput?.text?.toString().orEmpty())
        }
        searchInput?.setOnEditorActionListener { _, actionId, event ->
            val isEnter = actionId == EditorInfo.IME_ACTION_SEARCH ||
                actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (isEnter) {
                submitInUniverseSearch(searchInput?.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        searchClearBtn?.setOnClickListener {
            onResetAllFilters()
        }
    }

    private fun updateKidsPinButtonLabel() {
        val btn = kidsPinBtn ?: return
        if (!btn.isVisible) return
        val locked = MissaUiFactory.isKidsModeLocked()
        btn.setText(
            if (locked) R.string.universe_kids_pin_locked_badge
            else R.string.universe_kids_set_pin
        )
        MissaUiFactory.stylePillSelection(btn, locked)
    }

    private fun renderRecentSearchChips() {
        val container = recentSearchesContainer ?: return
        val scroll = recentSearchesScroll ?: return
        val recents = viewModel.getRecentSearches()
        scroll.isVisible = recents.isNotEmpty()
        container.removeAllViews()
        if (recents.isEmpty()) return

        val ctx = requireContext()
        for (term in recents) {
            val chip = MissaUiFactory.createPillButton(
                context = ctx,
                label = term,
                selected = term.equals(currentSearchQuery, ignoreCase = true),
                marginEndDp = 6
            ) {
                searchInput?.setText(term)
                submitInUniverseSearch(term)
            }
            container.addView(chip)
        }
        val clearChip = MissaUiFactory.createPillButton(
            context = ctx,
            label = ctx.getString(R.string.universe_clear_recent_searches),
            selected = false,
            marginEndDp = 0
        ) {
            viewModel.clearRecentSearches()
            renderRecentSearchChips()
        }
        container.addView(clearChip)
    }

    private fun setupMasterRecycler() {
        val ctx = requireContext()
        val isTv = MissaUiFactory.isTvLayoutMode()

        // SwipeRefreshLayout on mobile/tablet; disabled on TV where Menu/Refresh button is used (HOME-13)
        swipeRefresh?.apply {
            isEnabled = !isTv
            setColorSchemeColors(ctx.colorFromAttribute(R.attr.missaAccentPrimary))
            setProgressBackgroundColorSchemeColor(ctx.colorFromAttribute(R.attr.missaSurfaceElevated))
            setOnRefreshListener {
                onRefreshCatalog()
            }
        }

        val adapter = UniverseScreenAdapter(callbacks = this)
        screenAdapter = adapter

        masterRecycler?.apply {
            layoutManager = LinearLayoutManager(ctx, LinearLayoutManager.VERTICAL, false)
            // Gentle fade item animator respecting system animation settings (HOME-02, THEME-08)
            itemAnimator = if (MissaUiFactory.areAnimationsEnabled(ctx)) {
                DefaultItemAnimator().apply {
                    supportsChangeAnimations = false
                    addDuration = 160L
                    removeDuration = 120L
                    moveDuration = 160L
                }
            } else {
                null
            }
            this.adapter = adapter

            // Notify MainActivity for quick-return collapsing mobile top shell (NAV-05)
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    if (!isTv && dy != 0) {
                        (activity as? MainActivity)?.onMissaContentScrolled(
                            dy = dy,
                            atTop = !recyclerView.canScrollVertically(-1)
                        )
                    }
                }
            })

            // TV remote MENU key refreshes catalog; CH+/CH- zaps live channels (HOME-13, FLOW-02)
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_MENU -> {
                            onRefreshCatalog()
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_CHANNEL_UP -> if (zapLiveChannel(+1)) return@setOnKeyListener true
                        KeyEvent.KEYCODE_CHANNEL_DOWN -> if (zapLiveChannel(-1)) return@setOnKeyListener true
                    }
                }
                false
            }
        }
    }

    private fun zapLiveChannel(delta: Int): Boolean {
        if (universeId != "live_tv" && universeId != "sports") return false
        val allChannels = lastProjection?.rows?.flatMap { it.items }?.distinctBy { it.url }.orEmpty()
        if (allChannels.isEmpty()) return false
        val lastUrl = viewModel.getLastLiveChannelUrl()
        val currentIdx = allChannels.indexOfFirst { it.url == lastUrl }.let { if (it < 0) 0 else it }
        val next = allChannels[(currentIdx + delta).mod(allChannels.size)]
        viewModel.recordLastLiveChannel(next)
        activity?.loadSearchResult(next, START_ACTION_RESUME_LATEST)
        return true
    }

    private fun renderScreen() {
        val ctx = context ?: return
        val snapshot = viewModel.catalogSnapshot.value ?: UnifiedCatalogSnapshot()
        val projection = viewModel.projectUniverse(
            universeId = universeId,
            snapshot = snapshot,
            overrideEntries = searchOverrideEntries,
            searchQuery = currentSearchQuery,
            selectedCategoryKey = selectedCategoryKey,
            selectedSources = selectedSources,
            showAllRows = showAllRows,
            localizedContext = ctx
        )
        lastProjection = projection

        // 1. Update sticky category pills via DiffUtil (hidden on Home; shown in Films & dedicated universes)
        stickyFilterBar?.isVisible = showContextualFilterBar
        if (showContextualFilterBar) {
            val cats = projection.availableCategories
            val pillItems = ArrayList<CategoryFilterPillUi>()
            if (cats.size > 1) {
                pillItems.add(
                    CategoryFilterPillUi(
                        key = null,
                        label = ctx.getString(R.string.universe_all_categories),
                        selected = selectedCategoryKey.isNullOrBlank()
                    )
                )
                val inlineCats = cats.take(MAX_INLINE_CATEGORY_PILLS)
                for (c in inlineCats) {
                    pillItems.add(
                        CategoryFilterPillUi(
                            key = c.key,
                            label = c.shortLabel,
                            selected = selectedCategoryKey == c.key
                        )
                    )
                }
                if (cats.size > MAX_INLINE_CATEGORY_PILLS) {
                    val overflowSelected = selectedCategoryKey != null && inlineCats.none { it.key == selectedCategoryKey }
                    pillItems.add(
                        CategoryFilterPillUi(
                            key = "__more__",
                            label = ctx.getString(R.string.universe_more_categories),
                            selected = overflowSelected,
                            isOverflowMore = true
                        )
                    )
                }
            }
            categoryPillAdapter?.submitList(pillItems)

            // 2. Update multi-source pill button (HOME-08)
            val sources = projection.availableSources
            sourceFilterBtn?.isVisible = sources.isNotEmpty()
            if (sources.isNotEmpty()) {
                sourceFilterBtn?.text = when {
                    selectedSources.isEmpty() -> ctx.getString(R.string.universe_all_sources, sources.size)
                    selectedSources.size == 1 -> ctx.getString(R.string.universe_single_source, selectedSources.first())
                    else -> ctx.getString(R.string.universe_multi_selected_sources, selectedSources.size)
                }
                sourceFilterBtn?.let { MissaUiFactory.stylePillSelection(it, selectedSources.isNotEmpty()) }
            }

            searchClearBtn?.isVisible = currentSearchQuery.isNotBlank() ||
                !selectedCategoryKey.isNullOrBlank() ||
                selectedSources.isNotEmpty()
            toggleFilterBtn?.let {
                MissaUiFactory.stylePillSelection(
                    it,
                    inlineFilterPanel?.isVisible == true || currentSearchQuery.isNotBlank()
                )
            }
        } else {
            inlineFilterPanel?.isVisible = false
        }

        // 3. Build modular RecyclerView list items (HOME-01, HOME-02, HOME-05, HOME-06, HOME-07)
        val items = ArrayList<UniverseListItem>()
        val dataSaver = MissaUiFactory.isDataSaverActive(ctx)

        if (projection.heroSlides.isNotEmpty()) {
            items.add(
                UniverseListItem.HeroCarousel(
                    universeId = universeId,
                    slides = projection.heroSlides,
                    updatedAtMs = projection.updatedAtMs,
                    dataSaverActive = dataSaver
                )
            )
        } else if (currentSearchQuery.isBlank() && !projection.isLoading) {
            // Compact universe title ONLY when Hero is absent (HOME-01: never duplicate title below Hero)
            items.add(
                UniverseListItem.CompactBanner(
                    definition = projection.definition,
                    isKidsLocked = MissaUiFactory.isKidsModeLocked()
                )
            )
        }

        if (universeId == "radio") {
            val radioState = DirectRadioPlayerManager.state.value ?: RadioPlaybackState()
            val isFav = radioState.station?.id?.let { DirectRadioPlayerManager.isFavorite(it) } == true
            items.add(UniverseListItem.RadioDeck(radioState = radioState, isFavoriteStation = isFav))
        }

        if (projection.resumeItems.isNotEmpty() && currentSearchQuery.isBlank()) {
            items.add(
                UniverseListItem.ResumeRow(
                    universeId = universeId,
                    items = projection.resumeItems
                )
            )
        }

        if (projection.hubShortcuts.isNotEmpty() && currentSearchQuery.isBlank()) {
            items.add(UniverseListItem.HubShortcutsRow(items = projection.hubShortcuts))
        }

        when {
            projection.isLoading && projection.rows.isEmpty() -> {
                items.add(
                    UniverseListItem.ShimmerLoading(
                        loadedProviders = projection.loadedProvidersCount,
                        totalProviders = projection.totalProvidersCount
                    )
                )
            }
            projection.rows.isNotEmpty() -> {
                if (currentSearchQuery.isNotBlank()) {
                    for (row in projection.rows) {
                        items.add(UniverseListItem.SearchGrid(row))
                    }
                } else {
                    for (row in projection.rows) {
                        items.add(UniverseListItem.CategoryRow(row))
                    }
                    if (projection.hiddenRowCount > 0) {
                        items.add(UniverseListItem.ShowMoreRows(projection.hiddenRowCount))
                    }
                }
            }
            projection.emptyReason != null -> {
                items.add(
                    UniverseListItem.EmptyOrError(
                        reason = projection.emptyReason,
                        definition = projection.definition
                    )
                )
            }
        }

        screenAdapter?.submitList(items) {
            restoreUniverseFocusIfNeeded(items)
        }
    }

    /** Restores last focused row when returning to a previously visited universe on TV (NAV-02). */
    private fun restoreUniverseFocusIfNeeded(items: List<UniverseListItem>) {
        if (didRestoreUniverseFocus || !MissaUiFactory.isTvLayoutMode()) return
        val rememberedRowId = focusMemoryByUniverse[universeId] ?: return
        val idx = items.indexOfFirst { it.itemId == rememberedRowId }
        if (idx >= 0) {
            didRestoreUniverseFocus = true
            masterRecycler?.post {
                val vh = masterRecycler?.findViewHolderForAdapterPosition(idx)
                vh?.itemView?.requestFocus()
            }
        }
    }

    private fun submitInUniverseSearch(query: String) {
        hideKeyboard()
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            onResetAllFilters()
            return
        }
        currentSearchQuery = trimmed
        viewModel.recordRecentSearch(trimmed)
        renderRecentSearchChips()

        // First render immediate local matches across already-loaded catalog
        searchOverrideEntries = null
        renderScreen()

        // Also query active background extensions progressively (HOME-07)
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            loadingBar?.isVisible = true
            searchProgressText?.isVisible = true
            try {
                val results = viewModel.searchUnified(trimmed) { done, total ->
                    view?.post {
                        if (isAdded) {
                            searchProgressText?.isVisible = true
                            searchProgressText?.text = getString(
                                R.string.missa_searching_sources_progress,
                                done,
                                total
                            )
                        }
                    }
                }
                if (isAdded) {
                    if (results.isNotEmpty()) {
                        searchOverrideEntries = results
                    }
                    renderScreen()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isAdded) renderScreen()
            } finally {
                if (isAdded) {
                    loadingBar?.isVisible = viewModel.catalogSnapshot.value?.isLoading == true
                    searchProgressText?.isVisible = false
                }
            }
        }
    }

    // --- UniverseScreenAdapter.Callbacks ---

    override fun onHeroPrimaryAction(slide: HeroBannerSlide) {
        val item = slide.card
        if (item is DirectRadioSearchResponse) {
            DirectRadioPlayerManager.playDirectResponse(requireContext(), item)
            return
        }
        val preferred = viewModel.resolvePreferredCard(item, slide.allSources)
        if (universeId == "live_tv" || universeId == "sports") {
            viewModel.recordLastLiveChannel(preferred)
        }
        activity?.loadSearchResult(preferred, START_ACTION_RESUME_LATEST)
    }

    /** 1-tap details screen using remembered preferred source (HOME-12). */
    override fun onHeroDetailsAction(slide: HeroBannerSlide) {
        val item = slide.card
        if (item is DirectRadioSearchResponse) {
            DirectRadioPlayerManager.playDirectResponse(requireContext(), item)
            return
        }
        val preferred = viewModel.resolvePreferredCard(item, slide.allSources)
        activity?.loadSearchResult(preferred)
    }

    override fun onNavigateUniverse(definition: UniverseDefinition) {
        (activity as? MainActivity)?.navigateToUniverseWithParentalGuard(definition) ?: run {
            if (findNavController().currentDestination?.id == definition.destinationId) return
            val options = NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setRestoreState(true)
                .setPopUpTo(R.id.navigation_home, inclusive = false, saveState = true)
                .build()
            runCatching {
                findNavController().navigate(definition.destinationId, null, options)
            }.onFailure { logError(it) }
        }
    }

    override fun onCardClick(callback: SearchClickCallback) {
        if (callback.action == SEARCH_ACTION_FOCUSED) return
        val card = callback.card

        if (card is DirectRadioSearchResponse) {
            DirectRadioPlayerManager.playDirectResponse(requireContext(), card)
            return
        }

        if (callback.action == SEARCH_ACTION_SHOW_METADATA) {
            // Long-press on Live TV / Sports toggles Favorite channel (FLOW-02)
            if (universeId == "live_tv" || universeId == "sports") {
                val nowFav = viewModel.toggleFavoriteLiveChannel(card.url)
                Toast.makeText(
                    requireContext(),
                    if (nowFav) R.string.universe_radio_favorite_toggle else R.string.universe_retro_deleted,
                    Toast.LENGTH_SHORT
                ).show()
                renderScreen()
                return
            }

            // Long-press on multi-source VOD card lets user pick & remember preferred source (HOME-12)
            val alternates = viewModel.getAlternateSourcesFor(card)
            if (alternates.size > 1) {
                MissaUniverseDialogs.showPreferredSourceChooser(
                    context = requireContext(),
                    primary = card,
                    sources = alternates
                ) { chosen, directPlay ->
                    viewModel.rememberPreferredSource(chosen)
                    if (directPlay) {
                        activity?.loadSearchResult(chosen, START_ACTION_RESUME_LATEST)
                    } else {
                        activity?.loadSearchResult(chosen)
                    }
                }
                return
            }
            SearchHelper.handleSearchClickCallback(callback)
            return
        }

        val def = MissaUniverses.byId(universeId)
        val preferred = viewModel.resolvePreferredCard(card)
        if (callback.action == SEARCH_ACTION_LOAD &&
            def.directPlayOnClick &&
            card !is DataStoreHelper.ResumeWatchingResult
        ) {
            if (universeId == "live_tv" || universeId == "sports") {
                viewModel.recordLastLiveChannel(preferred)
            }
            activity?.loadSearchResult(preferred, START_ACTION_RESUME_LATEST)
            return
        }

        if (preferred !== card) {
            activity?.loadSearchResult(preferred)
        } else {
            SearchHelper.handleSearchClickCallback(callback)
        }
    }

    /** Single-item Resume removal on long-press with Undo Snackbar (HOME-11). */
    override fun onResumeSingleRemove(item: DataStoreHelper.ResumeWatchingResult) {
        val saved = viewModel.removeSingleResumeItem(item.id)
        val rootView = view ?: return
        val snackbar = Snackbar.make(rootView, R.string.universe_resume_removed, Snackbar.LENGTH_LONG)
        if (saved != null) {
            snackbar.setAction(R.string.universe_resume_undo) {
                viewModel.restoreResumeItem(saved)
            }
        }
        snackbar.show()
    }

    override fun onClearAllResume(items: List<DataStoreHelper.ResumeWatchingResult>) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.universe_resume_clear_all)
            .setMessage(R.string.universe_resume_clear_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewModel.clearResumeItems(items)
            }
            .show()
            .setDefaultFocus()
    }

    override fun onRowSeeAll(row: UniverseCategoryRow) {
        val act = activity ?: return
        MissaUniverseDialogs.showSeeAllGridSheet(act, row) { cb ->
            onCardClick(cb)
        }
    }

    override fun onExpandHiddenRows() {
        showAllRows = true
        renderScreen()
    }

    override fun onOpenOnboardingOrExtensions() {
        val ctx = context ?: return
        if (!MissaUiFactory.hasCompletedMissaOnboarding()) {
            MissaUniverseDialogs.showOnboardingWizard(
                context = ctx,
                onOpenExtensions = { navigateToExtensionsScreen() },
                onFinished = { renderScreen() }
            )
        } else {
            navigateToExtensionsScreen()
        }
    }

    private fun navigateToExtensionsScreen() {
        runCatching {
            findNavController().navigate(R.id.navigation_settings_extensions)
        }.onFailure { logError(it) }
    }

    override fun onRefreshCatalog() {
        if (currentSearchQuery.isNotBlank()) {
            submitInUniverseSearch(currentSearchQuery)
            swipeRefresh?.isRefreshing = false
        } else {
            viewModel.ensureCatalogLoaded(forceReload = true)
        }
    }

    override fun onResetAllFilters() {
        searchJob?.cancel()
        currentSearchQuery = ""
        searchOverrideEntries = null
        selectedCategoryKey = null
        selectedSources = emptySet()
        showAllRows = false
        searchInput?.setText("")
        searchProgressText?.isVisible = false
        hideKeyboard()
        renderScreen()
    }

    override fun onRadioTogglePlayPause() {
        DirectRadioPlayerManager.togglePlayPause(requireContext())
    }

    override fun onRadioPrevStation() {
        DirectRadioPlayerManager.previousStation(requireContext())
    }

    override fun onRadioNextStation() {
        DirectRadioPlayerManager.nextStation(requireContext())
    }

    override fun onRadioStop() {
        DirectRadioPlayerManager.stop()
    }

    override fun onRadioToggleFavorite() {
        val st = DirectRadioPlayerManager.state.value?.station ?: return
        DirectRadioPlayerManager.toggleFavorite(st.id)
        renderScreen()
    }

    override fun onRadioCycleSleepTimer() {
        val ctx = requireContext()
        val options = arrayOf(
            ctx.getString(R.string.universe_radio_sleep_off),
            ctx.getString(R.string.universe_radio_sleep_15),
            ctx.getString(R.string.universe_radio_sleep_30),
            ctx.getString(R.string.universe_radio_sleep_60)
        )
        val minutes = intArrayOf(0, 15, 30, 60)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.universe_radio_sleep_timer)
            .setItems(options) { _, which ->
                val mins = minutes.getOrElse(which) { 0 }
                DirectRadioPlayerManager.scheduleSleepTimer(mins)
                if (mins > 0) {
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.universe_radio_sleep_set, mins),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            .show()
            .setDefaultFocus()
    }

    override fun onRadioOpenCustomStreamDialog() {
        MissaUniverseDialogs.showCustomRadioStreamDialog(requireContext()) {
            renderScreen()
        }
    }

    override fun onContentItemFocused(rowKey: String) {
        focusMemoryByUniverse[universeId] = rowKey
    }

    /** Moves TV D-pad focus into the contextual filter strip (in dedicated universes) or primary content (NAV-02). */
    fun requestPrimaryContentFocus(): Boolean {
        val rv = masterRecycler ?: return false
        val rememberedRowId = focusMemoryByUniverse[universeId]
        if (rememberedRowId != null) {
            val adapterItems = screenAdapter?.currentList.orEmpty()
            val idx = adapterItems.indexOfFirst { it.itemId == rememberedRowId }
            if (idx >= 0) {
                val vh = rv.findViewHolderForAdapterPosition(idx)
                if (vh?.itemView?.requestFocus() == true) return true
            }
        }
        if (showContextualFilterBar && stickyFilterBar?.isVisible == true) {
            val pillsRv = categoryPillsRecycler
            if (pillsRv != null) {
                for (i in 0 until pillsRv.childCount) {
                    if (pillsRv.getChildAt(i)?.requestFocus() == true) return true
                }
            }
            if (sourceFilterBtn?.isVisible == true && sourceFilterBtn?.requestFocus() == true) return true
            if (toggleFilterBtn?.isVisible == true && toggleFilterBtn?.requestFocus() == true) return true
        }
        for (i in 0 until rv.childCount) {
            if (rv.getChildAt(i)?.requestFocus() == true) return true
        }
        return rv.requestFocus()
    }
}
