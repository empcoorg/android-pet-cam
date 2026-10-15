/** Camera offline / back-online emails. Kept free of other imports so the Durable Object can use it. */

function zoneFmt(env: Env, ts: number): string {
  const off = Number(env.CAMERA_UTC_OFFSET_MINUTES ?? "-360") || 0;
  const zone = "UTC" + (off >= 0 ? "+" : "-") + Math.abs(off / 60);
  return new Date(ts + off * 60_000).toISOString().replace("T", " ").slice(0, 19) + " " + zone;
}

function minutes(ms: number): string {
  const m = Math.round(ms / 60_000);
  return m < 90 ? `${m} min` : `${(m / 60).toFixed(1)} h`;
}

interface LastStatus { battery?: number; charging?: boolean; network?: string; recording?: boolean; label?: string; error?: string | null }

export async function sendCameraAlert(
  env: Env, kind: "offline" | "reminder" | "online", lastSeen: number, statusJson: string | undefined, downSince?: number,
): Promise<void> {
  if (!env.EMAIL || !env.DIGEST_TO || !env.DIGEST_FROM) return;
  let s: LastStatus = {};
  try { s = statusJson ? (JSON.parse(statusJson) as LastStatus) : {}; } catch { /* */ }
  const base = (env.PUBLIC_URL ?? "").replace(/\/$/, "");
  const label = s.label ? ` (${s.label})` : "";
  const now = Date.now();
  const facts = [
    ["Last heard from", lastSeen ? `${zoneFmt(env, lastSeen)} (${minutes(now - lastSeen)} ago)` : "unknown"],
    ["Battery then", s.battery != null && s.battery >= 0 ? `${s.battery}%${s.charging ? " charging" : " on battery"}` : "unknown"],
    ["Network then", s.network ?? "unknown"],
    ["Was recording", s.recording ? "yes" : "no"],
    ...(s.error ? [["Last error", s.error]] : []),
  ] as Array<[string, string]>;

  let subject: string; let lead: string;
  if (kind === "online") {
    const down = downSince ? minutes(now - downSince) : "a while";
    subject = `Pet Cam${label}: camera back online`;
    lead = `The camera reconnected after being unreachable for about ${down}. Footage recorded while it was offline uploads automatically.`;
  } else {
    subject = `Pet Cam${label}: camera OFFLINE${kind === "reminder" ? " (still)" : ""}`;
    lead = kind === "reminder"
      ? `The camera is still not reporting (down ${minutes(now - lastSeen)}).`
      : `The camera has not reported for ${minutes(now - lastSeen)}. It may still be recording locally if only the network is down; ` +
        `otherwise check power, the SIM/Wi-Fi, and that the camera app is open on the phone.`;
  }
  const esc = (x: string) => x.replace(/[&<>]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;" }[c]!));
  const rows = facts.map(([k, v]) => `<tr><td style="padding:4px 12px 4px 0;color:#555">${k}</td><td>${esc(v)}</td></tr>`).join("");
  const html = `<div style="font-family:system-ui,Arial,sans-serif;max-width:620px;color:#111">
    <h2 style="margin:0 0 8px;color:${kind === "online" ? "#15803d" : "#b91c1c"}">${esc(subject)}</h2>
    <p>${esc(lead)}</p><table style="font-size:14px">${rows}</table>
    <p><a href="${base}/">Open the camera page</a></p></div>`;
  const text = `${subject}\n${lead}\n` + facts.map(([k, v]) => `${k}: ${v}`).join("\n") + `\n${base}/\n`;
  await env.EMAIL.send({ to: env.DIGEST_TO, from: env.DIGEST_FROM, subject, html, text });
}
