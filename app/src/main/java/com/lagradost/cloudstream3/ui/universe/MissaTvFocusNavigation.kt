package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.graphics.Rect
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import kotlin.math.abs

/**
 * Pure math & layout helpers for deterministic, column-aligned D-Pad navigation across
 * horizontal rails, vertical category lists, and multi-column media grids on Android TV.
 */
internal object MissaTvFocusHelper {

    /**
     * Computes the horizontal scroll delta (`dx`) for a horizontal rail so that:
     * 1. Cards already completely inside `[leftEdge, rightEdge]` do NOT scroll (`dx = 0`),
     *    keeping column X coordinates stable across adjacent rows.
     * 2. Cards clipped on the left snap cleanly to `leftEdge`.
     * 3. Cards clipped on the right step by exact `childWidth` column multiples so the
     *    newly revealed card lands in the rightmost full column (`leftEdge + (visibleCols - 1) * childWidth`),
     *    preserving pixel-aligned vertical columns with neighboring rows.
     */
    fun computeAlignedHorizontalScrollDx(
        leftEdge: Int,
        rightEdge: Int,
        childLeft: Int,
        childRight: Int,
        childWidth: Int
    ): Int {
        val safeWidth = childWidth.coerceAtLeast(1)
        val usableWidth = (rightEdge - leftEdge).coerceAtLeast(1)
        val visibleCols = (usableWidth / safeWidth).coerceAtLeast(1)
        return when {
            childLeft < leftEdge -> childLeft - leftEdge
            childRight > rightEdge -> {
                val alignedRightColLeft = leftEdge + (visibleCols - 1) * safeWidth
                childLeft - alignedRightColLeft
            }
            else -> 0
        }
    }

    /**
     * Computes the next adapter position inside a uniform `spanCount` grid without ever
     * wrapping horizontally from the end of one row to the start of another, and while
     * keeping the exact same column index (`col = fromPos % spanCount`) during vertical moves.
     *
     * Returns:
     * - `-1` when focus should escape upward (top row + `FOCUS_UP`) or downward when `trapBottomEdge == false`.
     * - `fromPos` when focus should stay anchored at a horizontal border (or bottom border when `trapBottomEdge == true`).
     * - `0 until itemCount` target adapter position otherwise.
     */
    fun computeUniformGridNextPosition(
        fromPos: Int,
        itemCount: Int,
        spanCount: Int,
        direction: Int,
        isRtl: Boolean = false,
        trapBottomEdge: Boolean = false
    ): Int {
        if (itemCount <= 0 || fromPos !in 0 until itemCount) return -1
        val spans = spanCount.coerceAtLeast(1)
        val effectiveDir = if (isRtl) {
            when (direction) {
                View.FOCUS_RIGHT -> View.FOCUS_LEFT
                View.FOCUS_LEFT -> View.FOCUS_RIGHT
                else -> direction
            }
        } else {
            direction
        }

        val col = fromPos % spans
        return when (effectiveDir) {
            View.FOCUS_RIGHT -> {
                if (col + 1 >= spans || fromPos + 1 >= itemCount) fromPos else fromPos + 1
            }
            View.FOCUS_LEFT -> {
                if (col == 0) fromPos else fromPos - 1
            }
            View.FOCUS_UP -> {
                val target = fromPos - spans
                if (target < 0) -1 else target
            }
            View.FOCUS_DOWN -> {
                val currentRow = fromPos / spans
                val lastRow = (itemCount - 1) / spans
                if (currentRow >= lastRow) {
                    if (trapBottomEdge) fromPos else -1
                } else {
                    (fromPos + spans).coerceAtMost(itemCount - 1)
                }
            }
            else -> -1
        }
    }

    /**
     * Picks the index of the candidate center X closest to [targetCenterX].
     */
    fun pickClosestHorizontalIndex(candidateCentersX: List<Int>, targetCenterX: Int): Int {
        if (candidateCentersX.isEmpty()) return -1
        var bestIdx = 0
        var bestDist = abs(candidateCentersX[0] - targetCenterX)
        for (i in 1 until candidateCentersX.size) {
            val d = abs(candidateCentersX[i] - targetCenterX)
            if (d < bestDist) {
                bestDist = d
                bestIdx = i
            }
        }
        return bestIdx
    }

