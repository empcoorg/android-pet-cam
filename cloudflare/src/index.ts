import qrcode from "qrcode-generator";
import { appPage, loginPage, totpSetupPage, totpVerifyPage } from "./page";
import { analyzePhoto, importantKeys, forgetKeys, sendDigest } from "./intel";
import { base32Encode, base32Decode, generateTotpSecret, verifyTotp, otpauthUri } from "./totp";
export { CameraRoom } from "./room";

const DEVICE_PREFIX = "/api/device/";
const CLIP_PREFIX = "/api/clip/";
const ROOM_NAME = "main";

/** Push a command (e.g. setAudio) to the camera device via the room. */
export async function sendDeviceCommand(env: Env, command: object): Promise<number> {
  try {
    const stub = env.ROOM.get(env.ROOM.idFromName(ROOM_NAME));
    const r = await stub.fetch("https://room/command", { method: "POST", body: JSON.stringify(command) });
    const j = (await r.json()) as { delivered?: number };
    return j.delivered ?? 0;
  } catch (e) {
    console.error(JSON.stringify({ level: "error", event: "sendDeviceCommand", message: String(e) }));
    return 0;
  }
}
const APK_KEY = "app/pet-cam.apk";
const APK_VERSION_KEY = "app/version.json";

export default {
  async fetch(request, env, ctx): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname;
    try {
      if (path.startsWith(DEVICE_PREFIX)) return await handleDevice(request, env, ctx, url);
      if (path === "/login" && request.method === "POST") return await handleLogin(request, env);
      if (path === "/logout" && request.method === "POST") return logout();
      if (path === "/totp-setup" && request.method === "GET") return await handleTotpSetupGet(request, env);
      if (path === "/totp-setup" && request.method === "POST") return await handleTotpSetupPost(request, env);
      if (path === "/totp-verify" && request.method === "GET") return await handleTotpVerifyGet(request, env);
      if (path === "/totp-verify" && request.method === "POST") return await handleTotpVerifyPost(request, env);

      const authed = await isViewerAuthed(request, env);
      if (path === "/") {
        return authed ? htmlResponse(appPage()) : htmlResponse(loginPage(url.searchParams.has("error")));
      }
      if (!authed) return json({ error: "unauthorized" }, 401);
      if (path === "/api/devices" && request.method === "GET") return await handleListDevices(request, env);
      if (path.startsWith("/api/devices/") && path.endsWith("/revoke") && request.method === "POST") {
        const id = decodeURIComponent(path.slice("/api/devices/".length, -"/revoke".length));
        return await handleRevokeDevice(request, env, id);
      }

      if (path === "/ws") return await roomFetch(request, env, "viewer");
      if (path === "/app.apk") return await serveApk(env);
      if (path === "/api/setup") {
        return json({ workerUrl: url.origin, deviceToken: env.DEVICE_TOKEN, apkUrl: url.origin + "/app.apk" });
      }
      if (path === "/api/clips" && request.method === "GET") return await listClips(env, url);
      if (path === "/api/digest/test" && request.method === "POST") {
        const r = await sendDigest(env);
        return json({ ok: true, result: r });
      }
      if (path.startsWith(CLIP_PREFIX)) {
        const key = decodeURIComponent(path.slice(CLIP_PREFIX.length));
        if (!validKey(key)) return json({ error: "bad key" }, 400);
        if (request.method === "GET" || request.method === "HEAD") return await serveObject(request, env, key, url);
        if (request.method === "DELETE") {
          await env.BUCKET.delete(key);
          return json({ ok: true });
        }
        return json({ error: "method not allowed" }, 405);
      }
      return json({ error: "not found" }, 404);
    } catch (err) {
      console.error(JSON.stringify({ level: "error", path, message: err instanceof Error ? err.message : String(err) }));
      return json({ error: "internal error" }, 500);
    }
  },

  async scheduled(event, env, ctx): Promise<void> {
    if (event.cron === "0 14 * * *") {
      ctx.waitUntil(sendDigest(env).then(
        (r) => console.log(JSON.stringify({ level: "info", event: "digest", result: r })),
        (e) => console.error(JSON.stringify({ level: "error", event: "digest", message: String(e) })),
      ));
    } else {
      ctx.waitUntil(prune(env));
      ctx.waitUntil((async () => {
        const stub = env.ROOM.get(env.ROOM.idFromName(ROOM_NAME));
        await stub.fetch("https://room/ensure-watch", { method: "POST" });
      })().catch((e) => console.error(JSON.stringify({ level: "error", event: "ensure_watch", message: String(e) }))));
    }
  },
} satisfies ExportedHandler<Env>;

