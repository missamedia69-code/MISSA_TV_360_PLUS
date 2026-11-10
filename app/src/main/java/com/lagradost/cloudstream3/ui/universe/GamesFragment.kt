package com.lagradost.cloudstream3.ui.universe

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.getImageFromDrawable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Univers Jeux : la liste des jeux du catalogue `game/catalogue.json`.
 *
 * Un jeu se télécharge en un appui : l'application récupère au passage le moteur
 * d'émulation et l'émulateur de sa console s'ils manquent (voir [MissaGames.install]), puis
 * le jeu est prêt à lancer, hors ligne, dans [GamePlayerActivity].
 */
class GamesFragment : Fragment() {

    data class GameCard(
        val game: MissaGames.Game,
        val installed: Boolean,
        val progress: MissaGames.Progress?,
        /** Ce qu'il reste à télécharger : le jeu, plus le moteur et l'émulateur s'ils manquent. */
        val downloadBytes: Long
    )

    private var catalogue: MissaGames.Catalogue? = null
    private var selectedSystem: String = "all"
    private val filterButtons = LinkedHashMap<String, MaterialButton>()
    private val jobs = HashMap<String, Job>()
    private val progress = HashMap<String, MissaGames.Progress>()
    /** État d'installation de chaque jeu (installé, octets restant à télécharger), recalculé
     *  seulement quand il change : pas de lecture de fichiers à chaque tick de progression. */
    private val installState = HashMap<String, Pair<Boolean, Long>>()
    private var tvMode = false

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var controllerBadge: TextView
    private lateinit var filtersContainer: LinearLayout
    private lateinit var refreshButton: MaterialButton
    private lateinit var statusView: TextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var listAdapter: GamesAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_games, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        swipeRefresh = view.findViewById(R.id.games_swipe_refresh)
        controllerBadge = view.findViewById(R.id.games_controller_badge)
        filtersContainer = view.findViewById(R.id.games_filters)
        refreshButton = view.findViewById(R.id.games_refresh_button)
        statusView = view.findViewById(R.id.games_status)
        recyclerView = view.findViewById(R.id.games_recycler)

        tvMode = isLayout(TV or EMULATOR)
        if (tvMode) view.findViewById<TextView>(R.id.games_intro).setText(R.string.universe_games_intro_tv)
        listAdapter = GamesAdapter(
            tvMode = tvMode,
            onPrimary = { card -> onPrimaryAction(card) },
            onSecondary = { card -> onSecondaryAction(card) }
        )
        val isTvOrTablet = isLayout(TV or EMULATOR) || resources.configuration.smallestScreenWidthDp >= 600
        recyclerView.layoutManager = if (isTvOrTablet) {
            MissaAlignedGridLayoutManager(
                context = requireContext(),
                spanCount = 2,
                trapBottomEdge = true,
                leftBorderEscapeProvider = { view.rootView?.findViewById(R.id.nav_rail_view) },
                // Haut depuis la première rangée : retour au filtre de console sélectionné.
                topBorderEscapeProvider = { filterButtons[selectedSystem] ?: refreshButton }
            )
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

        refreshButton.setOnClickListener { refresh(force = true) }
        swipeRefresh.isEnabled = !isLayout(TV or EMULATOR)
        swipeRefresh.setOnRefreshListener { refresh(force = true) }

        catalogue = MissaGames.cachedCatalogue(requireContext())
        buildFilters()
        recomputeStates()
        render()
        refresh(force = false)
    }

    override fun onResume() {
        super.onResume()
        updateControllerBadge()
        recomputeStates() // un jeu a pu être supprimé ou installé ailleurs
        render()
    }

