package com.lagradost.cloudstream3.ui.settings.extensions

import android.os.Bundle
import android.view.View
import com.lagradost.cloudstream3.utils.BaseComposeFragment
import com.lagradost.cloudstream4.compose.Screen

class ExtensionsFragment2 : BaseComposeFragment(), Screen by ExtensionsScreen {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Réglages des extensions réservés au responsable de MISSA TV.
        MissaAdminLock.guard(this)
    }
}
