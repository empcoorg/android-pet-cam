# Optional cloud backend (Cloudflare Worker)

**This whole directory is optional.** The app records to local storage with no server configured.
Deploy this Worker only if you want off-site uploads and a password-protected live view, and don't
mind running it on your own Cloudflare account (free tier is enough for one camera). Any server
that accepts `HTTPS PUT` uploads and bridges a `WebSocket` of JPEG frames works instead — this is
just a complete, ready-made implementation.

## What you get

* **Web viewer** — live view (JPEG frames over WebSocket, streamed only while a browser is
  watching), clip/photo gallery, remote tweaks (mode, trigger, intervals, mounting).
* **Auth** — viewer password, optional TOTP authenticator enrollment per browser, HMAC-signed
  `HttpOnly` sessions; the phone uses a bearer token. Constant-time comparisons throughout.
* **Storage** — R2 bucket with a configurable size cap (oldest deleted first) and a D1 catalog.
* **Optional email** — daily digest, offline alerts and a "back online" notice.
* **Optional APK hosting** — serve the app's installer for cable-free phone setup, plus an
  over-the-air update check.

## Setup

```bash
cd cloudflare
npm install
npx wrangler login                      # free Cloudflare account

cp wrangler.jsonc.template wrangler.jsonc   # then edit the placeholders
```

Create the backing services (names must match your `wrangler.jsonc`):

```bash
npx wrangler r2 bucket create security-cam-<name>-clips
npx wrangler d1 create security-cam-<name>-catalog     # paste the printed database_id into wrangler.jsonc
npx wrangler d1 execute security-cam-<name>-catalog --remote --file schema.sql
```

Set the secrets:

```bash
openssl rand -hex 32 | npx wrangler secret put DEVICE_TOKEN      # phone authenticates with this
openssl rand -hex 32 | npx wrangler secret put SESSION_SECRET    # signs viewer session cookies
printf 'your-viewer-password' | npx wrangler secret put VIEWER_PASSWORD
```

Deploy and open the printed URL:

```bash
npm run deploy
```

## Connect the phone

1. On the phone's browser, open the Worker URL, sign in with the viewer password, and complete
   the authenticator-app prompt if shown.
2. Open **Phone setup** on that page: it shows the Worker URL and device token, and links to the
   APK download.
3. In the app: *Settings → Remote access* — paste the **Server URL** and **Device token**, then
   enable **Upload** and/or **Live view**. (Rows stay greyed until both values are entered.)

Alternatively bake the URL and token into the APK at build time: create git-ignored
`app/keys.properties` with `CLOUD_URL=...` and `DEVICE_TOKEN=...`.

Optional email vars (`DIGEST_TO`, `DIGEST_FROM`, `PUBLIC_URL`, `CAMERA_UTC_OFFSET_MINUTES`,
`CAMERA_LABEL`) live in `wrangler.jsonc`; remove the `send_email` block to disable email entirely.
Workers AI (`"ai"` binding) adds automatic importance labelling to the digest — remove the block
to run without it.

## Hardening

Put **Cloudflare Access** (Zero Trust) in front of the Worker on a custom domain with a bypass
rule for `/api/device/*` — then human viewers additionally need your identity-provider login,
while the phone keeps working with its token. Rotating `DEVICE_TOKEN` (`wrangler secret put
DEVICE_TOKEN`) instantly cuts off every phone still holding the old token.
