// API d'activation de MISSA TV 360 PLUS (Cloudflare Pages Functions + base D1).
// Servie à la même adresse que le site : https://missatv.pages.dev/api/...
//
// Règles :
//   - essai gratuit de 24 h, compté par le serveur depuis le premier contact de l'appareil
//     (réinstaller l'appli ne relance pas l'essai) ;
//   - à la fin de l'essai : blocage immédiat, il faut payer pour activer ;
//   - abonnement : 1 à 9 mois à 1 000 FCFA le mois, ou 1 an à 10 000 FCFA ; 2 appareils ;
//   - quand l'abonnement expire : 1 jour de grâce, puis blocage.
//
// Variables (Cloudflare Pages → Settings → Variables and secrets, ou workflow) :
//   KPAY_API_KEY, KPAY_SECRET_KEY   clés de l'application KPay (secrets)
//   KPAY_WEBHOOK_SECRET             secret de signature des notifications KPay (secret)
//   LICENCE_ENFORCED                "1" pour activer le blocage dans l'appli ; sinon tout est libre
//   ADMIN_TOKEN                     facultatif : pour offrir des jours à un client (support)

const DAY = 24 * 60 * 60 * 1000;
const TRIAL_MS = DAY;
const GRACE_MS = DAY;
const MAX_DEVICES = 2;
// Un appareil sans nouvelles depuis 30 jours ne compte plus dans la limite des 2 appareils
// (changement de TV ou de téléphone sans passer par le support).
const DEVICE_STALE_MS = 30 * DAY;
const KPAY_BASE = "https://admin.kpay.site/api/v1";

// De 1 à 9 mois : 1 000 FCFA par mois (30 jours). Au-delà, c'est l'année : 10 000 FCFA
// pour 365 jours (le prix de 10 mois pour 12).
const MONTH_PRICE = 1000;
const PLANS = { year: { amount: 10000, currency: "XAF", days: 365, label: "1 an" } };
for (let m = 1; m <= 9; m++) {
  PLANS["m" + m] = { amount: MONTH_PRICE * m, currency: "XAF", days: 30 * m, label: m === 1 ? "1 mois" : `${m} mois` };
}
PLANS.month = PLANS.m1; // ancien nom de la formule 1 mois

const CODE_ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

export async function onRequest(context) {
  const { request, env, params } = context;
  const path = "/" + [].concat(params.path || []).join("/");
  try {
    if (request.method === "OPTIONS") return cors(new Response(null, { status: 204 }));
    if (!env.DB) return json({ error: "db_missing" }, 503);

    if (path === "/licence" && request.method === "POST") return cors(await licence(request, env));
    if (path === "/code" && request.method === "GET") return cors(await codeInfo(request, env));
    if (path === "/pay" && request.method === "POST") return cors(await pay(request, env));
    if (path === "/link" && request.method === "POST") return cors(await link(request, env));
    if (path === "/kpay-webhook" && request.method === "POST") return await webhook(request, env);
    if (path === "/admin/grant" && request.method === "POST") return await adminGrant(request, env);
    return json({ error: "not_found" }, 404);
  } catch (e) {
    console.error(e && e.stack ? e.stack : e);
    return cors(json({ error: "server_error" }, 500));
  }
}

// ---------------------------------------------------------------- appli

/** Appelé par l'appli : enregistre l'appareil et renvoie son état. */
async function licence(request, env) {
  const body = await readJson(request);
  const deviceId = String(body.deviceId || "");
  if (!/^[a-f0-9]{64}$/.test(deviceId)) return json({ error: "bad_device" }, 400);
  const model = String(body.model || "").slice(0, 80);
  const now = Date.now();

  let device = await env.DB.prepare("SELECT * FROM devices WHERE id = ?").bind(deviceId).first();
  if (!device) {
    device = await createDevice(env, deviceId, model, now);
  } else {
    await env.DB.prepare("UPDATE devices SET last_seen = ?, model = ? WHERE id = ?")
      .bind(now, model || device.model, deviceId).run();
  }
  // Notification KPay perdue ? On demande l'état des paiements récents directement à KPay.
  await reconcilePending(env, deviceId, now);
  return json(await deviceState(env, deviceId, now, request));
}

