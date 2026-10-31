package com.lagradost.cloudstream3.widget

import android.content.Context
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.lagradost.cloudstream3.ui.universe.MissaTvFocusHelper

class LinearRecycleViewLayoutManager(
    val context: Context,
    val nextFocusUp: Int,
    val nextFocusDown: Int
) : LinearLayoutManager(context) {
    override fun onInterceptFocusSearch(focused: View, direction: Int): View? {
        val raw = try {
            val itemView = findContainingItemView(focused)
            val position = if (itemView != null) getPosition(itemView) else getPosition(focused)
            val count = itemCount

            (if (position == count - 1 && direction == View.FOCUS_DOWN) {
                focused.rootView.findViewById(nextFocusDown)
            } else if (position == 0 && direction == View.FOCUS_UP) {
                focused.rootView.findViewById(nextFocusUp)
            } else {
                super.onInterceptFocusSearch(focused, direction)
            }) ?: super.onInterceptFocusSearch(focused, direction)
        } catch (t: Throwable) {
            super.onInterceptFocusSearch(focused, direction)
        }
        return MissaTvFocusHelper.safeFocusSearchReturn(null, focused, raw, direction)
    }
}

