package com.lagradost.cloudstream3.ui.result

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.FocusDirection
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.ui.universe.MissaTvFocusHelper
import kotlin.math.abs

const val FOCUS_SELF = View.NO_ID - 1
const val FOCUS_INHERIT = FOCUS_SELF - 1

fun RecyclerView?.setLinearListLayout(
    isHorizontal: Boolean = true,
    nextLeft: Int = FOCUS_INHERIT,
    nextRight: Int = FOCUS_INHERIT,
    nextUp: Int = FOCUS_INHERIT,
    nextDown: Int = FOCUS_INHERIT,
    startEscapeTarget: (() -> View?)? = null,
    endEscapeTarget: (() -> View?)? = null,
) {
    if (this == null) return
    val ctx = this.context ?: return
    this.layoutManager = (this.layoutManager as? LinearListLayout ?: LinearListLayout(ctx)).apply {
        if (isHorizontal) setHorizontal() else setVertical()
        nextFocusLeft =
            if (nextLeft == FOCUS_INHERIT) this@setLinearListLayout.nextFocusLeftId else nextLeft
        nextFocusRight =
            if (nextRight == FOCUS_INHERIT) this@setLinearListLayout.nextFocusRightId else nextRight
        nextFocusUp =
            if (nextUp == FOCUS_INHERIT) this@setLinearListLayout.nextFocusUpId else nextUp
        nextFocusDown =
            if (nextDown == FOCUS_INHERIT) this@setLinearListLayout.nextFocusDownId else nextDown
        this.startEscapeTarget = startEscapeTarget
        this.endEscapeTarget = endEscapeTarget
    }
}