    fun screenCenterX(view: View, tmpLoc: IntArray = IntArray(2)): Int {
        view.getLocationOnScreen(tmpLoc)
        return tmpLoc[0] + view.width / 2
    }
}

/**
 * Column-aligned [GridLayoutManager] for MISSA TV 360 PLUS (`Voir tout` sheet, in-universe
 * search grids, and Retro Games multi-column grids).
 *
 * - Never wraps horizontally across row boundaries (`DPAD_LEFT` at col 0 / `DPAD_RIGHT` at col `N-1`).
 * - Preserves exact `spanIndex` column on `DPAD_UP` / `DPAD_DOWN`, including grids with full-span headers.
 * - Pre-scrolls off-screen target rows synchronously so AndroidX `onFocusSearchFailed` never jumps
 *   the cursor to column 0 or column `spanCount - 1`.
 */
class MissaAlignedGridLayoutManager(
    context: Context,
    spanCount: Int,
    var trapBottomEdge: Boolean = false,
    var leftBorderEscapeProvider: (() -> View?)? = null
) : GridLayoutManager(context, spanCount) {

    private var attachedRecyclerView: RecyclerView? = null

    override fun onAttachedToWindow(view: RecyclerView?) {
        super.onAttachedToWindow(view)
        attachedRecyclerView = view
    }

    override fun onDetachedFromWindow(view: RecyclerView?, recycler: RecyclerView.Recycler?) {
        attachedRecyclerView = null
        super.onDetachedFromWindow(view, recycler)
    }

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        if (itemCount <= 0) return null
        val itemView = findContainingItemView(focused) ?: return null
        val fromPos = getPosition(itemView)
        if (fromPos == RecyclerView.NO_POSITION) return null

        val spans = spanCount.coerceAtLeast(1)
        val effectiveDir = if (isLayoutRTL) {
            when (direction) {
                View.FOCUS_RIGHT -> View.FOCUS_LEFT
                View.FOCUS_LEFT -> View.FOCUS_RIGHT
                else -> direction
            }
        } else {
            direction
        }

        val lookup = spanSizeLookup
        val currentSpanIndex = lookup.getSpanIndex(fromPos, spans)
        val currentSpanSize = lookup.getSpanSize(fromPos)
        val currentGroup = lookup.getSpanGroupIndex(fromPos, spans)

        when (effectiveDir) {
            View.FOCUS_LEFT -> {
                if (currentSpanIndex == 0) {
                    val escape = leftBorderEscapeProvider?.invoke()
                        ?.takeIf { it.isShown && it.isFocusable }
                    return escape ?: itemView
                }
                val prevPos = fromPos - 1
                if (prevPos < 0 || lookup.getSpanGroupIndex(prevPos, spans) != currentGroup) {
                    return itemView
                }
                return resolveAndFocusPosition(prevPos, itemView, verticalDelta = 0)
            }

            View.FOCUS_RIGHT -> {
                if (currentSpanIndex + currentSpanSize >= spans || fromPos + 1 >= itemCount) {
                    return itemView
                }
                val nextPos = fromPos + 1
                if (lookup.getSpanGroupIndex(nextPos, spans) != currentGroup) {
                    return itemView
                }
                return resolveAndFocusPosition(nextPos, itemView, verticalDelta = 0)
            }

            View.FOCUS_UP -> {
                val targetPos = findPositionInAdjacentGroup(
                    fromPos = fromPos,
                    targetSpanIndex = currentSpanIndex,
                    spans = spans,
                    step = -1
                )
                if (targetPos < 0) {
                    // Escape cleanly above row 0 (e.g. sort pills / close button / search bar)
                    return null
                }
                return resolveAndFocusPosition(targetPos, itemView, verticalDelta = -1)
            }

            View.FOCUS_DOWN -> {
                val targetPos = findPositionInAdjacentGroup(
                    fromPos = fromPos,
                    targetSpanIndex = currentSpanIndex,
                    spans = spans,
                    step = +1
                )
                if (targetPos < 0) {
                    return if (trapBottomEdge) itemView else null
                }
                return resolveAndFocusPosition(targetPos, itemView, verticalDelta = +1)
            }
        }
        return null
    }

    override fun onFocusSearchFailed(
        focused: View,
        focusDirection: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): View? {
        return onInterceptFocusSearch(focused, focusDirection)
    }

    /**
     * Finds the adapter position in the immediate next (`step = +1`) or previous (`step = -1`)
     * grid row (`spanGroupIndex`) covering or closest to [targetSpanIndex].
     */
    internal fun findPositionInAdjacentGroup(
        fromPos: Int,
        targetSpanIndex: Int,
        spans: Int,
        step: Int
    ): Int {
        val lookup = spanSizeLookup
        val startGroup = lookup.getSpanGroupIndex(fromPos, spans)
        var cursor = fromPos + step
        var targetGroup: Int? = null
        var bestPos = -1
        var bestDist = Int.MAX_VALUE

        while (cursor in 0 until itemCount) {
            val g = lookup.getSpanGroupIndex(cursor, spans)
            if (g == startGroup) {
                cursor += step
                continue
            }
            if (targetGroup == null) {
                // Skip non-focusable section headers if we know view type, otherwise check coverage
                targetGroup = g
            }
            if (g != targetGroup) break

            val sIdx = lookup.getSpanIndex(cursor, spans)
            val sSize = lookup.getSpanSize(cursor)
            if (targetSpanIndex in sIdx until (sIdx + sSize)) {
                return cursor
            }
            val dist = minOf(abs(targetSpanIndex - sIdx), abs(targetSpanIndex - (sIdx + sSize - 1)))
            if (dist < bestDist) {
                bestDist = dist
                bestPos = cursor
            }
            cursor += step
        }
        return bestPos
    }

    private fun resolveAndFocusPosition(
        targetPos: Int,
        fallbackItemView: View,
        verticalDelta: Int
    ): View {
        findViewByPosition(targetPos)?.let { existing ->
            if (existing.isFocusable) return existing
        }

        val rv = attachedRecyclerView
        if (rv != null && verticalDelta != 0) {
            val stepPx = (getDecoratedMeasuredHeight(fallbackItemView).coerceAtLeast(120)) * verticalDelta
            var attempts = 0
            while (findViewByPosition(targetPos) == null && attempts < 3) {
                if (!rv.canScrollVertically(verticalDelta)) break
                rv.scrollBy(0, stepPx)
                attempts++
            }
            findViewByPosition(targetPos)?.let { revealed ->
                if (revealed.isFocusable) return revealed
                // If targetPos was a non-focusable header row, step one more row in same direction
                val lookup = spanSizeLookup
                val spans = spanCount.coerceAtLeast(1)
                val targetSpan = lookup.getSpanIndex(getPosition(fallbackItemView).coerceAtLeast(0), spans)
                val afterHeader = findPositionInAdjacentGroup(targetPos, targetSpan, spans, verticalDelta)
                if (afterHeader in 0 until itemCount) {
                    if (findViewByPosition(afterHeader) == null && rv.canScrollVertically(verticalDelta)) {
                        rv.scrollBy(0, stepPx)
                    }
                    findViewByPosition(afterHeader)?.takeIf { it.isFocusable }?.let { return it }
                }
            }
        }
        return fallbackItemView
    }
}

