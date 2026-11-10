package com.lagradost.cloudstream3.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.inspector.WindowInspector
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute

/**
 * Curseur vert des fenêtres posées au-dessus de l'écran principal (dialogues, feuilles du bas,
 * menus d'options, onboarding…).
 *
 * Le cadre vert animé de MainActivity (TvFocus) est une vue de la fenêtre principale : dès
 * qu'une fenêtre de dialogue s'ouvre, la sélection part dans cette autre fenêtre et le cadre
 * restait figé derrière, sur l'élément d'avant, tandis que rien ne montrait où l'on était dans
 * le dialogue. Ici, on dessine le même cadre dans la fenêtre du dialogue, en surimpression
 * (ViewOverlay, aucune vue ajoutée), et il suit la sélection tant que la fenêtre est ouverte.
 *
 * Les écrans Compose dessinent leur propre contour vert (Modifier.focusOutline) : on ne
 * double pas leur cadre.
 */
object MissaWindowFocusRing {

    /**
     * Pose le curseur dans chaque fenêtre de l'application qui a le focus et n'est pas
     * [mainDecor]. Les fenêtres ne sont listables qu'à partir d'Android 10 ; avant, rien ne
     * change (le dialogue garde l'aspect de sélection d'Android).
     */
    fun attachToFocusedWindows(mainDecor: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val roots = runCatching { WindowInspector.getGlobalWindowViews() }.getOrNull() ?: return
        roots.filter { it !== mainDecor && it.hasWindowFocus() && it.isAttachedToWindow }
            .forEach { attach(it) }
    }

    fun attach(root: View) {
        if (root.getTag(R.id.missa_window_focus_ring) != null) return
        val ctx = root.context
        val strokePx = ctx.resources.getDimensionPixelSize(R.dimen.missa_focus_stroke_width)
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            setStroke(strokePx, ctx.colorFromAttribute(R.attr.missaAccentNavigation))
            setBounds(0, 0, 0, 0)
        }
        root.overlay.add(ring)
        var focused: View? = root.findFocus()
        val rootLoc = IntArray(2)
        val loc = IntArray(2)

        fun refresh() {
            val v = focused
            if (v == null || !v.isAttachedToWindow || !v.isShown || v.width <= 0 || v.height <= 0 ||
                isInsideCompose(v) || isContainer(v, root)
            ) {
                ring.setBounds(0, 0, 0, 0)
                root.invalidate()
                return
            }
            root.getLocationInWindow(rootLoc)
            v.getLocationInWindow(loc)
            val left = loc[0] - rootLoc[0]
            val top = loc[1] - rootLoc[1]
            val w = (v.width * v.scaleX).toInt()
            val h = (v.height * v.scaleY).toInt()
            ring.cornerRadius = radiusFor(v)
            ring.setBounds(left, top, left + w, top + h)
            root.invalidate()
        }

        val vto = root.viewTreeObserver
        val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { _, newFocus ->
            focused = newFocus
            refresh()
        }
        val scrollListener = ViewTreeObserver.OnScrollChangedListener { refresh() }
        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { refresh() }
        vto.addOnGlobalFocusChangeListener(focusListener)
        vto.addOnScrollChangedListener(scrollListener)
        vto.addOnGlobalLayoutListener(layoutListener)
        root.setTag(R.id.missa_window_focus_ring, true)
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                val obs = v.viewTreeObserver
                if (obs.isAlive) {
                    obs.removeOnGlobalFocusChangeListener(focusListener)
                    obs.removeOnScrollChangedListener(scrollListener)
                    obs.removeOnGlobalLayoutListener(layoutListener)
                }
                v.overlay.remove(ring)
                v.setTag(R.id.missa_window_focus_ring, null)
                v.removeOnAttachStateChangeListener(this)
            }
        })
        refresh()
    }

    private fun radiusFor(v: View): Float = when (v) {
        is MaterialButton -> v.cornerRadius.toFloat()
        is CardView -> v.radius
        else -> v.resources.getDimension(R.dimen.rounded_image_radius)
    }

    private fun isInsideCompose(view: View): Boolean {
        var cur: Any? = view
        while (cur is View) {
            if (cur.javaClass.name.contains("ComposeView") || cur.javaClass.name.contains("AndroidComposeView")) return true
            cur = cur.parent
        }
        return false
    }

    /** Un conteneur (liste, défilement, fenêtre entière) n'est pas une cible : pas de cadre. */
    private fun isContainer(view: View, root: View): Boolean {
        if (view is RecyclerView || view is android.widget.ScrollView || view is android.widget.AdapterView<*>) return true
        if (view is ViewGroup && root.width > 0 && root.height > 0 &&
            view.width > root.width * 0.9f && view.height > root.height * 0.6f
        ) {
            return true
        }
        return false
    }
}
