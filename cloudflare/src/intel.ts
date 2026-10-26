import { sendDeviceCommand } from "./index";

// Security-relevant things the digest cares about. The vision model's free-text answer is
// scanned for these; any hit makes the frame "important".
const KEYWORDS = [
  "person", "people", "man", "woman", "child", "human", "face", "someone", "intruder",
  "vehicle", "car", "truck", "van", "motorcycle", "bike", "bicycle",
  "dog", "cat", "animal", "package", "parcel", "box", "delivery",
];

/** File names carry the phone's local wall-clock time; this is that time read as if it were UTC. */
function parseNaive(key: string): number | null {
  const m = /(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})-(\d{2})/.exec(key);
  if (!m) return null;
  return Date.UTC(+m[1]!, +m[2]! - 1, +m[3]!, +m[4]!, +m[5]!, +m[6]!);
}

/** Camera's UTC offset in minutes (e.g. -360 for Mexico City). */
export function tzOffsetMin(env: Env): number {
  const n = Number(env.CAMERA_UTC_OFFSET_MINUTES ?? "-360");
  return Number.isFinite(n) ? n : -360;
}

/** Real epoch ms of a key's capture time. */
function parseTs(env: Env, key: string): number {
  const naive = parseNaive(key);
  return naive === null ? Date.now() : naive - tzOffsetMin(env) * 60_000;
}

function naivePrefix(naiveMs: number): string {
  return new Date(naiveMs).toISOString().slice(0, 13).replace("T", "_"); // YYYY-MM-DD_HH
}

/** Ask the vision model what is in the frame; returns a verdict or null on failure. */
async function classify(env: Env, bytes: ArrayBuffer): Promise<{ important: boolean; labels: string[]; description: string; people: number } | null> {
  if (!env.AI) return null;
  try {
    const res = (await env.AI.run("@cf/llava-hf/llava-1.5-7b-hf", {
      image: [...new Uint8Array(bytes)],
      prompt:
        "You are a pet camera analyst. Briefly describe what you see. " +
        "How many people are visible? Does the image contain a person, a vehicle, an animal, or a delivery/package? " +
        "Start your answer with 'PEOPLE: <number>' then 'IMPORTANT: yes' or 'IMPORTANT: no'.",
      max_tokens: 120,
    })) as { description?: string };
    const text = (res.description ?? "").trim();
    const lower = text.toLowerCase();
    const labels = [...new Set(KEYWORDS.filter((k) => lower.includes(k)))];
    const saysYes = /important:\s*yes/.test(lower);
    const saysNo = /important:\s*no/.test(lower);
    const important = saysYes || (!saysNo && labels.length > 0);
    const pm = /people:\s*(\d+)/.exec(lower);
    let people = pm ? parseInt(pm[1]!, 10) : 0;
    // Fallback: plural wording implies 2+ even if the model skipped the count.
    if (!pm && /\b(people|men|women|two|three|several|group|crowd)\b/.test(lower)) people = 2;
    return { important, labels, description: text.slice(0, 500), people };
  } catch (err) {
    console.error(JSON.stringify({ level: "error", event: "ai_classify", message: String(err) }));
    return null;
  }
}

/** Analyze one uploaded photo; if important, catalog it and the clip(s) it belongs to. */
export async function analyzePhoto(env: Env, key: string, bytes: ArrayBuffer, capturedAt?: number): Promise<void> {
  if (!key.startsWith("photos/")) return;
  const verdict = await classify(env, bytes);
  if (!verdict) return;
  // People-triggered audio: 2+ people -> tell the phone to record sound for a cooldown window.
  if (verdict.people >= 2) {
    const n = await sendDeviceCommand(env, { type: "cmd", name: "setAudio", value: "on", seconds: 120 });
    console.log(JSON.stringify({ level: "info", event: "audio_on", people: verdict.people, delivered: n }));
  }
  if (!verdict.important) return;
  const ts = capturedAt ?? parseTs(env, key);
  const now = Date.now();
  const labels = JSON.stringify(verdict.labels);

  const rows: Array<{ key: string; type: string }> = [{ key, type: "photo" }];
  // The clip(s) this still belongs to: videos that started up to 10 min before (or 1 min after) the
  // photo. Both names are in the phone's local time, so compare them in that frame; look in this
  // hour and the previous one so clips that began before the hour boundary are found too.
  try {
    const pn = parseNaive(key) ?? (ts + tzOffsetMin(env) * 60_000);
    const prefixes = [...new Set([naivePrefix(pn - 3_600_000), naivePrefix(pn)])];
    for (const d of prefixes) {
      const page = await env.BUCKET.list({ prefix: `videos/${d}`, limit: 1000 });
      for (const o of page.objects) {
        const vt = parseNaive(o.key);
        if (vt !== null && vt <= pn + 60_000 && vt >= pn - 10 * 60_000) rows.push({ key: o.key, type: "video" });
      }
    }
  } catch { /* clip association is best-effort */ }

  const stmt = env.DB.prepare(
    "INSERT OR IGNORE INTO important (key, type, ts, score, labels, description, created) VALUES (?, ?, ?, ?, ?, ?, ?)",
  );
  await env.DB.batch(
    rows.map((r) => stmt.bind(r.key, r.type, ts, 1, labels, verdict.description, now)),
  );
  console.log(JSON.stringify({ level: "info", event: "important", key, labels: verdict.labels, clips: rows.length - 1 }));
}