/**
 * Column-preserving vertical [LinearLayoutManager] for universe & search master lists
 * (`MediaUniverseFragment` and `SearchFragment`).
 *
 * Guarantees that pressing `DPAD_DOWN` / `DPAD_UP` across horizontal rails and grids:
 * 1. Scrolls the next/previous row completely into the viewport BEFORE picking the target card
 *    (so header buttons like `Voir tout` never hijack focus while cards are still below the fold).
 * 2. Preserves the active visual column (`preferredColumnCenterX`) across rows, updating it
 *    automatically whenever focus moves horizontally within the same row.
 */
class MissaVerticalUniverseLayoutManager(
    context: Context
) : LinearLayoutManager(context, VERTICAL, false) {

    private var attachedRecyclerView: RecyclerView? = null
    private var preferredColumnCenterX: Int? = null
    private var lastFocusedRowPosition: Int = RecyclerView.NO_POSITION
    private var inVerticalDispatch: Boolean = false
    private val tmpLoc = IntArray(2)

    private val globalFocusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
        val rv = attachedRecyclerView ?: return@OnGlobalFocusChangeListener
        if (newFocus == null || !isDescendantOf(newFocus, rv)) return@OnGlobalFocusChangeListener
        val rowView = findContainingItemView(newFocus) ?: return@OnGlobalFocusChangeListener
        val newRowPos = getPosition(rowView)
        if (!inVerticalDispatch) {
            if (newRowPos == lastFocusedRowPosition || preferredColumnCenterX == null) {
                preferredColumnCenterX = MissaTvFocusHelper.screenCenterX(newFocus, tmpLoc)
            }
        }
        lastFocusedRowPosition = newRowPos
    }

    override fun onAttachedToWindow(view: RecyclerView?) {
        super.onAttachedToWindow(view)
        attachedRecyclerView = view
        view?.viewTreeObserver?.addOnGlobalFocusChangeListener(globalFocusListener)
    }

    override fun onDetachedFromWindow(view: RecyclerView?, recycler: RecyclerView.Recycler?) {
        view?.viewTreeObserver?.removeOnGlobalFocusChangeListener(globalFocusListener)
        attachedRecyclerView = null
        super.onDetachedFromWindow(view, recycler)
    }

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        if (direction != View.FOCUS_DOWN && direction != View.FOCUS_UP) {
            return super.onInterceptFocusSearch(focused, direction)
        }
        val rv = attachedRecyclerView ?: return super.onInterceptFocusSearch(focused, direction)
        val rowView = findContainingItemView(focused) ?: return super.onInterceptFocusSearch(focused, direction)
        val currentRowPos = getPosition(rowView)
        if (currentRowPos == RecyclerView.NO_POSITION) return null

        val currentCenterX = MissaTvFocusHelper.screenCenterX(focused, tmpLoc)
        val targetCenterX = preferredColumnCenterX ?: currentCenterX
        preferredColumnCenterX = targetCenterX

        inVerticalDispatch = true
        try {
            // 1. Check intra-row vertical navigation first (decks, hero controls, embedded grids, or header buttons)
            findIntraRowVerticalTarget(rv, rowView, focused, direction, targetCenterX)?.let { intra ->
                ensureDescendantVisibleVertically(rv, intra)
                return intra
            }

            // 2. Scan adjacent top-level rows in masterRecycler
            val step = if (direction == View.FOCUS_DOWN) +1 else -1
            var candidatePos = currentRowPos + step
            while (candidatePos in 0 until itemCount) {
                val nextRowView = bringRowIntoViewport(rv, candidatePos, step, rowView.height)
                if (nextRowView != null) {
                    val alignedTarget = findAlignedTargetInRow(nextRowView, direction, targetCenterX)
                    if (alignedTarget != null) {
                        ensureDescendantVisibleVertically(rv, alignedTarget)
                        return alignedTarget
                    }
                }
                candidatePos += step
            }

            // 3. Top/bottom boundary handling
            return if (direction == View.FOCUS_UP) {
                // Allow escaping upward to sticky category pills / search input / top bar
                null
            } else {
                // At the bottom-most row: check if persistent audio mini-player is focusable below, else stay anchored
                val root = rv.rootView as? ViewGroup
                val miniPlayer = root?.findViewById<View>(R.id.missa_radio_mini_player)
                if (miniPlayer != null && miniPlayer.isShown) {
                    val focusables = ArrayList<View>()
                    miniPlayer.addFocusables(focusables, View.FOCUS_DOWN, View.FOCUSABLES_ALL)
                    pickClosestByCenterX(focusables, targetCenterX) ?: focused
                } else {
                    focused
                }
            }
        } finally {
            rv.post { inVerticalDispatch = false }
        }
    }

    override fun onFocusSearchFailed(
        focused: View,
        focusDirection: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): View? {
        if (focusDirection == View.FOCUS_DOWN || focusDirection == View.FOCUS_UP) {
            return onInterceptFocusSearch(focused, focusDirection)
        }
        return super.onFocusSearchFailed(focused, focusDirection, recycler, state)
    }

    private fun findIntraRowVerticalTarget(
        masterRv: RecyclerView,
        rowView: View,
        focused: View,
        direction: Int,
        targetCenterX: Int
    ): View? {
        // Case A: Focused is a row header action button (`Voir tout` / `Tout effacer`) and user presses DOWN
        if (direction == View.FOCUS_DOWN &&
            (focused.id == R.id.row_see_all_button || focused.id == R.id.resume_clear_all_btn)
        ) {
            val childRv = findPrimaryChildRecycler(rowView)
            if (childRv != null) {
                return pickAlignedCardFromChildRecycler(childRv, direction, targetCenterX)
            }
        }

        // Case B: Focused is inside a horizontal child RecyclerView -> single row of cards, always exit row vertically
        val containingChildRv = findAncestorChildRecycler(focused, rowView)
        if (containingChildRv != null) {
            val lm = containingChildRv.layoutManager
            if (lm is LinearLayoutManager && lm.orientation == HORIZONTAL) {
                return null
            }
            if (lm is MissaAlignedGridLayoutManager) {
                val itemView = lm.findContainingItemView(focused) ?: return null
                val fromPos = lm.getPosition(itemView)
                if (fromPos == RecyclerView.NO_POSITION) return null
                val spans = lm.spanCount.coerceAtLeast(1)
                val spanIdx = lm.spanSizeLookup.getSpanIndex(fromPos, spans)
                val step = if (direction == View.FOCUS_DOWN) +1 else -1
                val nextGridPos = lm.findPositionInAdjacentGroup(fromPos, spanIdx, spans, step)
                if (nextGridPos !in 0 until lm.itemCount) return null
                lm.findViewByPosition(nextGridPos)?.let { return it }
                val stepY = itemView.height.coerceAtLeast(140) * step
                if (masterRv.canScrollVertically(step)) {
                    masterRv.scrollBy(0, stepY)
                }
                return lm.findViewByPosition(nextGridPos)
            }
        }

        // Case C: Composite non-rail rows (HeroCarousel, MusicDeck, RadioDeck, EmptyOrError)
        if (rowView is ViewGroup && findPrimaryChildRecycler(rowView) == null) {
            focused.getLocationOnScreen(tmpLoc)
            val focusedTop = tmpLoc[1]
            val focusedBottom = focusedTop + focused.height

            val candidates = ArrayList<View>()
            rowView.addFocusables(candidates, direction, View.FOCUSABLES_ALL)
            val directionalCandidates = candidates.filter { cand ->
                if (cand === focused || !cand.isShown) return@filter false
                cand.getLocationOnScreen(tmpLoc)
                val cTop = tmpLoc[1]
                val cBottom = cTop + cand.height
                if (direction == View.FOCUS_DOWN) {
                    cTop >= focusedBottom - 8
                } else {
                    cBottom <= focusedTop + 8
                }
            }
            if (directionalCandidates.isNotEmpty()) {
                return pickNearestBandAndCenterX(directionalCandidates, direction, targetCenterX)
            }
            // Fallback to FocusFinder strictly within rowView if subviews slightly overlap
            val ff = FocusFinder.getInstance().findNextFocus(rowView, focused, direction)
            if (ff != null && ff !== focused) return ff
        }
        return null
    }

    private fun bringRowIntoViewport(
        rv: RecyclerView,
        targetPos: Int,
        step: Int,
        referenceRowHeight: Int
    ): View? {
        val fallbackStepPx = referenceRowHeight.coerceAtLeast(180) * step
        var attempts = 0
        while (findViewByPosition(targetPos) == null && attempts < 4) {
            if (!rv.canScrollVertically(step)) break
            rv.scrollBy(0, fallbackStepPx)
            attempts++
        }
        val rowView = findViewByPosition(targetPos) ?: return null
        val topLimit = rv.paddingTop
        val bottomLimit = rv.height - rv.paddingBottom
        val rowTop = getDecoratedTop(rowView)
        val rowBottom = getDecoratedBottom(rowView)
        val rowHeight = rowBottom - rowTop
        val viewportHeight = (bottomLimit - topLimit).coerceAtLeast(1)

        val dy = when {
            step > 0 && rowBottom > bottomLimit -> {
                if (rowHeight <= viewportHeight) rowBottom - bottomLimit else rowTop - topLimit
            }
            step < 0 && rowTop < topLimit -> {
                if (rowHeight <= viewportHeight) rowTop - topLimit else rowBottom - bottomLimit
            }
            else -> 0
        }
        if (dy != 0 && rv.canScrollVertically(step)) {
            rv.scrollBy(0, dy)
        }
        return findViewByPosition(targetPos) ?: rowView
    }

    private fun ensureDescendantVisibleVertically(rv: RecyclerView, target: View) {
        val rect = Rect(0, 0, target.width, target.height)
        if (isDescendantOf(target, rv)) {
            rv.offsetDescendantRectToMyCoords(target, rect)
            val topMargin = rv.paddingTop + 12
            val bottomMargin = rv.height - rv.paddingBottom - 12
            val dy = when {
                rect.top < topMargin -> rect.top - topMargin
                rect.bottom > bottomMargin -> rect.bottom - bottomMargin
                else -> 0
            }
            if (dy != 0) {
                rv.scrollBy(0, dy)
            }
        }
    }

    private fun findAlignedTargetInRow(
        rowView: View,
        direction: Int,
        targetCenterX: Int
    ): View? {
        val childRv = findPrimaryChildRecycler(rowView)
        if (childRv != null && childRv.isShown) {
            pickAlignedCardFromChildRecycler(childRv, direction, targetCenterX)?.let { return it }
        }
        if (rowView is ViewGroup) {
            val focusables = ArrayList<View>()
            rowView.addFocusables(focusables, direction, View.FOCUSABLES_ALL)
            val valid = focusables.filter {
                it.isShown &&
                    it.id != R.id.row_see_all_button &&
                    it.id != R.id.resume_clear_all_btn
            }.ifEmpty { focusables.filter { it.isShown } }
            return pickNearestBandAndCenterX(valid, direction, targetCenterX)
        }
        return rowView.takeIf { it.isFocusable && it.isShown }
    }

    private fun pickAlignedCardFromChildRecycler(
        childRv: RecyclerView,
        direction: Int,
        targetCenterX: Int
    ): View? {
        val lm = childRv.layoutManager ?: return null
        val visibleCards = ArrayList<View>(lm.childCount)
        for (i in 0 until lm.childCount) {
            val child = lm.getChildAt(i) ?: continue
            if (child.isShown && child.isFocusable) {
                visibleCards.add(child)
            }
        }
        if (visibleCards.isEmpty()) return null
        return if (lm is GridLayoutManager) {
            pickNearestBandAndCenterX(visibleCards, direction, targetCenterX)
        } else {
            pickClosestByCenterX(visibleCards, targetCenterX)
        }
    }

    private fun pickNearestBandAndCenterX(
        views: List<View>,
        direction: Int,
        targetCenterX: Int
    ): View? {
        if (views.isEmpty()) return null
        data class PosView(val view: View, val topY: Int, val bottomY: Int, val centerX: Int)
        val measured = views.map { v ->
            v.getLocationOnScreen(tmpLoc)
            PosView(v, tmpLoc[1], tmpLoc[1] + v.height, tmpLoc[0] + v.width / 2)
        }
        val anchorY = if (direction == View.FOCUS_DOWN) {
            measured.minOf { it.topY }
        } else {
            measured.maxOf { it.bottomY }
        }
        val bandTolerancePx = 48
        val inBand = measured.filter { pv ->
            if (direction == View.FOCUS_DOWN) {
                abs(pv.topY - anchorY) <= bandTolerancePx
            } else {
                abs(pv.bottomY - anchorY) <= bandTolerancePx
            }
        }.ifEmpty { measured }

        return inBand.minByOrNull { abs(it.centerX - targetCenterX) }?.view
    }

    private fun pickClosestByCenterX(views: List<View>, targetCenterX: Int): View? {
        if (views.isEmpty()) return null
        val centers = views.map { MissaTvFocusHelper.screenCenterX(it, tmpLoc) }
        val idx = MissaTvFocusHelper.pickClosestHorizontalIndex(centers, targetCenterX)
        return views.getOrNull(idx)
    }

    private fun findPrimaryChildRecycler(rowView: View): RecyclerView? {
        if (rowView is RecyclerView) return rowView
        if (rowView !is ViewGroup) return null
        val knownIds = intArrayOf(
            R.id.row_child_recycler,
            R.id.resume_recycler,
            R.id.hub_shortcuts_recycler,
            R.id.search_grid_recycler,
            R.id.home_child_recyclerview
        )
        for (id in knownIds) {
            rowView.findViewById<RecyclerView>(id)?.let { return it }
        }
        for (i in 0 until rowView.childCount) {
            val c = rowView.getChildAt(i)
            if (c is RecyclerView) return c
        }
        return null
    }

    private fun findAncestorChildRecycler(view: View, stopAtRowView: View): RecyclerView? {
        var cur: View? = view
        while (cur != null && cur !== stopAtRowView) {
            if (cur is RecyclerView) return cur
            cur = cur.parent as? View
        }
        return null
    }

    private fun isDescendantOf(child: View, ancestor: View): Boolean {
        var cur: View? = child
        while (cur != null) {
            if (cur === ancestor) return true
            cur = cur.parent as? View
        }
        return false
    }
}
