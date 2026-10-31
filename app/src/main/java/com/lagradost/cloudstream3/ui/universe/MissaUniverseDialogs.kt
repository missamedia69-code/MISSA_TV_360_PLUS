package com.lagradost.cloudstream3.ui.universe

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDialog
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.lifecycle.Observer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ui.home.HomeChildItemAdapter
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_FOCUSED
import com.lagradost.cloudstream3.ui.search.SEARCH_ACTION_SHOW_METADATA
import com.lagradost.cloudstream3.ui.search.SearchClickCallback
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import com.lagradost.cloudstream3.utils.AppContextUtils.setDefaultFocus
import com.lagradost.cloudstream3.utils.UIHelper.colorFromAttribute
import com.lagradost.cloudstream3.utils.UIHelper.toPx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * XML-backed dialogs and BottomSheets for MISSA TV 360 PLUS:
 * - FLOW-01: 3-step guided First-Launch Wizard
 * - HOME-08: Multi-source checkbox + search BottomSheet & Category overflow sheet
 * - HOME-10: Styled See-All full-grid BottomSheet with Sort chips (Recommended, A–Z, Top rated)
 * - RAD-03: Custom Radio Stream XML dialog with Test Stream, Edit & Delete
 * - TRUST-01: 4-digit Parental PIN lock/unlock dialog for Kids Universe
 */
internal object MissaUniverseDialogs {

    // --- 1. 3-Step Guided First-Launch Onboarding Wizard (FLOW-01) ---

    fun showOnboardingWizard(
        context: Context,
        onOpenExtensions: () -> Unit,
        onFinished: () -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_missa_onboarding, null, false)
        val stepBadge: TextView = view.findViewById(R.id.onboarding_step_badge)
        val skipBtn: MaterialButton = view.findViewById(R.id.onboarding_skip_btn)
        val titleView: TextView = view.findViewById(R.id.onboarding_title)
        val descView: TextView = view.findViewById(R.id.onboarding_description)
        val dataSaverSwitch: SwitchMaterial = view.findViewById(R.id.onboarding_data_saver_switch)
        val openExtensionsBtn: MaterialButton = view.findViewById(R.id.onboarding_open_extensions_btn)
        val backBtn: MaterialButton = view.findViewById(R.id.onboarding_back_btn)
        val nextBtn: MaterialButton = view.findViewById(R.id.onboarding_next_btn)

        var step = 1
        dataSaverSwitch.isChecked = MissaUiFactory.isDataSaverSettingEnabled()
        dataSaverSwitch.setOnCheckedChangeListener { _, checked ->
            MissaUiFactory.setDataSaverEnabled(checked)
        }

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .create()

        fun renderStep() {
            stepBadge.text = context.getString(R.string.missa_onboarding_step_indicator, step)
            backBtn.isVisible = step > 1
            dataSaverSwitch.isVisible = step == 2
            openExtensionsBtn.isVisible = step == 3

            when (step) {
                1 -> {
                    titleView.setText(R.string.missa_onboarding_step1_title)
                    descView.setText(R.string.missa_onboarding_step1_desc)
                    nextBtn.setText(R.string.missa_onboarding_next)
                }
                2 -> {
                    titleView.setText(R.string.missa_onboarding_step2_title)
                    descView.setText(R.string.missa_onboarding_step2_desc)
                    nextBtn.setText(R.string.missa_onboarding_next)
                }
                else -> {
                    titleView.setText(R.string.missa_onboarding_step3_title)
                    descView.setText(R.string.missa_onboarding_step3_desc)
                    nextBtn.setText(R.string.missa_onboarding_finish)
                }
            }
        }

        skipBtn.setOnClickListener {
            MissaUiFactory.markMissaOnboardingCompleted()
            dialog.dismiss()
            onFinished()
        }
        backBtn.setOnClickListener {
            if (step > 1) {
                step--
                renderStep()
            }
        }
        nextBtn.setOnClickListener {
            if (step < 3) {
                step++
                renderStep()
            } else {
                MissaUiFactory.markMissaOnboardingCompleted()
                dialog.dismiss()
                onFinished()
            }
        }
        openExtensionsBtn.setOnClickListener {
            MissaUiFactory.markMissaOnboardingCompleted()
            dialog.dismiss()
            onOpenExtensions()
        }