// ------------------------------------------------------------------ device API (bearer token)

async function handleDevice(request: Request, env: Env, ctx: ExecutionContext, url: URL): Promise<Response> {
  const auth = request.headers.get("Authorization") ?? "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
  // DEVICE_TOKEN is the phone's original key; DEVICE_TOKEN_APP is the key built into the APK.
  const ok = !!token && (
    (await timingSafeEqualStrings(token, env.DEVICE_TOKEN)) ||
    (!!env.DEVICE_TOKEN_APP && (await timingSafeEqualStrings(token, env.DEVICE_TOKEN_APP)))
  );
  if (!ok) return json({ error: "unauthorized" }, 401);
  const sub = url.pathname.slice(DEVICE_PREFIX.length);

  if (sub === "ws") return roomFetch(request, env, "device");

  // Over-the-air app updates for the phone.
  if (sub === "app/version" && request.method === "GET") {
    const v = await env.BUCKET.get(APK_VERSION_KEY);
    return v ? new Response(v.body, { headers: { "content-type": "application/json", "cache-control": "no-store" } })
      : json({ versionCode: 0 });
  }
  if (sub === "app/apk" && request.method === "GET") return await serveApk(env);

  const capturedAtHeader = Number(request.headers.get("X-Captured-At") ?? url.searchParams.get("capturedAt") ?? "");
  const capturedAt = Number.isFinite(capturedAtHeader) && capturedAtHeader > 1_500_000_000_000 ? capturedAtHeader : undefined;
  const customMetadata = capturedAt ? { capturedAt: String(capturedAt) } : undefined;

  if (sub.startsWith("upload/") && request.method === "PUT") {
    const key = decodeURIComponent(sub.slice("upload/".length));
    if (!validKey(key)) return json({ error: "bad key" }, 400);
    if (!request.body) return json({ error: "empty body" }, 400);
    const contentType = request.headers.get("Content-Type") ?? guessType(key);
    if (key.startsWith("photos/")) {
      // Buffer the (small) photo so Workers AI can inspect the same bytes we store.
      const bytes = await request.arrayBuffer();
      const obj = await env.BUCKET.put(key, bytes, { httpMetadata: { contentType }, customMetadata });
      ctx.waitUntil(analyzePhoto(env, key, bytes, capturedAt));
      ctx.waitUntil(prune(env));
      return json({ ok: true, key, size: obj.size });
    }
    const obj = await env.BUCKET.put(key, request.body, { httpMetadata: { contentType }, customMetadata });
    ctx.waitUntil(prune(env));
    return json({ ok: true, key, size: obj.size });
  }

  // R2 multipart upload for clips larger than the per-request body limit.
  const key = url.searchParams.get("key") ?? "";
  if (sub === "mpu/create" && request.method === "POST") {
    if (!validKey(key)) return json({ error: "bad key" }, 400);
    const mpu = await env.BUCKET.createMultipartUpload(key, {
      httpMetadata: { contentType: url.searchParams.get("contentType") ?? guessType(key) },
      customMetadata,
    });
    return json({ uploadId: mpu.uploadId });
  }
  if (sub === "mpu/part" && request.method === "PUT") {
    const uploadId = url.searchParams.get("uploadId") ?? "";
    const part = Number(url.searchParams.get("part"));
    if (!validKey(key) || !uploadId || !Number.isInteger(part) || part < 1) return json({ error: "bad params" }, 400);
    if (!request.body) return json({ error: "empty body" }, 400);
    const mpu = env.BUCKET.resumeMultipartUpload(key, uploadId);
    const uploaded = await mpu.uploadPart(part, request.body);
    return json({ partNumber: uploaded.partNumber, etag: uploaded.etag });
  }
  if (sub === "mpu/complete" && request.method === "POST") {
    const uploadId = url.searchParams.get("uploadId") ?? "";
    if (!validKey(key) || !uploadId) return json({ error: "bad params" }, 400);
    const body = (await request.json()) as { parts?: Array<{ partNumber: number; etag: string }> };
    if (!Array.isArray(body.parts) || body.parts.length === 0) return json({ error: "no parts" }, 400);
    const mpu = env.BUCKET.resumeMultipartUpload(key, uploadId);
    const obj = await mpu.complete(body.parts);
    ctx.waitUntil(prune(env));
    return json({ ok: true, key, size: obj.size });
  }
  if (sub === "mpu/abort" && request.method === "POST") {
    const uploadId = url.searchParams.get("uploadId") ?? "";
    if (!validKey(key) || !uploadId) return json({ error: "bad params" }, 400);
    await env.BUCKET.resumeMultipartUpload(key, uploadId).abort();
    return json({ ok: true });
  }
  return json({ error: "not found" }, 404);
}

