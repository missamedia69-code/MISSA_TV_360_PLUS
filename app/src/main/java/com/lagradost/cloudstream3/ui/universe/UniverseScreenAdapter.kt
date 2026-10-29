package com.lagradost.cloudstream3.ui.universe

import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.home.ResumeItemAdapter
import com.lagradost.cloudstream3.ui.result.FOCUS_SELF
import com.lagradost.cloudstream3.ui.result.setLinearListLayout
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_FOCUSED
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_SHOW_METADATA
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.setRecycledViewPool
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.ImageLoader.loadImage
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import com.lagradost.cloudstream3.utils.getImageFromDrawable

/**
 * UI model for the sticky category filter strip (HOME-02, HOME-08, I18N-01).
 */
internal data class CategoryFilterPillUi(
    val key: String?,
    val label: String,
    val selected: Boolean,
    val isOverflowMore: Boolean = false
)

internal class CategoryFilterPillAdapter(
    private val onPillClicked: (CategoryFilterPillUi) -> Unit
) : ListAdapter<CategoryFilterPillUi, CategoryFilterPillAdapter.PillHolder>(Diff) {

    object Diff : DiffUtil.ItemCallback<CategoryFilterPillUi>() {
        override fun areItemsTheSame(oldItem: CategoryFilterPillUi, newItem: CategoryFilterPillUi): Boolean =
            oldItem.key == newItem.key && oldItem.isOverflowMore == newItem.isOverflowMore

        override fun areContentsTheSame(oldItem: CategoryFilterPillUi, newItem: CategoryFilterPillUi): Boolean =
            oldItem == newItem

        override fun getChangePayload(oldItem: CategoryFilterPillUi, newItem: CategoryFilterPillUi): Any? =
            if (oldItem.selected != newItem.selected || oldItem.label != newItem.label) PAYLOAD_PILL_STATE else null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PillHolder {
        val btn = MissaUiFactory.createPillButton(
            context = parent.context,
            label = "",
            selected = false,
            marginEndDp = 6
        ) {}
        btn.tag = "missa_pill"
        return PillHolder(btn)
    }

    override fun onBindViewHolder(holder: PillHolder, position: Int) {
        holder.bind(getItem(position), onPillClicked)
    }

    override fun onBindViewHolder(holder: PillHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val item = getItem(position)
            holder.button.text = item.label
            MissaUiFactory.stylePillSelection(holder.button, item.selected)
            holder.button.setOnClickListener { onPillClicked(item) }
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    class PillHolder(val button: MaterialButton) : RecyclerView.ViewHolder(button) {
        fun bind(item: CategoryFilterPillUi, onClick: (CategoryFilterPillUi) -> Unit) {
            button.text = item.label
            MissaUiFactory.stylePillSelection(button, item.selected)
            button.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private const val PAYLOAD_PILL_STATE = "pill_state"
    }
}

/**
 * DiffUtil-backed Hub shortcut card adapter (HOME-02, HOME-09, A11Y-01).
 */
internal class UniverseHubCardAdapter(
    private val onSelectUniverse: (UniverseDefinition) -> Unit
) : ListAdapter<UniverseHubCardItem, UniverseHubCardAdapter.HubCardViewHolder>(Diff) {

    object Diff : DiffUtil.ItemCallback<UniverseHubCardItem>() {
        override fun areItemsTheSame(oldItem: UniverseHubCardItem, newItem: UniverseHubCardItem): Boolean =
            oldItem.definition.id == newItem.definition.id

        override fun areContentsTheSame(oldItem: UniverseHubCardItem, newItem: UniverseHubCardItem): Boolean =
            oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HubCardViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_universe_hub_card, parent, false)
        MissaUiFactory.attachInteractiveMotion(view)
        return HubCardViewHolder(view)
    }

    override fun onBindViewHolder(holder: HubCardViewHolder, position: Int) {
        holder.bind(getItem(position), onSelectUniverse)
    }

    class HubCardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.universe_card_icon)
        private val liveDot: TextView = itemView.findViewById(R.id.universe_card_live_dot)
        private val badge: TextView = itemView.findViewById(R.id.universe_card_badge)
        private val title: TextView = itemView.findViewById(R.id.universe_card_title)
        private val subtitle: TextView = itemView.findViewById(R.id.universe_card_subtitle)

        fun bind(item: UniverseHubCardItem, onSelect: (UniverseDefinition) -> Unit) {
            val ctx = itemView.context
            icon.setImageResource(item.definition.iconRes)
            val titleText = ctx.getString(item.definition.titleRes)
            val subText = ctx.getString(item.definition.cardSubtitleRes)
            title.text = titleText
            subtitle.text = subText
            liveDot.isVisible = item.hasLiveNow
            badge.isVisible = !item.countBadge.isNullOrBlank()
            badge.text = item.countBadge.orEmpty()

            itemView.contentDescription = listOfNotNull(
                titleText,
                subText,
                if (item.hasLiveNow) ctx.getString(R.string.universe_live_badge) else null,
                item.countBadge
            ).joinToString(", ")

            itemView.setOnClickListener { onSelect(item.definition) }
        }
    }
}

/**
 * Modular RecyclerView items for the main Universe screen (HOME-02).
 * Each section updates independently via DiffUtil & payloads without rebinding siblings or losing TV focus.
 */
internal sealed interface UniverseListItem {
    val itemId: String

    data class HeroCarousel(
        val universeId: String,
        val slides: List<HeroBannerSlide>,
        val updatedAtMs: Long,
        val dataSaverActive: Boolean
    ) : UniverseListItem {
        override val itemId: String = "hero_$universeId"
    }

    data class CompactBanner(
        val definition: UniverseDefinition,
        val isKidsLocked: Boolean
    ) : UniverseListItem {
        override val itemId: String = "compact_banner_${definition.id}"
    }

    data class RadioDeck(
        val radioState: RadioPlaybackState,
        val isFavoriteStation: Boolean
    ) : UniverseListItem {
        override val itemId: String = "radio_deck"
    }

    data class MusicDeck(
        val musicState: MusicPlaybackState,
        val isFavoriteTrack: Boolean
    ) : UniverseListItem {
        override val itemId: String = "music_deck"
    }

    data class ResumeRow(
        val universeId: String,
        val items: List<DataStoreHelper.ResumeWatchingResult>
    ) : UniverseListItem {
        override val itemId: String = "resume_$universeId"
    }