/** Keys currently flagged important (for prune protection and gallery badges). */
export async function importantKeys(env: Env): Promise<Set<string>> {
  try {
    const r = await env.DB.prepare("SELECT key FROM important").all<{ key: string }>();
    return new Set(r.results.map((x) => x.key));
  } catch {
    return new Set();
  }
}

/** Drop catalog rows whose object no longer exists (e.g. recycled). */
export async function forgetKeys(env: Env, keys: string[]): Promise<void> {
  if (keys.length === 0) return;
  const stmt = env.DB.prepare("DELETE FROM important WHERE key = ?");
  await env.DB.batch(keys.map((k) => stmt.bind(k)));
}

interface ImportantRow { key: string; type: string; ts: number; labels: string; description: string }

/** Build and send the 24-hour digest email. Returns a short status string. */
export async function sendDigest(env: Env): Promise<string> {
  if (!env.EMAIL || !env.DIGEST_TO || !env.DIGEST_FROM) return "digest disabled (no EMAIL binding / DIGEST_TO)";
  const since = Date.now() - 24 * 60 * 60 * 1000;
  const r = await env.DB.prepare(
    "SELECT key, type, ts, labels, description FROM important WHERE ts >= ? ORDER BY ts DESC LIMIT 200",
  ).bind(since).all<ImportantRow>();
  const rows = r.results;
  // A quiet day is not worth an email; the offline watchdog covers a camera that has stopped.
  if (rows.length === 0) return "nothing important in the last 24h: digest skipped";
  const base = (env.PUBLIC_URL ?? "").replace(/\/$/, "");
  const photos = rows.filter((x) => x.type === "photo").length;
  const clips = rows.filter((x) => x.type === "video").length;
  const when = new Date().toUTCString();

  const labelCounts = new Map<string, number>();
  for (const x of rows) {
    try { for (const l of JSON.parse(x.labels) as string[]) labelCounts.set(l, (labelCounts.get(l) ?? 0) + 1); } catch { /* */ }
  }
  const topLabels = [...labelCounts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 6)
    .map(([l, n]) => `${l} (${n})`).join(", ") || "none";

  const esc = (s: string) => s.replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]!));
  const off = tzOffsetMin(env);
  const zone = "UTC" + (off >= 0 ? "+" : "-") + Math.abs(off / 60);
  const fmt = (ts: number) => new Date(ts + off * 60_000).toISOString().replace("T", " ").slice(0, 19) + " " + zone;
  const list = rows.slice(0, 40).map((x) => {
    const link = `${base}/api/clip/${encodeURIComponent(x.key)}`;
    return `<tr><td style="padding:6px 10px;border-bottom:1px solid #eee">${fmt(x.ts)}</td>`
      + `<td style="padding:6px 10px;border-bottom:1px solid #eee">${x.type}</td>`
      + `<td style="padding:6px 10px;border-bottom:1px solid #eee">${esc(x.description || "")}</td>`
      + `<td style="padding:6px 10px;border-bottom:1px solid #eee"><a href="${link}">open</a></td></tr>`;
  }).join("");

  const gallery = `${base}/?important=1`;
  const html = `<div style="font-family:system-ui,Arial,sans-serif;max-width:680px;margin:0 auto;color:#111">
    <h2 style="margin:0 0 4px">Pet Cam daily digest</h2>
    <p style="color:#555;margin:0 0 16px">${when}</p>
    <p><b>${rows.length}</b> important item(s) in the last 24h — <b>${photos}</b> photo(s), <b>${clips}</b> clip(s).<br>
    Detected: ${esc(topLabels)}.</p>
    <p><a href="${gallery}" style="display:inline-block;background:#3b82f6;color:#fff;padding:10px 16px;border-radius:8px;text-decoration:none">View camera &amp; important footage</a></p>
    ${rows.length ? `<table style="border-collapse:collapse;width:100%;font-size:14px;margin-top:12px">
      <tr><th align="left" style="padding:6px 10px;border-bottom:2px solid #ddd">When</th>
      <th align="left" style="padding:6px 10px;border-bottom:2px solid #ddd">Type</th>
      <th align="left" style="padding:6px 10px;border-bottom:2px solid #ddd">What the camera saw</th>
      <th style="padding:6px 10px;border-bottom:2px solid #ddd"></th></tr>${list}</table>
      <p style="color:#888;font-size:12px;margin-top:10px">Links require your viewer sign-in. Showing up to 40 of ${rows.length}.</p>`
    : `<p style="color:#555">Nothing flagged important today. The camera is still recording and storing footage normally.</p>`}
  </div>`;
  const text = `Pet Cam daily digest — ${when}\n${rows.length} important item(s): ${photos} photos, ${clips} clips.\n`
    + `Detected: ${topLabels}\nView: ${gallery}\n`;

  await env.EMAIL.send({
    to: env.DIGEST_TO,
    from: env.DIGEST_FROM,
    subject: `Pet Cam: ${rows.length} important item(s) in the last 24h`,
    html,
    text,
  });
  await env.DB.prepare("INSERT OR REPLACE INTO meta (k, v) VALUES ('last_digest', ?)")
    .bind(String(Date.now())).run();
  return `sent: ${rows.length} items to ${env.DIGEST_TO}`;
}