// ------------------------------------------------------------------ live view room

async function roomFetch(request: Request, env: Env, role: "device" | "viewer"): Promise<Response> {
  if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
    return json({ error: "expected websocket" }, 426);
  }
  const stub = env.ROOM.get(env.ROOM.idFromName(ROOM_NAME));
  const u = new URL(request.url);
  u.pathname = "/ws";
  u.search = "";
  u.searchParams.set("role", role);
  return stub.fetch(new Request(u.toString(), request));
}

// ------------------------------------------------------------------ clips

interface ClipInfo { key: string; size: number; uploaded: string; type: "video" | "photo"; important: boolean }

async function listAll(env: Env): Promise<R2Object[]> {
  const out: R2Object[] = [];
  for (const prefix of ["videos/", "photos/"]) {
    let cursor: string | undefined;
    do {
      const page = await env.BUCKET.list({ prefix, cursor, limit: 1000 });
      out.push(...page.objects);
      cursor = page.truncated ? page.cursor : undefined;
    } while (cursor);
  }
  return out;
}

/** The Android installer, uploaded with `wrangler r2 object put my-camera-clips/app/pet-cam.apk`. */
async function serveApk(env: Env): Promise<Response> {
  const obj = await env.BUCKET.get(APK_KEY);
  if (!obj) return json({ error: "apk not uploaded yet" }, 404);
  return new Response(obj.body, {
    headers: {
      "content-type": "application/vnd.android.package-archive",
      "content-length": String(obj.size),
      "content-disposition": 'attachment; filename="pet-cam.apk"',
      "cache-control": "no-store",
    },
  });
}

async function listClips(env: Env, url: URL): Promise<Response> {
  const objects = await listAll(env);
  const important = await importantKeys(env);
  const onlyImportant = url.searchParams.has("important");
  let clips: ClipInfo[] = objects
    .map((o) => ({
      key: o.key,
      size: o.size,
      uploaded: o.uploaded.toISOString(),
      type: (o.key.startsWith("photos/") ? "photo" : "video") as ClipInfo["type"],
      important: important.has(o.key),
    }))
    // Sort by filename only (not the full key): "photos/" vs "videos/" would otherwise dominate
    // the comparison and group all videos before all photos regardless of actual timestamp.
    .sort((a, b) => {
      const an = a.key.split("/").pop() ?? a.key;
      const bn = b.key.split("/").pop() ?? b.key;
      return an < bn ? 1 : an > bn ? -1 : 0;
    });
  if (onlyImportant) clips = clips.filter((c) => c.important);
  const total = objects.reduce((n, o) => n + o.size, 0);
  return json({
    clips, totalBytes: total, limitBytes: maxBytes(env), importantCount: important.size,
    // So the portal can label timestamps with the camera's own timezone, not the viewer's.
    utcOffsetMinutes: Number(env.CAMERA_UTC_OFFSET_MINUTES ?? "0"),
  });
}