open class LinearListLayout(context: Context?) :
    LinearLayoutManager(context) {

    var nextFocusLeft: Int = View.NO_ID
    var nextFocusRight: Int = View.NO_ID
    var nextFocusUp: Int = View.NO_ID
    var nextFocusDown: Int = View.NO_ID
    var startEscapeTarget: (() -> View?)? = null
    var endEscapeTarget: (() -> View?)? = null

    fun setHorizontal() {
        orientation = HORIZONTAL
    }

    fun setVertical() {
        orientation = VERTICAL
    }

    private fun getViewFromPos(pos: Int): View? {
        findViewByPosition(pos)?.let { return it }
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if ((child?.layoutParams as? RecyclerView.LayoutParams)?.absoluteAdapterPosition == pos) {
                return child
            }
        }
        return null
    }

    /** From the current focus go to a configured boundary direction. */
    private fun getNextDirection(focused: View?, direction: FocusDirection): View? {
        if (focused == null) return null
        if (direction == FocusDirection.Start) {
            startEscapeTarget?.invoke()?.takeIf { it.isShown && it.isFocusable }?.let { return it }
        } else if (direction == FocusDirection.End) {
            endEscapeTarget?.invoke()?.takeIf { it.isShown && it.isFocusable }?.let { return it }
        }

        val id = when (direction) {
            FocusDirection.Start -> if (isLayoutRTL) nextFocusRight else nextFocusLeft
            FocusDirection.End -> if (isLayoutRTL) nextFocusLeft else nextFocusRight
            FocusDirection.Up -> nextFocusUp
            FocusDirection.Down -> nextFocusDown
        }

        return when (id) {
            View.NO_ID -> null
            FOCUS_SELF -> focused
            else -> CommonActivity.continueGetNextFocus(
                activity ?: focused,
                focused,
                direction,
                id
            )?.takeIf { it.isShown && it.isFocusable } ?: if (
                direction == FocusDirection.Start || direction == FocusDirection.End
            ) {
                focused
            } else {
                null
            }
        }
    }

    fun redirectRecycleToFirstItem(focused: View, referenceSource: View? = null): View? {
        return when (focused) {
            is RecyclerView -> {
                val lm = focused.layoutManager ?: return focused
                if (referenceSource != null && lm.childCount > 0) {
                    val tmp = IntArray(2)
                    val targetX = MissaTvFocusHelper.screenCenterX(referenceSource, tmp)
                    var bestView: View? = null
                    var bestDist = Int.MAX_VALUE
                    for (i in 0 until lm.childCount) {
                        val candidate = lm.getChildAt(i) ?: continue
                        if (!candidate.isShown || !candidate.isFocusable) continue
                        val d = abs(MissaTvFocusHelper.screenCenterX(candidate, tmp) - targetX)
                        if (d < bestDist) {
                            bestDist = d
                            bestView = candidate
                        }
                    }
                    if (bestView != null) return bestView
                }
                (lm as? LinearListLayout)?.let { focusedLayoutManager ->
                    val firstPosition = focusedLayoutManager.findFirstVisibleItemPosition()
                    focusedLayoutManager.findViewByPosition(firstPosition)
                } ?: focused
            }

            else -> focused
        }
    }

    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        val itemView = findContainingItemView(focused) ?: return null
        val dir = if (orientation == HORIZONTAL) {
            if (direction == View.FOCUS_DOWN) {
                getNextDirection(itemView, FocusDirection.Down)?.let { newFocus ->
                    return redirectRecycleToFirstItem(newFocus, itemView)
                }
                return null
            }
            if (direction == View.FOCUS_UP) {
                getNextDirection(itemView, FocusDirection.Up)?.let { newFocus ->
                    return redirectRecycleToFirstItem(newFocus, itemView)
                }
                return null
            }
            if (direction != View.FOCUS_LEFT && direction != View.FOCUS_RIGHT) {
                return null
            }
            var ret = if (direction == View.FOCUS_RIGHT) 1 else -1
            if (isLayoutRTL) {
                ret = -ret
            }
            ret
        } else {
            if (direction == View.FOCUS_RIGHT) {
                getNextDirection(itemView, FocusDirection.End)?.let { return it }
                return null
            }
            if (direction == View.FOCUS_LEFT) {
                getNextDirection(itemView, FocusDirection.Start)?.let { return it }
                return null
            }
            if (direction != View.FOCUS_DOWN && direction != View.FOCUS_UP) {
                return null
            }
            if (direction == View.FOCUS_DOWN) 1 else -1
        }

        return try {
            val position = getPosition(itemView)
            if (position == RecyclerView.NO_POSITION) return itemView
            val lookFor = position + dir

            when {
                lookFor >= itemCount -> {
                    getNextDirection(
                        itemView,
                        if (orientation == HORIZONTAL) FocusDirection.End else FocusDirection.Down
                    ) ?: if (orientation == HORIZONTAL) itemView else null
                }

                lookFor < 0 -> {
                    getNextDirection(
                        itemView,
                        if (orientation == HORIZONTAL) FocusDirection.Start else FocusDirection.Up
                    ) ?: if (orientation == HORIZONTAL) itemView else null
                }

                else -> {
                    getViewFromPos(lookFor) ?: run {
                        val parentRv = itemView.parent as? RecyclerView
                        if (parentRv != null) {
                            if (orientation == HORIZONTAL) {
                                val visualDir = if (isLayoutRTL) -dir else dir
                                val stepPx = getDecoratedMeasuredWidth(itemView).coerceAtLeast(1) * visualDir
                                parentRv.scrollBy(stepPx, 0)
                            } else {
                                val stepPx = getDecoratedMeasuredHeight(itemView).coerceAtLeast(1) * dir
                                parentRv.scrollBy(0, stepPx)
                            }
                        } else {
                            scrollToPosition(lookFor)
                        }
                        // Never return null on valid horizontal moves so FocusFinder cannot jump rows
                        getViewFromPos(lookFor) ?: if (orientation == HORIZONTAL) itemView else null
                    }
                }
            }
        } catch (e: Exception) {
            logError(e)
            if (orientation == HORIZONTAL) itemView else null
        }
    }

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView,
        child: View,
        rect: android.graphics.Rect,
        immediate: Boolean,
        focusedChildVisible: Boolean
    ): Boolean {
        if (isLayout(TV or EMULATOR) && orientation == HORIZONTAL) {
            val dx = MissaTvFocusHelper.computeAlignedHorizontalScrollDx(
                leftEdge = parent.paddingLeft,
                rightEdge = parent.width - parent.paddingRight,
                childLeft = getDecoratedLeft(child),
                childRight = getDecoratedRight(child),
                childWidth = getDecoratedMeasuredWidth(child)
            )
            return if (dx != 0) {
                parent.scrollBy(dx, 0)
                true
            } else {
                false
            }
        }
        return super.requestChildRectangleOnScreen(
            parent,
            child,
            rect,
            immediate,
            focusedChildVisible
        )
    }
}
