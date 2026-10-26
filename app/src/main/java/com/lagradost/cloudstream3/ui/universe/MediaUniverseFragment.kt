package com.lagradost.cloudstream3.ui.universe

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
import com.lagradost.cloudstream3.APIHolder.apis
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_LOAD
import com.lagradost.cloudstream3.ui.search.SearchAdapter
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.search.SearchHelper
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.getResourceColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * A provider-agnostic catalogue screen. Every universe is a first-class navigation destination,
 * while providers remain sources beneath the same catalogue and result adapter.
 */
class MediaUniverseFragment : Fragment() {
    companion object {
        const val ARG_UNIVERSE_ID = "universe_id"
        private const val REQUEST_TIMEOUT_MS = 18_000L
        private const val SEARCH_TIMEOUT_MS = 35_000L
        private const val MAX_CATALOG_ITEMS = 100
        private const val MAX_METADATA_ITEMS = 24
        private const val DETAIL_TIMEOUT_MS = 6_000L

    }

    private val universeId: String
        get() = arguments?.getString(ARG_UNIVERSE_ID)?.lowercase(Locale.ROOT) ?: "home"

    private var requestJob: kotlinx.coroutines.Job? = null
    private var catalogueAdapter: SearchAdapter? = null
    private var resumeAdapter: SearchAdapter? = null

    private lateinit var universeTitle: TextView
    private lateinit var universeDescription: TextView
    private lateinit var heroPanel: View
    private lateinit var heroImage: ImageView
    private lateinit var heroTitle: TextView
    private lateinit var heroPlay: Button
    private lateinit var searchInput: EditText
    private lateinit var searchButton: Button
    private lateinit var loading: ProgressBar
    private lateinit var emptyMessage: TextView
    private lateinit var resultHeader: TextView
    private lateinit var resultList: com.lagradost.cloudstream3.ui.AutofitRecyclerView
    private lateinit var resumeHeader: TextView
    private lateinit var resumeList: com.lagradost.cloudstream3.ui.AutofitRecyclerView
    private lateinit var shortcutsHeader: TextView
    private lateinit var shortcutsScroll: android.widget.HorizontalScrollView
    private lateinit var shortcutsContainer: LinearLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_media_universe, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        universeTitle = view.findViewById(R.id.universe_title)
        universeDescription = view.findViewById(R.id.universe_description)
        heroPanel = view.findViewById(R.id.universe_hero_panel)
        heroImage = view.findViewById(R.id.universe_hero_image)
        heroTitle = view.findViewById(R.id.universe_hero_title)
        heroPlay = view.findViewById(R.id.universe_hero_play)
        searchInput = view.findViewById(R.id.universe_search_input)
        searchButton = view.findViewById(R.id.universe_search_button)
        loading = view.findViewById(R.id.universe_loading)
        emptyMessage = view.findViewById(R.id.universe_empty_message)
        resultHeader = view.findViewById(R.id.universe_results_header)
        resultList = view.findViewById(R.id.universe_results)
        resumeHeader = view.findViewById(R.id.universe_resume_header)
        resumeList = view.findViewById(R.id.universe_resume_results)
        shortcutsHeader = view.findViewById(R.id.universe_shortcuts_header)
        shortcutsScroll = view.findViewById(R.id.universe_shortcuts_scroll)
        shortcutsContainer = view.findViewById(R.id.universe_shortcuts_container)

        resultList.spanCount = if (isTvLayout()) 5 else 2
        resultList.layoutManager = GridLayoutManager(requireContext(), resultList.spanCount)
        resultList.isNestedScrollingEnabled = false
        resultList.setRecycledViewPool(SearchAdapter.sharedPool)
        catalogueAdapter = SearchAdapter(resultList, false) { callback ->
            SearchHelper.handleSearchClickCallback(callback)
        }
        resultList.adapter = catalogueAdapter

        resumeList.spanCount = if (isTvLayout()) 4 else 2
        resumeList.layoutManager = GridLayoutManager(requireContext(), resumeList.spanCount)
        resumeList.isNestedScrollingEnabled = false
        resumeList.setRecycledViewPool(SearchAdapter.sharedPool)
        resumeAdapter = SearchAdapter(resumeList, true) { callback ->
            SearchHelper.handleSearchClickCallback(callback)
        }
        resumeList.adapter = resumeAdapter

        configureHeader()
        heroPanel.isVisible = universeId == "home"
        resumeHeader.isVisible = universeId == "home"
        resumeList.isVisible = universeId == "home"
        shortcutsHeader.isVisible = universeId == "home"
        shortcutsScroll.isVisible = universeId == "home"
        if (universeId == "home") configureUniverseShortcuts()

        searchButton.setOnClickListener { loadCatalogue(searchInput.text?.toString().orEmpty()) }
        searchInput.setOnEditorActionListener { _, actionId, event ->
            val submit = actionId == EditorInfo.IME_ACTION_SEARCH ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (submit) {
                loadCatalogue(searchInput.text?.toString().orEmpty())
                true
            } else false
        }

