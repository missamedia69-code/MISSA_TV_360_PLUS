package com.lagradost.cloudstream3.ui.activation

import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream3.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Branché sur l'écran principal : tant qu'il est au premier plan, vérifie chaque minute
 * (localement) si l'accès est encore ouvert, redemande l'état au serveur de temps en temps,
 * et ouvre l'écran de blocage quand il le faut.
 */
object MissaLicenceGate {
    private var loop: Job? = null

    fun onResume(activity: AppCompatActivity) {
        loop?.cancel()
        if (MissaLicence.verdict(activity).blocked) {
            MissaActivationActivity.open(activity)
            return
        }
        loop = activity.lifecycleScope.launch {
            while (isActive) {
                if (MissaLicence.shouldRefresh(activity)) MissaLicence.refresh(activity)
                val verdict = MissaLicence.verdict(activity)
                if (verdict.blocked) {
                    MissaActivationActivity.open(activity)
                    break
                }
                if (verdict == MissaLicence.Verdict.GRACE && MissaLicence.takeGraceReminder(activity)) {
                    Toast.makeText(activity, R.string.missa_activation_grace, Toast.LENGTH_LONG).show()
                }
                delay(60_000)
            }
        }
    }

    fun onPause() {
        loop?.cancel()
        loop = null
    }
}