        renderStep()
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setDefaultFocus()
    }

    // --- 2. Multi-Source Checkbox + Search Filter Sheet (HOME-08) ---

    fun showSourceFilterSheet(
        context: Context,
        availableSources: List<String>,
        initiallySelected: Set<String>,
        onApply: (Set<String>) -> Unit
    ) {
        if (availableSources.isEmpty()) return
        val sheet = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.sheet_missa_source_filter, null, false)
        val searchInput: EditText = view.findViewById(R.id.source_sheet_search_input)
        val checkboxContainer: LinearLayout = view.findViewById(R.id.source_sheet_checkbox_container)
        val resetBtn: MaterialButton = view.findViewById(R.id.source_sheet_reset_btn)
        val applyBtn: MaterialButton = view.findViewById(R.id.source_sheet_apply_btn)

        val workingSelection = initiallySelected.toMutableSet()
        val textColor = context.colorFromAttribute(R.attr.textColor)

        fun rebuildCheckboxes(filter: String) {
            checkboxContainer.removeAllViews()
            val filtered = if (filter.isBlank()) {
                availableSources
            } else {
                availableSources.filter { it.contains(filter.trim(), ignoreCase = true) }
            }
            for (src in filtered) {
                val cb = CheckBox(context).apply {
                    text = src
                    minHeight = 48.toPx
                    setTextAppearance(R.style.TextAppearance_Missa_Body)
                    setTextColor(textColor)
                    isChecked = workingSelection.any { it.equals(src, ignoreCase = true) }
                    setOnCheckedChangeListener { _, checked ->
                        workingSelection.removeAll { it.equals(src, ignoreCase = true) }
                        if (checked) workingSelection.add(src)
                    }
                }
                checkboxContainer.addView(cb)
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                rebuildCheckboxes(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        resetBtn.setOnClickListener {
            workingSelection.clear()
            onApply(emptySet())
            sheet.dismiss()
        }
        applyBtn.setOnClickListener {
            onApply(workingSelection.toSet())
            sheet.dismiss()
        }

        rebuildCheckboxes("")
        sheet.setContentView(view)
        sheet.show()
    }

    // --- 3. Category Overflow Sheet for >8 Categories (HOME-08) ---

    fun showCategoryOverflowSheet(
        context: Context,
        categories: List<CategoryFilterChip>,
        selectedKey: String?,
        onSelectKey: (String?) -> Unit
    ) {
        val labels = arrayOf(context.getString(R.string.universe_all_categories)) +
            categories.map { it.fullTitle }.toTypedArray()
        val checked = if (selectedKey.isNullOrBlank()) 0
        else (categories.indexOfFirst { it.key == selectedKey } + 1).coerceAtLeast(0)

        AlertDialog.Builder(context)
            .setTitle(R.string.universe_more_categories)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                val chosenKey = if (which == 0) null else categories.getOrNull(which - 1)?.key
                onSelectKey(chosenKey)
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .setDefaultFocus()
    }

    // --- 4. Styled See-All Grid BottomSheet with Sort Chips (HOME-10) ---

    fun showSeeAllGridSheet(
        activity: Activity,
        row: UniverseCategoryRow,
        onCardClick: (SearchClickCallback) -> Unit
    ) {
        val isTv = MissaUiFactory.isTvLayoutMode()
        val dialog: AppCompatDialog = if (isTv) {
            AppCompatDialog(activity).apply {
                supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
                setCanceledOnTouchOutside(false)
            }
        } else {
            BottomSheetDialog(activity)
        }
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_missa_see_all, null, false)
        val titleView: TextView = view.findViewById(R.id.see_all_title)
        val closeBtn: MaterialButton = view.findViewById(R.id.see_all_close_btn)
        val sortBar: LinearLayout = view.findViewById(R.id.see_all_sort_bar)
        val gridRecycler: RecyclerView = view.findViewById(R.id.see_all_grid_recycler)

        titleView.text = "${row.title} (${row.items.size})"
        closeBtn.setOnClickListener { dialog.dismiss() }

        val minCardPx = activity.resources.getDimensionPixelSize(R.dimen.missa_search_grid_card_min_width)
        val baseSpan = (activity.resources.displayMetrics.widthPixels / minCardPx.coerceAtLeast(1)).coerceIn(2, 7)
        val spanCount = if (row.isHorizontalCards) (baseSpan - 1).coerceAtLeast(2) else baseSpan

        val sortPills = ArrayList<MaterialButton>()

        val gridLayoutManager = MissaAlignedGridLayoutManager(
            context = activity,
            spanCount = spanCount,
            trapBottomEdge = true,
            topBorderEscapeProvider = { cardView ->
                val headerTargets = ArrayList<View>(sortPills.size + 1).apply {
                    addAll(sortPills.filter { it.isShown && it.isFocusable })
                    if (closeBtn.isShown && closeBtn.isFocusable) add(closeBtn)
                }
                if (headerTargets.isEmpty()) {
                    null
                } else {
                    val tmp = IntArray(2)
                    val (fLeft, fRight) = MissaTvFocusHelper.screenHorizontalBounds(cardView, tmp)
                    val idx = MissaTvFocusHelper.pickBestAdjacentIndex(
                        candidateBounds = headerTargets.map {
                            MissaTvFocusHelper.screenHorizontalBounds(it, tmp)
                        },
                        focusedLeft = fLeft,
                        focusedRight = fRight
                    )
                    headerTargets.getOrNull(idx)
                }
            }
        )
        gridRecycler.isFocusable = false
        gridRecycler.isFocusableInTouchMode = false
        gridRecycler.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        gridRecycler.isNestedScrollingEnabled = !isTv
        gridRecycler.layoutManager = gridLayoutManager
        gridRecycler.itemAnimator = null

        fun focusFirstGridCard(): Boolean {
            val firstHolder = gridRecycler.findViewHolderForAdapterPosition(0)?.itemView
                ?: gridLayoutManager.getChildAt(0)
            if (firstHolder != null) {
                firstHolder.isFocusable = true
                firstHolder.isFocusableInTouchMode = true
                if (firstHolder.requestFocus()) return true
            }
            return false
        }

        val gridAdapter = HomeChildItemAdapter(
            id = "see_all_${row.id}".hashCode(),
            clickCallback = { cb ->
                // Never close sheet on D-Pad focus change or long-press favorite toggle
                if (cb.action == SEARCH_ACTION_FOCUSED) return@HomeChildItemAdapter
                if (cb.action == SEARCH_ACTION_SHOW_METADATA) {
                    onCardClick(cb)
                    return@HomeChildItemAdapter
                }
                // Keep See-All open only for quick live Radio zapping; clicking a Music Clip,
                // Album or Playlist dismisses See-All so the Top MP3/Clip Player & Playlist
                // Tracklist are immediately visible at position 0.
                val keepSheetOpen = cb.card is DirectRadioSearchResponse
                if (!keepSheetOpen) {
                    dialog.dismiss()
                }
                onCardClick(cb)
            }
        ).apply {
            forceFocusOutline = true
            isHorizontal = row.isHorizontalCards
        }
        gridRecycler.adapter = gridAdapter
        gridAdapter.submitList(row.items) {
            if (isTv && dialog.isShowing) {
                gridRecycler.post {
                    val currentFocus = dialog.window?.currentFocus
                    if (currentFocus == null || currentFocus === gridRecycler) {
                        if (!focusFirstGridCard()) {
                            sortPills.firstOrNull()?.requestFocus()
                        }
                    }
                }
            }
        }

        fun focusGridAlignedWith(sourceView: View): Boolean {
            val lm = gridRecycler.layoutManager ?: return false
            val tmp = IntArray(2)
            val (sLeft, sRight) = MissaTvFocusHelper.screenHorizontalBounds(sourceView, tmp)
            val topRowCandidates = ArrayList<View>()
            for (i in 0 until minOf(spanCount, lm.childCount)) {
                val child = lm.getChildAt(i) ?: continue
                if (!child.isShown) continue
                child.isFocusable = true
                child.isFocusableInTouchMode = true
                topRowCandidates.add(child)
            }
            val chosen = if (topRowCandidates.isNotEmpty()) {
                val idx = MissaTvFocusHelper.pickBestAdjacentIndex(
                    candidateBounds = topRowCandidates.map {
                        MissaTvFocusHelper.screenHorizontalBounds(it, tmp)
                    },
                    focusedLeft = sLeft,
                    focusedRight = sRight
                )
                topRowCandidates.getOrNull(idx)
            } else {
                gridRecycler.findViewHolderForAdapterPosition(0)?.itemView
            }
            return chosen?.requestFocus() == true
        }

        closeBtn.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusGridAlignedWith(v)
            } else false
        }

        var currentSort = 0
        val sortLabels = listOf(
            0 to activity.getString(R.string.missa_sort_editorial),
            1 to activity.getString(R.string.missa_sort_az),
            2 to activity.getString(R.string.missa_sort_rating)
        )

        fun applySort(mode: Int) {
            currentSort = mode
            val sorted = when (mode) {
                1 -> row.items.sortedBy { it.name.lowercase(Locale.ROOT) }
                2 -> row.items.sortedByDescending { it.score?.toDouble(10) ?: 0.0 }
                else -> row.items
            }
            gridAdapter.submitIncomparableList(sorted)
            for (i in 0 until sortBar.childCount) {
                val btn = sortBar.getChildAt(i) as? MaterialButton ?: continue
                MissaUiFactory.stylePillSelection(btn, i == mode)
            }
        }

        for ((mode, label) in sortLabels) {
            val pill = MissaUiFactory.createPillButton(
                context = activity,
                label = label,
                selected = mode == currentSort
            ) {
                applySort(mode)
            }.apply {
                id = View.generateViewId()
                isFocusable = true
                nextFocusUpId = id
                setOnKeyListener { v, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        focusGridAlignedWith(v)
                    } else false
                }
            }
            sortPills.add(pill)
            sortBar.addView(pill)
        }

        closeBtn.nextFocusUpId = closeBtn.id
        MissaTvFocusHelper.chainHorizontalFocusRing(
            controls = sortPills + closeBtn,
            clampStart = true,
            clampEnd = true
        )

        dialog.setContentView(view)
        if (isTv) {
            dialog.window?.apply {
                setBackgroundDrawable(
                    ColorDrawable(activity.colorFromAttribute(R.attr.primaryBlackBackground))
                )
                setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        }
        dialog.setOnShowListener {
            if (dialog is BottomSheetDialog) {
                val bottomSheet =
                    dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                if (bottomSheet != null) {
                    bottomSheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                    BottomSheetBehavior.from(bottomSheet).apply {
                        state = BottomSheetBehavior.STATE_EXPANDED
                        skipCollapsed = true
                    }
                }
            }
            if (isTv) {
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                gridRecycler.post {
                    if (!focusFirstGridCard()) {
                        sortPills.firstOrNull()?.requestFocus()
                    }
                }
            }
        }
        dialog.show()
    }

    // --- 5. Custom Radio Stream XML Dialog with Test, Edit & Delete (RAD-03) ---

    fun showCustomRadioStreamDialog(
        context: Context,
        onStationsChanged: () -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_missa_radio_stream, null, false)
        val nameInput: TextInputEditText = view.findViewById(R.id.radio_dialog_name_input)
        val urlLayout: TextInputLayout = view.findViewById(R.id.radio_dialog_url_layout)
        val urlInput: TextInputEditText = view.findViewById(R.id.radio_dialog_url_input)
        val countryInput: TextInputEditText = view.findViewById(R.id.radio_dialog_country_input)
        val customListContainer: LinearLayout = view.findViewById(R.id.radio_custom_list_container)
        val testBtn: MaterialButton = view.findViewById(R.id.radio_dialog_test_btn)
        val saveBtn: MaterialButton = view.findViewById(R.id.radio_dialog_save_btn)

        var editingId: String? = null

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .setNegativeButton(R.string.cancel, null)
            .create()

        fun populateCustomList() {
            val customs = DirectRadioPlayerManager.customStations()
            customListContainer.removeAllViews()
            customListContainer.isVisible = customs.isNotEmpty()
            for (st in customs) {
                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, 4.toPx, 0, 4.toPx)
                }
                val label = TextView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    text = "${st.name} (${st.country})"
                    setTextAppearance(R.style.TextAppearance_Missa_Body)
                    isClickable = true
                    setOnClickListener {
                        editingId = st.id
                        nameInput.setText(st.name)
                        urlInput.setText(st.streamUrl)
                        countryInput.setText(st.country)
                    }
                }
                val delBtn = MissaUiFactory.createPillButton(
                    context = context,
                    label = context.getString(R.string.universe_retro_delete),
                    selected = false,
                    marginEndDp = 0
                ) {
                    DirectRadioPlayerManager.deleteCustomStation(st.id)
                    populateCustomList()
                    onStationsChanged()
                }
                row.addView(label)
                row.addView(delBtn)
                customListContainer.addView(row)
            }
        }

        testBtn.setOnClickListener {
            val url = urlInput.text?.toString()?.trim().orEmpty()
            if (!DirectRadioPlayerManager.isValidStreamUrl(url)) {
                urlLayout.error = context.getString(R.string.universe_radio_invalid_url)
                return@setOnClickListener
            }
            urlLayout.error = null
            Toast.makeText(context, R.string.universe_radio_testing_stream, Toast.LENGTH_SHORT).show()
            val preview = RadioStationPreset(
                id = "preview_${url.hashCode()}",
                name = nameInput.text?.toString()?.trim().orEmpty()
                    .ifBlank { context.getString(R.string.universe_radio_custom_name_default) },
                genre = context.getString(R.string.universe_radio_custom_genre_default),
                country = countryInput.text?.toString()?.trim().orEmpty().ifBlank { "WEB" },
                streamUrl = url,
                isCustom = true
            )
            DirectRadioPlayerManager.playStation(context, preview)
        }

        saveBtn.setOnClickListener {
            val url = urlInput.text?.toString()?.trim().orEmpty()
            if (!DirectRadioPlayerManager.isValidStreamUrl(url)) {
                urlLayout.error = context.getString(R.string.universe_radio_invalid_url)
                return@setOnClickListener
            }
            urlLayout.error = null
            val enteredName = nameInput.text?.toString().orEmpty()
            val enteredCountry = countryInput.text?.toString().orEmpty()
            val lowerPath = url.lowercase(Locale.ROOT).substringBefore('?')
            if (editingId == null && (lowerPath.endsWith(".m3u") || lowerPath.endsWith(".pls") || lowerPath.endsWith(".m3u8"))) {
                saveBtn.isEnabled = false
                CoroutineScope(Dispatchers.Main).launch {
                    val imported = DirectRadioPlayerManager.importCustomStationOrPlaylist(
                        context = context,
                        name = enteredName,
                        streamOrPlaylistUrl = url,
                        country = enteredCountry
                    )
                    onStationsChanged()
                    imported.firstOrNull()?.let { first ->
                        DirectRadioPlayerManager.playStation(context, first)
                    }
                    if (imported.size > 1) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.universe_radio_m3u_imported, imported.size),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    dialog.dismiss()
                }
            } else {
                val saved = DirectRadioPlayerManager.saveCustomStation(
                    context = context,
                    name = enteredName,
                    streamUrl = url,
                    country = enteredCountry,
                    existingId = editingId
                )
                onStationsChanged()
                DirectRadioPlayerManager.playStation(context, saved)
                dialog.dismiss()
            }
        }

        populateCustomList()
        dialog.show()
        dialog.setDefaultFocus()
    }

    // --- 6. Parental 4-Digit PIN Lock / Unlock Dialog (TRUST-01) ---

    fun showKidsPinDialog(
        context: Context,
        unlockOnly: Boolean,
        onUnlockedOrUpdated: (unlocked: Boolean) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_missa_kids_pin, null, false)
        val descView: TextView = view.findViewById(R.id.kids_pin_dialog_desc)
        val inputLayout: TextInputLayout = view.findViewById(R.id.kids_pin_input_layout)
        val pinInput: TextInputEditText = view.findViewById(R.id.kids_pin_input)

        val currentlyLocked = MissaUiFactory.isKidsModeLocked()
        val requiringUnlock = unlockOnly || currentlyLocked
        descView.setText(
            if (requiringUnlock) R.string.universe_kids_pin_unlock_desc
            else R.string.universe_kids_pin_set_desc
        )

        val builder = AlertDialog.Builder(context)
            .setView(view)
            .setNegativeButton(R.string.cancel) { _, _ ->
                if (unlockOnly) onUnlockedOrUpdated(false)
            }
            .setPositiveButton(R.string.ok, null)

        if (currentlyLocked && !unlockOnly) {
            builder.setNeutralButton(R.string.reset_btn, null)
        }

        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = pinInput.text?.toString()?.trim().orEmpty()
                if (pin.length != 4 || !pin.all { it.isDigit() }) {
                    inputLayout.error = context.getString(R.string.universe_kids_pin_invalid)
                    return@setOnClickListener
                }
                if (requiringUnlock) {
                    if (MissaUiFactory.unlockKidsModeWithPin(pin)) {
                        Toast.makeText(context, R.string.universe_kids_pin_unlocked, Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                        onUnlockedOrUpdated(true)
                    } else {
                        inputLayout.error = context.getString(R.string.universe_kids_pin_invalid)
                    }
                } else {
                    MissaUiFactory.enableKidsPinLock(pin)
                    Toast.makeText(context, R.string.universe_kids_pin_saved, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onUnlockedOrUpdated(true)
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                val pin = pinInput.text?.toString()?.trim().orEmpty()
                if (MissaUiFactory.unlockKidsModeWithPin(pin)) {
                    MissaUiFactory.clearKidsPinLock()
                    Toast.makeText(context, R.string.universe_kids_pin_unlocked, Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    onUnlockedOrUpdated(true)
                } else {
                    inputLayout.error = context.getString(R.string.universe_kids_pin_invalid)
                }
            }
        }
        dialog.show()
        dialog.setDefaultFocus()
    }

    // --- 7. Source Switcher Sheet for Multi-Source Cards (HOME-12) ---

    fun showPreferredSourceChooser(
        context: Context,
        primary: SearchResponse,
        sources: List<SearchResponse>,
        onPickSource: (SearchResponse, directPlay: Boolean) -> Unit
    ) {
        val labels = sources.map { src -> "${src.apiName}" }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(context.getString(R.string.missa_choose_source_for_title, primary.name))
            .setItems(labels) { _, index ->
                val chosen = sources.getOrNull(index) ?: primary
                onPickSource(chosen, false)
            }
            .setNeutralButton(R.string.universe_hero_watch) { _, _ ->
                onPickSource(primary, true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
            .setDefaultFocus()
    }

    // --- 8. 1st-Launch & On-Demand Musical Style Selector Dialog (YouTube Music Engine) ---

    private var activeMusicStyleDialog: AlertDialog? = null

    fun showMusicStyleOnboardingDialog(
        context: Context,
        isInitialOnboarding: Boolean = false,
        onStylesSaved: (() -> Unit)? = null
    ) {
        if (activeMusicStyleDialog?.isShowing == true) return
        val activity = context as? Activity
        if (activity != null && (activity.isFinishing || activity.isDestroyed)) return

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_missa_music_styles, null, false)

        val titleView: TextView = view.findViewById(R.id.missa_music_styles_title)
        val countView: TextView = view.findViewById(R.id.missa_music_styles_count)
        val selectAllBtn: MaterialButton = view.findViewById(R.id.missa_music_styles_select_all_btn)
        val clearBtn: MaterialButton = view.findViewById(R.id.missa_music_styles_clear_btn)
        val grid: GridLayout = view.findViewById(R.id.missa_music_styles_grid)
        val laterBtn: MaterialButton = view.findViewById(R.id.missa_music_styles_later_btn)
        val confirmBtn: MaterialButton = view.findViewById(R.id.missa_music_styles_confirm_btn)

        titleView.setText(
            if (isInitialOnboarding) {
                R.string.universe_music_styles_onboarding_title
            } else {
                R.string.universe_music_styles_manage_title
            }
        )

        val isWideLayout = isLayout(TV or EMULATOR) ||
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val columns = if (isWideLayout) 3 else 2
        grid.columnCount = columns

        val accentColor = context.colorFromAttribute(R.attr.missaAccentPrimary)
        val primaryTextColor = context.colorFromAttribute(R.attr.textColor)
        val subtleStrokeColor = context.colorFromAttribute(R.attr.missaStrokeSubtle)
        val surfaceColor = context.colorFromAttribute(R.attr.missaSurfaceCard)
        val selectedSurfaceColor = ColorUtils.blendARGB(surfaceColor, accentColor, 0.20f)

        val selectedIds = LinkedHashSet<String>().apply {
            addAll(DirectMusicPlayerManager.selectedMusicStyleIds())
        }

        val allStyles = MissaMusicProviders.MUSIC_STYLE_OPTIONS
        val styleButtons = ArrayList<Pair<MissaMusicProviders.MusicStyleOption, MaterialButton>>(allStyles.size)

        fun refreshButtonsUi() {
            countView.text = context.getString(R.string.universe_music_styles_selected_count, selectedIds.size)
            for ((style, btn) in styleButtons) {
                val checked = selectedIds.contains(style.id)
                val baseLabel = context.getString(style.labelRes)
                btn.text = if (checked) "✓  $baseLabel" else baseLabel
                btn.strokeColor = ColorStateList.valueOf(if (checked) accentColor else subtleStrokeColor)
                btn.strokeWidth = if (checked) 2.toPx else 1.toPx
                btn.backgroundTintList = ColorStateList.valueOf(if (checked) selectedSurfaceColor else surfaceColor)
                btn.setTextColor(if (checked) accentColor else primaryTextColor)
            }
        }

        allStyles.forEachIndexed { index, style ->
            val btn = inflater.inflate(R.layout.item_missa_music_style_choice, grid, false) as MaterialButton
            btn.id = View.generateViewId()
            val row = index / columns
            val col = index % columns
            val lp = GridLayout.LayoutParams(
                GridLayout.spec(row, 1),
                GridLayout.spec(col, 1, 1f)
            ).apply {
                width = 0
                val halfGap = 5.toPx
                setMargins(
                    if (col == 0) 0 else halfGap,
                    if (row == 0) 0 else halfGap,
                    if (col == columns - 1) 0 else halfGap,
                    halfGap
                )
            }
            btn.layoutParams = lp
            btn.setOnClickListener {
                if (selectedIds.contains(style.id)) {
                    selectedIds.remove(style.id)
                } else {
                    selectedIds.add(style.id)
                }
                refreshButtonsUi()
            }
            grid.addView(btn)
            styleButtons.add(style to btn)
        }

        // Strict adjacent-cell D-Pad focus wiring inside the style grid
        val total = styleButtons.size
        styleButtons.forEachIndexed { index, (_, btn) ->
            val col = index % columns
            btn.nextFocusLeftId = if (col > 0) styleButtons[index - 1].second.id else btn.id
            btn.nextFocusRightId = if (col < columns - 1 && index + 1 < total) {
                styleButtons[index + 1].second.id
            } else {
                btn.id
            }
            val upIdx = index - columns
            btn.nextFocusUpId = if (upIdx >= 0) styleButtons[upIdx].second.id else selectAllBtn.id
            val downIdx = index + columns
            btn.nextFocusDownId = when {
                downIdx < total -> styleButtons[downIdx].second.id
                col == 0 -> laterBtn.id
                else -> confirmBtn.id
            }
        }

        selectAllBtn.setOnClickListener {
            allStyles.forEach { selectedIds.add(it.id) }
            refreshButtonsUi()
        }

        clearBtn.setOnClickListener {
            selectedIds.clear()
            refreshButtonsUi()
        }

        refreshButtonsUi()

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .create()
        activeMusicStyleDialog = dialog
        dialog.setOnDismissListener {
            if (activeMusicStyleDialog === dialog) {
                activeMusicStyleDialog = null
            }
            DirectMusicPlayerManager.hasPromptedOnboardingThisSession = true
        }

        laterBtn.setOnClickListener {
            DirectMusicPlayerManager.hasPromptedOnboardingThisSession = true
            dialog.dismiss()
        }

        confirmBtn.setOnClickListener {
            if (selectedIds.isEmpty()) {
                Toast.makeText(
                    context,
                    R.string.universe_music_styles_empty_warning,
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            val chosen = selectedIds.toList()
            DirectMusicPlayerManager.saveSelectedMusicStyles(chosen, triggerReload = true)
            Toast.makeText(
                context,
                context.getString(R.string.universe_music_styles_saved_toast, chosen.size),
                Toast.LENGTH_SHORT
            ).show()
            onStylesSaved?.invoke()
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.let { win ->
            win.setBackgroundDrawableResource(android.R.color.transparent)
            if (isWideLayout) {
                val maxWidth = (context.resources.displayMetrics.widthPixels * 0.86f).toInt()
                win.setLayout(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        val firstStyleButton = styleButtons.firstOrNull()?.second
        if (firstStyleButton != null) {
            firstStyleButton.post { firstStyleButton.requestFocus() }
        } else {
            dialog.setDefaultFocus()
        }
    }

    // --- 7. Fullscreen Official Video Clip Monitor for the Sophisticated MP3 Player ---

    @Volatile
    var isMusicFullscreenClipDialogOpen: Boolean = false
        private set

    fun showFullscreenMusicClipDialog(
        activity: Activity,
        deckPlayerView: PlayerView,
        onDismissed: () -> Unit
    ) {
        val exo = DirectMusicPlayerManager.peekActiveExoPlayer() ?: return
        val dialog = AppCompatDialog(activity).apply {
            supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
        }
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.dialog_missa_music_fullscreen_clip, null, false)

        val fullscreenPlayerView: PlayerView = view.findViewById(R.id.fullscreen_clip_player_view)
        val titleView: TextView = view.findViewById(R.id.fullscreen_clip_title)
        val subtitleView: TextView = view.findViewById(R.id.fullscreen_clip_subtitle)
        val prevBtn: MaterialButton = view.findViewById(R.id.fullscreen_clip_prev_btn)
        val playBtn: MaterialButton = view.findViewById(R.id.fullscreen_clip_play_btn)
        val nextBtn: MaterialButton = view.findViewById(R.id.fullscreen_clip_next_btn)
        val closeBtn: MaterialButton = view.findViewById(R.id.fullscreen_clip_close_btn)

        MissaTvFocusHelper.chainHorizontalFocusRing(
            controls = listOf(prevBtn, playBtn, nextBtn, closeBtn),
            clampStart = true,
            clampEnd = true
        )

        val stateObserver = Observer<MusicPlaybackState> { st ->
            val tr = st.track
            if (tr == null) {
                dialog.dismiss()
                return@Observer
            }
            titleView.text = tr.title
            subtitleView.text = listOf(tr.artist, tr.album, tr.genre)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(" • ")
            playBtn.setIconResource(
                if (st.isPlaying) R.drawable.ic_baseline_pause_24
                else R.drawable.ic_baseline_play_arrow_24
            )
            val currentExo = DirectMusicPlayerManager.peekActiveExoPlayer()
            if (currentExo != null && fullscreenPlayerView.player !== currentExo) {
                fullscreenPlayerView.player = currentExo
            }
        }

        prevBtn.setOnClickListener { DirectMusicPlayerManager.previousTrack(activity) }
        playBtn.setOnClickListener { DirectMusicPlayerManager.togglePlayPause(activity) }
        nextBtn.setOnClickListener { DirectMusicPlayerManager.nextTrack(activity) }
        closeBtn.setOnClickListener { dialog.dismiss() }

        isMusicFullscreenClipDialogOpen = true
        deckPlayerView.player = null
        fullscreenPlayerView.player = exo
        DirectMusicPlayerManager.state.observeForever(stateObserver)

        dialog.setOnDismissListener {
            DirectMusicPlayerManager.state.removeObserver(stateObserver)
            fullscreenPlayerView.player = null
            isMusicFullscreenClipDialogOpen = false
            val currentExo = DirectMusicPlayerManager.peekActiveExoPlayer()
            if (currentExo != null && DirectMusicPlayerManager.state.value?.videoClipModeEnabled == true) {
                deckPlayerView.player = currentExo
            }
            onDismissed()
        }

        dialog.setContentView(view)
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        playBtn.post { playBtn.requestFocus() }
    }
}
