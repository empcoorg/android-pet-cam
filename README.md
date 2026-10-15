# Android Security Cam

An old Android phone is worth almost nothing these days. It can become a **private, encrypted
security camera** — no proprietary third-party servers, no accounts, no subscriptions. Footage is
recorded to the phone's own storage (or its SD card) and stays there unless you choose a server
to sync it to.

Works on a wide range of devices — Android 8.0+ (API 26), front or back camera, portrait or
sideways mounting — and automatically switches to a reduced-load profile on low-end hardware
(2 GB Android Go phones included).

**[Quick start](#quick-start)** · **[Features](#features)** · **[Settings guide](#settings-guide)** ·
**[Recording modes](#recording-modes)** · **[Remote access (optional)](#remote-access-optional)** ·
**[Security model](#security-model)** · **[How it works](#how-it-works)** · **[License](#license)**

## Quick start

Everything in this section is optional except steps 1–6; the app is fully usable with **no server
at all**.

### On your computer

1. Install [Android Studio](https://developer.android.com/studio) (it bundles the JDK and Android
   SDK). Open it, choose **Open**, and pick this repo's root folder (not `app/`). Wait for the
   Gradle sync to finish.
2. Plug the phone in over USB. When it appears in Android Studio's device dropdown, press
   **Run ▶**. Terminal alternative: `./gradlew :app:installDebug`.

### On the phone

3. Enable developer options: *Settings → About phone → Build number* — tap it 7 times, then turn
   on *USB debugging* under the new *Developer options* menu (only needed once, to install the app).
4. On first launch, grant **Camera**, **Microphone** and **Notifications**.
5. In the app's *Settings* tab → *Battery* → **Disable battery optimisation**, and allow the
   system prompt. (By hand: *Settings → Apps → Security Cam → Battery → Unrestricted*.)
6. Press **Start** on the Camera tab. Recording continues with the screen off and the phone
   locked. Set *Auto-start* in Settings so it resumes on its own after reboots and updates.

That's it: a self-contained camera recording to local storage. Browse footage on the phone in the
**Clips** tab (play / save / share / delete), or pull files over USB:

```bash
adb pull /sdcard/Android/data/org.empco.securitycam/files/ ./footage
```

## Features

| | |
|---|---|
| Records with the screen off / locked | Camera + microphone foreground service; survives screen lock |
| Motion mode | Records while something moves, plus a configurable tail; stills on a cadence |
| Continuous mode | Rolling clips (5 min default, configurable) |
| Photo-only mode | Full-res stills only — for hardware too weak for video |
| Timestamp | Burned into the video, or drawn as an overlay on live view/stills (profile-dependent) |
| Local storage | Built-in storage or SD card; oldest footage recycled only when free space runs low (or an optional fixed cap) |
| Night vision | Automatic low-light boost from exposure/luma — never emits any light |
| Unattended operation | Restarts after reboot, app update and crashes; camera errors retry with backoff |
| Clip list | Play / save to gallery / share / delete, on-device |
| PIN lock | Optional PIN gates the app UI; recording keeps running either way |
| Remote access | Optional: off-site backup + password-protected live view via [your own server](#remote-access-optional) |

## Settings guide

Every tunable is in the app's **Settings** tab, applied live. Controls that can't work on your
device or configuration are greyed out with an explanation instead of silently doing nothing:

| Greyed out | Why |
|---|---|
| *Store footage on SD card* | No removable storage detected — footage goes to built-in storage (the toggle lights up when you insert an SD card) |
| *Video quality* options above what was achieved | The camera has only delivered that resolution so far on the selected profile |
| *Burn timestamp into the video* | Not possible on the *Max* profile — the timestamp is shown as an overlay on live view, stills and playback instead |
| *Upload*, *Live view* (+ fps) | No server URL / device token entered — the app is in local-only mode |
| *Upload on Wi-Fi only* | Uploads are off |
| Camera *Capability profile* options that repeatedly crashed | Retained but dimmed so you can retry deliberately; not recommended unattended |

Retention defaults are conservative: the oldest clips/photos are deleted only when free space
drops below the configured percentage (50% by default), so weeks of footage can accumulate on a
64 GB phone.

## Recording modes

* **Motion** (default) — record only when the camera sees movement, then keep going for the
  configured tail (15 s). Sensitivity is a 1–10 slider. Cheapest on battery and storage.
* **Continuous** — rolling clips back to back regardless of motion (clip length configurable).
* **Photo-only** — no video stream at all; bursts of full-res stills while motion is detected,
  tapering to a slow cadence. For hardware too weak to run the video pipeline reliably.

Media type (video vs photo-only) and trigger (motion vs continuous) are independent, so all four
combinations are available.

## Remote access (optional)

Everything works with **zero cloud**: local recording, browsing, sharing and retention never touch
a network. If you do want off-site backup or to check in from anywhere, the app speaks two simple,
well-documented things to *your* server:

* file uploads over plain `HTTPS PUT` (with a bearer device token), and
* a live view as a `WebSocket` of JPEG frames + JSON status, sent only while someone is watching.

Any server you control can implement that — a NAS, a Raspberry Pi, a $5 VPS. As a suggestion, a
complete, free-tier-friendly **Cloudflare Worker** is included in [`cloudflare/`](cloudflare/README.md):
password (+ optional TOTP) protected web view, R2 storage with retention, D1 catalog, optional
email digests and offline alerts, and an APK download page for cable-free installs. Cloudflare is
*one* option, not a requirement; nothing in the app depends on it.

Rotating the device token on your server instantly cuts off a phone that was lost or stolen.

## Security model

* No accounts, no telemetry, no analytics, no third-party SDKs.
* All remote traffic is TLS-encrypted (HTTPS uploads, WSS live view).
* Footage lives in app-private storage; Android backup is disabled (`allowBackup=false`).
* Optional PIN (salted hash stored on-device) blocks the app UI without stopping recording.
* The phone authenticates to your server with a bearer token; viewers sign in with a password and
  can optionally enroll a TOTP authenticator app per browser. Sessions are HMAC-signed,
  `HttpOnly`, `Secure` cookies; all comparisons are constant-time.
* Putting a reverse proxy with its own auth (e.g. a Cloudflare Access policy) in front of your
  server adds another layer without touching the app.

## How it works

```
android-security-cam/
├── app/           Android app (Kotlin, CameraX, Jetpack Compose) — open the repo root in Android Studio
└── cloudflare/    Optional Worker: R2 uploads + retention, Durable Object live view, web UI
```

* `camera/CameraService` — foreground service that binds the camera at the best resolution the
  device actually delivers, runs motion analysis on a 640×480 stream, and writes clips/stills via
  `storage/ClipStore` (built-in or SD).
* `camera/DeviceProfile` — detects low-RAM hardware and switches to a reduced-load profile
  (720p/2 Mbps, slower motion analysis, lower live-view fps, serial uploads, camera auto-reopen).
* `settings/Settings` — every tunable, persisted and applied live; the UI greys out what the
  device can't do.
* Files are named by local start time, e.g. `videos/2026-10-05_17-03-22_motion.mp4`,
  `photos/2026-10-05_17-03-25_photo.jpg`.

## License

[MIT](LICENSE) — use it, fork it, ship it.