        if (universeId == "home") loadContinueWatching()
        loadCatalogue("")
    }

    override fun onDestroyView() {
        requestJob?.cancel()
        catalogueAdapter = null
        resumeAdapter = null
        super.onDestroyView()
    }

    private fun isTvLayout(): Boolean {
        val configuration = resources.configuration
        return configuration.smallestScreenWidthDp >= 600 ||
            (configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK) ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    private fun configureHeader() {
        val (title, description) = when (universeId) {
            "films" -> R.string.universe_films_title to R.string.universe_films_description
            "series" -> R.string.universe_series_title to R.string.universe_series_description
            "shorts" -> R.string.universe_shorts_title to R.string.universe_shorts_description
            "kids" -> R.string.universe_kids_title to R.string.universe_kids_description
            "live_tv" -> R.string.universe_live_tv_title to R.string.universe_live_tv_description
            "sports" -> R.string.universe_sports_title to R.string.universe_sports_description
            "podcasts" -> R.string.universe_podcasts_title to R.string.universe_podcasts_description
            "radio" -> R.string.universe_radio_title to R.string.universe_radio_description
            else -> R.string.universe_home_title to R.string.universe_home_description
        }
        universeTitle.setText(title)
        universeDescription.setText(description)
        resultHeader.setText(if (universeId == "home") R.string.universe_home_catalogue else R.string.universe_catalogue_results)
        searchInput.hint = getString(R.string.universe_search_hint)
    }

    private fun configureUniverseShortcuts() {
        val entries = listOf(
            R.id.navigation_universe_films to R.string.universe_films_title,
            R.id.navigation_universe_series to R.string.universe_series_title,
            R.id.navigation_universe_shorts to R.string.universe_shorts_title,
            R.id.navigation_universe_kids to R.string.universe_kids_title,
            R.id.navigation_universe_live_tv to R.string.universe_live_tv_title,
            R.id.navigation_universe_sports to R.string.universe_sports_title,
            R.id.navigation_universe_podcasts to R.string.universe_podcasts_title,
            R.id.navigation_universe_radio to R.string.universe_radio_title,
            R.id.navigation_universe_retro_games to R.string.universe_games_title
        )
        shortcutsContainer.removeAllViews()
        val density = resources.displayMetrics.density
        entries.forEachIndexed { index, entry ->
            val button = MaterialButton(
                requireContext(),
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                text = getString(entry.second)
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                insetTop = 0
                insetBottom = 0
                cornerRadius = (10 * density).toInt()
                strokeWidth = (1 * density).toInt()
                setPadding((13 * density).toInt(), 0, (13 * density).toInt(), 0)
                backgroundTintList = ColorStateList.valueOf(
                    requireContext().getResourceColor(
                        if (index % 3 == 0) R.attr.colorPrimary else R.attr.iconGrayBackground
                    )
                )
                setTextColor(
                    requireContext().getResourceColor(
                        if (index % 3 == 0) R.attr.black else R.attr.textColor
                    )
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    (42 * density).toInt()
                ).apply {
                    marginEnd = (7 * density).toInt()
                }
                setOnClickListener {
                    runCatching { findNavController().navigate(entry.first) }
                        .onFailure { com.lagradost.cloudstream3.mvvm.logError(it) }
                }
            }
            shortcutsContainer.addView(button)
        }
    }

    private fun loadContinueWatching() {
        viewLifecycleOwner.lifecycleScope.launch {
            val resume = withContext(Dispatchers.IO) {
                try {
                    HomeViewModel.getResumeWatching().orEmpty().take(24)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
            }
            if (!isAdded) return@launch
            resumeHeader.isVisible = resume.isNotEmpty()
            resumeList.isVisible = resume.isNotEmpty()
            resumeAdapter?.submitList(resume)
        }
    }

    private fun loadCatalogue(query: String) {
        requestJob?.cancel()
        requestJob = viewLifecycleOwner.lifecycleScope.launch {
            loading.isVisible = true
            emptyMessage.isVisible = false
            resultList.isVisible = false
            heroPanel.isVisible = universeId == "home" && query.isBlank()
            searchButton.isEnabled = false
            try {
                val repositories = withContext(Dispatchers.IO) {
                    apis.withLock { apis.map { APIRepository(it) } }
                }
                val sourceItems = if (query.isBlank()) {
                    fetchHomeItems(repositories)
                } else {
                    fetchSearchItems(repositories, query.trim())
                }
                val filtered = filterForUniverse(sourceItems, repositories)
                    .distinctBy { item -> "${item.apiName.lowercase(Locale.ROOT)}|${item.url}" }
                    .take(MAX_CATALOG_ITEMS)

                if (universeId == "home" && query.isBlank()) {
                    val featured = filtered.firstOrNull()
                    heroPanel.isVisible = featured != null
                    if (featured != null) {
                        heroTitle.text = featured.name
                        if (!featured.posterUrl.isNullOrBlank()) {
                            heroImage.loadImage(featured.posterUrl, featured.posterHeaders)
                        } else {
                            heroImage.setImageResource(R.drawable.default_cover)
                        }
                        heroPlay.setOnClickListener { view ->
                            SearchHelper.handleSearchClickCallback(
                                SearchClickCallback(SEARCH_ACTION_LOAD, view, 0, featured)
                            )
                        }
                    }
                }
                catalogueAdapter?.submitList(filtered)
                resultList.isVisible = filtered.isNotEmpty()
                emptyMessage.isVisible = filtered.isEmpty()
                emptyMessage.setText(
                    if (query.isBlank()) R.string.universe_empty_home
                    else R.string.universe_empty_search
                )
                if (query.isNotBlank()) resultHeader.text = getString(R.string.universe_search_results_for, query.trim())
                else resultHeader.setText(if (universeId == "home") R.string.universe_home_catalogue else R.string.universe_catalogue_results)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (isAdded) {
                    emptyMessage.setText(R.string.universe_catalogue_error)
                    emptyMessage.isVisible = true
                    resultList.isVisible = false
                }
            } finally {
                // A cancelled older request must not hide the loading indicator for a newer query.
                if (isAdded && currentCoroutineContext().isActive) {
                    loading.isVisible = false
                    searchButton.isEnabled = true
                }
            }
        }
    }

    private suspend fun fetchHomeItems(repositories: List<APIRepository>): List<SearchResponse> = coroutineScope {
        val semaphore = Semaphore(4)
        repositories
            .filter { it.hasMainPage }
            .map { repository ->
                async(Dispatchers.IO) {
                    // Do not spend the provider timeout waiting for a semaphore slot.
                    semaphore.withPermit {
                        withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                            try {
                                when (val response = repository.getMainPage(1)) {
                                    is Resource.Success -> response.value
                                        .filterNotNull()
                                        .flatMap { page -> page.items }
                                        .flatMap { section -> section.list }
                                    else -> emptyList()
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }.orEmpty()
                    }
                }
            }
            .awaitAll()
            .flatten()
    }

    private suspend fun fetchSearchItems(
        repositories: List<APIRepository>,
        query: String
    ): List<SearchResponse> = coroutineScope {
        val semaphore = Semaphore(4)
        withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
            repositories
                // Query every source, then classify results centrally; the source is metadata only.
                .map { repository ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            try {
                                when (val response = repository.search(query, 1)) {
                                    is Resource.Success -> response.value.items
                                    else -> emptyList()
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // An unavailable plugin must not hide results from other plugins.
                                emptyList()
                            }
                        }
                    }
                }
                .awaitAll()
                .flatten()
        }.orEmpty()
    }

    private suspend fun filterForUniverse(
        items: List<SearchResponse>,
        repositories: List<APIRepository>
    ): List<SearchResponse> {
        val unique = items
            .filter { it.type != TvType.NSFW && it.type != TvType.CustomMedia }
            .distinctBy { item -> "${item.apiName.lowercase(Locale.ROOT)}|${item.url}" }

        val metadataUniverses = setOf("shorts", "kids", "live_tv", "sports", "radio")
        if (universeId !in metadataUniverses) {
            return unique.filter { matchesUniverse(it, null) }
        }

        // Enrich a bounded set of items for tags, age ratings and duration. The same classifier
        // is used for results from every provider, so no plugin owns a top-level category.
        val candidates = unique
            .filter { item ->
                MediaUniverseClassifier.shouldLoadDetails(universeId, item.type, item.name)
            }
            .take(MAX_METADATA_ITEMS)
        val repoByName = repositories.associateBy { it.name }
        val semaphore = Semaphore(4)
        return coroutineScope {
            candidates.map { item ->
                async(Dispatchers.IO) {
                    val repository = repoByName[item.apiName]
                    val details = if (repository == null) {
                        null
                    } else {
                        try {
                            // The timeout starts after a worker acquires a slot, not while queued.
                            semaphore.withPermit {
                                withTimeoutOrNull(DETAIL_TIMEOUT_MS) {
                                    when (val result = repository.load(item.url)) {
                                        is Resource.Success -> result.value
                                        else -> null
                                    }
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (matchesUniverse(item, details)) item else null
                }
            }.awaitAll().filterNotNull()
        }
    }

    private fun matchesUniverse(item: SearchResponse, details: LoadResponse?): Boolean =
        MediaUniverseClassifier.matches(
            universeId = universeId,
            type = item.type,
            title = item.name,
            tags = details?.tags.orEmpty(),
            contentRating = details?.contentRating,
            durationMinutes = details?.duration
        )
}