    data class HubShortcutsRow(
        val items: List<UniverseHubCardItem>
    ) : UniverseListItem {
        override val itemId: String = "hub_shortcuts"
    }

    data class CategoryRow(
        val row: UniverseCategoryRow
    ) : UniverseListItem {
        override val itemId: String = "cat_row_${row.id}"
    }

    data class SearchGrid(
        val row: UniverseCategoryRow
    ) : UniverseListItem {
        override val itemId: String = "search_grid_${row.id}"
    }

    data class ShowMoreRows(
        val hiddenCount: Int
    ) : UniverseListItem {
        override val itemId: String = "show_more_rows"
    }

    data class ShimmerLoading(
        val loadedProviders: Int,
        val totalProviders: Int
    ) : UniverseListItem {
        override val itemId: String = "shimmer_loading"
    }

    data class EmptyOrError(
        val reason: UniverseEmptyReason,
        val definition: UniverseDefinition
    ) : UniverseListItem {
        override val itemId: String = "empty_or_error_${reason.name}_${definition.id}"
    }
}

internal class UniverseScreenAdapter(
    private val callbacks: Callbacks
) : ListAdapter<UniverseListItem, RecyclerView.ViewHolder>(Diff) {

    interface Callbacks {
        fun onHeroPrimaryAction(slide: HeroBannerSlide)
        fun onHeroDetailsAction(slide: HeroBannerSlide)
        fun onNavigateUniverse(definition: UniverseDefinition)
        fun onCardClick(callback: SearchClickCallback)
        fun onResumeSingleRemove(item: DataStoreHelper.ResumeWatchingResult)
        fun onClearAllResume(items: List<DataStoreHelper.ResumeWatchingResult>)
        fun onRowSeeAll(row: UniverseCategoryRow)
        fun onExpandHiddenRows()
        fun onOpenOnboardingOrExtensions()
        fun onRefreshCatalog()
        fun onResetAllFilters()
        fun onRadioTogglePlayPause()
        fun onRadioPrevStation()
        fun onRadioNextStation()
        fun onRadioStop()
        fun onRadioToggleFavorite()
        fun onRadioCycleSleepTimer()
        fun onRadioOpenCustomStreamDialog()
        fun onMusicTogglePlayPause()
        fun onMusicPrevTrack()
        fun onMusicNextTrack()
        fun onMusicStop()
        fun onMusicToggleShuffle()
        fun onMusicCycleRepeat()
        fun onMusicSeekTo(positionMs: Long)
        fun onMusicToggleFavorite()
        fun onMusicCycleSleepTimer()
        fun onMusicOpenServerDialog()
        fun onContentItemFocused(rowKey: String)
    }

    companion object {
        private const val VIEW_HERO = 1
        private const val VIEW_COMPACT_BANNER = 2
        private const val VIEW_RADIO_DECK = 3
        private const val VIEW_RESUME_ROW = 4
        private const val VIEW_HUB_ROW = 5
        private const val VIEW_CATEGORY_ROW = 6
        private const val VIEW_SEARCH_GRID = 7
        private const val VIEW_SHOW_MORE = 8
        private const val VIEW_SHIMMER = 9
        private const val VIEW_EMPTY = 10
        private const val VIEW_MUSIC_DECK = 11

        private const val PAYLOAD_RADIO_UPDATE = "payload_radio"
        private const val PAYLOAD_MUSIC_UPDATE = "payload_music"
        private const val PAYLOAD_HERO_UPDATE = "payload_hero"
        private const val PAYLOAD_SHIMMER_PROGRESS = "payload_shimmer"
        private const val HERO_AUTO_ROTATE_MS = 7_500L
    }

    init {
        setHasStableIds(true)
    }

    override fun getItemId(position: Int): Long = getItem(position).itemId.hashCode().toLong()

    object Diff : DiffUtil.ItemCallback<UniverseListItem>() {
        override fun areItemsTheSame(oldItem: UniverseListItem, newItem: UniverseListItem): Boolean =
            oldItem.itemId == newItem.itemId

        override fun areContentsTheSame(oldItem: UniverseListItem, newItem: UniverseListItem): Boolean =
            oldItem == newItem

        override fun getChangePayload(oldItem: UniverseListItem, newItem: UniverseListItem): Any? {
            return when {
                oldItem is UniverseListItem.RadioDeck && newItem is UniverseListItem.RadioDeck ->
                    PAYLOAD_RADIO_UPDATE
                oldItem is UniverseListItem.MusicDeck && newItem is UniverseListItem.MusicDeck ->
                    PAYLOAD_MUSIC_UPDATE
                oldItem is UniverseListItem.HeroCarousel && newItem is UniverseListItem.HeroCarousel ->
                    PAYLOAD_HERO_UPDATE
                oldItem is UniverseListItem.ShimmerLoading && newItem is UniverseListItem.ShimmerLoading ->
                    PAYLOAD_SHIMMER_PROGRESS
                else -> null
            }
        }
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is UniverseListItem.HeroCarousel -> VIEW_HERO
        is UniverseListItem.CompactBanner -> VIEW_COMPACT_BANNER
        is UniverseListItem.RadioDeck -> VIEW_RADIO_DECK
        is UniverseListItem.MusicDeck -> VIEW_MUSIC_DECK
        is UniverseListItem.ResumeRow -> VIEW_RESUME_ROW
        is UniverseListItem.HubShortcutsRow -> VIEW_HUB_ROW
        is UniverseListItem.CategoryRow -> VIEW_CATEGORY_ROW
        is UniverseListItem.SearchGrid -> VIEW_SEARCH_GRID
        is UniverseListItem.ShowMoreRows -> VIEW_SHOW_MORE
        is UniverseListItem.ShimmerLoading -> VIEW_SHIMMER
        is UniverseListItem.EmptyOrError -> VIEW_EMPTY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_HERO -> HeroCarouselHolder(
                inflater.inflate(R.layout.item_universe_hero_carousel, parent, false),
                callbacks
            )
            VIEW_COMPACT_BANNER -> CompactBannerHolder(
                inflater.inflate(R.layout.item_universe_compact_banner, parent, false)
            )
            VIEW_RADIO_DECK -> RadioDeckHolder(
                inflater.inflate(R.layout.item_universe_radio_deck, parent, false),
                callbacks
            )
            VIEW_MUSIC_DECK -> MusicDeckHolder(
                inflater.inflate(R.layout.item_universe_music_deck, parent, false),
                callbacks
            )
            VIEW_RESUME_ROW -> ResumeRowHolder(
                inflater.inflate(R.layout.item_universe_resume_row, parent, false),
                callbacks
            )
            VIEW_HUB_ROW -> HubRowHolder(
                inflater.inflate(R.layout.item_universe_hub_row, parent, false),
                callbacks
            )
            VIEW_CATEGORY_ROW -> CategoryRowHolder(
                inflater.inflate(R.layout.item_universe_category_row, parent, false),
                callbacks
            )
            VIEW_SEARCH_GRID -> SearchGridHolder(
                inflater.inflate(R.layout.item_universe_search_grid, parent, false),
                callbacks
            )
            VIEW_SHOW_MORE -> ShowMoreHolder(
                inflater.inflate(R.layout.item_universe_show_more_rows, parent, false),
                callbacks
            )
            VIEW_SHIMMER -> ShimmerHolder(
                inflater.inflate(R.layout.item_universe_shimmer, parent, false)
            )
            else -> EmptyStateHolder(
                inflater.inflate(R.layout.item_universe_empty, parent, false),
                callbacks
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is UniverseListItem.HeroCarousel -> (holder as HeroCarouselHolder).bind(item)
            is UniverseListItem.CompactBanner -> (holder as CompactBannerHolder).bind(item)
            is UniverseListItem.RadioDeck -> (holder as RadioDeckHolder).bind(item)
            is UniverseListItem.MusicDeck -> (holder as MusicDeckHolder).bind(item)
            is UniverseListItem.ResumeRow -> (holder as ResumeRowHolder).bind(item)
            is UniverseListItem.HubShortcutsRow -> (holder as HubRowHolder).bind(item)
            is UniverseListItem.CategoryRow -> (holder as CategoryRowHolder).bind(item, position)
            is UniverseListItem.SearchGrid -> (holder as SearchGridHolder).bind(item)
            is UniverseListItem.ShowMoreRows -> (holder as ShowMoreHolder).bind(item)
            is UniverseListItem.ShimmerLoading -> (holder as ShimmerHolder).bind(item)
            is UniverseListItem.EmptyOrError -> (holder as EmptyStateHolder).bind(item)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (payloads.isEmpty()) {
            super.onBindViewHolder(holder, position, payloads)
            return
        }
        when (val item = getItem(position)) {
            is UniverseListItem.RadioDeck -> (holder as RadioDeckHolder).bind(item)
            is UniverseListItem.MusicDeck -> (holder as MusicDeckHolder).bind(item)
            is UniverseListItem.HeroCarousel -> (holder as HeroCarouselHolder).bind(item)
            is UniverseListItem.ShimmerLoading -> (holder as ShimmerHolder).bind(item)
            else -> super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is HeroCarouselHolder) holder.onAttached()
        if (holder is ShimmerHolder) holder.startShimmer()
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        if (holder is HeroCarouselHolder) holder.onDetached()
        if (holder is ShimmerHolder) holder.stopShimmer()
        super.onViewDetachedFromWindow(holder)
    }

    // --- 1. Hero ViewPager2 Carousel with Dots & Pause-Aware Auto-Rotation (HOME-03, HOME-04, HOME-12, HOME-13) ---

    class HeroCarouselHolder(
        itemView: View,
        private val callbacks: Callbacks
    ) : RecyclerView.ViewHolder(itemView) {
        private val viewPager: ViewPager2 = itemView.findViewById(R.id.hero_viewpager)
        private val freshnessBadge: TextView = itemView.findViewById(R.id.hero_freshness_badge)
        private val dotsContainer: LinearLayout = itemView.findViewById(R.id.hero_dots_container)
        private val slideAdapter = HeroSlidePagerAdapter(callbacks)
        private val handler = Handler(Looper.getMainLooper())
        private var currentItem: UniverseListItem.HeroCarousel? = null
        private var userInteracting = false
        private var attached = false

        private val rotateRunnable = object : Runnable {
            override fun run() {
                val slides = currentItem?.slides.orEmpty()
                if (!attached || slides.size <= 1) return
                val ctx = itemView.context
                val hasHeroFocus = itemView.hasFocus()
                if (!userInteracting &&
                    !hasHeroFocus &&
                    MissaUiFactory.areAnimationsEnabled(ctx) &&
                    !(currentItem?.dataSaverActive == true)
                ) {
                    val next = (viewPager.currentItem + 1) % slides.size
                    viewPager.setCurrentItem(next, true)
                }
                handler.postDelayed(this, HERO_AUTO_ROTATE_MS)
            }
        }

        init {
            viewPager.adapter = slideAdapter
            viewPager.offscreenPageLimit = 1
            viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    renderDots(currentItem?.slides?.size ?: 0, position)
                }

                override fun onPageScrollStateChanged(state: Int) {
                    userInteracting = state == ViewPager2.SCROLL_STATE_DRAGGING
                }
            })
        }

        fun bind(item: UniverseListItem.HeroCarousel) {
            val universeChanged = currentItem?.universeId != item.universeId
            currentItem = item
            slideAdapter.dataSaverActive = item.dataSaverActive
            slideAdapter.submitList(item.slides) {
                if (universeChanged && item.slides.isNotEmpty()) {
                    viewPager.setCurrentItem(0, false)
                }
                renderDots(item.slides.size, viewPager.currentItem.coerceAtLeast(0))
            }
            renderFreshness(item)
            scheduleRotationIfEligible()
        }

        private fun renderFreshness(item: UniverseListItem.HeroCarousel) {
            val ctx = itemView.context
            if (item.dataSaverActive) {
                freshnessBadge.isVisible = true
                freshnessBadge.setText(R.string.missa_data_saver_badge)
                return
            }
            if (item.updatedAtMs <= 0L) {
                freshnessBadge.isVisible = false
                return
            }
            val elapsedMin = ((System.currentTimeMillis() - item.updatedAtMs) / 60_000L).toInt()
            freshnessBadge.isVisible = true
            freshnessBadge.text = if (elapsedMin <= 0) {
                ctx.getString(R.string.missa_updated_just_now)
            } else {
                ctx.getString(R.string.missa_updated_minutes_ago, elapsedMin)
            }
        }

        private fun renderDots(total: Int, activeIndex: Int) {
            dotsContainer.isVisible = total > 1
            if (total <= 1) {
                dotsContainer.removeAllViews()
                return
            }
            val activeWidthPx = 18.toPx
            val inactiveWidthPx = 6.toPx
            val dotHeightPx = 6.toPx
            if (dotsContainer.childCount != total) {
                dotsContainer.removeAllViews()
                repeat(total) { idx ->
                    val dot = View(itemView.context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            if (idx == activeIndex) activeWidthPx else inactiveWidthPx,
                            dotHeightPx
                        ).apply {
                            marginStart = 5.toPx
                        }
                        isFocusable = false
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    dotsContainer.addView(dot)
                }
            }
            for (i in 0 until dotsContainer.childCount) {
                val dot = dotsContainer.getChildAt(i)
                val targetWidthPx = if (i == activeIndex) activeWidthPx else inactiveWidthPx
                val lp = (dot.layoutParams as? LinearLayout.LayoutParams)
                    ?: LinearLayout.LayoutParams(targetWidthPx, dotHeightPx).apply {
                        marginStart = 5.toPx
                    }
                if (lp.width != targetWidthPx || lp.height != dotHeightPx) {
                    lp.width = targetWidthPx
                    lp.height = dotHeightPx
                    dot.layoutParams = lp
                }
                dot.setBackgroundResource(
                    if (i == activeIndex) R.drawable.missa_hero_dot_active
                    else R.drawable.missa_hero_dot_inactive
                )
            }
        }

        fun onAttached() {
            attached = true
            scheduleRotationIfEligible()
        }

        fun onDetached() {
            attached = false
            handler.removeCallbacks(rotateRunnable)
        }

        private fun scheduleRotationIfEligible() {
            handler.removeCallbacks(rotateRunnable)
            val slides = currentItem?.slides.orEmpty()
            if (attached && slides.size > 1 && !(currentItem?.dataSaverActive == true)) {
                handler.postDelayed(rotateRunnable, HERO_AUTO_ROTATE_MS)
            }
        }
    }

    private class HeroSlidePagerAdapter(
        private val callbacks: Callbacks
    ) : ListAdapter<HeroBannerSlide, HeroSlidePagerAdapter.SlideHolder>(SlideDiff) {
        var dataSaverActive: Boolean = false

        object SlideDiff : DiffUtil.ItemCallback<HeroBannerSlide>() {
            override fun areItemsTheSame(oldItem: HeroBannerSlide, newItem: HeroBannerSlide): Boolean =
                oldItem.card.url == newItem.card.url

            override fun areContentsTheSame(oldItem: HeroBannerSlide, newItem: HeroBannerSlide): Boolean =
                oldItem == newItem
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlideHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_universe_hero_slide, parent, false)
            return SlideHolder(view, callbacks)
        }

        override fun onBindViewHolder(holder: SlideHolder, position: Int) {
            holder.bind(getItem(position), position, itemCount, dataSaverActive) { delta ->
                val vp = holder.itemView.parent?.parent as? ViewPager2 ?: return@bind
                val next = (position + delta).mod(itemCount)
                vp.setCurrentItem(next, true)
            }
        }

        class SlideHolder(
            itemView: View,
            private val callbacks: Callbacks
        ) : RecyclerView.ViewHolder(itemView) {
            private val backdrop: ImageView = itemView.findViewById(R.id.hero_backdrop_image)
            private val eyebrow: TextView = itemView.findViewById(R.id.hero_eyebrow)
            private val sourcesBadge: TextView = itemView.findViewById(R.id.hero_sources_badge)
            private val title: TextView = itemView.findViewById(R.id.hero_title)
            private val metaLine: TextView = itemView.findViewById(R.id.hero_meta_line)
            private val plot: TextView = itemView.findViewById(R.id.hero_plot)
            private val primaryCta: MaterialButton = itemView.findViewById(R.id.hero_primary_cta)
            private val detailsCta: MaterialButton = itemView.findViewById(R.id.hero_details_cta)

            init {
                MissaUiFactory.attachInteractiveMotion(primaryCta)
                MissaUiFactory.attachInteractiveMotion(detailsCta)
            }

            fun bind(
                slide: HeroBannerSlide,
                position: Int,
                totalCount: Int,
                dataSaverActive: Boolean,
                onStepSlide: (Int) -> Unit
            ) {
                val ctx = itemView.context
                val imageUrl = if (dataSaverActive) {
                    slide.card.posterUrl ?: slide.details?.posterUrl
                } else {
                    slide.details?.backgroundPosterUrl
                        ?: slide.details?.posterUrl
                        ?: slide.card.posterUrl
                }
                backdrop.loadImage(imageUrl, slide.card.posterHeaders)
                backdrop.contentDescription = slide.card.name

                eyebrow.text = slide.eyebrow
                sourcesBadge.isVisible = slide.allSources.size > 1
                if (slide.allSources.size > 1) {
                    sourcesBadge.text = ctx.resources.getQuantityString(
                        R.plurals.missa_sources_count,
                        slide.allSources.size,
                        slide.allSources.size
                    )
                }

                title.text = slide.card.name

                val metaParts = listOfNotNull(
                    slide.yearText?.takeIf { it.isNotBlank() },
                    slide.durationText?.takeIf { it.isNotBlank() },
                    slide.scoreText?.takeIf { it.isNotBlank() },
                    slide.tags.take(2).joinToString(" · ").takeIf { it.isNotBlank() }
                )
                metaLine.isVisible = metaParts.isNotEmpty()
                metaLine.text = metaParts.joinToString("  •  ")

                plot.isVisible = !slide.plot.isNullOrBlank()
                plot.text = slide.plot.orEmpty()

                val isAudio = slide.card is DirectRadioSearchResponse
                val isListenItem = slide.card.type == TvType.Music ||
                    slide.card.type == TvType.Podcast ||
                    slide.card.type == TvType.Audio ||
                    slide.card.type == TvType.AudioBook
                primaryCta.setText(
                    when {
                        isAudio -> R.string.universe_hero_listen_live
                        isListenItem -> R.string.universe_hero_listen
                        slide.isDirectStream -> R.string.universe_hero_watch_live
                        else -> R.string.universe_hero_watch
                    }
                )
                detailsCta.isVisible = !isAudio && slide.card !is DirectMusicSearchResponse

                primaryCta.setOnClickListener { callbacks.onHeroPrimaryAction(slide) }
                detailsCta.setOnClickListener { callbacks.onHeroDetailsAction(slide) }

                // TV D-pad left/right on Hero CTAs navigates slides cleanly (HOME-03)
                val keyNav = View.OnKeyListener { v, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && totalCount > 1) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && v == primaryCta && position > 0) {
                            onStepSlide(-1)
                            return@OnKeyListener true
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT &&
                            ((v == detailsCta && detailsCta.isVisible) || (v == primaryCta && !detailsCta.isVisible))
                        ) {
                            onStepSlide(1)
                            return@OnKeyListener true
                        }
                    }
                    false
                }
                primaryCta.setOnKeyListener(keyNav)
                detailsCta.setOnKeyListener(keyNav)

                val focusTracker = View.OnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) callbacks.onContentItemFocused("hero")
                    if (MissaUiFactory.isTvLayoutMode() && MissaUiFactory.areAnimationsEnabled(v.context)) {
                        val s = if (hasFocus) 1.05f else 1f
                        v.animate().scaleX(s).scaleY(s).setDuration(140L).start()
                    }
                }
                primaryCta.onFocusChangeListener = focusTracker
                detailsCta.onFocusChangeListener = focusTracker
            }
        }
    }

    // --- 2. Compact Banner (shown ONLY when Hero is absent - HOME-01) ---

    class CompactBannerHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val icon: ImageView = itemView.findViewById(R.id.compact_universe_icon)
        private val title: TextView = itemView.findViewById(R.id.compact_universe_title)
        private val subtitle: TextView = itemView.findViewById(R.id.compact_universe_subtitle)
        private val badge: TextView = itemView.findViewById(R.id.compact_special_badge)

        fun bind(item: UniverseListItem.CompactBanner) {
            val def = item.definition
            icon.setImageResource(def.iconRes)
            title.setText(def.titleRes)
            subtitle.setText(def.descriptionRes)
            when (def.id) {
                "kids" -> {
                    badge.isVisible = true
                    badge.setBackgroundResource(R.drawable.missa_pill_360)
                    badge.setText(
                        if (item.isKidsLocked) R.string.universe_kids_pin_locked_badge
                        else R.string.universe_kids_safe_badge
                    )
                }
                "live_tv", "sports" -> {
                    badge.isVisible = true
                    badge.setBackgroundResource(R.drawable.missa_live_pill_bg)
                    badge.setText(R.string.universe_live_badge)
                }
                else -> badge.isVisible = false
            }
        }
    }

    // --- 3. Radio Console Deck (RAD-02, RAD-03) ---

    class RadioDeckHolder(
        itemView: View,
        private val callbacks: Callbacks
    ) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.radio_station_title)
        private val statusView: TextView = itemView.findViewById(R.id.radio_station_status)
        private val liveBadge: TextView = itemView.findViewById(R.id.radio_live_badge)
        private val prevBtn: MaterialButton = itemView.findViewById(R.id.radio_prev_button)
        private val playPauseBtn: MaterialButton = itemView.findViewById(R.id.radio_play_pause_button)
        private val nextBtn: MaterialButton = itemView.findViewById(R.id.radio_next_button)
        private val stopBtn: MaterialButton = itemView.findViewById(R.id.radio_stop_button)
        private val favBtn: MaterialButton = itemView.findViewById(R.id.radio_favorite_button)
        private val sleepBtn: MaterialButton = itemView.findViewById(R.id.radio_sleep_button)
        private val addStreamBtn: MaterialButton = itemView.findViewById(R.id.radio_add_stream_button)

        init {
            prevBtn.setOnClickListener { callbacks.onRadioPrevStation() }
            playPauseBtn.setOnClickListener { callbacks.onRadioTogglePlayPause() }
            nextBtn.setOnClickListener { callbacks.onRadioNextStation() }
            stopBtn.setOnClickListener { callbacks.onRadioStop() }
            favBtn.setOnClickListener { callbacks.onRadioToggleFavorite() }
            sleepBtn.setOnClickListener { callbacks.onRadioCycleSleepTimer() }
            addStreamBtn.setOnClickListener { callbacks.onRadioOpenCustomStreamDialog() }
        }

        fun bind(item: UniverseListItem.RadioDeck) {
            val ctx = itemView.context
            val state = item.radioState
            val st = state.station
            titleView.text = st?.name ?: ctx.getString(R.string.universe_radio_player_title)
            statusView.text = when {
                st == null -> ctx.getString(R.string.universe_radio_player_idle)
                state.hasError -> ctx.getString(R.string.universe_radio_error, st.name)
                state.isBuffering -> ctx.getString(R.string.universe_radio_buffering, st.name)
                state.isPlaying -> {
                    val base = ctx.getString(
                        R.string.universe_radio_playing,
                        "${st.providerName} • ${st.genre} (${st.country})"
                    )
                    if (state.sleepTimerMinutes > 0) "$base • ⏱ ${state.sleepTimerMinutes}m" else base
                }
                else -> ctx.getString(R.string.universe_radio_paused, st.name)
            }
            liveBadge.isVisible = state.isPlaying
            playPauseBtn.setIconResource(
                if (state.isPlaying) R.drawable.ic_baseline_pause_24
                else R.drawable.ic_baseline_play_arrow_24
            )
            stopBtn.isVisible = st != null
            favBtn.isVisible = st != null
            favBtn.setIconResource(
                if (item.isFavoriteStation) R.drawable.ic_baseline_bookmark_24
                else R.drawable.ic_baseline_bookmark_border_24
            )
        }
    }

    // --- 3b. Dedicated Music Console Deck (Queue, Seekbar, Shuffle, Repeat, Server/MP3) ---

    class MusicDeckHolder(
        itemView: View,
        private val callbacks: Callbacks
    ) : RecyclerView.ViewHolder(itemView) {
        private val artworkView: ImageView = itemView.findViewById(R.id.music_deck_artwork)
        private val titleView: TextView = itemView.findViewById(R.id.music_deck_title)
        private val statusView: TextView = itemView.findViewById(R.id.music_deck_status)
        private val addServerBtn: MaterialButton = itemView.findViewById(R.id.music_deck_add_server_btn)
        private val progressRow: View = itemView.findViewById(R.id.music_deck_progress_row)
        private val positionView: TextView = itemView.findViewById(R.id.music_deck_position)
        private val seekBar: SeekBar = itemView.findViewById(R.id.music_deck_seekbar)
        private val durationView: TextView = itemView.findViewById(R.id.music_deck_duration)
        private val shuffleBtn: MaterialButton = itemView.findViewById(R.id.music_deck_shuffle_btn)
        private val prevBtn: MaterialButton = itemView.findViewById(R.id.music_deck_prev_btn)
        private val playBtn: MaterialButton = itemView.findViewById(R.id.music_deck_play_btn)
        private val nextBtn: MaterialButton = itemView.findViewById(R.id.music_deck_next_btn)
        private val repeatBtn: MaterialButton = itemView.findViewById(R.id.music_deck_repeat_btn)
        private val favBtn: MaterialButton = itemView.findViewById(R.id.music_deck_fav_btn)
        private val sleepBtn: MaterialButton = itemView.findViewById(R.id.music_deck_sleep_btn)
        private val stopBtn: MaterialButton = itemView.findViewById(R.id.music_deck_stop_btn)

        private var isUserScrubbing = false
        private var boundDurationMs: Long = 0L
        private var lastArtworkUrl: String? = "__init__"

        init {
            addServerBtn.setOnClickListener { callbacks.onMusicOpenServerDialog() }
            shuffleBtn.setOnClickListener { callbacks.onMusicToggleShuffle() }
            prevBtn.setOnClickListener { callbacks.onMusicPrevTrack() }
            playBtn.setOnClickListener { callbacks.onMusicTogglePlayPause() }
            nextBtn.setOnClickListener { callbacks.onMusicNextTrack() }
            repeatBtn.setOnClickListener { callbacks.onMusicCycleRepeat() }
            favBtn.setOnClickListener { callbacks.onMusicToggleFavorite() }
            sleepBtn.setOnClickListener { callbacks.onMusicCycleSleepTimer() }
            stopBtn.setOnClickListener { callbacks.onMusicStop() }

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser && boundDurationMs > 0L) {
                        val previewMs = (boundDurationMs * progress) / 1000L
                        positionView.text = formatTimeMs(previewMs)
                    }
                }

                override fun onStartTrackingTouch(bar: SeekBar?) {
                    isUserScrubbing = true
                }

                override fun onStopTrackingTouch(bar: SeekBar?) {
                    isUserScrubbing = false
                    val barProgress = bar?.progress ?: return
                    if (boundDurationMs > 0L) {
                        callbacks.onMusicSeekTo((boundDurationMs * barProgress) / 1000L)
                    }
                }
            })
        }

        fun bind(item: UniverseListItem.MusicDeck) {
            val ctx = itemView.context
            val state = item.musicState
            val tr = state.track
            val accentColor = ctx.colorFromAttribute(R.attr.missaAccentPrimary)
            val primaryTextColor = ctx.colorFromAttribute(R.attr.textColor)
            val mutedColor = ctx.colorFromAttribute(R.attr.grayTextColor)

            titleView.text = tr?.displayLabel() ?: ctx.getString(R.string.universe_music_player_title)

            val metaDetails = if (tr != null) {
                val queueBadge = if (state.queueSize > 1) {
                    "(${state.queueIndex + 1}/${state.queueSize})"
                } else {
                    ""
                }
                listOf(tr.providerName, tr.genre, queueBadge)
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
            } else {
                ""
            }

            statusView.text = when {
                tr == null -> ctx.getString(R.string.universe_music_player_idle)
                state.hasError -> ctx.getString(R.string.universe_music_error, tr.title)
                state.isBuffering -> ctx.getString(R.string.universe_music_buffering, tr.title)
                state.isPlaying -> {
                    val base = ctx.getString(R.string.universe_music_playing, metaDetails)
                    if (state.sleepTimerMinutes > 0) "$base • ⏱ ${state.sleepTimerMinutes}m" else base
                }
                else -> ctx.getString(R.string.universe_music_paused, tr.title)
            }

            val artUrl = tr?.artworkUrl?.takeIf { it.isNotBlank() }
            if (artUrl != lastArtworkUrl) {
                lastArtworkUrl = artUrl
                if (artUrl != null) {
                    ImageViewCompat.setImageTintList(artworkView, null)
                    artworkView.scaleType = ImageView.ScaleType.CENTER_CROP
                    artworkView.setPadding(0, 0, 0, 0)
                    artworkView.loadImage(artUrl) {
                        error { getImageFromDrawable(ctx, R.drawable.ic_missa_music) }
                    }
                } else {
                    ImageViewCompat.setImageTintList(artworkView, ColorStateList.valueOf(accentColor))
                    artworkView.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    val pad = 10.toPx
                    artworkView.setPadding(pad, pad, pad, pad)
                    artworkView.setImageResource(R.drawable.ic_missa_music)
                }
            }

            boundDurationMs = state.durationMs.coerceAtLeast(0L)
            progressRow.isVisible = tr != null && boundDurationMs > 0L
            if (!isUserScrubbing && tr != null && boundDurationMs > 0L) {
                positionView.text = formatTimeMs(state.positionMs)
                durationView.text = formatTimeMs(boundDurationMs)
                seekBar.progress = ((state.positionMs * 1000L) / boundDurationMs).toInt().coerceIn(0, 1000)
            }

            playBtn.setIconResource(
                if (state.isPlaying) R.drawable.ic_baseline_pause_24
                else R.drawable.ic_baseline_play_arrow_24
            )
            shuffleBtn.iconTint = ColorStateList.valueOf(if (state.shuffleEnabled) accentColor else mutedColor)
            repeatBtn.setIconResource(
                if (state.repeatMode == MusicRepeatMode.ONE) R.drawable.ic_missa_repeat_one
                else R.drawable.ic_missa_repeat
            )
            repeatBtn.iconTint = ColorStateList.valueOf(
                if (state.repeatMode != MusicRepeatMode.OFF) accentColor else mutedColor
            )

            favBtn.isVisible = tr != null
            stopBtn.isVisible = tr != null
            favBtn.setIconResource(
                if (item.isFavoriteTrack) R.drawable.ic_baseline_favorite_24
                else R.drawable.ic_baseline_favorite_border_24
            )
            favBtn.iconTint = ColorStateList.valueOf(if (item.isFavoriteTrack) accentColor else primaryTextColor)
        }

        private fun formatTimeMs(ms: Long): String {
            val totalSecs = (ms / 1000L).coerceAtLeast(0L)
            val hours = totalSecs / 3600L
            val mins = (totalSecs % 3600L) / 60L
            val secs = totalSecs % 60L
            return if (hours > 0L) {
                "%d:%02d:%02d".format(hours, mins, secs)
            } else {
                "%02d:%02d".format(mins, secs)
            }
        }
    }

    // --- 4. Resume Row with Single-Item Long-Press Removal & Clear All (HOME-11) ---

    class ResumeRowHolder(
        itemView: View,
        private val callbacks: Callbacks
    ) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.resume_section_title)
        private val clearAllBtn: MaterialButton = itemView.findViewById(R.id.resume_clear_all_btn)
        private val recycler: RecyclerView = itemView.findViewById(R.id.resume_recycler)
        private var currentItems: List<DataStoreHelper.ResumeWatchingResult> = emptyList()

        private val resumeAdapter = ResumeItemAdapter(
            nextFocusUp = View.NO_ID,
            nextFocusDown = View.NO_ID,
            clickCallback = { cb ->
                when (cb.action) {
                    SEARCH_ACTION_SHOW_METADATA -> {
                        val match = currentItems.firstOrNull { it.url == cb.card.url || it.id == cb.card.id }
                        if (match != null) {
                            callbacks.onResumeSingleRemove(match)
                        }
                    }
                    SEARCH_ACTION_FOCUSED -> callbacks.onContentItemFocused("resume")
                    else -> callbacks.onCardClick(cb)
                }
            },
            removeCallback = { callbacks.onClearAllResume(currentItems) }
        )

        init {
            recycler.setRecycledViewPool(HomeChildItemAdapter.sharedPool)
            recycler.setLinearListLayout(
                isHorizontal = true,
                nextLeft = R.id.nav_rail_view,
                nextRight = FOCUS_SELF,
                endEscapeTarget = { clearAllBtn.takeIf { it.isShown && it.isFocusable } }
            )
            recycler.adapter = resumeAdapter
            clearAllBtn.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    val lm = recycler.layoutManager
                    val rightmost = lm?.let {
                        (it.childCount - 1 downTo 0)
                            .mapNotNull { idx -> it.getChildAt(idx) }
                            .firstOrNull { v -> v.isShown && v.isFocusable }
                    }
                    rightmost?.requestFocus() == true
                } else false
            }
        }

        fun bind(item: UniverseListItem.ResumeRow) {
            currentItems = item.items
            titleView.setText(
                if (item.universeId == "podcasts" || item.universeId == "music") {
                    R.string.universe_resume_listening_title
                } else {
                    R.string.universe_resume_watching_title
                }
            )
            clearAllBtn.setOnClickListener { callbacks.onClearAllResume(item.items) }
            resumeAdapter.isHorizontal = true
            resumeAdapter.submitList(item.items)
        }
    }

    // --- 5. Home Hub Shortcuts Row (HOME-09) ---

    class HubRowHolder(
        itemView: View,
        callbacks: Callbacks
    ) : RecyclerView.ViewHolder(itemView) {
        private val recycler: RecyclerView = itemView.findViewById(R.id.hub_shortcuts_recycler)
        private val hubAdapter = UniverseHubCardAdapter { callbacks.onNavigateUniverse(it) }

        init {
            recycler.setLinearListLayout(
                isHorizontal = true,
                nextLeft = R.id.nav_rail_view,
                nextRight = FOCUS_SELF
            )
            recycler.adapter = hubAdapter
        }

        fun bind(item: UniverseListItem.HubShortcutsRow) {
            hubAdapter.submitList(item.items)
        }
    }

    // --- 6. Editorial Category Row (HOME-10, A11Y-01) ---

    class CategoryRowHolder(
        itemView: View,
        private val callbacks: Callbacks
    ) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.row_category_title)
        private val countView: TextView = itemView.findViewById(R.id.row_category_count)
        private val seeAllBtn: MaterialButton = itemView.findViewById(R.id.row_see_all_button)
        private val childRecycler: RecyclerView = itemView.findViewById(R.id.row_child_recycler)

        init {
            childRecycler.setRecycledViewPool(HomeChildItemAdapter.sharedPool)
            childRecycler.setLinearListLayout(
                isHorizontal = true,
                nextLeft = R.id.nav_rail_view,
                nextRight = FOCUS_SELF,
                endEscapeTarget = { seeAllBtn.takeIf { it.isShown && it.isFocusable } }
            )
            seeAllBtn.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                    val lm = childRecycler.layoutManager
                    val rightmost = lm?.let {
                        (it.childCount - 1 downTo 0)
                            .mapNotNull { idx -> it.getChildAt(idx) }
                            .firstOrNull { v -> v.isShown && v.isFocusable }
                    }
                    rightmost?.requestFocus() == true
                } else false
            }
        }

        fun bind(item: UniverseListItem.CategoryRow, adapterPosition: Int) {
            val row = item.row
            val ctx = itemView.context
            titleView.text = row.title
            countView.text = ctx.resources.getQuantityString(
                R.plurals.missa_titles_count,
                row.items.size,
                row.items.size
            )
            seeAllBtn.isVisible = row.items.size > 3
            seeAllBtn.setOnClickListener { callbacks.onRowSeeAll(row) }

            // On TV, cap horizontal rail preview to 12 items so "Voir tout" is reachable quickly at the right end,
            // while preserving the full `row.items` catalogue inside the "Voir tout" full-screen grid.
            val railItems = if (MissaUiFactory.isTvLayoutMode() && row.items.size > 12) {
                row.items.take(12)
            } else {
                row.items
            }

            val handleClick: (SearchClickCallback) -> Unit = { cb ->
                if (cb.action == SEARCH_ACTION_FOCUSED) {
                    callbacks.onContentItemFocused(item.itemId)
                } else {
                    callbacks.onCardClick(cb)
                }
            }

            val existing = childRecycler.adapter as? HomeChildItemAdapter
            if (existing == null) {
                childRecycler.adapter = HomeChildItemAdapter(
                    id = item.itemId.hashCode() + adapterPosition,
                    clickCallback = handleClick
                ).apply {
                    isHorizontal = row.isHorizontalCards
                    submitList(railItems)
                }
            } else {
                existing.isHorizontal = row.isHorizontalCards
                existing.clickCallback = handleClick
                existing.submitIncomparableList(railItems)
            }
        }
    }

    // --- 7. Responsive Multi-Column Search / Filter Results Grid (HOME-07) ---

    class SearchGridHolder(
        itemView: View,
        private val callbacks: Callbacks
    ) : RecyclerView.ViewHolder(itemView) {
        private val titleView: TextView = itemView.findViewById(R.id.search_grid_title)
        private val countView: TextView = itemView.findViewById(R.id.search_grid_count)
        private val gridRecycler: RecyclerView = itemView.findViewById(R.id.search_grid_recycler)

        fun bind(item: UniverseListItem.SearchGrid) {
            val ctx = itemView.context
            val row = item.row
            titleView.text = row.title
            countView.text = ctx.resources.getQuantityString(
                R.plurals.missa_titles_count,
                row.items.size,
                row.items.size
            )

            val minCardWidthPx = ctx.resources.getDimensionPixelSize(R.dimen.missa_search_grid_card_min_width)
            val screenWidthPx = ctx.resources.displayMetrics.widthPixels
            val baseSpan = (screenWidthPx / minCardWidthPx.coerceAtLeast(1)).coerceIn(2, 7)
            val spanCount = if (row.isHorizontalCards) (baseSpan - 1).coerceAtLeast(2) else baseSpan

            val currentLm = gridRecycler.layoutManager as? MissaAlignedGridLayoutManager
            if (currentLm == null || currentLm.spanCount != spanCount) {
                gridRecycler.layoutManager = MissaAlignedGridLayoutManager(
                    context = ctx,
                    spanCount = spanCount,
                    trapBottomEdge = false,
                    leftBorderEscapeProvider = { itemView.rootView?.findViewById(R.id.nav_rail_view) }
                )
            }

            val handleClick: (SearchClickCallback) -> Unit = { cb ->
                if (cb.action == SEARCH_ACTION_FOCUSED) {
                    callbacks.onContentItemFocused(item.itemId)
                } else {
                    callbacks.onCardClick(cb)
                }
            }

            val existing = gridRecycler.adapter as? HomeChildItemAdapter
            if (existing == null) {
                gridRecycler.adapter = HomeChildItemAdapter(
                    id = item.itemId.hashCode(),
                    clickCallback = handleClick
                ).apply {
                    isHorizontal = row.isHorizontalCards
                    submitList(row.items)
                }
            } else {
                existing.isHorizontal = row.isHorizontalCards
                existing.clickCallback = handleClick
                existing.submitIncomparableList(row.items)
            }
        }
    }

    // --- 8. Show More Categories Fold Toggle (HOME-10) ---

    class ShowMoreHolder(
        itemView: View,
        private val callbacks: Callbacks
    ) : RecyclerView.ViewHolder(itemView) {
        private val button: MaterialButton = itemView.findViewById(R.id.show_more_rows_btn)

        fun bind(item: UniverseListItem.ShowMoreRows) {
            button.text = itemView.context.getString(R.string.universe_show_more_rows, item.hiddenCount)
            button.setOnClickListener { callbacks.onExpandHiddenRows() }
        }
    }

    // --- 9. Shimmer Skeleton Loading + Provider Progress (HOME-05) ---

    class ShimmerHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val progressLabel: TextView = itemView.findViewById(R.id.shimmer_progress_label)
        private val shimmerLayout: ShimmerFrameLayout = itemView.findViewById(R.id.universe_shimmer_container)

        fun bind(item: UniverseListItem.ShimmerLoading) {
            val ctx = itemView.context
            progressLabel.text = if (item.totalProviders > 0) {
                ctx.getString(
                    R.string.missa_loading_sources_progress,
                    item.loadedProviders.coerceAtMost(item.totalProviders),
                    item.totalProviders
                )
            } else {
                ctx.getString(R.string.missa_loading_catalog)
            }
            startShimmer()
        }

        fun startShimmer() {
            if (MissaUiFactory.areAnimationsEnabled(itemView.context)) {
                shimmerLayout.startShimmer()
            } else {
                shimmerLayout.hideShimmer()
            }
        }

        fun stopShimmer() {
            shimmerLayout.stopShimmer()
        }
    }

    // --- 10. 4-State Empty / Offline / Onboarding / Filter Card (HOME-06) ---

    class EmptyStateHolder(
        itemView: View,
        private val callbacks: Callbacks
    ) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.empty_state_icon)
        private val titleView: TextView = itemView.findViewById(R.id.empty_state_title)
        private val messageView: TextView = itemView.findViewById(R.id.empty_state_message)
        private val primaryBtn: MaterialButton = itemView.findViewById(R.id.empty_state_primary_action)
        private val secondaryBtn: MaterialButton = itemView.findViewById(R.id.empty_state_secondary_action)

        fun bind(item: UniverseListItem.EmptyOrError) {
            val ctx = itemView.context
            val uniTitle = ctx.getString(item.definition.titleRes)

            when (item.reason) {
                UniverseEmptyReason.OFFLINE_OR_ERROR -> {
                    iconView.setImageResource(R.drawable.ic_outline_info_24)
                    titleView.setText(R.string.missa_state_offline_title)
                    messageView.setText(R.string.missa_state_offline_desc)
                    primaryBtn.setText(R.string.missa_state_offline_cta)
                    primaryBtn.setOnClickListener { callbacks.onRefreshCatalog() }
                    secondaryBtn.isVisible = true
                    secondaryBtn.setText(R.string.universe_games_title)
                    secondaryBtn.setOnClickListener { callbacks.onNavigateUniverse(MissaUniverses.RETRO_GAMES) }
                }

                UniverseEmptyReason.EMPTY_SEARCH_OR_FILTER -> {
                    iconView.setImageResource(R.drawable.search_icon)
                    titleView.setText(R.string.missa_state_empty_filter_title)
                    messageView.setText(R.string.missa_state_empty_filter_desc)
                    primaryBtn.setText(R.string.missa_state_empty_filter_cta)
                    primaryBtn.setOnClickListener { callbacks.onResetAllFilters() }
                    secondaryBtn.isVisible = false
                }

                UniverseEmptyReason.EMPTY_UNIVERSE -> {
                    iconView.setImageResource(item.definition.iconRes)
                    titleView.text = ctx.getString(R.string.missa_state_empty_universe_title, uniTitle)
                    messageView.setText(R.string.missa_state_empty_universe_desc)
                    primaryBtn.setText(R.string.missa_state_empty_universe_cta)
                    primaryBtn.setOnClickListener { callbacks.onRefreshCatalog() }
                    secondaryBtn.isVisible = false
                }
            }
        }
    }
}
