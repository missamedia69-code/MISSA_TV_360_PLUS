package com.lagradost.cloudstream3.ui.universe

import android.app.Activity
import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
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
        val sheet = BottomSheetDialog(activity)
        if (isTv) {
            sheet.setCanceledOnTouchOutside(false)
        }
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_missa_see_all, null, false)
        val titleView: TextView = view.findViewById(R.id.see_all_title)
        val closeBtn: MaterialButton = view.findViewById(R.id.see_all_close_btn)
        val sortBar: LinearLayout = view.findViewById(R.id.see_all_sort_bar)
        val gridRecycler: RecyclerView = view.findViewById(R.id.see_all_grid_recycler)

        titleView.text = "${row.title} (${row.items.size})"
        closeBtn.setOnClickListener { sheet.dismiss() }

        val minCardPx = activity.resources.getDimensionPixelSize(R.dimen.missa_search_grid_card_min_width)
        val baseSpan = (activity.resources.displayMetrics.widthPixels / minCardPx.coerceAtLeast(1)).coerceIn(2, 7)
        val spanCount = if (row.isHorizontalCards) (baseSpan - 1).coerceAtLeast(2) else baseSpan
        gridRecycler.layoutManager = MissaAlignedGridLayoutManager(
            context = activity,
            spanCount = spanCount,
            trapBottomEdge = true
        )
        gridRecycler.itemAnimator = null

        val gridAdapter = HomeChildItemAdapter(
            id = "see_all_${row.id}".hashCode(),
            clickCallback = { cb ->
                // Never close sheet on D-Pad focus change or long-press favorite toggle
                if (cb.action == SEARCH_ACTION_FOCUSED) return@HomeChildItemAdapter
                if (cb.action == SEARCH_ACTION_SHOW_METADATA) {
                    onCardClick(cb)
                    return@HomeChildItemAdapter
                }
                val isInlineAudio = cb.card is DirectMusicSearchResponse ||
                    cb.card is DirectRadioSearchResponse
                if (!isInlineAudio) {
                    sheet.dismiss()
                }
                onCardClick(cb)
            }
        ).apply {
            isHorizontal = row.isHorizontalCards
            submitList(row.items)
        }
        gridRecycler.adapter = gridAdapter

        fun focusGridAlignedWith(sourceView: View): Boolean {
            val lm = gridRecycler.layoutManager ?: return false
            val targetX = MissaTvFocusHelper.screenCenterX(sourceView)
            val topRowCandidates = ArrayList<View>()
            for (i in 0 until minOf(spanCount, lm.childCount)) {
                lm.getChildAt(i)?.takeIf { it.isShown && it.isFocusable }?.let { topRowCandidates.add(it) }
            }
            val chosen = if (topRowCandidates.isNotEmpty()) {
                val idx = MissaTvFocusHelper.pickClosestHorizontalIndex(
                    topRowCandidates.map { MissaTvFocusHelper.screenCenterX(it) },
                    targetX
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
            }
            pill.setOnKeyListener { v, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusGridAlignedWith(v)
                } else false
            }
            sortBar.addView(pill)
        }

        sheet.setContentView(view)
        sheet.setOnShowListener {
            sheet.findViewById<View>(com.google.android.material.R.id.touch_outside)?.apply {
                if (isTv) {
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setOnClickListener(null)
                }
            }
            val bottomSheet = sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                bottomSheet.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                BottomSheetBehavior.from(bottomSheet).apply {
                    isDraggable = !isTv
                    state = BottomSheetBehavior.STATE_EXPANDED
                    skipCollapsed = true
                }
            }
            if (isTv) {
                gridRecycler.post {
                    val firstCard = gridRecycler.findViewHolderForAdapterPosition(0)?.itemView
                    if (firstCard != null) {
                        firstCard.requestFocus()
                    } else {
                        sortBar.getChildAt(0)?.requestFocus()
                    }
                }
            }
        }
        sheet.show()
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

    // --- 8. Dedicated Music Server (Navidrome / OpenSubsonic) & Custom MP3/M3U Dialog ---

    fun showMusicServerDialog(
        context: Context,
        onTracksChanged: () -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_missa_music_server, null, false)
        val subUrlInput: EditText = view.findViewById(R.id.missa_music_subsonic_url)
        val subUserInput: EditText = view.findViewById(R.id.missa_music_subsonic_user)
        val subPassInput: EditText = view.findViewById(R.id.missa_music_subsonic_pass)
        val subConnectBtn: MaterialButton = view.findViewById(R.id.missa_music_subsonic_connect_btn)
        val subDisconnectBtn: MaterialButton = view.findViewById(R.id.missa_music_subsonic_disconnect_btn)

        val trackTitleInput: EditText = view.findViewById(R.id.missa_music_track_title)
        val trackArtistInput: EditText = view.findViewById(R.id.missa_music_track_artist)
        val trackUrlInput: EditText = view.findViewById(R.id.missa_music_track_url)
        val cancelBtn: MaterialButton = view.findViewById(R.id.missa_music_cancel_btn)
        val saveBtn: MaterialButton = view.findViewById(R.id.missa_music_save_btn)

        val existingConfig = DirectMusicPlayerManager.subsonicConfig()
        if (existingConfig != null) {
            subUrlInput.setText(existingConfig.baseUrl)
            subUserInput.setText(existingConfig.username)
            subPassInput.setText(existingConfig.password)
            subDisconnectBtn.isVisible = true
        }

        val dialog = AlertDialog.Builder(context)
            .setView(view)
            .create()

        cancelBtn.setOnClickListener { dialog.dismiss() }

        subDisconnectBtn.setOnClickListener {
            DirectMusicPlayerManager.disconnectOpenSubsonicServer()
            subDisconnectBtn.isVisible = false
            onTracksChanged()
            dialog.dismiss()
        }

        subConnectBtn.setOnClickListener {
            val config = OpenSubsonicServerConfig(
                baseUrl = subUrlInput.text?.toString()?.trim().orEmpty(),
                username = subUserInput.text?.toString()?.trim().orEmpty(),
                password = subPassInput.text?.toString().orEmpty()
            )
            if (!MissaMusicProviders.isValidAudioUrl(config.baseUrl) || !config.isConfigured()) {
                Toast.makeText(context, R.string.universe_music_subsonic_error_toast, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            subConnectBtn.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                val count = DirectMusicPlayerManager.connectOpenSubsonicServer(config)
                subConnectBtn.isEnabled = true
                if (count > 0) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.universe_music_subsonic_connected_toast, count),
                        Toast.LENGTH_SHORT
                    ).show()
                    onTracksChanged()
                    dialog.dismiss()
                } else {
                    Toast.makeText(context, R.string.universe_music_subsonic_error_toast, Toast.LENGTH_SHORT).show()
                }
            }
        }

        saveBtn.setOnClickListener {
            val url = trackUrlInput.text?.toString()?.trim().orEmpty()
            if (!MissaMusicProviders.isValidAudioUrl(url)) {
                Toast.makeText(context, R.string.universe_radio_invalid_url, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val enteredTitle = trackTitleInput.text?.toString().orEmpty()
            val enteredArtist = trackArtistInput.text?.toString().orEmpty()
            saveBtn.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                val imported = DirectMusicPlayerManager.importCustomTrackOrPlaylist(
                    context = context,
                    title = enteredTitle,
                    artist = enteredArtist,
                    url = url
                )
                onTracksChanged()
                imported.firstOrNull()?.let { first ->
                    DirectMusicPlayerManager.playTrack(context, first, imported.takeIf { it.size > 1 })
                }
                if (imported.size > 1) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.universe_music_m3u_imported, imported.size),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                dialog.dismiss()
            }
        }

        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setDefaultFocus()
    }
}