    override fun onDestroyView() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        progress.clear()
        super.onDestroyView()
    }

    /** Appelé par MainActivity pour poser le sélecteur dans le contenu (navigation TV). */
    fun requestPrimaryContentFocus(): Boolean {
        if (!isAdded || !::recyclerView.isInitialized) return false
        val first = recyclerView.getChildAt(0)
        val target = if (tvMode) first else first?.findViewById(R.id.game_card_primary_btn)
        if (target?.requestFocus() == true) return true
        return refreshButton.requestFocus()
    }

    private fun refresh(force: Boolean) {
        val ctx = context ?: return
        swipeRefresh.isRefreshing = true
        viewLifecycleOwner.lifecycleScope.launch {
            val fresh = MissaGames.refreshCatalogue(ctx, force)
            if (!isAdded) return@launch
            swipeRefresh.isRefreshing = false
            if (fresh != null) catalogue = fresh
            buildFilters()
            recomputeStates()
            render()
        }
    }

    private fun systemsWithGames(): List<MissaGames.Emulator> {
        val cat = catalogue ?: return emptyList()
        val used = cat.games.map { it.system }.toSet()
        return cat.emulators.values.filter { it.system in used }
    }

    private fun buildFilters() {
        val ctx = context ?: return
        filterButtons.values.forEach { filtersContainer.removeView(it) }
        filterButtons.clear()
        val systems = listOf("all" to getString(R.string.universe_games_filter_all)) +
            systemsWithGames().map { it.system to it.label }
        if (selectedSystem != "all" && systems.none { it.first == selectedSystem }) selectedSystem = "all"
        systems.forEach { (id, label) ->
            val pill = MissaUiFactory.createPillButton(ctx, label, id == selectedSystem, marginEndDp = 6) {
                selectedSystem = id
                filterButtons.forEach { (key, button) -> MissaUiFactory.stylePillSelection(button, key == selectedSystem) }
                render()
            }
            filtersContainer.addView(pill)
            filterButtons[id] = pill
        }
    }

    private fun recomputeStates() {
        val ctx = context ?: return
        val cat = catalogue ?: return
        installState.clear()
        cat.games.forEach { game ->
            val plan = MissaGames.planInstall(ctx, cat, game)
            val bytes = (if (plan.engine) cat.engine.bytes else 0L) +
                (plan.emulator?.pkg?.bytes ?: 0L) + (if (plan.game) game.pkg.bytes else 0L)
            installState[game.id] = plan.isEmpty to bytes
        }
    }

    private fun render() {
        if (!isAdded || !::listAdapter.isInitialized) return
        val cat = catalogue
        if (cat == null) {
            statusView.isVisible = true
            statusView.setText(R.string.universe_games_unavailable)
            listAdapter.submitList(emptyList())
            return
        }
        statusView.isVisible = false
        val cards = cat.games
            .filter { selectedSystem == "all" || it.system == selectedSystem }
            .map { game ->
                val (installed, bytes) = installState[game.id] ?: (false to game.pkg.bytes)
                GameCard(game, installed, progress[game.id], bytes)
            }
        // Ordre fixe (celui du catalogue) : une carte ne change jamais de place quand son jeu
        // s'installe, sinon le sélecteur suivrait la carte et sauterait ailleurs dans la grille.
        listAdapter.submitList(cards)
    }

    private fun onPrimaryAction(card: GameCard) {
        val ctx = context ?: return
        val cat = catalogue ?: return
        when {
            jobs.containsKey(card.game.id) -> cancelInstall(card.game)
            MissaGames.isGameInstalled(ctx, cat, card.game) ->
                startActivity(GamePlayerActivity.intent(ctx, card.game))
            else -> startInstall(cat, card.game)
        }
    }

    private fun startInstall(cat: MissaGames.Catalogue, game: MissaGames.Game) {
        val ctx = context ?: return
        progress[game.id] = MissaGames.Progress(0, game.pkg.bytes, MissaGames.Step.GAME)
        render()
        val job = viewLifecycleOwner.lifecycleScope.launch {
            try {
                MissaGames.install(ctx, cat, game) { p ->
                    recyclerView.post {
                        if (jobs.containsKey(game.id)) {
                            progress[game.id] = p
                            render()
                        }
                    }
                }
                jobs.remove(game.id)
                progress.remove(game.id)
                if (!isAdded) return@launch
                recomputeStates()
                render()
                Toast.makeText(ctx, getString(R.string.universe_games_ready, game.title), Toast.LENGTH_SHORT).show()
            } catch (_: CancellationException) {
                jobs.remove(game.id)
                progress.remove(game.id)
                if (isAdded) {
                    recomputeStates()
                    render()
                }
            } catch (_: Throwable) {
                jobs.remove(game.id)
                progress.remove(game.id)
                if (!isAdded) return@launch
                recomputeStates()
                render()
                Toast.makeText(ctx, R.string.universe_games_download_error, Toast.LENGTH_LONG).show()
            }
        }
        jobs[game.id] = job
    }

    private fun cancelInstall(game: MissaGames.Game) {
        jobs.remove(game.id)?.cancel()
        progress.remove(game.id)
        render()
    }

    /**
     * Options d'un jeu (bouton « Infos » / « Supprimer » sur téléphone, appui long sur OK ou
     * touche Menu sur TV) : une liste navigable à la télécommande.
     */
    private fun onSecondaryAction(card: GameCard) {
        val ctx = context ?: return
        val g = card.game
        val downloading = jobs.containsKey(g.id)
        val options = buildList<Pair<String, () -> Unit>> {
            when {
                downloading -> add(getString(R.string.universe_games_cancel) to { cancelInstall(g) })
                card.installed -> add(getString(R.string.universe_games_play) to { onPrimaryAction(card) })
                else -> add(getString(R.string.universe_games_download, GamesAdapter.formatBytes(card.downloadBytes)) to { onPrimaryAction(card) })
            }
            add(getString(R.string.universe_games_info_button) to { showInfo(g) })
            if (card.installed && !downloading) add(getString(R.string.delete) to { confirmDelete(g) })
        }
        AlertDialog.Builder(ctx)
            .setTitle(g.title)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    private fun showInfo(g: MissaGames.Game) {
        val ctx = context ?: return
        val emulator = catalogue?.emulatorFor(g)
        AlertDialog.Builder(ctx)
            .setTitle(g.title)
            .setMessage(
                getString(
                    R.string.universe_games_info,
                    g.author, g.platformLabel, g.licence,
                    emulator?.core.orEmpty(), emulator?.licence.orEmpty(), g.sourceUrl
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun confirmDelete(g: MissaGames.Game) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(g.title)
            .setMessage(R.string.universe_games_delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                MissaGames.deleteGame(ctx, g)
                recomputeStates()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateControllerBadge() {
        if (!::controllerBadge.isInitialized) return
        val pad = InputDevice.getDeviceIds().asSequence()
            .mapNotNull { InputDevice.getDevice(it) }
            .firstOrNull { dev ->
                !dev.isVirtual && (
                    (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                        (dev.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                    )
            }
        controllerBadge.text = when {
            pad != null -> getString(R.string.universe_games_controller_gamepad, pad.name)
            isLayout(TV or EMULATOR) -> getString(R.string.universe_games_controller_tv)
            else -> getString(R.string.universe_games_controller_touch)
        }
    }

    // ------------------------------------------------------------------------- Liste

    private class GamesAdapter(
        private val tvMode: Boolean,
        private val onPrimary: (GameCard) -> Unit,
        private val onSecondary: (GameCard) -> Unit
    ) : ListAdapter<GameCard, GamesAdapter.CardVH>(DIFF) {

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<GameCard>() {
                override fun areItemsTheSame(oldItem: GameCard, newItem: GameCard) = oldItem.game.id == newItem.game.id
                override fun areContentsTheSame(oldItem: GameCard, newItem: GameCard) = oldItem == newItem
                // Seule la progression change : on met à jour la barre sans redessiner la carte,
                // pour que le sélecteur ne saute pas pendant un téléchargement.
                override fun getChangePayload(oldItem: GameCard, newItem: GameCard): Any? =
                    if (oldItem.game == newItem.game && oldItem.installed == newItem.installed) "state" else null
            }

            fun formatBytes(bytes: Long): String {
                if (bytes <= 0L) return ""
                val kb = bytes / 1024.0
                return if (kb < 1024.0) String.format(Locale.FRANCE, "%.0f Ko", kb.coerceAtLeast(1.0))
                else String.format(Locale.FRANCE, "%.1f Mo", kb / 1024.0)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CardVH =
            CardVH(LayoutInflater.from(parent.context).inflate(R.layout.item_game_card, parent, false), tvMode, onPrimary, onSecondary)

        override fun onBindViewHolder(holder: CardVH, position: Int) = holder.bind(getItem(position), onPrimary, onSecondary)

        override fun onBindViewHolder(holder: CardVH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty()) holder.bindState(getItem(position), onPrimary)
            else onBindViewHolder(holder, position)
        }

        /**
         * Sur TV, la **carte entière** reçoit le sélecteur (cadre vert), comme dans les autres
         * univers : la grille passe de carte en carte, OK lance l'action principale (jouer,
         * télécharger, annuler) et un appui long sur OK ou la touche Menu ouvre les options.
         * Les boutons de la carte restent visibles comme indication mais ne prennent pas le focus.
         * Sur téléphone et tablette tactile, les deux boutons fonctionnent normalement.
         */
        class CardVH(
            view: View,
            private val tvMode: Boolean,
            onPrimary: (GameCard) -> Unit,
            onSecondary: (GameCard) -> Unit
        ) : RecyclerView.ViewHolder(view) {
            private var current: GameCard? = null
            private val cardView = view as MaterialCardView
            private val artwork: ImageView = view.findViewById(R.id.game_card_artwork)
            private val title: TextView = view.findViewById(R.id.game_card_title)
            private val systemBadge: TextView = view.findViewById(R.id.game_card_system_badge)
            private val metaBadge: TextView = view.findViewById(R.id.game_card_meta_badge)
            private val description: TextView = view.findViewById(R.id.game_card_description)
            private val progressGroup: View = view.findViewById(R.id.game_card_progress_group)
            private val progressBar: LinearProgressIndicator = view.findViewById(R.id.game_card_progress_bar)
            private val progressText: TextView = view.findViewById(R.id.game_card_progress_text)
            private val primaryBtn: MaterialButton = view.findViewById(R.id.game_card_primary_btn)
            private val secondaryBtn: MaterialButton = view.findViewById(R.id.game_card_secondary_btn)

            init {
                if (tvMode) {
                    primaryBtn.isFocusable = false
                    secondaryBtn.isFocusable = false
                    secondaryBtn.isVisible = false
                    cardView.isFocusable = true
                    cardView.isClickable = true
                    cardView.setOnClickListener { current?.let(onPrimary) }
                    cardView.setOnLongClickListener { current?.let(onSecondary); true }
                    cardView.setOnKeyListener { _, keyCode, event ->
                        if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                            current?.let(onSecondary)
                            true
                        } else {
                            false
                        }
                    }
                    cardView.setOnFocusChangeListener { _, hasFocus -> styleFocus(hasFocus) }
                    styleFocus(false)
                }
            }

            private fun styleFocus(focused: Boolean) {
                val ctx = cardView.context
                val density = ctx.resources.displayMetrics.density
                cardView.strokeColor = ctx.colorFromAttribute(if (focused) R.attr.missaAccentNavigation else R.attr.missaStrokeSubtle)
                cardView.strokeWidth = ((if (focused) 3 else 1) * density).toInt()
                cardView.setCardBackgroundColor(ctx.colorFromAttribute(if (focused) R.attr.missaSurfaceFocused else R.attr.missaSurfaceCard))
            }

            fun bind(card: GameCard, onPrimary: (GameCard) -> Unit, onSecondary: (GameCard) -> Unit) {
                current = card
                val g = card.game
                bindArtwork(g.coverUrl)
                title.text = g.title
                systemBadge.text = g.platformLabel
                metaBadge.text = listOf(g.genre, g.author, formatBytes(g.pkg.bytes)).filter { it.isNotBlank() }.joinToString(" • ")
                description.text = g.description
                description.isVisible = g.description.isNotBlank()
                secondaryBtn.setText(if (card.installed) R.string.delete else R.string.universe_games_info_button)
                secondaryBtn.setOnClickListener { onSecondary(card) }
                bindState(card, onPrimary)
            }

            /** Pochette en plein cadre si le jeu en a une, sinon l'icône manette teintée. */
            private fun bindArtwork(coverUrl: String?) {
                val lp = artwork.layoutParams
                if (coverUrl != null) {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT
                    artwork.imageTintList = null
                    artwork.scaleType = ImageView.ScaleType.CENTER_CROP
                    artwork.loadImage(coverUrl) {
                        error { getImageFromDrawable(artwork.context, R.drawable.ic_missa_gamepad) }
                    }
                } else {
                    val size = (32 * artwork.resources.displayMetrics.density).toInt()
                    lp.width = size
                    lp.height = size
                    artwork.imageTintList = ColorStateList.valueOf(artwork.context.colorFromAttribute(R.attr.missaAccentPrimary))
                    artwork.scaleType = ImageView.ScaleType.FIT_CENTER
                    artwork.setImageResource(R.drawable.ic_missa_gamepad)
                }
                artwork.layoutParams = lp
            }

            fun bindState(card: GameCard, onPrimary: (GameCard) -> Unit) {
                current = card
                val ctx = itemView.context
                val p = card.progress
                progressGroup.isVisible = p != null
                if (p != null) {
                    progressBar.isIndeterminate = p.totalBytes <= 0
                    progressBar.setProgressCompat(p.percent, true)
                    progressText.text = ctx.getString(
                        when (p.step) {
                            MissaGames.Step.ENGINE -> R.string.universe_games_step_engine
                            MissaGames.Step.EMULATOR -> R.string.universe_games_step_emulator
                            MissaGames.Step.GAME -> R.string.universe_games_step_game
                        },
                        p.percent
                    )
                }
                primaryBtn.text = when {
                    p != null -> ctx.getString(R.string.universe_games_cancel)
                    card.installed -> ctx.getString(R.string.universe_games_play)
                    else -> ctx.getString(R.string.universe_games_download, formatBytes(card.downloadBytes))
                }
                primaryBtn.setOnClickListener { onPrimary(card) }
            }
        }
    }
}
