package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.graphics.Rect
import android.view.FocusFinder
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.R
import kotlin.math.abs

/**
 * Pure math & layout helpers for deterministic, strictly adjacent cell-to-cell D-Pad navigation
 * across horizontal rails, vertical category lists, player bars, and multi-column media grids.
 */
internal object MissaTvFocusHelper {

    /**
     * Computes the horizontal scroll delta (`dx`) for a horizontal rail so that:
     * 1. Cards already completely inside `[leftEdge, rightEdge]` do NOT scroll (`dx = 0`),
     *    keeping column X coordinates strictly static across adjacent rows.
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

    /**
     * Picks the index of the candidate horizontal interval `[left, right]` that is most
     * directly adjacent to `[focusedLeft, focusedRight]`:
     * 1. Candidates that horizontally overlap `[focusedLeft, focusedRight]` win first,
     *    ranked by largest overlap and tie-broken by closest center X.
     * 2. If no candidate overlaps horizontally, picks the candidate with the smallest
     *    horizontal gap / center distance.
     */
    fun pickBestAdjacentIndex(
        candidateBounds: List<Pair<Int, Int>>,
        focusedLeft: Int,
        focusedRight: Int
    ): Int {
        if (candidateBounds.isEmpty()) return -1
        val fCenter = (focusedLeft + focusedRight) / 2
        var bestOverlapIdx = -1
        var bestOverlapPx = 0
        var bestOverlapCenterDist = Int.MAX_VALUE

        var bestGapIdx = 0
        var bestGapPx = Int.MAX_VALUE
        var bestGapCenterDist = Int.MAX_VALUE

        for (i in candidateBounds.indices) {
            val (cLeft, cRight) = candidateBounds[i]
            val cCenter = (cLeft + cRight) / 2
            val centerDist = abs(cCenter - fCenter)
            val overlap = minOf(focusedRight, cRight) - maxOf(focusedLeft, cLeft)
            if (overlap > 0) {
                if (overlap > bestOverlapPx ||
                    (overlap == bestOverlapPx && centerDist < bestOverlapCenterDist)
                ) {
                    bestOverlapPx = overlap
                    bestOverlapCenterDist = centerDist
                    bestOverlapIdx = i
                }
            } else {
                val gap = -overlap
                if (gap < bestGapPx || (gap == bestGapPx && centerDist < bestGapCenterDist)) {
                    bestGapPx = gap
                    bestGapCenterDist = centerDist
                    bestGapIdx = i
                }
            }
        }
        return if (bestOverlapIdx >= 0) bestOverlapIdx else bestGapIdx
    }

    fun screenCenterX(view: View, tmpLoc: IntArray = IntArray(2)): Int {
        view.getLocationOnScreen(tmpLoc)
        return tmpLoc[0] + view.width / 2
    }

    fun screenHorizontalBounds(view: View, tmpLoc: IntArray = IntArray(2)): Pair<Int, Int> {
        view.getLocationOnScreen(tmpLoc)
        return tmpLoc[0] to (tmpLoc[0] + view.width.coerceAtLeast(1))
    }

    /**
     * Collects visible leaf-level focusable controls inside [container] (excluding full-screen or
     * scrolling wrapper `ViewGroup`s) and returns the cell in the nearest vertical band (`topmost`
     * for [View.FOCUS_DOWN], `bottommost` for [View.FOCUS_UP]) most directly aligned with [sourceView].
     */
    fun findAdjacentFocusableInContainer(
        container: View,
        sourceView: View,
        direction: Int
    ): View? {
        val raw = ArrayList<View>()
        container.addFocusables(raw, direction, View.FOCUSABLES_ALL)
        val candidates = raw.filter { v ->
            v !== sourceView &&
                v.isShown &&
                v.width > 0 &&
                v.height > 0 &&
                !isWrapperContainer(v)
        }
        if (candidates.isEmpty()) return null
        val tmp = IntArray(2)
        val (fLeft, fRight) = screenHorizontalBounds(sourceView, tmp)
        return pickNearestBandAdjacentView(candidates, direction, fLeft, fRight, tmp)
    }

    internal fun isWrapperContainer(view: View): Boolean {
        if (view is RecyclerView ||
            view is android.widget.ScrollView ||
            view is android.widget.HorizontalScrollView ||
            view is androidx.core.widget.NestedScrollView ||
            view is androidx.fragment.app.FragmentContainerView ||
            view is androidx.swiperefreshlayout.widget.SwipeRefreshLayout ||
            view.id == R.id.missa_radio_mini_player
        ) {
            return true
        }
        return false
    }

