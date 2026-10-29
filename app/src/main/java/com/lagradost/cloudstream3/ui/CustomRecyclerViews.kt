package com.lagradost.cloudstream3.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.core.content.withStyledAttributes
import androidx.core.view.children
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.universe.MissaTvFocusHelper

class GrdLayoutManager(val context: Context, spanCount: Int) :
    GridLayoutManager(context, spanCount) {

    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToWindow(view: RecyclerView?) {
        super.onAttachedToWindow(view)
        attachedRecyclerView = view
    }

    override fun onDetachedFromWindow(view: RecyclerView?, recycler: RecyclerView.Recycler?) {
        attachedRecyclerView = null
        super.onDetachedFromWindow(view, recycler)
    }

    override fun onFocusSearchFailed(
        focused: View,
        focusDirection: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): View? {
        return onInterceptFocusSearch(focused, focusDirection)
    }

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        return try {
            if (itemCount <= 0) return null
            val itemView = findContainingItemView(focused) ?: return null
            val fromPos = getPosition(itemView)
            if (fromPos == RecyclerView.NO_POSITION) return null

            if (orientation == VERTICAL) {
                val effectiveLeft = (!isLayoutRTL && direction == View.FOCUS_LEFT) ||
                    (isLayoutRTL && direction == View.FOCUS_RIGHT)
                val spans = spanCount.coerceAtLeast(1)
                if (effectiveLeft && fromPos % spans == 0) {
                    val navRail = (attachedRecyclerView?.rootView)?.findViewById<View>(R.id.nav_rail_view)
                    if (navRail != null && navRail.isShown && navRail.isFocusable) {
                        return navRail
                    }
                    return itemView
                }

                val nextPos = MissaTvFocusHelper.computeUniformGridNextPosition(
                    fromPos = fromPos,
                    itemCount = itemCount,
                    spanCount = spans,
                    direction = direction,
                    isRtl = isLayoutRTL,
                    trapBottomEdge = true
                )
                if (nextPos < 0) return null
                if (nextPos == fromPos) return itemView

                findViewByPosition(nextPos)?.let { return it }

                val rv = attachedRecyclerView
                if (rv != null && (direction == View.FOCUS_DOWN || direction == View.FOCUS_UP)) {
                    val vStep = if (direction == View.FOCUS_DOWN) +1 else -1
                    val stepPx = getDecoratedMeasuredHeight(itemView).coerceAtLeast(120) * vStep
                    var tries = 0
                    while (findViewByPosition(nextPos) == null && tries < 3 && rv.canScrollVertically(vStep)) {
                        rv.scrollBy(0, stepPx)
                        tries++
                    }
                }
                return findViewByPosition(nextPos) ?: itemView
            }

            // Horizontal GridLayoutManager fallback
            val step = when (direction) {
                View.FOCUS_DOWN -> 1
                View.FOCUS_UP -> -1
                View.FOCUS_RIGHT -> if (isLayoutRTL) -spanCount else spanCount
                View.FOCUS_LEFT -> if (isLayoutRTL) spanCount else -spanCount
                else -> 0
            }
            val nextPos = (fromPos + step).coerceIn(0, itemCount - 1)
            findViewByPosition(nextPos) ?: itemView
        } catch (e: Exception) {
            null
        }
    }
}

// https://riptutorial.com/android/example/4810/gridlayoutmanager-with-dynamic-span-count
class AutofitRecyclerView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    RecyclerView(context, attrs) {

    private val manager = GrdLayoutManager(context, 2) // THIS CONTROLS SPANS

    private var columnWidth = -1

    var spanCount = 0
        set(value) {
            field = value
            if (value > 0) {
                manager.spanCount = value
            }
        }

    val itemWidth: Int
        get() = measuredWidth / manager.spanCount

    init {
        if (attrs != null) {
            context.withStyledAttributes(attrs, intArrayOf(android.R.attr.columnWidth)) {
                columnWidth = getDimensionPixelSize(0, -1)
            }
        }

        layoutManager = manager
    }
}

/**
 * Recyclerview wherein the max item width or height is set by the biggest view to prevent inconsistent view sizes.
 */
class MaxRecyclerView(ctx: Context, attrs: AttributeSet) : RecyclerView(ctx, attrs) {
    private var biggestObserved: Int = 0
    private val orientation = LayoutManager.getProperties(context, attrs, 0, 0).orientation
    private val isHorizontal = orientation == HORIZONTAL
    private fun View.updateMaxSize() {
        if (isHorizontal) {
            this.minimumHeight = biggestObserved
        } else {
            this.minimumWidth = biggestObserved
        }
    }

    override fun onChildAttachedToWindow(child: View) {
        child.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
        val observed = if (isHorizontal) child.measuredHeight else child.measuredWidth
        if (observed > biggestObserved) {
            biggestObserved = observed
            children.forEach { it.updateMaxSize() }
        } else {
            child.updateMaxSize()
        }
        super.onChildAttachedToWindow(child)
    }
}