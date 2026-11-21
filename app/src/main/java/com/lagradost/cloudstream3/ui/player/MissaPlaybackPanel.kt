package com.lagradost.cloudstream3.ui.player

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.lagradost.cloudstream3.R

/**
 * « Langue et qualité » : un seul panneau, simple, pour tout ce qui se règle pendant une
 * vidéo. Chaque rubrique (Langue, Piste audio, Sous-titres, Qualité, Serveur) est une rangée
 * de pastilles : on touche (ou on valide à la télécommande) et c'est appliqué tout de suite,
 * sans bouton « Appliquer ». Une rubrique sans choix possible n'est pas affichée.
 *
 * Le panneau ne connaît pas le lecteur : [GeneratorPlayer] lui donne les rubriques et
 * l'action de chaque pastille. Après un choix, [Content] est recalculé et le panneau
 * redessiné, sauf si l'action demande de le fermer (changement de source).
 */
class MissaPlaybackPanel(private val context: Context, private val isTv: Boolean) {

    data class Option(
        val label: String,
        val selected: Boolean,
        /** Vrai : fermer le panneau après le choix (la vidéo est rechargée). */
        val closes: Boolean = false,
        val onPick: () -> Unit
    )

    data class Section(val title: String, val hint: String?, val options: List<Option>)

    data class Action(val label: String, val onClick: () -> Unit)

    data class Content(val sections: List<Section>, val actions: List<Action>)

    private var dialog: Dialog? = null
    private lateinit var body: LinearLayout
    private lateinit var build: () -> Content

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    fun show(content: () -> Content, onDismiss: () -> Unit) {
        build = content
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(18f), dp(20f), dp(14f))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F2121214"))
                cornerRadius = dp(22f).toFloat()
            }
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(context).apply {
            text = context.getString(R.string.missa_playback_panel_title)
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isTv) 22f else 19f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = context.getString(R.string.missa_playback_close)
            setTextColor(Color.WHITE)
            strokeColor = ColorStateList.valueOf(Color.parseColor("#66FFFFFF"))
            setOnClickListener { dialog?.dismiss() }
        })
        root.addView(header)

        body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val d = Dialog(context, R.style.AlertDialogCustomTransparent)
        d.setContentView(root)
        d.setOnDismissListener {
            dialog = null
            onDismiss()
        }
        d.window?.let { w ->
            val metrics = context.resources.displayMetrics
            val landscape = metrics.widthPixels > metrics.heightPixels
            val width = when {
                isTv -> (metrics.widthPixels * 0.62f).toInt()
                landscape -> (metrics.widthPixels * 0.7f).toInt()
                else -> ViewGroup.LayoutParams.MATCH_PARENT
            }
            w.setLayout(width, (metrics.heightPixels * if (landscape) 0.9f else 0.75f).toInt())
            w.setGravity(if (isTv || landscape) Gravity.CENTER else Gravity.BOTTOM)
            w.setBackgroundDrawableResource(android.R.color.transparent)
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(0.35f)
        }
        dialog = d
        render()
        d.show()
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    private fun render() {
        body.removeAllViews()
        val content = build()
        var firstSelected: View? = null
        var firstOption: View? = null

        if (content.sections.isEmpty()) {
            body.addView(TextView(context).apply {
                text = context.getString(R.string.missa_playback_nothing_to_choose)
                setTextColor(Color.parseColor("#B3FFFFFF"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, dp(16f), 0, dp(8f))
            })
        }

        for (section in content.sections) {
            body.addView(TextView(context).apply {
                text = section.title
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isTv) 17f else 16f)
                setPadding(0, dp(18f), 0, dp(2f))
            })
            section.hint?.let { hint ->
                body.addView(TextView(context).apply {
                    text = hint
                    setTextColor(Color.parseColor("#99FFFFFF"))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, 0, 0, dp(4f))
                })
            }
            val group = ChipGroup(context).apply {
                chipSpacingHorizontal = dp(8f)
                chipSpacingVertical = dp(4f)
            }
            for (option in section.options) {
                val chip = makeChip(option)
                group.addView(chip)
                if (firstOption == null) firstOption = chip
                if (option.selected && firstSelected == null) firstSelected = chip
            }
            body.addView(group)
        }

        if (content.actions.isNotEmpty()) {
            body.addView(View(context).apply {
                setBackgroundColor(Color.parseColor("#22FFFFFF"))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f)).apply {
                topMargin = dp(18f)
                bottomMargin = dp(6f)
            })
            for (action in content.actions) {
                body.addView(MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                    text = action.label
                    isAllCaps = false
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                    setTextColor(ContextCompat.getColor(context, R.color.colorPrimaryGreenApple))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    setOnClickListener {
                        dialog?.dismiss()
                        action.onClick()
                    }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            }
        }

        // Télécommande : le curseur part du choix en cours (ou du premier).
        (firstSelected ?: firstOption)?.post { (firstSelected ?: firstOption)?.requestFocus() }
    }

    private fun makeChip(option: Option): Chip {
        val green = ContextCompat.getColor(context, R.color.colorPrimaryGreenApple)
        val checked = intArrayOf(android.R.attr.state_checked)
        val focused = intArrayOf(android.R.attr.state_focused)
        return Chip(context).apply {
            text = option.label
            isCheckable = true
            isChecked = option.selected
            isCheckedIconVisible = option.selected
            checkedIcon = ContextCompat.getDrawable(context, R.drawable.ic_baseline_check_24)
            checkedIconTint = ColorStateList.valueOf(Color.BLACK)
            isFocusable = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isTv) 16f else 15f)
            chipMinHeight = dp(if (isTv) 44f else 40f).toFloat()
            chipBackgroundColor = ColorStateList(
                arrayOf(checked, focused, intArrayOf()),
                intArrayOf(green, Color.parseColor("#55FFFFFF"), Color.parseColor("#26FFFFFF"))
            )
            setTextColor(
                ColorStateList(
                    arrayOf(checked, intArrayOf()),
                    intArrayOf(Color.BLACK, Color.WHITE)
                )
            )
            chipStrokeWidth = dp(2f).toFloat()
            chipStrokeColor = ColorStateList(
                arrayOf(focused, intArrayOf()),
                intArrayOf(Color.WHITE, Color.TRANSPARENT)
            )
            setOnClickListener {
                // La pastille ne se décoche pas toute seule : l'état vient du lecteur.
                isChecked = option.selected
                if (option.selected) return@setOnClickListener
                option.onPick()
                if (option.closes) dialog?.dismiss() else render()
            }
        }
    }
}