    internal fun pickNearestBandAdjacentView(
        views: List<View>,
        direction: Int,
        focusedLeft: Int,
        focusedRight: Int,
        tmpLoc: IntArray = IntArray(2)
    ): View? {
        if (views.isEmpty()) return null
        data class MeasuredView(
            val view: View,
            val left: Int,
            val right: Int,
            val top: Int,
            val bottom: Int
        )
        val measured = views.map { v ->
            v.getLocationOnScreen(tmpLoc)
            MeasuredView(
                view = v,
                left = tmpLoc[0],
                right = tmpLoc[0] + v.width.coerceAtLeast(1),
                top = tmpLoc[1],
                bottom = tmpLoc[1] + v.height.coerceAtLeast(1)
            )
        }
        val anchorY = if (direction == View.FOCUS_DOWN) {
            measured.minOf { it.top }
        } else {
            measured.maxOf { it.bottom }
        }
        val bandTolerancePx = 56
        val inBand = measured.filter { mv ->
            if (direction == View.FOCUS_DOWN) {
                abs(mv.top - anchorY) <= bandTolerancePx
            } else {
                abs(mv.bottom - anchorY) <= bandTolerancePx
            }
        }.ifEmpty { measured }

        val idx = pickBestAdjacentIndex(
            candidateBounds = inBand.map { it.left to it.right },
            focusedLeft = focusedLeft,
            focusedRight = focusedRight
        )
        return inBand.getOrNull(idx)?.view
    }

