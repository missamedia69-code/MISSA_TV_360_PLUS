-- Base D1 « missatv » : essais, abonnements et paiements de MISSA TV 360 PLUS.
-- Appliquée à chaque publication (instructions idempotentes).

CREATE TABLE IF NOT EXISTS devices (
  id              TEXT PRIMARY KEY,      -- empreinte SHA-256 envoyée par l'appli (jamais l'ID brut)
  code            TEXT NOT NULL UNIQUE,  -- code court affiché à l'écran, ex. MISSA-7K2Q9P
  first_seen      INTEGER NOT NULL,      -- début de l'essai (ms)
  last_seen       INTEGER NOT NULL,
  subscription_id TEXT,
  model           TEXT
);
CREATE INDEX IF NOT EXISTS devices_subscription ON devices(subscription_id);

CREATE TABLE IF NOT EXISTS subscriptions (
  id          TEXT PRIMARY KEY,
  owner_code  TEXT NOT NULL,             -- code de l'appareil qui a payé en premier
  expires_at  INTEGER NOT NULL,
  created_at  INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS payments (
  external_id  TEXT PRIMARY KEY,         -- notre identifiant, transmis à KPay
  device_id    TEXT NOT NULL,
  plan         TEXT NOT NULL,
  amount       INTEGER NOT NULL,
  currency     TEXT NOT NULL,
  kpay_ref     TEXT,
  status       TEXT NOT NULL,            -- PENDING, COMPLETED, FAILED, CANCELLED
  is_test      INTEGER NOT NULL DEFAULT 0,
  created_at   INTEGER NOT NULL,
  completed_at INTEGER,
  last_check   INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS payments_device ON payments(device_id, status);

-- Tentatives ratées d'association d'un 2e appareil (anti-devinette des codes) :
-- au plus 10 par appareil et par heure.
CREATE TABLE IF NOT EXISTS link_attempts (
  code  TEXT NOT NULL,
  at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS link_attempts_code ON link_attempts(code, at);

-- Téléchargements de l'APK depuis le bouton du site (/api/dl) : date et pays seulement.
CREATE TABLE IF NOT EXISTS downloads (
  at       INTEGER NOT NULL,
  country  TEXT
);
CREATE INDEX IF NOT EXISTS downloads_at ON downloads(at);
CREATE INDEX IF NOT EXISTS devices_first_seen ON devices(first_seen);
CREATE INDEX IF NOT EXISTS devices_last_seen ON devices(last_seen);

-- Messages des utilisateurs (« Aide et contact » de l'appli, page Contact du site).
CREATE TABLE IF NOT EXISTS tickets (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  at           INTEGER NOT NULL,
  code         TEXT,                     -- code de l'appareil (absent depuis le site)
  category     TEXT NOT NULL,            -- probleme, paiement, contenu, suggestion, autre
  message      TEXT NOT NULL,
  contact      TEXT,                     -- WhatsApp, téléphone ou e-mail laissé par l'utilisateur
  app_version  TEXT,
  model        TEXT,
  source       TEXT NOT NULL,            -- app ou site
  status       TEXT NOT NULL DEFAULT 'open'
);
CREATE INDEX IF NOT EXISTS tickets_status ON tickets(status, at);
CREATE INDEX IF NOT EXISTS tickets_code ON tickets(code, at);

-- Annonces affichées à tous dans l'application (page /admin/ : publier, arrêter).
-- Une seule active à la fois ; ends_at nul = jusqu'à l'arrêt.
CREATE TABLE IF NOT EXISTS announcements (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  at         INTEGER NOT NULL,
  title      TEXT NOT NULL,
  message    TEXT NOT NULL,
  link       TEXT,
  ends_at    INTEGER,
  active     INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS announcements_active ON announcements(active, id);

-- Appareils retirés d'un abonnement par l'abonné (« Mes appareils ») : 3 au plus par 30 jours.
CREATE TABLE IF NOT EXISTS device_detaches (
  subscription_id TEXT NOT NULL,
  device_code     TEXT NOT NULL,
  by_code         TEXT NOT NULL,
  at              INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS device_detaches_sub ON device_detaches(subscription_id, at);