async function createDevice(env, deviceId, model, now) {
  for (let i = 0; i < 6; i++) {
    const code = newCode();
    await env.DB.prepare(
      "INSERT OR IGNORE INTO devices (id, code, first_seen, last_seen, model) VALUES (?, ?, ?, ?, ?)"
    ).bind(deviceId, code, now, now, model).run();
    const row = await env.DB.prepare("SELECT * FROM devices WHERE id = ?").bind(deviceId).first();
    if (row) return row; // créé ici, ou en parallèle par une autre requête
    // Sinon le code tiré était déjà pris : on en tire un autre.
  }
  throw new Error("code_generation_failed");
}

async function deviceState(env, deviceId, now, request) {
  const device = await env.DB.prepare("SELECT * FROM devices WHERE id = ?").bind(deviceId).first();
  let expiresAt = 0;
  if (device.subscription_id) {
    const sub = await env.DB.prepare("SELECT expires_at FROM subscriptions WHERE id = ?")
      .bind(device.subscription_id).first();
    expiresAt = sub ? sub.expires_at : 0;
  }
  const trialEndsAt = device.first_seen + TRIAL_MS;
  const graceEndsAt = expiresAt ? expiresAt + GRACE_MS : 0;
  let state;
  if (expiresAt > now) state = "active";
  else if (graceEndsAt > now) state = "grace";
  else if (!expiresAt && trialEndsAt > now) state = "trial";
  else state = "blocked";
  const origin = new URL(request.url).origin;
  return {
    enforced: env.LICENCE_ENFORCED === "1",
    state,
    code: device.code,
    trialEndsAt,
    expiresAt,
    graceEndsAt,
    serverTime: now,
    payUrl: `${origin}/activer/?code=${encodeURIComponent(device.code)}`,
  };
}

// ---------------------------------------------------------------- page de paiement

/** Page « Activer » : état d'un code saisi ou scanné. */
async function codeInfo(request, env) {
  const code = normCode(new URL(request.url).searchParams.get("code"));
  if (!code) return json({ error: "bad_code" }, 400);
  const device = await env.DB.prepare("SELECT id FROM devices WHERE code = ?").bind(code).first();
  if (!device) return json({ error: "unknown_code" }, 404);
  const now = Date.now();
  await reconcilePending(env, device.id, now);
  const st = await deviceState(env, device.id, now, request);
  return json({
    code: st.code,
    state: st.state,
    expiresAt: st.expiresAt,
    trialEndsAt: st.trialEndsAt,
    paymentsReady: !!(env.KPAY_API_KEY && env.KPAY_SECRET_KEY),
    plans: Object.fromEntries(Object.entries(PLANS).filter(([k]) => k !== "month").map(([k, p]) => [k, {
      amount: p.amount, currency: p.currency, days: p.days,
    }])),
  });
}