async function serveObject(request: Request, env: Env, key: string, url: URL): Promise<Response> {
  // Need the size first to clamp ranges and build Content-Range reliably.
  const head = await env.BUCKET.head(key);
  if (!head) return json({ error: "not found" }, 404);
  const size = head.size;

  const baseHeaders = (): Headers => {
    const h = new Headers();
    head.writeHttpMetadata(h);
    if (!h.has("content-type")) h.set("content-type", guessType(key));
    h.set("etag", head.httpEtag);
    h.set("accept-ranges", "bytes");
    h.set("cache-control", "private, max-age=3600");
    if (url.searchParams.has("download")) {
      h.set("content-disposition", `attachment; filename="${key.split("/").pop() ?? "clip"}"`);
    }
    return h;
  };

  const rangeHeader = request.headers.get("Range");
  if (rangeHeader) {
    const m = /^bytes=(\d*)-(\d*)$/.exec(rangeHeader.trim());
    if (m && (m[1] || m[2])) {
      let start: number;
      let end: number;
      if (m[1] === "") {
        // suffix: last N bytes
        const n = Math.min(parseInt(m[2]!, 10), size);
        start = size - n;
        end = size - 1;
      } else {
        start = parseInt(m[1]!, 10);
        end = m[2] === "" ? size - 1 : Math.min(parseInt(m[2]!, 10), size - 1);
      }
      if (Number.isFinite(start) && Number.isFinite(end) && start >= 0 && start <= end && start < size) {
        const length = end - start + 1;
        const obj = await env.BUCKET.get(key, { range: { offset: start, length } });
        if (!obj) return json({ error: "not found" }, 404);
        const h = baseHeaders();
        h.set("content-range", `bytes ${start}-${end}/${size}`);
        h.set("content-length", String(length));
        return new Response(request.method === "HEAD" ? null : obj.body, { status: 206, headers: h });
      }
      // Unsatisfiable range
      const h = baseHeaders();
      h.set("content-range", `bytes */${size}`);
      return new Response(null, { status: 416, headers: h });
    }
  }

  const h = baseHeaders();
  h.set("content-length", String(size));
  if (request.method === "HEAD") return new Response(null, { headers: h });
  const obj = await env.BUCKET.get(key);
  if (!obj) return json({ error: "not found" }, 404);
  return new Response(obj.body, { headers: h });
}

/** Delete oldest objects until the bucket is under MAX_STORAGE_BYTES. */
async function prune(env: Env): Promise<void> {
  const limit = maxBytes(env);
  const objects = await listAll(env);
  let total = objects.reduce((n, o) => n + o.size, 0);
  if (total <= limit) return;
  const important = await importantKeys(env);
  // Oldest first, but important footage is recycled last (only if nothing else is left).
  objects.sort((a, b) => {
    const ai = important.has(a.key) ? 1 : 0, bi = important.has(b.key) ? 1 : 0;
    if (ai !== bi) return ai - bi;
    return a.uploaded.getTime() - b.uploaded.getTime();
  });
  const victims: string[] = [];
  for (const o of objects) {
    if (total <= limit) break;
    victims.push(o.key);
    total -= o.size;
  }
  for (let i = 0; i < victims.length; i += 1000) {
    await env.BUCKET.delete(victims.slice(i, i + 1000));
  }
  await forgetKeys(env, victims.filter((k) => important.has(k)));
  console.log(JSON.stringify({ level: "info", event: "prune", deleted: victims.length, remainingBytes: total }));
}

// ------------------------------------------------------------------ viewer auth (password -> signed cookie)
//
// Flow: password always required. A browser that has never passed a TOTP (authenticator app)
// challenge, or whose "device" cookie is missing/expired/revoked, is stepped up to /totp-setup
// (first ever enrollment) or /totp-verify (a code, since TOTP is already enrolled) before it gets
// a session. Passing that step issues a long-lived signed "device" cookie (DEVICE_TRUST_DAYS,
// refreshed on each use) alongside the normal session cookie, recorded in D1 so it can be revoked
// from the portal's Devices panel.

const DEVICE_TRUST_DAYS = 90;
const PENDING_TTL_SECONDS = 5 * 60;
const SETUP_TTL_SECONDS = 10 * 60;

