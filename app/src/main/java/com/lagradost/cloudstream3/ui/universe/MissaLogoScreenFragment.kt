package com.lagradost.cloudstream3.ui.universe

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout

/**
 * Base des écrans « grille de logos » (Radio, TV en direct) : en-tête, onglets, fil d'Ariane
 * (« ‹ Pays  Cameroun »), grille compacte et touche Retour qui remonte d'un niveau avant de
 * quitter l'univers. La navigation à la télécommande suit les règles des autres univers :
 * gauche depuis la première colonne = menu, haut depuis la première rangée = onglets.
 */
abstract class MissaLogoScreenFragment : Fragment() {

    protected var tvMode = false
        private set
    protected var spanCount = 4
        private set

    protected lateinit var grid: RecyclerView
        private set
    protected lateinit var gridAdapter: MissaLogoGridAdapter
        private set
    private lateinit var statusView: TextView
    private lateinit var counterView: TextView
    private lateinit var tabsContainer: LinearLayout
    private lateinit var crumb: View
    private lateinit var backButton: MaterialButton
    private lateinit var crumbTitle: TextView

    private val tabButtons = LinkedHashMap<String, MaterialButton>()
    private val actionButtons = ArrayList<MaterialButton>()
    protected var selectedTab: String = ""
        private set

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (!onBackInside()) isEnabled = false
        }
    }

    protected abstract val eyebrowRes: Int
    protected abstract val titleRes: Int

    /** Onglets : identifiant et libellé. */
    protected abstract fun tabs(): List<Pair<String, String>>

    /** Boutons d'action placés après les onglets (rechercher, ajouter...). */
    protected open fun actions(): List<Pair<String, () -> Unit>> = emptyList()

    protected abstract fun onTabSelected(tabId: String)
    protected abstract fun onItemClick(item: LogoGridItem)
    protected open fun onItemLongClick(item: LogoGridItem) = Unit
    protected open fun onNearEnd() = Unit

    /** Retour depuis un sous-niveau (un pays ouvert...). Vrai si l'écran l'a traité. */
    protected open fun onBackInside(): Boolean = false

    protected abstract fun initialTab(): String

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_radio, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvMode = isLayout(TV or EMULATOR)
        grid = view.findViewById(R.id.logo_screen_grid)
        statusView = view.findViewById(R.id.logo_screen_status)
        counterView = view.findViewById(R.id.logo_screen_counter)
        tabsContainer = view.findViewById(R.id.logo_screen_tabs)
        crumb = view.findViewById(R.id.logo_screen_crumb)
        backButton = view.findViewById(R.id.logo_screen_back)
        crumbTitle = view.findViewById(R.id.logo_screen_crumb_title)
        view.findViewById<TextView>(R.id.logo_screen_eyebrow).setText(eyebrowRes)
        view.findViewById<TextView>(R.id.logo_screen_title).setText(titleRes)
        counterView.isVisible = false

        gridAdapter = MissaLogoGridAdapter(
            onClick = { onItemClick(it) },
            onLongClick = { onItemLongClick(it) },
            onNearEnd = { grid.post { if (isAdded) onNearEnd() } }
        )
        spanCount = MissaLogoGridAdapter.spanCountFor(requireContext(), tvMode)
        val layoutManager = MissaAlignedGridLayoutManager(
            context = requireContext(),
            spanCount = spanCount,
            trapBottomEdge = true,
            leftBorderEscapeProvider = { view.rootView?.findViewById(R.id.nav_rail_view) },
            topBorderEscapeProvider = { topEscapeTarget() },
            instantReveal = true
        )
        layoutManager.spanSizeLookup = gridAdapter.spanSizeLookup { layoutManager.spanCount }
        grid.layoutManager = layoutManager
        MissaLogoGridAdapter.bindAdaptiveSpans(
            grid, layoutManager, MissaLogoGridAdapter.tileWidthDp(requireContext(), tvMode)
        ) { spanCount = it }
        grid.setHasFixedSize(true)
        grid.itemAnimator = null
        // Petits téléviseurs : on garde peu de tuiles hors écran en réserve.
        grid.setItemViewCacheSize(4)
        grid.adapter = gridAdapter
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                (activity as? MainActivity)?.onMissaContentScrolled(dy, !rv.canScrollVertically(-1))
            }
        })

        backButton.setOnClickListener { onBackInside() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        buildTabs()
        selectTab(initialTab(), notify = true)
    }

    override fun onDestroyView() {
        tabButtons.clear()
        actionButtons.clear()
        super.onDestroyView()
    }

    private fun topEscapeTarget(): View? =
        if (crumb.isVisible) backButton else tabButtons[selectedTab] ?: tabButtons.values.firstOrNull()

    private fun buildTabs() {
        val ctx = context ?: return
        tabsContainer.removeAllViews()
        tabButtons.clear()
        actionButtons.clear()
        tabs().forEach { (id, label) ->
            val pill = MissaUiFactory.createPillButton(ctx, label, id == selectedTab, marginEndDp = 6) {
                selectTab(id, notify = true)
            }
            tabsContainer.addView(pill)
            tabButtons[id] = pill
        }
        actions().forEach { (label, action) ->
            val button = MissaUiFactory.createPillButton(ctx, label, false, marginEndDp = 6) { action() }
            tabsContainer.addView(button)
            actionButtons += button
        }
    }

    protected fun selectTab(id: String, notify: Boolean) {
        selectedTab = id
        tabButtons.forEach { (key, button) -> MissaUiFactory.stylePillSelection(button, key == id) }
        if (notify) onTabSelected(id)
    }

    /** Ouvre un sous-niveau : affiche « ‹ [backLabel] » et [title]. */
    protected fun showCrumb(backLabel: String, title: String) {
        backButton.text = getString(R.string.logo_screen_back, backLabel)
        crumbTitle.text = title
        crumb.isVisible = true
        backCallback.isEnabled = true
    }

    protected fun updateCrumbTitle(title: String) {
        crumbTitle.text = title
    }

    protected fun hideCrumb() {
        crumb.isVisible = false
        backCallback.isEnabled = false
    }

    protected val isCrumbVisible: Boolean get() = ::crumb.isInitialized && crumb.isVisible

    protected fun setStatus(text: String?) {
        if (!::statusView.isInitialized) return
        statusView.isVisible = text != null
        statusView.text = text
    }

    protected fun setCounter(text: String?) {
        if (!::counterView.isInitialized) return
        counterView.isVisible = text != null
        counterView.text = text
    }

    /**
     * Affiche une liste. [scrollToTop] : nouvelle liste (onglet, pays). [focusKey] : remet le
     * sélecteur sur cet élément une fois la liste affichée (retour d'un sous-niveau).
     */
    protected fun submit(items: List<LogoGridItem>, scrollToTop: Boolean = false, focusKey: String? = null) {
        if (!::gridAdapter.isInitialized) return
        gridAdapter.submitList(items) {
            if (!isAdded) return@submitList
            if (scrollToTop) {
                grid.scrollToPosition(0)
                // L'en-tête (fil d'Ariane) change de hauteur juste après : on recale.
                grid.post { if (isAdded) grid.scrollToPosition(0) }
            }
            if (focusKey != null) {
                val pos = items.indexOfFirst { it.key == focusKey }
                if (pos >= 0) focusPosition(pos)
            }
        }
    }

    private fun focusPosition(pos: Int, attempt: Int = 0) {
        val holder = grid.findViewHolderForAdapterPosition(pos)
        if (holder != null) {
            holder.itemView.requestFocus()
            return
        }
        if (attempt > 3) return
        grid.scrollToPosition(pos)
        grid.post { focusPosition(pos, attempt + 1) }
    }

    /** Appelé par MainActivity pour poser le sélecteur dans le contenu (navigation TV). */
    fun requestPrimaryContentFocus(): Boolean {
        if (!isAdded || !::grid.isInitialized) return false
        val lm = grid.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager
        val first = lm?.findFirstVisibleItemPosition() ?: 0
        val start = if (first < 0) 0 else first
        for (i in start until minOf(start + 30, gridAdapter.itemCount)) {
            val v = grid.findViewHolderForAdapterPosition(i)?.itemView ?: continue
            if (v.isFocusable && v.requestFocus()) return true
        }
        return topEscapeTarget()?.requestFocus() == true
    }
}
