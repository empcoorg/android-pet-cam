import { SELF } from "cloudflare:test";

/** Headers the phone sends to open its live-view socket. */
export const deviceHeaders = { Upgrade: "websocket", Authorization: "Bearer test-device-token" };

/** A signed viewer session cookie, exactly as /login would issue it. */
export async function viewerHeaders(): Promise<Record<string, string>> {
  const expires = Date.now() + 3600_000;
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode("test-session-secret"), { name: "HMAC", hash: "SHA-256" }, false, ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(String(expires)));
  const hex = [...new Uint8Array(sig)].map((b) => b.toString(16).padStart(2, "0")).join("");
  return { Upgrade: "websocket", Cookie: `session=${expires}.${hex}` };
}

const opened: WebSocket[] = [];

/** Close every socket a test opened: the room keeps live sockets across tests, storage isolation does not cover them. */
export async function closeAll(): Promise<void> {
  for (const ws of opened.splice(0)) { try { ws.close(1000, "test over"); } catch { /* already closed */ } }
  await new Promise((r) => setTimeout(r, 20));
}

/** Open a WebSocket through the Worker and read its text messages in order. */
export async function connect(path: string, headers: Record<string, string>) {
  const res = await SELF.fetch("https://camera.test" + path, { headers });
  if (res.status !== 101 || !res.webSocket) throw new Error(`${path}: HTTP ${res.status}`);
  const ws = res.webSocket;
  ws.accept();
  opened.push(ws);
  const queue: string[] = [];
  const waiting: Array<(s: string) => void> = [];
  ws.addEventListener("message", (e: MessageEvent) => {
    if (typeof e.data !== "string") return;
    const w = waiting.shift();
    if (w) w(e.data); else queue.push(e.data);
  });
  return {
    ws,
    /** Next text message as parsed JSON; fails the test after 2 s of silence. */
    next: (): Promise<Record<string, unknown>> => new Promise((resolve, reject) => {
      const q = queue.shift();
      if (q !== undefined) return resolve(JSON.parse(q));
      const t = setTimeout(() => reject(new Error(`no message on ${path} within 2 s`)), 2000);
      waiting.push((s) => { clearTimeout(t); resolve(JSON.parse(s)); });
    }),
  };
}
