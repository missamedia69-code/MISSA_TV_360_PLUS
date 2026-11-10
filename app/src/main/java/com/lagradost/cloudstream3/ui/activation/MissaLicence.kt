package com.lagradost.cloudstream3.ui.activation

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import com.lagradost.cloudstream3.BuildConfig
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Essai de 24 h et abonnement.
 *
 * C'est le serveur (missatv.pages.dev/api, voir cloudflare/) qui décide : il note la date du
 * premier contact de l'appareil, l'abonnement payé via KPay et sa date de fin. L'appli garde
 * sa dernière réponse pour fonctionner hors connexion pendant [OFFLINE_MS].
 *
 * Tant que le serveur n'a jamais répondu « enforced », rien n'est bloqué : une appli publiée
 * avant la mise en service des paiements (ou un serveur en panne dès l'installation) ne
 * bloque personne.
 */
object MissaLicence {
    const val API_URL = "https://missatv.pages.dev/api/licence"
    const val SITE_ACTIVATE = "missatv.pages.dev/activer"

    private const val PREFS = "missa_licence"
    private const val HOUR = 60L * 60 * 1000
    private const val OFFLINE_MS = 3 * 24 * HOUR
    private const val REFRESH_MS = 15L * 60 * 1000

    enum class Verdict(val blocked: Boolean) {
        OPEN(false),
        GRACE(false),
        BLOCKED_PAY(true),
        BLOCKED_OFFLINE(true),
    }

    data class Snapshot(
        val enforced: Boolean,
        val state: String,
        val code: String,
        val trialEndsAt: Long,
        val expiresAt: Long,
        val graceEndsAt: Long,
        val payUrl: String,
        /** Heure du serveur moins heure locale, au dernier contact. */
        val serverOffset: Long,
        /** Heure locale du dernier contact réussi. */
        val lastOk: Long,
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Identifiant stable de l'appareil, haché : ANDROID_ID ne change pas à la réinstallation
     * (Android 8+), ce qui empêche de relancer l'essai en réinstallant. L'ID brut ne quitte
     * jamais l'appareil.
     */
    @SuppressLint("HardwareIds")
    fun deviceId(ctx: Context): String {
        val androidId = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() && it != "9774d56d682e549c" }
        val base = androidId ?: prefs(ctx).getString("fallback_id", null) ?: UUID.randomUUID().toString().also {
            prefs(ctx).edit { putString("fallback_id", it) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest("missatv:$base".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun snapshot(ctx: Context): Snapshot? {
        val p = prefs(ctx)
        if (!p.contains("lastOk")) return null
        return Snapshot(
            enforced = p.getBoolean("enforced", false),
            state = p.getString("state", "") ?: "",
            code = p.getString("code", "") ?: "",
            trialEndsAt = p.getLong("trialEndsAt", 0),
            expiresAt = p.getLong("expiresAt", 0),
            graceEndsAt = p.getLong("graceEndsAt", 0),
            payUrl = p.getString("payUrl", "") ?: "",
            serverOffset = p.getLong("serverOffset", 0),
            lastOk = p.getLong("lastOk", 0),
        )
    }

    fun verdict(ctx: Context, now: Long = System.currentTimeMillis()): Verdict {
        val s = snapshot(ctx) ?: return Verdict.OPEN
        if (!s.enforced) return Verdict.OPEN
        // Trop longtemps sans vérification, ou horloge reculée : il faut se reconnecter.
        if (now - s.lastOk > OFFLINE_MS || now < s.lastOk - HOUR) return Verdict.BLOCKED_OFFLINE
        val serverNow = now + s.serverOffset
        return when {
            s.expiresAt > serverNow -> Verdict.OPEN
            s.graceEndsAt > serverNow -> Verdict.GRACE
            s.expiresAt == 0L && s.trialEndsAt > serverNow -> Verdict.OPEN
            else -> Verdict.BLOCKED_PAY
        }
    }

    /** Vrai s'il est temps de redemander l'état au serveur. */
    fun shouldRefresh(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        val lastTry = prefs(ctx).getLong("lastTry", 0)
        if (now - lastTry > REFRESH_MS || now < lastTry) return true
        // Fin d'essai ou d'abonnement imminente : on vérifie plus souvent.
        val s = snapshot(ctx) ?: return true
        val serverNow = now + s.serverOffset
        val next = listOf(s.trialEndsAt, s.expiresAt, s.graceEndsAt).filter { it > serverNow }.minOrNull()
        return next != null && next - serverNow < REFRESH_MS && now - lastTry > 60_000
    }

    /** Demande l'état au serveur et le garde. Renvoie false si le serveur n'a pas répondu. */
    suspend fun refresh(ctx: Context): Boolean = withContext(Dispatchers.IO) {
        val p = prefs(ctx)
        p.edit { putLong("lastTry", System.currentTimeMillis()) }
        try {
            val sent = System.currentTimeMillis()
            val res = app.post(
                API_URL,
                json = mapOf(
                    "deviceId" to deviceId(ctx),
                    "model" to "${Build.MANUFACTURER} ${Build.MODEL}".take(80),
                    "version" to BuildConfig.VERSION_NAME,
                ),
                timeout = 15L,
            )
            if (!res.isSuccessful) return@withContext false
            val j = JSONObject(res.text)
            val received = System.currentTimeMillis()
            val serverTime = j.optLong("serverTime", received)
            p.edit {
                putBoolean("enforced", j.optBoolean("enforced", false))
                putString("state", j.optString("state"))
                putString("code", j.optString("code"))
                putLong("trialEndsAt", j.optLong("trialEndsAt"))
                putLong("expiresAt", j.optLong("expiresAt"))
                putLong("graceEndsAt", j.optLong("graceEndsAt"))
                putString("payUrl", j.optString("payUrl"))
                putLong("serverOffset", serverTime - (sent + received) / 2)
                putLong("lastOk", received)
            }
            true
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    /** Pour n'afficher le rappel « délai de grâce » qu'une fois par jour. */
    fun takeGraceReminder(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(ctx)
        if (now - p.getLong("graceShown", 0) < 20 * HOUR) return false
        p.edit { putLong("graceShown", now) }
        return true
    }
}
