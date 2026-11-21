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
 * premier contact de l'appareil, l'abonnement payé via SasPay et sa date de fin. L'appli garde
 * sa dernière réponse pour fonctionner hors connexion pendant [OFFLINE_MS].
 *
 * Tant que le serveur n'a jamais répondu « enforced », rien n'est bloqué : une appli publiée
 * avant la mise en service des paiements (ou un serveur en panne dès l'installation) ne
 * bloque personne.
 */
object MissaLicence {
    const val API_URL = "https://missatv.pages.dev/api/licence"
    private const val API_BASE = "https://missatv.pages.dev/api/"
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
                j.optJSONArray("plans")?.let { putString("plans", it.toString()) }
                putLong("serverOffset", serverTime - (sent + received) / 2)
                putLong("lastOk", received)
            }
            true
        } catch (e: Exception) {
            logError(e)
            false
        }
    }

    /** Formule proposée sur l'écran de blocage (prix envoyés par le serveur). */
    data class Plan(val id: String, val amount: Int, val currency: String, val days: Int, val label: String)

    /** Formules du dernier contact avec le serveur, ou les formules habituelles par défaut. */
    fun plans(ctx: Context): List<Plan> {
        val raw = prefs(ctx).getString("plans", null)
        val parsed = raw?.let { text ->
            runCatching {
                val arr = org.json.JSONArray(text)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id")
                    if (!Regex("^(m[1-9]|year)$").matches(id)) return@mapNotNull null
                    Plan(id, o.optInt("amount"), o.optString("currency", "XAF"), o.optInt("days"), o.optString("label"))
                }
            }.getOrNull()
        }
        return parsed?.takeIf { it.isNotEmpty() } ?: listOf(
            Plan("m1", 1000, "XAF", 30, "1 mois"),
            Plan("m3", 3000, "XAF", 90, "3 mois"),
            Plan("m6", 6000, "XAF", 180, "6 mois"),
            Plan("year", 10000, "XAF", 365, "1 an"),
        )
    }

    /** Adresse de la page Activer qui lance tout de suite le paiement de [planId] (QR code TV). */
    fun payUrlFor(ctx: Context, planId: String): String? {
        val base = snapshot(ctx)?.payUrl?.takeIf { it.startsWith("https://") } ?: return null
        return base + (if (base.contains('?')) "&" else "?") + "plan=" + planId
    }

    /** Crée le paiement chez le prestataire et renvoie l'adresse de sa page, ou null. */
    suspend fun createPayment(ctx: Context, planId: String): String? = withContext(Dispatchers.IO) {
        val code = snapshot(ctx)?.code?.takeIf { it.isNotBlank() } ?: return@withContext null
        try {
            val res = app.post(API_BASE + "pay", json = mapOf("code" to code, "plan" to planId), timeout = 20L)
            JSONObject(res.text).optString("gatewayUrl").takeIf { it.startsWith("https://") }
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    enum class LinkResult { OK, BAD_CODE, NO_SUBSCRIPTION, TOO_MANY, LOCKED, NETWORK }

    /**
     * Rattache cet appareil à l'abonnement d'un autre appareil (2 appareils par abonnement) :
     * [ownerCode] est le code affiché sur l'appareil qui a payé.
     */
    suspend fun linkToSubscription(ctx: Context, ownerCode: String): LinkResult = withContext(Dispatchers.IO) {
        val code = snapshot(ctx)?.code?.takeIf { it.isNotBlank() } ?: return@withContext LinkResult.NETWORK
        try {
            val res = app.post(API_BASE + "link", json = mapOf("code" to code, "ownerCode" to ownerCode.trim()), timeout = 15L)
            val j = runCatching { JSONObject(res.text) }.getOrNull() ?: return@withContext LinkResult.NETWORK
            if (j.optBoolean("ok")) {
                refresh(ctx)
                return@withContext LinkResult.OK
            }
            when (j.optString("error")) {
                "too_many_devices" -> LinkResult.TOO_MANY
                "too_many_attempts" -> LinkResult.LOCKED
                "no_subscription" -> LinkResult.NO_SUBSCRIPTION
                "unknown_code", "bad_request" -> LinkResult.BAD_CODE
                else -> LinkResult.NETWORK
            }
        } catch (e: Exception) {
            logError(e)
            LinkResult.NETWORK
        }
    }

    // ------------------------------------------------------------ Mes appareils

    data class Device(
        val code: String,
        val model: String?,
        val lastSeen: Long,
        val current: Boolean,
        val inactive: Boolean
    )

    data class Devices(
        val subscribed: Boolean,
        val devices: List<Device>,
        val maxDevices: Int,
        val detachesLeft: Int
    )

    /** Appareils de l'abonnement de cet appareil (null : pas de réponse du serveur). */
    suspend fun myDevices(ctx: Context): Devices? = withContext(Dispatchers.IO) {
        try {
            val res = app.post(API_BASE + "devices", json = mapOf("deviceId" to deviceId(ctx)), timeout = 15L)
            val j = JSONObject(res.text)
            if (j.has("error")) return@withContext null
            val arr = j.optJSONArray("devices")
            val list = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
                val o = arr?.optJSONObject(i) ?: return@mapNotNull null
                Device(
                    code = o.optString("code"),
                    model = o.optString("model").takeIf { it.isNotBlank() && it != "null" },
                    lastSeen = o.optLong("lastSeen"),
                    current = o.optBoolean("current"),
                    inactive = o.optBoolean("inactive")
                )
            }
            Devices(
                subscribed = j.optBoolean("subscribed"),
                devices = list,
                maxDevices = j.optInt("maxDevices", 2),
                detachesLeft = j.optInt("detachesLeft", 0)
            )
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    enum class DetachResult { OK, LIMIT, REFUSED, NETWORK }

    /** Retire l'appareil [code] de l'abonnement (ancien téléphone, TV changée…). */
    suspend fun detachDevice(ctx: Context, code: String): DetachResult = withContext(Dispatchers.IO) {
        try {
            val res = app.post(
                API_BASE + "devices/detach",
                json = mapOf("deviceId" to deviceId(ctx), "code" to code),
                timeout = 15L
            )
            val j = runCatching { JSONObject(res.text) }.getOrNull() ?: return@withContext DetachResult.NETWORK
            when {
                j.optBoolean("ok") -> DetachResult.OK
                j.optString("error") == "too_many_detaches" -> DetachResult.LIMIT
                j.has("error") -> DetachResult.REFUSED
                else -> DetachResult.NETWORK
            }
        } catch (e: Exception) {
            logError(e)
            DetachResult.NETWORK
        }
    }

    /** Pour n'afficher le rappel « délai de grâce » qu'une fois par jour. */
    /**
     * Rappel avant la fin de l'essai (moins de 3 h) ou de l'abonnement (moins de 3 jours).
     * Renvoie le moment de fin (heure du serveur) quand il faut prévenir, au plus une fois
     * toutes les 10 h ; null sinon.
     */
    fun takeExpiryReminder(ctx: Context, now: Long = System.currentTimeMillis()): Long? {
        val s = snapshot(ctx) ?: return null
        if (!s.enforced) return null
        val serverNow = now + s.serverOffset
        val endsAt = when {
            s.expiresAt > serverNow -> s.expiresAt.takeIf { it - serverNow < 3 * 24 * HOUR }
            s.expiresAt == 0L && s.trialEndsAt > serverNow -> s.trialEndsAt.takeIf { it - serverNow < 3 * HOUR }
            else -> null
        } ?: return null
        val p = prefs(ctx)
        if (now - p.getLong("expiryShown", 0) < 10 * HOUR) return null
        p.edit { putLong("expiryShown", now) }
        return endsAt
    }

    fun takeGraceReminder(ctx: Context, now: Long = System.currentTimeMillis()): Boolean {
        val p = prefs(ctx)
        if (now - p.getLong("graceShown", 0) < 20 * HOUR) return false
        p.edit { putLong("graceShown", now) }
        return true
    }
}
