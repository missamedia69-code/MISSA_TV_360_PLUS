package com.lagradost.cloudstream3.ui.activation

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
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
import java.text.NumberFormat
import java.util.Locale

/**
 * Écran de blocage : fin de l'essai de 24 h, abonnement expiré, ou trop longtemps sans
 * vérification. Il propose les formules (1 mois, 3 mois, 6 mois, 1 an) :
 *  - téléphone / tablette : toucher une formule ouvre directement la page de paiement ;
 *  - TV : la formule sélectionnée s'affiche en QR code ; scanné avec le téléphone, il ouvre le
 *    paiement de CETTE TV (son code est dans l'adresse) avec la formule déjà choisie.
 * Un abonnement couvre 2 appareils : « J'ai déjà un abonnement » rattache cet appareil à celui
 * d'un autre appareil en saisissant le code de l'appareil qui a payé.
 * L'écran vérifie l'état toutes les 5 s et se ferme tout seul dès que l'accès est ouvert.
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
    private lateinit var plansBox: LinearLayout
    private val planButtons = LinkedHashMap<String, MaterialButton>()
    private var selectedPlan = "m1"
    private var qrFor: String? = null
    private var paying = false
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
        plansBox = findViewById(R.id.activation_plans)
        val check: MaterialButton = findViewById(R.id.activation_check)
        val quit: MaterialButton = findViewById(R.id.activation_quit)
        val link: MaterialButton = findViewById(R.id.activation_link)

        check.setOnClickListener {
            status.setText(R.string.missa_activation_checking)
            lifecycleScope.launch { checkNow(userAsked = true) }
        }
        link.setOnClickListener { askOwnerCode() }
        quit.setOnClickListener { finishAffinity() }
        findViewById<MaterialButton>(R.id.activation_help).setOnClickListener { MissaSupport.show(this) }
        // Retour ne contourne pas le blocage : il ferme l'application.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishAffinity()
        })

        buildPlans()
        render()
        planButtons[selectedPlan]?.requestFocus()

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

    // ------------------------------------------------------------------ Formules

    private fun price(plan: MissaLicence.Plan): String {
        val n = NumberFormat.getIntegerInstance(Locale.FRANCE).format(plan.amount)
        return if (plan.currency == "XAF") "$n FCFA" else "$n ${plan.currency}"
    }

    private fun planLabel(plan: MissaLicence.Plan): String = when (plan.id) {
        "year" -> getString(R.string.missa_activation_plan_year)
        "m1" -> getString(R.string.missa_activation_plan_month)
        else -> getString(R.string.missa_activation_plan_months, plan.id.removePrefix("m").toIntOrNull() ?: 1)
    }

    private fun buildPlans() {
        plansBox.removeAllViews()
        planButtons.clear()
        val plans = MissaLicence.plans(this)
        if (plans.none { it.id == selectedPlan }) selectedPlan = plans.first().id
        val dp = resources.displayMetrics.density
        var row: LinearLayout? = null
        plans.forEachIndexed { i, plan ->
            if (i % 2 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                }
                plansBox.addView(row)
            }
            val best = plan.id == "year"
            val text = planLabel(plan) + "\n" + price(plan) +
                if (best) "\n" + getString(R.string.missa_activation_plan_best) else ""
            val button = MaterialButton(
                this, null,
                if (best) com.google.android.material.R.attr.materialButtonStyle
                else com.google.android.material.R.attr.materialButtonOutlinedStyle
            ).apply {
                this.text = text
                isAllCaps = false
                minWidth = (150 * dp).toInt()
                isFocusable = true
                setOnClickListener { onPlanClicked(plan) }
                setOnFocusChangeListener { _, has -> if (has && isTv) selectPlan(plan.id) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt()) }
            row?.addView(button, lp)
            planButtons[plan.id] = button
        }
        selectPlan(selectedPlan)
    }

    private fun selectPlan(id: String) {
        selectedPlan = id
        planButtons.forEach { (key, b) -> b.isSelected = key == id; b.alpha = if (key == id || !isTv) 1f else 0.75f }
        if (isTv) updateQr()
    }

    private fun onPlanClicked(plan: MissaLicence.Plan) {
        selectPlan(plan.id)
        if (isTv) {
            // Sur TV, pas de navigateur pratique : on paie avec le téléphone via le QR code.
            status.text = getString(R.string.missa_activation_scan_plan, planLabel(plan), price(plan))
            return
        }
        if (paying) return
        paying = true
        status.setText(R.string.missa_activation_opening)
        lifecycleScope.launch {
            val url = MissaLicence.createPayment(this@MissaActivationActivity, plan.id)
                ?: MissaLicence.payUrlFor(this@MissaActivationActivity, plan.id)
            paying = false
            if (url == null) {
                status.setText(R.string.missa_activation_no_network)
                return@launch
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                status.setText(R.string.missa_activation_waiting)
            } catch (e: Exception) {
                logError(e)
                status.text = getString(R.string.missa_activation_scan, MissaLicence.SITE_ACTIVATE)
            }
        }
    }

    private fun updateQr() {
        val url = MissaLicence.payUrlFor(this, selectedPlan)
        val plan = MissaLicence.plans(this).firstOrNull { it.id == selectedPlan }
        qrHint.isVisible = true
        qrHint.text = if (plan != null) {
            getString(R.string.missa_activation_scan_plan, planLabel(plan), price(plan)) + "\n" +
                getString(R.string.missa_activation_scan, MissaLicence.SITE_ACTIVATE)
        } else getString(R.string.missa_activation_scan, MissaLicence.SITE_ACTIVATE)
        if (url != null && url != qrFor) {
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
        qr.isVisible = qrFor != null
    }

    // ------------------------------------------------------------------ Deuxième appareil

    private fun askOwnerCode() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(14))
            hint = "MISSA-XXXXXX"
            setSingleLine()
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.missa_activation_link)
            .setMessage(R.string.missa_activation_link_help)
            .setView(box)
            .setPositiveButton(R.string.missa_activation_link_go, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val owner = input.text?.toString().orEmpty()
                if (owner.isBlank()) return@setOnClickListener
                input.error = null
                status.setText(R.string.missa_activation_checking)
                lifecycleScope.launch {
                    when (MissaLicence.linkToSubscription(this@MissaActivationActivity, owner)) {
                        MissaLicence.LinkResult.OK -> {
                            dialog.dismiss()
                            checkNow(userAsked = true)
                        }
                        MissaLicence.LinkResult.BAD_CODE -> input.error = getString(R.string.missa_activation_link_bad)
                        MissaLicence.LinkResult.NO_SUBSCRIPTION -> input.error = getString(R.string.missa_activation_link_nosub)
                        MissaLicence.LinkResult.TOO_MANY -> input.error = getString(R.string.missa_activation_link_full)
                        MissaLicence.LinkResult.LOCKED -> input.error = getString(R.string.missa_activation_link_locked)
                        MissaLicence.LinkResult.NETWORK -> input.error = getString(R.string.missa_activation_no_network)
                    }
                }
            }
        }
        dialog.show()
        input.post { input.requestFocus() }
    }

    // ------------------------------------------------------------------ État

    private suspend fun checkNow(userAsked: Boolean) {
        val ok = MissaLicence.refresh(this)
        if (!MissaLicence.verdict(this).blocked) {
            onUnlocked()
            return
        }
        render()
        status.setText(
            when {
                !ok -> R.string.missa_activation_no_network
                userAsked -> R.string.missa_activation_not_yet
                paying -> R.string.missa_activation_opening
                else -> R.string.missa_activation_waiting
            }
        )
    }

    private var unlocking = false

    /** Accès ouvert : on rappelle le code à saisir sur le 2e appareil, puis on ferme. */
    private fun onUnlocked() {
        if (unlocking || isFinishing) return
        unlocking = true
        val myCode = MissaLicence.snapshot(this)?.code.orEmpty()
        if (myCode.isBlank()) {
            Toast.makeText(this, R.string.missa_activation_unlocked, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.missa_activation_unlocked)
            .setMessage(getString(R.string.missa_activation_second_device, myCode))
            .setPositiveButton(R.string.ok) { _, _ -> finish() }
            .setOnDismissListener { if (!isFinishing) finish() }
            .show()
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
        // Les prix viennent du serveur : on reconstruit les boutons s'ils ont changé.
        if (MissaLicence.plans(this).map { it.id } != planButtons.keys.toList()) buildPlans()
        if (isTv) updateQr() else {
            qr.isVisible = false
            qrHint.isVisible = false
        }
    }
}
