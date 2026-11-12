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
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
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
        view.findViewById<TextView>(R.id.games_intro).setText(
            if (tvMode) R.string.universe_games_intro_tv else R.string.universe_games_intro
        )
        listAdapter = GamesAdapter(
            onPrimary = { card -> onPrimaryAction(card) },
            onSecondary = { card -> onSecondaryAction(card) }
        )
        // Petites tuiles : autant de colonnes que la largeur le permet (environ 130 dp par jeu
        // sur TV, 110 dp sur téléphone), soit 6 à 7 jeux par rangée sur un téléviseur.
        val widthDp = resources.configuration.screenWidthDp
        val spans = (widthDp / (if (tvMode) 130 else 110)).coerceIn(3, 8)
        recyclerView.layoutManager = MissaAlignedGridLayoutManager(
            context = requireContext(),
            spanCount = spans,
            trapBottomEdge = true,
            leftBorderEscapeProvider = { view.rootView?.findViewById(R.id.nav_rail_view) },
            // Haut depuis la première rangée : retour au filtre de console sélectionné.
            topBorderEscapeProvider = { filterButtons[selectedSystem] ?: refreshButton },
            instantReveal = true
        )
        recyclerView.setHasFixedSize(true)
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
        if (recyclerView.getChildAt(0)?.requestFocus() == true) return true
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
        // Une seule console (NES) : pas de filtre à afficher.
        val used = systemsWithGames()
        val systems = if (used.size <= 1) emptyList() else
            listOf("all" to getString(R.string.universe_games_filter_all)) + used.map { it.system to it.label }
        if (systems.isEmpty()) {
            selectedSystem = "all"
            return
        }
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
        // Catalogue vide : on l'annonce au lieu d'afficher une grille vide.
        statusView.isVisible = cat.games.isEmpty()
        if (cat.games.isEmpty()) statusView.setText(R.string.universe_games_empty)
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
        private val onPrimary: (GameCard) -> Unit,
        private val onSecondary: (GameCard) -> Unit
    ) : ListAdapter<GameCard, GamesAdapter.TileVH>(DIFF) {

        companion object {
            private val DIFF = object : DiffUtil.ItemCallback<GameCard>() {
                override fun areItemsTheSame(oldItem: GameCard, newItem: GameCard) = oldItem.game.id == newItem.game.id
                override fun areContentsTheSame(oldItem: GameCard, newItem: GameCard) = oldItem == newItem
                // Seul l'état change : on met à jour la ligne d'état et la barre sans recharger
                // la pochette, pour que le sélecteur ne saute pas pendant un téléchargement.
                override fun getChangePayload(oldItem: GameCard, newItem: GameCard): Any? =
                    if (oldItem.game == newItem.game) "state" else null
            }

            fun formatBytes(bytes: Long): String {
                if (bytes <= 0L) return ""
                val kb = bytes / 1024.0
                return if (kb < 1024.0) String.format(Locale.FRANCE, "%.0f Ko", kb.coerceAtLeast(1.0))
                else String.format(Locale.FRANCE, "%.1f Mo", kb / 1024.0)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileVH =
            TileVH(LayoutInflater.from(parent.context).inflate(R.layout.item_game_tile, parent, false), onPrimary, onSecondary)

        override fun onBindViewHolder(holder: TileVH, position: Int) = holder.bind(getItem(position))

        override fun onBindViewHolder(holder: TileVH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty()) holder.bindState(getItem(position)) else holder.bind(getItem(position))
        }

        /**
         * Une tuile = un jeu. Appui / OK : jouer, télécharger ou annuler. Appui long (OK maintenu)
         * ou touche Menu : options (infos, supprimer). Le cadre vert du sélecteur TV est dessiné
         * par MainActivity, comme dans les autres univers.
         */
        class TileVH(
            view: View,
            onPrimary: (GameCard) -> Unit,
            onSecondary: (GameCard) -> Unit
        ) : RecyclerView.ViewHolder(view) {
            private var current: GameCard? = null
            private val artwork: ImageView = view.findViewById(R.id.game_tile_artwork)
            private val system: TextView = view.findViewById(R.id.game_tile_system)
            private val progressBar: LinearProgressIndicator = view.findViewById(R.id.game_tile_progress)
            private val title: TextView = view.findViewById(R.id.game_tile_title)
            private val state: TextView = view.findViewById(R.id.game_tile_state)

            init {
                view.setOnClickListener { current?.let(onPrimary) }
                view.setOnLongClickListener { current?.let(onSecondary); true }
                view.setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                        current?.let(onSecondary)
                        true
                    } else {
                        false
                    }
                }
            }

            fun bind(card: GameCard) {
                current = card
                val g = card.game
                bindArtwork(g.coverUrl)
                system.text = when (g.system) {
                    "gb" -> if (g.fileName.endsWith(".gbc", ignoreCase = true)) "GBC" else "GB"
                    else -> g.system.uppercase(Locale.ROOT)
                }
                title.text = g.title
                itemView.contentDescription = "${g.title}, ${g.platformLabel}"
                bindState(card)
            }

            /** Pochette en plein cadre si le jeu en a une, sinon l'icône manette au centre. */
            private fun bindArtwork(coverUrl: String?) {
                if (coverUrl != null) {
                    artwork.imageTintList = null
                    artwork.scaleType = ImageView.ScaleType.CENTER_CROP
                    artwork.setPadding(0, 0, 0, 0)
                    artwork.loadImage(coverUrl) {
                        error { getImageFromDrawable(artwork.context, R.drawable.ic_missa_gamepad) }
                    }
                } else {
                    val pad = (artwork.resources.displayMetrics.density * 22).toInt()
                    artwork.setPadding(pad, pad, pad, pad)
                    artwork.imageTintList = ColorStateList.valueOf(artwork.context.colorFromAttribute(R.attr.missaAccentPrimary))
                    artwork.scaleType = ImageView.ScaleType.FIT_CENTER
                    artwork.setImageResource(R.drawable.ic_missa_gamepad)
                }
            }

            fun bindState(card: GameCard) {
                current = card
                val ctx = itemView.context
                val p = card.progress
                progressBar.isVisible = p != null
                if (p != null) progressBar.setProgressCompat(p.percent, false)
                state.text = when {
                    p != null -> ctx.getString(R.string.universe_games_tile_progress, p.percent)
                    card.installed -> ctx.getString(R.string.universe_games_play)
                    else -> formatBytes(card.downloadBytes)
                }
                state.setTextColor(
                    ctx.colorFromAttribute(if (card.installed || p != null) R.attr.missaAccentPrimary else R.attr.grayTextColor)
                )
            }
        }
    }
}