/** Crée le paiement chez KPay (page de paiement hébergée) et renvoie son adresse. */
async function pay(request, env) {
  const body = await readJson(request);
  const code = normCode(body.code);
  const plan = Object.prototype.hasOwnProperty.call(PLANS, body.plan) ? PLANS[body.plan] : null;
  if (!code || !plan) return json({ error: "bad_request" }, 400);
  if (!env.KPAY_API_KEY || !env.KPAY_SECRET_KEY) return json({ error: "payments_not_configured" }, 503);
  const device = await env.DB.prepare("SELECT id FROM devices WHERE code = ?").bind(code).first();
  if (!device) return json({ error: "unknown_code" }, 404);

  const now = Date.now();
  const externalId = "MTV-" + randomHex(10);
  const origin = new URL(request.url).origin;
  const returnUrl = `${origin}/activer/?code=${encodeURIComponent(code)}&retour=1`;

  const res = await fetch(`${KPAY_BASE}/payments/init`, {
    method: "POST",
    headers: kpayHeaders(env),
    body: JSON.stringify({
      amount: plan.amount,
      currency: plan.currency,
      externalId,
      returnUrl,
      description: `MISSA TV 360 PLUS — abonnement ${plan.label} (${code})`,
      metadata: { code, plan: body.plan },
    }),
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok || !data.gatewayUrl) {
    console.error("KPay init", res.status, JSON.stringify(data));
    return json({ error: "kpay_error" }, 502);
  }
  await env.DB.prepare(
    `INSERT INTO payments (external_id, device_id, plan, amount, currency, kpay_ref, status, is_test, created_at)
     VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)`
  ).bind(externalId, device.id, body.plan, plan.amount, plan.currency, data.reference || data.id || null,
    data.isTest ? 1 : 0, now).run();
  return json({ gatewayUrl: data.gatewayUrl });
}

/** Ajoute un appareil à l'abonnement d'un autre appareil (2 appareils par abonnement). */
async function link(request, env) {
  const body = await readJson(request);
  const code = normCode(body.code);
  const ownerCode = normCode(body.ownerCode);
  if (!code || !ownerCode || code === ownerCode) return json({ error: "bad_request" }, 400);
  const now = Date.now();
  const device = await env.DB.prepare("SELECT * FROM devices WHERE code = ?").bind(code).first();
  const owner = await env.DB.prepare("SELECT * FROM devices WHERE code = ?").bind(ownerCode).first();
  if (!device || !owner) return json({ error: "unknown_code" }, 404);
  if (!owner.subscription_id) return json({ error: "no_subscription" }, 409);
  const sub = await env.DB.prepare("SELECT * FROM subscriptions WHERE id = ?").bind(owner.subscription_id).first();
  if (!sub || sub.expires_at <= now) return json({ error: "no_subscription" }, 409);
  if (device.subscription_id === sub.id) return json({ ok: true });
  const used = await env.DB.prepare(
    "SELECT COUNT(*) AS n FROM devices WHERE subscription_id = ? AND last_seen > ?"
  ).bind(sub.id, now - DEVICE_STALE_MS).first();
  if ((used ? used.n : 0) >= MAX_DEVICES) return json({ error: "too_many_devices" }, 409);
  await env.DB.prepare("UPDATE devices SET subscription_id = ? WHERE id = ?").bind(sub.id, device.id).run();
  return json({ ok: true });
}

// ---------------------------------------------------------------- KPay

/** Notification signée de KPay : seule source qui valide un paiement. */
async function webhook(request, env) {
  const raw = await request.text();
  const sig = request.headers.get("x-kpay-signature") || "";
  if (!env.KPAY_WEBHOOK_SECRET) return new Response("not configured", { status: 503 });
  const expected = await hmacHex(env.KPAY_WEBHOOK_SECRET, raw);
  if (!timingSafeEqual(sig.toLowerCase(), expected)) return new Response("invalid signature", { status: 400 });
  let ev;
  try { ev = JSON.parse(raw); } catch { return new Response("bad json", { status: 400 }); }
  if (!ev.externalId) return new Response("OK");
  const status = String(ev.status || "").toUpperCase();
  if (status === "COMPLETED") {
    await applyCompleted(env, ev.externalId, Number(ev.amount), ev.reference || ev.paymentId);
  } else if (status === "FAILED" || status === "CANCELLED") {
    await env.DB.prepare("UPDATE payments SET status = ? WHERE external_id = ? AND status = 'PENDING'")
      .bind(status, ev.externalId).run();
  }
  return new Response("OK");
}

/** Interroge KPay pour les paiements en attente (au plus toutes les 15 s, pendant 2 h). */
async function reconcilePending(env, deviceId, now) {
  if (!env.KPAY_API_KEY || !env.KPAY_SECRET_KEY) return;
  const { results } = await env.DB.prepare(
    `SELECT external_id, kpay_ref FROM payments
     WHERE device_id = ? AND status = 'PENDING' AND created_at > ? AND last_check < ? AND kpay_ref IS NOT NULL
     LIMIT 3`
  ).bind(deviceId, now - 2 * 60 * 60 * 1000, now - 15000).all();
  for (const p of results || []) {
    await env.DB.prepare("UPDATE payments SET last_check = ? WHERE external_id = ?").bind(now, p.external_id).run();
    try {
      const res = await fetch(`${KPAY_BASE}/payments/${encodeURIComponent(p.kpay_ref)}`, { headers: kpayHeaders(env) });
      if (!res.ok) continue;
      const data = await res.json();
      if (data.externalId && data.externalId !== p.external_id) continue;
      const status = String(data.status || "").toUpperCase();
      if (status === "COMPLETED") await applyCompleted(env, p.external_id, Number(data.amount), data.reference);
      else if (status === "FAILED" || status === "CANCELLED") {
        await env.DB.prepare("UPDATE payments SET status = ? WHERE external_id = ? AND status = 'PENDING'")
          .bind(status, p.external_id).run();
      }
    } catch (e) {
      console.error("KPay status", e);
    }
  }
}

/** Valide un paiement une seule fois et prolonge l'abonnement de l'appareil. */
async function applyCompleted(env, externalId, amount, reference) {
  const payment = await env.DB.prepare("SELECT * FROM payments WHERE external_id = ?").bind(externalId).first();
  if (!payment) return;
  const plan = Object.prototype.hasOwnProperty.call(PLANS, payment.plan) ? PLANS[payment.plan] : null;
  if (!plan) return;
  // Le montant est fixé par ce serveur à la création (le client ne peut pas le changer sur
  // la page KPay). En Mobile Money hors zone XAF/XOF, KPay peut le convertir dans la devise
  // de l'opérateur : on le note seulement.
  if (amount !== payment.amount) console.log("Montant converti", externalId, amount);
  const now = Date.now();
  const upd = await env.DB.prepare(
    "UPDATE payments SET status = 'COMPLETED', completed_at = ?, kpay_ref = COALESCE(?, kpay_ref) WHERE external_id = ? AND status != 'COMPLETED'"
  ).bind(now, reference || null, externalId).run();
  if (!upd.meta || upd.meta.changes !== 1) return; // déjà traité (notification reçue deux fois)
  await extend(env, payment.device_id, plan.days * DAY, now);
}

async function extend(env, deviceId, durationMs, now) {
  const device = await env.DB.prepare("SELECT * FROM devices WHERE id = ?").bind(deviceId).first();
  if (!device) return;
  let sub = device.subscription_id
    ? await env.DB.prepare("SELECT * FROM subscriptions WHERE id = ?").bind(device.subscription_id).first()
    : null;
  if (!sub) {
    const id = "sub_" + randomHex(10);
    await env.DB.batch([
      env.DB.prepare("INSERT INTO subscriptions (id, owner_code, expires_at, created_at) VALUES (?, ?, ?, ?)")
        .bind(id, device.code, now + durationMs, now),
      env.DB.prepare("UPDATE devices SET subscription_id = ? WHERE id = ?").bind(id, deviceId),
    ]);
    return;
  }
  // Renouvellement : on ajoute la durée à la date de fin si elle n'est pas encore passée.
  await env.DB.prepare("UPDATE subscriptions SET expires_at = MAX(expires_at, ?) + ? WHERE id = ?")
    .bind(now, durationMs, sub.id).run();
}

// ---------------------------------------------------------------- support

async function adminGrant(request, env) {
  const auth = request.headers.get("authorization") || "";
  if (!env.ADMIN_TOKEN || !timingSafeEqual(auth, `Bearer ${env.ADMIN_TOKEN}`)) {
    return json({ error: "forbidden" }, 403);
  }
  const body = await readJson(request);
  const code = normCode(body.code);
  const days = Number(body.days);
  if (!code || !(days > 0 && days <= 3660)) return json({ error: "bad_request" }, 400);
  const device = await env.DB.prepare("SELECT id FROM devices WHERE code = ?").bind(code).first();
  if (!device) return json({ error: "unknown_code" }, 404);
  const now = Date.now();
  await extend(env, device.id, days * DAY, now);
  return json(await deviceState(env, device.id, now, request));
}

// ---------------------------------------------------------------- outils

function kpayHeaders(env) {
  return {
    "X-API-Key": env.KPAY_API_KEY,
    "X-Secret-Key": env.KPAY_SECRET_KEY,
    "Content-Type": "application/json",
  };
}

function newCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(6));
  let s = "";
  for (const b of bytes) s += CODE_ALPHABET[b % CODE_ALPHABET.length];
  return "MISSA-" + s;
}

function normCode(v) {
  const s = String(v || "").toUpperCase().replace(/[^A-Z0-9]/g, "");
  const core = s.startsWith("MISSA") ? s.slice(5) : s;
  if (!/^[2-9A-HJ-NP-Z]{6}$/.test(core)) return null;
  return "MISSA-" + core;
}

function randomHex(n) {
  return [...crypto.getRandomValues(new Uint8Array(n))].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function hmacHex(secret, data) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(data));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function timingSafeEqual(a, b) {
  if (typeof a !== "string" || typeof b !== "string" || a.length !== b.length) return false;
  let r = 0;
  for (let i = 0; i < a.length; i++) r |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return r === 0;
}

async function readJson(request) {
  try { return await request.json(); } catch { return {}; }
}

function json(obj, status = 200) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

function cors(res) {
  res.headers.set("access-control-allow-origin", "*");
  res.headers.set("access-control-allow-headers", "content-type");
  res.headers.set("access-control-allow-methods", "GET, POST, OPTIONS");
  return res;
}
