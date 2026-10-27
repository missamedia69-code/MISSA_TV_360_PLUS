package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.toPx

/**
 * Shared UI component, layout detection, motion, accessibility and preference helpers for
 * MISSA TV 360 PLUS (NAV-03, THEME-02, THEME-04, THEME-06, THEME-08, A11Y-01, TRUST-01, FLOW-03).
 *
 * Uses strictly semantic theme attributes (?attr/missa*) — zero hardcoded #RRGGBB colors.
 */
object MissaUiFactory {
    private const val PREF_DATA_SAVER = "missa_data_saver_enabled_v1"
    private const val PREF_ONBOARDING_DONE = "missa_onboarding_completed_v1"
    private const val PREF_KIDS_PIN = "missa_kids_parental_pin_v1"
    private const val PREF_KIDS_LOCKED = "missa_kids_mode_locked_v1"

    /** Unified TV layout check across the entire application (NAV-03). */
    fun isTvLayoutMode(): Boolean = isLayout(TV or EMULATOR)

    /** True on tablets / foldables (>= 600dp smallest width) that are NOT Android TV (NAV-03). */
    fun isTabletLayoutMode(context: Context): Boolean =
        !isTvLayoutMode() && context.resources.configuration.smallestScreenWidthDp >= 600

    /** Respects system accessibility reduced-motion setting (THEME-08, HOME-03). */
    fun areAnimationsEnabled(context: Context): Boolean {
        val scale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f)
        return scale > 0.01f
    }

    /** True when user enabled MISSA Data Saver or OS Data Saver is active on metered network (FLOW-03). */
    fun isDataSaverActive(context: Context): Boolean {
        if (getKey<Boolean>(PREF_DATA_SAVER) == true) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null && cm.isActiveNetworkMetered &&
                cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
            ) {
                return true
            }
        }
        return false
    }

    fun setDataSaverEnabled(enabled: Boolean) {
        setKey(PREF_DATA_SAVER, enabled)
    }

    fun isDataSaverSettingEnabled(): Boolean = getKey<Boolean>(PREF_DATA_SAVER) == true

    fun hasCompletedMissaOnboarding(): Boolean = getKey<Boolean>(PREF_ONBOARDING_DONE) == true

    fun markMissaOnboardingCompleted() {
        setKey(PREF_ONBOARDING_DONE, true)
    }

    // --- Parental PIN lock for Kids Universe (TRUST-01) ---

    fun getKidsPin(): String? = getKey<String>(PREF_KIDS_PIN)?.takeIf { it.length == 4 }

    fun isKidsModeLocked(): Boolean =
        getKidsPin() != null && (getKey<Boolean>(PREF_KIDS_LOCKED) == true)

    fun enableKidsPinLock(pin: String) {
        setKey(PREF_KIDS_PIN, pin)
        setKey(PREF_KIDS_LOCKED, true)
    }

    fun unlockKidsModeWithPin(inputPin: String): Boolean {
        val saved = getKidsPin() ?: return true
        return if (saved == inputPin.trim()) {
            setKey(PREF_KIDS_LOCKED, false)
            true
        } else {
            false
        }
    }

    fun clearKidsPinLock() {
        removeKey(PREF_KIDS_PIN)
        removeKey(PREF_KIDS_LOCKED)
    }

    /**
     * Creates a standardized MISSA pill button backed by @style/Widget.Missa.Pill
     * with a guaranteed 48dp touch height, optional leading icon, and semantic colors.
     */
    fun createPillButton(
        context: Context,
        label: String,
        selected: Boolean,
        @DrawableRes iconRes: Int = 0,
        marginEndDp: Int = 8,
        onClick: () -> Unit
    ): MaterialButton {
        val themedCtx = ContextThemeWrapper(context, R.style.Widget_Missa_Pill)
        return MaterialButton(themedCtx, null, 0).apply {
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = marginEndDp.toPx
            }
            minHeight = context.resources.getDimensionPixelSize(R.dimen.missa_pill_touch_height)
            minimumHeight = minHeight
            minWidth = 48.toPx
            insetTop = context.resources.getDimensionPixelSize(R.dimen.missa_pill_inset_vertical)
            insetBottom = context.resources.getDimensionPixelSize(R.dimen.missa_pill_inset_vertical)
            val hPad = context.resources.getDimensionPixelSize(R.dimen.missa_pill_horizontal_padding)
            setPadding(hPad, 0, hPad, 0)
            cornerRadius = 999.toPx
            isAllCaps = false
            maxLines = 1
            text = label
            setTextAppearance(R.style.TextAppearance_Missa_Button)
            if (iconRes != 0) {
                icon = ContextCompat.getDrawable(context, iconRes)
                iconSize = 16.toPx
                iconPadding = 6.toPx
            } else {
                icon = null
            }
            isFocusable = true
            isClickable = true
            stylePillSelection(this, selected)
            attachInteractiveMotion(this)
            setOnClickListener { onClick() }
        }
    }

    /**
     * Applies unified active/inactive semantic pill colors without recreating the View,
     * keeping RecyclerView focus stable and TalkBack selection state accurate.
     */
    fun stylePillSelection(button: MaterialButton, selected: Boolean, isFocusedOverride: Boolean = button.isFocused) {
        val ctx = button.context
        val accentPrimary = ctx.colorFromAttribute(R.attr.missaAccentPrimary)
        val onAccentPrimary = ctx.colorFromAttribute(R.attr.missaOnAccentPrimary)
        val surfaceElevated = ctx.colorFromAttribute(R.attr.missaSurfaceElevated)
        val surfaceFocused = ctx.colorFromAttribute(R.attr.missaSurfaceFocused)
        val strokeSubtle = ctx.colorFromAttribute(R.attr.missaStrokeSubtle)
        val primaryText = ctx.colorFromAttribute(R.attr.textColor)
        val ripple = ctx.colorFromAttribute(R.attr.missaRippleColor)

        button.isSelected = selected
        button.rippleColor = ColorStateList.valueOf(ripple)

        if (selected) {
            button.backgroundTintList = ColorStateList.valueOf(accentPrimary)
            button.setTextColor(onAccentPrimary)
            button.iconTint = ColorStateList.valueOf(onAccentPrimary)
            button.strokeWidth = if (isFocusedOverride) 3.toPx else 0
            button.strokeColor = ColorStateList.valueOf(primaryText)
        } else {
            button.backgroundTintList = ColorStateList.valueOf(
                if (isFocusedOverride) surfaceFocused else surfaceElevated
            )
            button.setTextColor(primaryText)
            button.iconTint = ColorStateList.valueOf(primaryText)
            button.strokeWidth = if (isFocusedOverride) 3.toPx else 1.toPx
            button.strokeColor = ColorStateList.valueOf(
                if (isFocusedOverride) accentPrimary else strokeSubtle
            )
        }

        ViewCompat.setStateDescription(
            button,
            if (selected) ctx.getString(R.string.selected) else null
        )
    }

    /**
     * Creates a subtle vertical divider between universe family groups in the top navigation strip (NAV-01).
     */
    fun createStripFamilyDivider(context: Context): View {
        return View(context).apply {
            layoutParams = ViewGroup.MarginLayoutParams(1.toPx, 20.toPx).apply {
                marginStart = 4.toPx
                marginEnd = 10.toPx
            }
            setBackgroundColor(context.colorFromAttribute(R.attr.missaStrokeSubtle))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isFocusable = false
        }
    }

    /**
     * Attaches subtle tactile press feedback (0.97x) and unified TV focus scale (1.05x)
     * while honoring system reduced-motion settings (THEME-04 & THEME-08).
     */
    fun attachInteractiveMotion(view: View, focusScale: Float = 1.05f) {
        view.setOnTouchListener { v, event ->
            if (areAnimationsEnabled(v.context)) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90L).start()
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val target = if (v.isFocused && isTvLayoutMode()) focusScale else 1f
                        v.animate().scaleX(target).scaleY(target).setDuration(120L).start()
                    }
                }
            }
            false
        }
        view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (v is MaterialButton && v.tag == "missa_pill") {
                stylePillSelection(v, v.isSelected, hasFocus)
            }
            if (isTvLayoutMode() && areAnimationsEnabled(v.context)) {
                val target = if (hasFocus) focusScale else 1f
                v.animate().scaleX(target).scaleY(target).setDuration(140L).start()
            } else if (!hasFocus) {
                v.scaleX = 1f
                v.scaleY = 1f
            }
        }
    }
}
