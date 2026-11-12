package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage

/**
 * Grille de petits logos partagée par les univers Radio et TV en direct.
 *
 * Quatre sortes d'éléments : un titre de section, un « dossier » (pays, thème, groupe de
 * chaînes), une tuile logo (une station, une chaîne) et un pied « chargement de la page
 * suivante ». Les titres et le pied occupent toute la largeur, un dossier deux colonnes.
 */
sealed class LogoGridItem {
    abstract val key: String

    data class Header(override val key: String, val title: String) : LogoGridItem()

    data class Folder(
        override val key: String,
        val title: String,
        val subtitle: String,
        /** Texte du badge (code pays, initiale du thème) quand il n'y a pas d'image. */
        val badge: String,
        val imageUrl: String? = null
    ) : LogoGridItem()

    data class Logo(
        override val key: String,
        val title: String,
        val logos: List<String>,
        val favorite: Boolean = false,
        val playing: Boolean = false,
        val description: String = title
    ) : LogoGridItem()

    data class More(override val key: String = "__more__") : LogoGridItem()
}

class MissaLogoGridAdapter(
    private val onClick: (LogoGridItem) -> Unit,
    private val onLongClick: (LogoGridItem) -> Unit,
    /** Appelé quand l'une des dernières tuiles s'affiche : moment de charger la page suivante. */
    private val onNearEnd: () -> Unit = {}
) : ListAdapter<LogoGridItem, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_FOLDER = 1
        private const val TYPE_LOGO = 2
        private const val TYPE_MORE = 3
        private const val PAYLOAD_STATE = "state"

        private val DIFF = object : DiffUtil.ItemCallback<LogoGridItem>() {
            override fun areItemsTheSame(oldItem: LogoGridItem, newItem: LogoGridItem) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: LogoGridItem, newItem: LogoGridItem) = oldItem == newItem
            // Seuls « favori » ou « en cours » ont changé : on ne recharge pas le logo.
            override fun getChangePayload(oldItem: LogoGridItem, newItem: LogoGridItem): Any? =
                if (oldItem is LogoGridItem.Logo && newItem is LogoGridItem.Logo &&
                    oldItem.copy(favorite = newItem.favorite, playing = newItem.playing) == newItem
                ) PAYLOAD_STATE else null
        }

        /** Nombre de colonnes : des tuiles d'environ 92 dp sur TV, 84 dp sur téléphone. */
        fun spanCountFor(context: Context, tvMode: Boolean): Int {
            val widthDp = context.resources.configuration.screenWidthDp
            return (widthDp / (if (tvMode) 92 else 84)).coerceIn(3, 12)
        }

        /** Initiales d'une station ou d'une chaîne, quand elle n'a pas de logo. */
        fun monogram(name: String): String {
            val words = name.trim().split(Regex("\\s+")).filter { it.any(Char::isLetterOrDigit) }
            if (words.isEmpty()) return "?"
            val first = words[0].trim { !it.isLetterOrDigit() }
            val acronym = first.filter(Char::isLetterOrDigit)
            if (acronym.length in 2..4 && acronym.all { it.isUpperCase() || it.isDigit() } && first == first.uppercase()) {
                return acronym
            }
            val initials = words.take(2).mapNotNull { word ->
                word.trim { !it.isLetterOrDigit() }.firstOrNull(Char::isLetterOrDigit)?.uppercaseChar()
            }.joinToString("")
            return initials.ifBlank { first.take(2).uppercase() }
        }
    }

    /** À donner au GridLayoutManager : titres et pied sur toute la largeur, dossiers sur deux. */
    fun spanSizeLookup(spanCount: () -> Int) = object : GridLayoutManager.SpanSizeLookup() {
        override fun getSpanSize(position: Int): Int {
            val spans = spanCount()
            return when (currentList.getOrNull(position)) {
                is LogoGridItem.Header, is LogoGridItem.More -> spans
                is LogoGridItem.Folder -> minOf(2, spans)
                else -> 1
            }
        }
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is LogoGridItem.Header -> TYPE_HEADER
        is LogoGridItem.Folder -> TYPE_FOLDER
        is LogoGridItem.Logo -> TYPE_LOGO
        is LogoGridItem.More -> TYPE_MORE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_logo_header, parent, false))
            TYPE_FOLDER -> FolderVH(inflater.inflate(R.layout.item_logo_folder, parent, false))
            TYPE_MORE -> object : RecyclerView.ViewHolder(inflater.inflate(R.layout.item_logo_more, parent, false)) {}
            else -> LogoVH(inflater.inflate(R.layout.item_logo_tile, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is HeaderVH -> holder.bind(item as LogoGridItem.Header)
            is FolderVH -> holder.bind(item as LogoGridItem.Folder)
            is LogoVH -> holder.bind(item as LogoGridItem.Logo)
        }
        if (position >= itemCount - 12) onNearEnd()
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && holder is LogoVH) {
            holder.bindState(getItem(position) as LogoGridItem.Logo)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        // Libère tout de suite le bitmap d'une tuile sortie de l'écran (mémoire des petites TV).
        if (holder is LogoVH) holder.recycle()
        if (holder is FolderVH) holder.recycle()
        super.onViewRecycled(holder)
    }

    private inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.logo_header_title)
        fun bind(item: LogoGridItem.Header) {
            title.text = item.title
        }
    }

    private inner class FolderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ImageView = view.findViewById(R.id.logo_folder_image)
        private val badge: TextView = view.findViewById(R.id.logo_folder_badge)
        private val title: TextView = view.findViewById(R.id.logo_folder_title)
        private val subtitle: TextView = view.findViewById(R.id.logo_folder_subtitle)
        private var current: LogoGridItem.Folder? = null

        init {
            view.setOnClickListener { current?.let(onClick) }
            view.setOnLongClickListener { current?.let(onLongClick); true }
        }

        fun bind(item: LogoGridItem.Folder) {
            current = item
            title.text = item.title
            subtitle.text = item.subtitle
            subtitle.isVisible = item.subtitle.isNotBlank()
            badge.text = item.badge
            itemView.contentDescription = listOf(item.title, item.subtitle).filter { it.isNotBlank() }.joinToString(", ")
            val url = item.imageUrl
            if (url == null) {
                image.dispose()
                image.isVisible = false
                badge.isVisible = true
            } else {
                image.isVisible = true
                badge.isVisible = false
                image.loadImage(url) {
                    listener(onError = { _, _ ->
                        image.post {
                            if (current?.key == item.key) {
                                image.isVisible = false
                                badge.isVisible = true
                            }
                        }
                    })
                }
            }
        }

        fun recycle() {
            image.dispose()
            image.setImageDrawable(null)
        }
    }

    private inner class LogoVH(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ImageView = view.findViewById(R.id.logo_tile_image)
        private val monogram: TextView = view.findViewById(R.id.logo_tile_monogram)
        private val favorite: TextView = view.findViewById(R.id.logo_tile_favorite)
        private val playing: TextView = view.findViewById(R.id.logo_tile_playing)
        private val title: TextView = view.findViewById(R.id.logo_tile_title)
        private var current: LogoGridItem.Logo? = null

        init {
            view.setOnClickListener { current?.let(onClick) }
            view.setOnLongClickListener { current?.let(onLongClick); true }
            view.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                    current?.let(onLongClick)
                    true
                } else {
                    false
                }
            }
        }

        fun bind(item: LogoGridItem.Logo) {
            current = item
            title.text = item.title
            monogram.text = monogram(item.title)
            image.setTag(R.id.missa_radio_logo_key, item.key)
            loadLogo(item.key, item.logos, 0)
            bindState(item)
        }

        fun bindState(item: LogoGridItem.Logo) {
            current = item
            favorite.isVisible = item.favorite
            playing.isVisible = item.playing
            itemView.isSelected = item.playing
            itemView.contentDescription = item.description
        }

        /**
         * Essaie les logos dans l'ordre. Les initiales restent affichées pendant le chargement
         * (connexion lente) et si aucun logo ne répond ; elles disparaissent dès qu'un logo
         * est affiché.
         */
        private fun loadLogo(key: String, candidates: List<String>, index: Int) {
            if (image.getTag(R.id.missa_radio_logo_key) != key) return
            val url = candidates.getOrNull(index)
            monogram.isVisible = true
            if (url == null) {
                image.dispose()
                image.setImageDrawable(null)
                return
            }
            image.loadImage(url) {
                listener(
                    onSuccess = { _, _ ->
                        if (image.getTag(R.id.missa_radio_logo_key) == key) monogram.isVisible = false
                    },
                    onError = { _, _ -> image.post { loadLogo(key, candidates, index + 1) } },
                    onCancel = { }
                )
            }
        }

        fun recycle() {
            image.setTag(R.id.missa_radio_logo_key, null)
            image.dispose()
            image.setImageDrawable(null)
        }
    }
}
