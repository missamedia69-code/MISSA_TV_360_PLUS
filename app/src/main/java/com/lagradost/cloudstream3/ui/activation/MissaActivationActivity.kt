package com.lagradost.cloudstream3.ui.activation

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.ui.settings.Globals.EMULATOR
import com.lagradost.cloudstream3.ui.settings.Globals.TV
import com.lagradost.cloudstream3.ui.settings.Globals.isLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import qrcode.QRCode

/**
 * Écran de blocage : fin de l'essai de 24 h, abonnement expiré, ou trop longtemps sans
 * vérification. Sur TV, un QR code mène à la page de paiement (à ouvrir sur le téléphone) ;
 * sur téléphone et tablette, un bouton l'ouvre directement. L'écran vérifie l'état toutes
 * les 5 s et se ferme tout seul dès que le paiement est confirmé.
 */
class MissaActivationActivity : AppCompatActivity() {

    companion object {
        @Volatile
        var isShowing = false
            private set

        fun open(activity: android.app.Activity) {
            if (isShowing) return
            isShowing = true
            activity.startActivity(
                Intent(activity, MissaActivationActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    private lateinit var message: TextView
    private lateinit var status: TextView
    private lateinit var code: TextView
    private lateinit var qr: ImageView
    private lateinit var qrHint: TextView
    private lateinit var pay: MaterialButton
    private var qrFor: String? = null
    private val isTv by lazy { isLayout(TV or EMULATOR) }

    override fun onCreate(savedInstanceState: Bundle?) {
        CommonActivity.loadThemes(this)
        super.onCreate(savedInstanceState)
        isShowing = true
        setContentView(R.layout.activity_missa_activation)
        message = findViewById(R.id.activation_message)
        status = findViewById(R.id.activation_status)
        code = findViewById(R.id.activation_code)
        qr = findViewById(R.id.activation_qr)
        qrHint = findViewById(R.id.activation_qr_hint)
        pay = findViewById(R.id.activation_pay)
        val check: MaterialButton = findViewById(R.id.activation_check)
        val quit: MaterialButton = findViewById(R.id.activation_quit)

        pay.setOnClickListener { openPayPage() }
        check.setOnClickListener {
            status.setText(R.string.missa_activation_checking)
            lifecycleScope.launch { checkNow(userAsked = true) }
        }
        quit.setOnClickListener { finishAffinity() }
        // Retour ne contourne pas le blocage : il ferme l'application.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishAffinity()
        })

        render()
        (if (isTv) check else pay).requestFocus()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    checkNow(userAsked = false)
                    delay(5_000)
                }
            }
        }
    }

    override fun onDestroy() {
        isShowing = false
        super.onDestroy()
    }

    private suspend fun checkNow(userAsked: Boolean) {
        val ok = MissaLicence.refresh(this)
        if (!MissaLicence.verdict(this).blocked) {
            Toast.makeText(this, R.string.missa_activation_unlocked, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        render()
        status.setText(
            when {
                !ok -> R.string.missa_activation_no_network
                userAsked -> R.string.missa_activation_not_yet
                else -> R.string.missa_activation_waiting
            }
        )
    }

    private fun render() {
        val snap = MissaLicence.snapshot(this)
        val verdict = MissaLicence.verdict(this)
        message.setText(
            when {
                verdict == MissaLicence.Verdict.BLOCKED_OFFLINE -> R.string.missa_activation_offline
                (snap?.expiresAt ?: 0L) > 0L -> R.string.missa_activation_expired
                else -> R.string.missa_activation_trial_over
            }
        )
        code.text = snap?.code.orEmpty()
        val url = snap?.payUrl?.takeIf { it.startsWith("https://") }
        pay.isVisible = !isTv && url != null
        qrHint.isVisible = isTv
        qrHint.text = getString(R.string.missa_activation_scan, MissaLicence.SITE_ACTIVATE)
        if (isTv && url != null && url != qrFor) {
            try {
                val bmp = QRCode.ofSquares()
                    .withColor(Color.BLACK)
                    .withBackgroundColor(Color.WHITE)
                    .build(url)
                    .render()
                    .nativeImage() as Bitmap
                qr.setImageBitmap(bmp)
                qrFor = url
            } catch (e: Throwable) {
                logError(e)
            }
        }
        qr.isVisible = isTv && qrFor != null
    }

    private fun openPayPage() {
        val url = MissaLicence.snapshot(this)?.payUrl?.takeIf { it.startsWith("https://") } ?: return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: Exception) {
            logError(e)
            Toast.makeText(this, getString(R.string.missa_activation_scan, MissaLicence.SITE_ACTIVATE), Toast.LENGTH_LONG).show()
        }
    }
}
