CREATE TABLE IF NOT EXISTS important (
  key TEXT PRIMARY KEY,
  type TEXT NOT NULL,
  ts INTEGER NOT NULL,
  score REAL NOT NULL,
  labels TEXT,
  description TEXT,
  created INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_important_ts ON important(ts);
CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);

-- Browsers that passed a TOTP (authenticator app) challenge once and are trusted for
-- DEVICE_TRUST_DAYS afterward, so the viewer password alone is enough on a return visit.
-- meta.totp_secret (base32) holds the shared TOTP secret once enrollment completes.
CREATE TABLE IF NOT EXISTS devices (
  id TEXT PRIMARY KEY,
  label TEXT NOT NULL,
  created INTEGER NOT NULL,
  last_seen INTEGER NOT NULL,
  expires INTEGER NOT NULL,
  revoked INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_devices_expires ON devices(expires);