async function handleLogin(request: Request, env: Env): Promise<Response> {
  const form = await request.formData();
  const password = String(form.get("password") ?? "");
  const ok = password.length > 0 && (await timingSafeEqualStrings(password, env.VIEWER_PASSWORD));
  if (!ok) return Response.redirect(new URL("/?error=1", request.url).toString(), 303);

  const deviceId = await readSignedCookie(request, env, "device");
  if (deviceId) {
    const row = await env.DB.prepare("SELECT id FROM devices WHERE id = ? AND revoked = 0 AND expires > ?")
      .bind(deviceId, Date.now()).first<{ id: string }>();
    if (row) {
      await refreshDeviceTrust(env, deviceId);
      return redirectWithCookies(new URL("/", request.url).toString(), [await buildSessionCookie(env)]);
    }
  }

  // Unrecognized, expired, or revoked device: step up before granting a session.
  const pendingCookie = await signedCookie(env, "pending", "ok", PENDING_TTL_SECONDS);
  const enrolled = await env.DB.prepare("SELECT v FROM meta WHERE k = 'totp_secret'").first<{ v: string }>();
  const dest = enrolled ? "/totp-verify" : "/totp-setup";
  return redirectWithCookies(new URL(dest, request.url).toString(), [pendingCookie]);
}

function logout(): Response {
  // Ends the session only; the device stays trusted so a future password-only login skips TOTP.
  return new Response(null, {
    status: 303,
    headers: { Location: "/", "Set-Cookie": "session=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0" },
  });
}

async function isViewerAuthed(request: Request, env: Env): Promise<boolean> {
  const cookie = request.headers.get("Cookie") ?? "";
  const m = /(?:^|;\s*)session=(\d+)\.([0-9a-f]+)/.exec(cookie);
  if (!m) return false;
  const expires = Number(m[1]);
  if (!Number.isFinite(expires) || expires < Date.now()) return false;
  const expected = await hmacHex(env.SESSION_SECRET, String(expires));
  return timingSafeEqualStrings(m[2] ?? "", expected);
}

async function buildSessionCookie(env: Env): Promise<string> {
  const ttl = Number(env.SESSION_TTL_SECONDS ?? "2592000");
  const expires = Date.now() + ttl * 1000;
  const sig = await hmacHex(env.SESSION_SECRET, String(expires));
  return `session=${expires}.${sig}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=${ttl}`;
}

function redirectWithCookies(location: string, cookies: string[]): Response {
  const headers = new Headers({ Location: location });
  for (const c of cookies) headers.append("Set-Cookie", c);
  return new Response(null, { status: 303, headers });
}

/** Sign `purpose:payload|expiresAtMs` so the cookie's own expiry is verified server-side, not just left to the browser's Max-Age. */
async function signedCookie(env: Env, purpose: string, payload: string, maxAgeSeconds: number): Promise<string> {
  const expires = Date.now() + maxAgeSeconds * 1000;
  const body = `${payload}|${expires}`;
  const sig = await hmacHex(env.SESSION_SECRET, `${purpose}:${body}`);
  return `${purpose}=${encodeURIComponent(body)}.${sig}; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=${maxAgeSeconds}`;
}

async function readSignedCookie(request: Request, env: Env, purpose: string): Promise<string | null> {
  const cookie = request.headers.get("Cookie") ?? "";
  const m = new RegExp(`(?:^|;\\s*)${purpose}=([^;]*)`).exec(cookie);
  if (!m || !m[1]) return null;
  const raw = decodeURIComponent(m[1]);
  const dot = raw.lastIndexOf(".");
  if (dot < 0) return null;
  const body = raw.slice(0, dot);
  const sig = raw.slice(dot + 1);
  const expected = await hmacHex(env.SESSION_SECRET, `${purpose}:${body}`);
  if (!(await timingSafeEqualStrings(sig, expected))) return null;
  const bar = body.lastIndexOf("|");
  if (bar < 0) return null;
  const expires = Number(body.slice(bar + 1));
  if (!Number.isFinite(expires) || expires < Date.now()) return null;
  return body.slice(0, bar);
}

function clearCookie(name: string): string {
  return `${name}=; Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=0`;
}

async function refreshDeviceTrust(env: Env, deviceId: string): Promise<void> {
  const now = Date.now();
  const expires = now + DEVICE_TRUST_DAYS * 24 * 60 * 60 * 1000;
  await env.DB.prepare("UPDATE devices SET last_seen = ?, expires = ? WHERE id = ?").bind(now, expires, deviceId).run();
}