    /**
     * Chains deterministic horizontal `nextFocusLeftId` / `nextFocusRightId` across a list of
     * visible controls on a bar/deck so lateral D-Pad presses step strictly cell-by-cell without
     * stalling on wide spacers or jumping out to neighboring rows.
     */
    fun chainHorizontalFocusRing(
        controls: List<View>,
        clampStart: Boolean = true,
        clampEnd: Boolean = true
    ) {
        val visible = controls.filter { it.isShown && it.isFocusable }
        if (visible.isEmpty()) return
        for (v in visible) {
            if (v.id == View.NO_ID) {
                v.id = View.generateViewId()
            }
        }
        for (i in visible.indices) {
            val cur = visible[i]
            cur.nextFocusLeftId = when {
                i > 0 -> visible[i - 1].id
                clampStart -> cur.id
                else -> View.NO_ID
            }
            cur.nextFocusRightId = when {
                i < visible.lastIndex -> visible[i + 1].id
                clampEnd -> cur.id
                else -> View.NO_ID
            }
        }
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
    var leftBorderEscapeProvider: (() -> View?)? = null,
    var topBorderEscapeProvider: ((View) -> View?)? = null
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
                    topBorderEscapeProvider?.invoke(itemView)
                        ?.takeIf { it.isShown && it.isFocusable }
                        ?.let { return it }
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

    private fun isSelectableGridChild(view: View): Boolean {
        if (view.isFocusable) return true
        // Skip non-interactive section headers while allowing clickable cards in Emulator/touch mode
        if (view.isClickable) {
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            return true
        }
        return false
    }

    private fun resolveAndFocusPosition(
        targetPos: Int,
        fallbackItemView: View,
        verticalDelta: Int
    ): View? {
        val rv = attachedRecyclerView
        findViewByPosition(targetPos)?.let { existing ->
            if (isSelectableGridChild(existing)) {
                if (rv != null && verticalDelta != 0) {
                    ensureGridRowVisible(rv, existing)
                }
                return existing
            }
        }

        if (rv != null && verticalDelta != 0) {
            val stepPx = (getDecoratedMeasuredHeight(fallbackItemView).coerceAtLeast(120)) * verticalDelta
            var attempts = 0
            while (findViewByPosition(targetPos) == null && attempts < 4) {
                if (!rv.canScrollVertically(verticalDelta)) break
                rv.scrollBy(0, stepPx)
                attempts++
            }
            findViewByPosition(targetPos)?.let { revealed ->
                if (isSelectableGridChild(revealed)) {
                    ensureGridRowVisible(rv, revealed)
                    return revealed
                }
                // Target position was a non-focusable section header row (e.g., Retro Games header):
                // step one more group in the same vertical direction, or escape upward if at group 0.
                val lookup = spanSizeLookup
                val spans = spanCount.coerceAtLeast(1)
                val targetSpan = lookup.getSpanIndex(getPosition(fallbackItemView).coerceAtLeast(0), spans)
                val afterHeader = findPositionInAdjacentGroup(targetPos, targetSpan, spans, verticalDelta)
                if (afterHeader < 0 && verticalDelta < 0) {
                    topBorderEscapeProvider?.invoke(fallbackItemView)
                        ?.takeIf { it.isShown && it.isFocusable }
                        ?.let { return it }
                    return null
                }
                if (afterHeader in 0 until itemCount) {
                    if (findViewByPosition(afterHeader) == null && rv.canScrollVertically(verticalDelta)) {
                        rv.scrollBy(0, stepPx)
                    }
                    findViewByPosition(afterHeader)?.takeIf { isSelectableGridChild(it) }?.let {
                        ensureGridRowVisible(rv, it)
                        return it
                    }
                }
            }
        }
        return fallbackItemView
    }

    private fun ensureGridRowVisible(rv: RecyclerView, item: View) {
        val topLimit = rv.paddingTop + 8
        val bottomLimit = rv.height - rv.paddingBottom - 8
        val itemTop = getDecoratedTop(item)
        val itemBottom = getDecoratedBottom(item)
        val dy = when {
            itemTop < topLimit -> itemTop - topLimit
            itemBottom > bottomLimit -> itemBottom - bottomLimit
            else -> 0
        }
        if (dy != 0) {
            rv.scrollBy(0, dy)
        }
    }
}

/**
 * Strictly adjacent cell-to-cell vertical [LinearLayoutManager] for universe & search master lists
 * (`MediaUniverseFragment` and `SearchFragment`).
 *
 * Guarantees that pressing `DPAD_DOWN` / `DPAD_UP` always correlates cursor movement to the
 * immediately adjacent cell directly below or above the currently focused cell's live screen
 * horizontal bounds `[focusedLeft, focusedRight]`.
 */
class MissaVerticalUniverseLayoutManager(
    context: Context
) : LinearLayoutManager(context, VERTICAL, false) {

    private var attachedRecyclerView: RecyclerView? = null
    private val tmpLoc = IntArray(2)

    override fun onAttachedToWindow(view: RecyclerView?) {
        super.onAttachedToWindow(view)
        attachedRecyclerView = view
    }

    override fun onDetachedFromWindow(view: RecyclerView?, recycler: RecyclerView.Recycler?) {
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

        val (focusedLeft, focusedRight) = MissaTvFocusHelper.screenHorizontalBounds(focused, tmpLoc)

        // 1. Check intra-row vertical navigation first (decks, hero controls, embedded grids, or header buttons)
        findIntraRowVerticalTarget(rv, rowView, focused, direction, focusedLeft, focusedRight)?.let { intra ->
            ensureDescendantVisibleVertically(rv, intra)
            return intra
        }

        // 2. Scan adjacent top-level rows in masterRecycler strictly one row at a time
        val step = if (direction == View.FOCUS_DOWN) +1 else -1
        var candidatePos = currentRowPos + step
        while (candidatePos in 0 until itemCount) {
            val nextRowView = bringRowIntoViewport(rv, candidatePos, step, rowView.height)
            if (nextRowView != null) {
                val alignedTarget = findAlignedTargetInRow(nextRowView, direction, focusedLeft, focusedRight)
                if (alignedTarget != null) {
                    ensureDescendantVisibleVertically(rv, alignedTarget)
                    return alignedTarget
                }
            }
            candidatePos += step
        }

        // 3. Top/bottom boundary handling
        return if (direction == View.FOCUS_UP) {
            // Escape cleanly upward to the adjacent cell in sticky filter bar or top navigation bar
            val root = rv.rootView as? ViewGroup
            val stickyBar = root?.findViewById<View>(R.id.universe_sticky_filter_bar)
            if (stickyBar != null && stickyBar.isShown) {
                MissaTvFocusHelper.findAdjacentFocusableInContainer(stickyBar, focused, View.FOCUS_UP)
                    ?.let { return it }
            }
            val topShell = root?.findViewById<View>(R.id.missa_top_shell)
            if (topShell != null && topShell.isShown) {
                MissaTvFocusHelper.findAdjacentFocusableInContainer(topShell, focused, View.FOCUS_UP)
                    ?.let { return it }
            }
            null
        } else {
            // At the bottom-most row: step down into the adjacent cell of the persistent Radio/Music bar if visible
            val root = rv.rootView as? ViewGroup
            val miniPlayer = root?.findViewById<View>(R.id.missa_radio_mini_player)
            if (miniPlayer != null && miniPlayer.isShown) {
                MissaTvFocusHelper.findAdjacentFocusableInContainer(miniPlayer, focused, View.FOCUS_DOWN)
                    ?: focused
            } else {
                focused
            }
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
        focusedLeft: Int,
        focusedRight: Int
    ): View? {
        // Case A: Focused is a row header action button (`Voir tout` / `Tout effacer`)
        if (focused.id == R.id.row_see_all_button || focused.id == R.id.resume_clear_all_btn) {
            if (direction == View.FOCUS_DOWN) {
                val childRv = findPrimaryChildRecycler(rowView)
                if (childRv != null) {
                    return pickAlignedCardFromChildRecycler(childRv, direction, focusedLeft, focusedRight)
                }
            }
            // For FOCUS_UP on header button, return null so Step 2 moves to the row above
            return null
        }

        // Case B: Focused is inside a child RecyclerView of this row
        val containingChildRv = findAncestorChildRecycler(focused, rowView)
        if (containingChildRv != null) {
            val lm = containingChildRv.layoutManager
            if (lm is LinearLayoutManager && lm.orientation == HORIZONTAL) {
                // When pressing UP from a card directly beneath this row's `Voir tout` / `Tout effacer` button,
                // visit that adjacent header button; all other columns step straight to the row above.
                if (direction == View.FOCUS_UP) {
                    findOverlappingRowHeaderButton(rowView, focusedLeft, focusedRight)?.let { return it }
                }
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
                if (cand === focused || !cand.isShown || MissaTvFocusHelper.isWrapperContainer(cand)) {
                    return@filter false
                }
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
                return MissaTvFocusHelper.pickNearestBandAdjacentView(
                    views = directionalCandidates,
                    direction = direction,
                    focusedLeft = focusedLeft,
                    focusedRight = focusedRight,
                    tmpLoc = tmpLoc
                )
            }
            val ff = FocusFinder.getInstance().findNextFocus(rowView, focused, direction)
            if (ff != null && ff !== focused && !MissaTvFocusHelper.isWrapperContainer(ff)) {
                return ff
            }
        }
        return null
    }

    private fun findOverlappingRowHeaderButton(
        rowView: View,
        focusedLeft: Int,
        focusedRight: Int
    ): View? {
        if (rowView !is ViewGroup) return null
        val headerIds = intArrayOf(R.id.row_see_all_button, R.id.resume_clear_all_btn)
        for (id in headerIds) {
            val btn = rowView.findViewById<View>(id) ?: continue
            if (!btn.isShown || !btn.isFocusable || btn.width <= 0) continue
            val (bLeft, bRight) = MissaTvFocusHelper.screenHorizontalBounds(btn, tmpLoc)
            val overlap = minOf(focusedRight, bRight) - maxOf(focusedLeft, bLeft)
            if (overlap > 0) {
                return btn
            }
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
        focusedLeft: Int,
        focusedRight: Int
    ): View? {
        // When entering from ABOVE in the far-right column directly over `Voir tout` / `Tout effacer`,
        // visit that immediately adjacent header button first; all other columns land directly on the aligned card.
        if (direction == View.FOCUS_DOWN) {
            findOverlappingRowHeaderButton(rowView, focusedLeft, focusedRight)?.let { return it }
        }
        val childRv = findPrimaryChildRecycler(rowView)
        if (childRv != null && childRv.isShown) {
            pickAlignedCardFromChildRecycler(childRv, direction, focusedLeft, focusedRight)?.let { return it }
        }
        if (rowView is ViewGroup) {
            val focusables = ArrayList<View>()
            rowView.addFocusables(focusables, direction, View.FOCUSABLES_ALL)
            val valid = focusables.filter {
                it.isShown &&
                    !MissaTvFocusHelper.isWrapperContainer(it) &&
                    it.id != R.id.row_see_all_button &&
                    it.id != R.id.resume_clear_all_btn
            }.ifEmpty {
                focusables.filter { it.isShown && !MissaTvFocusHelper.isWrapperContainer(it) }
            }
            return MissaTvFocusHelper.pickNearestBandAdjacentView(
                views = valid,
                direction = direction,
                focusedLeft = focusedLeft,
                focusedRight = focusedRight,
                tmpLoc = tmpLoc
            )
        }
        return rowView.takeIf { it.isFocusable && it.isShown }
    }

    private fun pickAlignedCardFromChildRecycler(
        childRv: RecyclerView,
        direction: Int,
        focusedLeft: Int,
        focusedRight: Int
    ): View? {
        val lm = childRv.layoutManager ?: return null
        val visibleCards = ArrayList<View>(lm.childCount)
        for (i in 0 until lm.childCount) {
            val child = lm.getChildAt(i) ?: continue
            if (!child.isShown) continue
            if (!child.isFocusable && child.isClickable) {
                child.isFocusable = true
                child.isFocusableInTouchMode = true
            }
            if (child.isFocusable) {
                visibleCards.add(child)
            }
        }
        if (visibleCards.isEmpty()) return null
        return if (lm is GridLayoutManager) {
            MissaTvFocusHelper.pickNearestBandAdjacentView(
                views = visibleCards,
                direction = direction,
                focusedLeft = focusedLeft,
                focusedRight = focusedRight,
                tmpLoc = tmpLoc
            )
        } else {
            val bounds = visibleCards.map { MissaTvFocusHelper.screenHorizontalBounds(it, tmpLoc) }
            val idx = MissaTvFocusHelper.pickBestAdjacentIndex(bounds, focusedLeft, focusedRight)
            visibleCards.getOrNull(idx)
        }
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