/** Record a newly-verified browser as a trusted device; returns its cookie alongside a fresh session cookie. */
async function grantTrustedDevice(request: Request, env: Env): Promise<string[]> {
  const id = crypto.randomUUID();
  const now = Date.now();
  const expires = now + DEVICE_TRUST_DAYS * 24 * 60 * 60 * 1000;
  const label = labelFromUserAgent(request.headers.get("User-Agent") ?? "");
  await env.DB.prepare("INSERT INTO devices (id, label, created, last_seen, expires, revoked) VALUES (?, ?, ?, ?, ?, 0)")
    .bind(id, label, now, now, expires).run();
  const deviceCookie = await signedCookie(env, "device", id, DEVICE_TRUST_DAYS * 24 * 60 * 60);
  return [deviceCookie, await buildSessionCookie(env)];
}

/** Rough, dependency-free browser/OS label for the devices list (not meant to be a precise UA parse). */
function labelFromUserAgent(ua: string): string {
  const os = /iPhone|iPad/.test(ua) ? "iOS" : /Android/.test(ua) ? "Android" : /Mac OS X/.test(ua) ? "macOS"
    : /Windows/.test(ua) ? "Windows" : /Linux/.test(ua) ? "Linux" : "Unknown OS";
  const browser = /Edg\//.test(ua) ? "Edge" : /OPR\//.test(ua) ? "Opera" : /Chrome\//.test(ua) ? "Chrome"
    : /CriOS\//.test(ua) ? "Chrome" : /Firefox\//.test(ua) ? "Firefox" : /Safari\//.test(ua) ? "Safari" : "Unknown browser";
  return `${browser} on ${os}`;
}

// ------------------------------------------------------------------ TOTP enrollment & step-up verification

async function handleTotpSetupGet(request: Request, env: Env): Promise<Response> {
  const pending = await readSignedCookie(request, env, "pending");
  if (!pending) return Response.redirect(new URL("/", request.url).toString(), 303);

  const enrolled = await env.DB.prepare("SELECT v FROM meta WHERE k = 'totp_secret'").first<{ v: string }>();
  if (enrolled) return Response.redirect(new URL("/totp-verify", request.url).toString(), 303);

  let secretBase32 = await readSignedCookie(request, env, "totpsetup");
  let setupCookie: string | null = null;
  if (!secretBase32) {
    secretBase32 = base32Encode(generateTotpSecret());
    setupCookie = await signedCookie(env, "totpsetup", secretBase32, SETUP_TTL_SECONDS);
  }

  // Distinguish this deployment in the authenticator app (e.g. "Pet Cam (kitchen)") since each
  // camera is its own portal with its own TOTP secret. CAMERA_LABEL is set per environment.
  const uri = otpauthUri(secretBase32, "viewer", `Pet Cam (${env.CAMERA_LABEL ?? "camera"})`);
  const html = totpSetupPage(uri, secretBase32, renderQrSvg(uri), urlHasError(request));
  return htmlResponse(html, setupCookie ?? undefined);
}

async function handleTotpSetupPost(request: Request, env: Env): Promise<Response> {
  const pending = await readSignedCookie(request, env, "pending");
  if (!pending) return Response.redirect(new URL("/", request.url).toString(), 303);
  const secretBase32 = await readSignedCookie(request, env, "totpsetup");
  if (!secretBase32) return Response.redirect(new URL("/totp-setup", request.url).toString(), 303);

  const form = await request.formData();
  const code = String(form.get("code") ?? "");
  const ok = await verifyTotp(base32Decode(secretBase32), code);
  if (!ok) return Response.redirect(new URL("/totp-setup?error=1", request.url).toString(), 303);

  await env.DB.prepare("INSERT OR REPLACE INTO meta (k, v) VALUES ('totp_secret', ?)").bind(secretBase32).run();
  const grant = await grantTrustedDevice(request, env);
  return redirectWithCookies(new URL("/", request.url).toString(), [...grant, clearCookie("pending"), clearCookie("totpsetup")]);
}

async function handleTotpVerifyGet(request: Request, env: Env): Promise<Response> {
  const pending = await readSignedCookie(request, env, "pending");
  if (!pending) return Response.redirect(new URL("/", request.url).toString(), 303);
  const enrolled = await env.DB.prepare("SELECT v FROM meta WHERE k = 'totp_secret'").first<{ v: string }>();
  if (!enrolled) return Response.redirect(new URL("/totp-setup", request.url).toString(), 303);
  return htmlResponse(totpVerifyPage(urlHasError(request)));
}

async function handleTotpVerifyPost(request: Request, env: Env): Promise<Response> {
  const pending = await readSignedCookie(request, env, "pending");
  if (!pending) return Response.redirect(new URL("/", request.url).toString(), 303);
  const row = await env.DB.prepare("SELECT v FROM meta WHERE k = 'totp_secret'").first<{ v: string }>();
  if (!row) return Response.redirect(new URL("/totp-setup", request.url).toString(), 303);

  const form = await request.formData();
  const code = String(form.get("code") ?? "");
  const ok = await verifyTotp(base32Decode(row.v), code);
  if (!ok) return Response.redirect(new URL("/totp-verify?error=1", request.url).toString(), 303);

  const grant = await grantTrustedDevice(request, env);
  return redirectWithCookies(new URL("/", request.url).toString(), [...grant, clearCookie("pending")]);
}

function urlHasError(request: Request): boolean {
  return new URL(request.url).searchParams.has("error");
}

function renderQrSvg(data: string): string {
  const qr = qrcode(0, "M");
  qr.addData(data);
  qr.make();
  return qr.createSvgTag({ scalable: true });
}

// ------------------------------------------------------------------ trusted-device management (authenticated)

async function handleListDevices(request: Request, env: Env): Promise<Response> {
  const currentDeviceId = await readSignedCookie(request, env, "device");
  const r = await env.DB.prepare(
    "SELECT id, label, created, last_seen, expires FROM devices WHERE revoked = 0 ORDER BY last_seen DESC",
  ).all<{ id: string; label: string; created: number; last_seen: number; expires: number }>();
  const devices = r.results.map((d) => ({ ...d, isThisDevice: d.id === currentDeviceId }));
  return json({ devices });
}

async function handleRevokeDevice(request: Request, env: Env, id: string): Promise<Response> {
  if (!id) return json({ error: "bad id" }, 400);
  await env.DB.prepare("UPDATE devices SET revoked = 1 WHERE id = ?").bind(id).run();
  const currentDeviceId = await readSignedCookie(request, env, "device");
  if (currentDeviceId === id) {
    const headers = new Headers({ "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
    headers.append("Set-Cookie", clearCookie("device"));
    return new Response(JSON.stringify({ ok: true }), { headers });
  }
  return json({ ok: true });
}

async function hmacHex(secret: string, data: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(data));
  return [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Constant-time string comparison (hashes both sides so lengths never leak). */
async function timingSafeEqualStrings(a: string, b: string): Promise<boolean> {
  const enc = new TextEncoder();
  const [ha, hb] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(a)),
    crypto.subtle.digest("SHA-256", enc.encode(b)),
  ]);
  return crypto.subtle.timingSafeEqual(ha, hb);
}

// ------------------------------------------------------------------ helpers

function validKey(key: string): boolean {
  return /^(videos|photos)\/[A-Za-z0-9._-]{1,200}$/.test(key) && !key.includes("..");
}

function guessType(key: string): string {
  return key.endsWith(".mp4") ? "video/mp4" : key.endsWith(".jpg") ? "image/jpeg" : "application/octet-stream";
}

function maxBytes(env: Env): number {
  const n = Number(env.MAX_STORAGE_BYTES ?? "5368709120");
  return Number.isFinite(n) && n > 0 ? n : 5368709120;
}

function json(data: unknown, status = 200): Response {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" },
  });
}

function htmlResponse(html: string, setCookie?: string): Response {
  const headers = new Headers({
    "content-type": "text/html; charset=utf-8",
    "cache-control": "no-store",
    "x-frame-options": "DENY",
    "referrer-policy": "no-referrer",
    "content-security-policy":
      "default-src 'self'; img-src 'self' blob: data:; media-src 'self' blob:; " +
      "connect-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; " +
      "frame-ancestors 'none'; form-action 'self'",
  });
  if (setCookie) headers.append("Set-Cookie", setCookie);
  return new Response(html, { headers });
}
