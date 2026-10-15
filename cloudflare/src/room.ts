import { DurableObject } from "cloudflare:workers";
import { sendCameraAlert } from "./alerts";

const REMINDER_MS = 12 * 60 * 60 * 1000;

/**
 * One room per camera. The phone connects with tag "device"; browsers connect with tag "viewer".
 * Binary frames (JPEG) and text status (JSON) from the device are fanned out to every viewer.
 * Viewer text messages of {type:"cmd"} are forwarded to the device. The device is told how
 * many viewers are connected so it only streams when someone is watching.
 */
export class CameraRoom extends DurableObject<Env> {
  private lastFrame: ArrayBuffer | null = null;
  private lastSeenSaved = 0;

  /** No heartbeat for this long = offline (the phone sends status every 15 s). */
  private offlineMs(): number {
    const m = Number(this.env.OFFLINE_ALERT_MINUTES ?? "10");
    return (Number.isFinite(m) && m > 0 ? m : 10) * 60_000;
  }

  /** Record a heartbeat; persisted at most once a minute. */
  private async heartbeat(force = false): Promise<void> {
    const now = Date.now();
    if (!force && now - this.lastSeenSaved < 60_000) return;
    this.lastSeenSaved = now;
    await this.ctx.storage.put("lastSeen", now);
    const alarm = await this.ctx.storage.getAlarm();
    if (alarm === null) await this.ctx.storage.setAlarm(now + this.offlineMs());
  }

  /** Offline watchdog: fires after the last heartbeat; emails once, then a reminder every 12 h. */
  async alarm(): Promise<void> {
    const now = Date.now();
    let lastSeen = (await this.ctx.storage.get<number>("lastSeen")) ?? 0;
    if (!lastSeen) {
      try { lastSeen = Number((JSON.parse((await this.ctx.storage.get<string>("status")) ?? "{}") as { ts?: number }).ts) || 0; } catch { /* */ }
    }
    if (this.ctx.getWebSockets("device").length > 0 && !lastSeen) lastSeen = now;
    const off = this.offlineMs();
    if (lastSeen && now - lastSeen < off) {
      await this.ctx.storage.setAlarm(lastSeen + off);
      return;
    }
    const alertedAt = await this.ctx.storage.get<number>("alertedAt");
    if (alertedAt && now - alertedAt < REMINDER_MS) {
      await this.ctx.storage.setAlarm(alertedAt + REMINDER_MS);
      return;
    }
    const status = await this.ctx.storage.get<string>("status");
    try {
      await sendCameraAlert(this.env, alertedAt ? "reminder" : "offline", lastSeen, status);
      console.log(JSON.stringify({ level: "warn", event: "camera_offline_alert", lastSeen, reminder: !!alertedAt }));
    } catch (e) {
      console.error(JSON.stringify({ level: "error", event: "offline_alert_failed", message: String(e) }));
    }
    if (!(await this.ctx.storage.get<number>("downSince"))) await this.ctx.storage.put("downSince", lastSeen || now);
    await this.ctx.storage.put("alertedAt", now);
    await this.ctx.storage.setAlarm(now + REMINDER_MS);
  }

  /** Device (re)connected: if an offline alert went out, say it is back. */
  private async deviceBack(): Promise<void> {
    await this.heartbeat(true);
    const alertedAt = await this.ctx.storage.get<number>("alertedAt");
    if (!alertedAt) return;
    const downSince = await this.ctx.storage.get<number>("downSince");
    await this.ctx.storage.delete(["alertedAt", "downSince"]);
    await this.ctx.storage.setAlarm(Date.now() + this.offlineMs());
    try {
      await sendCameraAlert(this.env, "online", Date.now(), await this.ctx.storage.get<string>("status"), downSince);
    } catch (e) {
      console.error(JSON.stringify({ level: "error", event: "online_alert_failed", message: String(e) }));
    }
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    // Cron: make sure the offline watchdog is armed even if the camera never reconnected since deploy.
    if (request.method === "POST" && url.pathname.endsWith("/ensure-watch")) {
      if ((await this.ctx.storage.getAlarm()) === null) await this.ctx.storage.setAlarm(Date.now() + 1_000);
      return new Response("ok");
    }
    // Server-side command push (from the Worker): deliver a command to the device socket(s).
    if (request.method === "POST" && url.pathname.endsWith("/command")) {
      const body = await request.text();
      let n = 0;
      for (const d of this.ctx.getWebSockets("device")) { safeSend(d, body); n++; }
      return new Response(JSON.stringify({ delivered: n }), { headers: { "content-type": "application/json" } });
    }
    const role = url.searchParams.get("role");
    if (role !== "device" && role !== "viewer") return new Response("bad role", { status: 400 });
    if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    if (role === "device") {
      for (const old of this.ctx.getWebSockets("device")) {
        try { old.close(1000, "replaced by new device connection"); } catch { /* ignore */ }
      }
    }
    this.ctx.acceptWebSocket(server, [role]);

    if (role === "viewer") {
      const status = await this.ctx.storage.get<string>("status");
      if (status) safeSend(server, status);
      safeSend(server, JSON.stringify({ type: "device", online: this.deviceOnline() }));
      if (this.lastFrame) safeSend(server, this.lastFrame);
      this.notifyViewerCount();
    } else {
      safeSend(server, JSON.stringify({ type: "viewers", count: this.viewerCount() }));
      this.broadcastViewers(JSON.stringify({ type: "device", online: true }));
      this.ctx.waitUntil(this.deviceBack());
    }
    return new Response(null, { status: 101, webSocket: client });
  }

  private viewerCount(): number {
    return this.ctx.getWebSockets("viewer").length;
  }

  private deviceOnline(): boolean {
    return this.ctx.getWebSockets("device").length > 0;
  }

  private notifyViewerCount(): void {
    const msg = JSON.stringify({ type: "viewers", count: this.viewerCount() });
    for (const d of this.ctx.getWebSockets("device")) safeSend(d, msg);
  }

  private broadcastViewers(msg: string | ArrayBuffer): void {
    for (const v of this.ctx.getWebSockets("viewer")) safeSend(v, msg);
  }

  async webSocketMessage(ws: WebSocket, message: string | ArrayBuffer): Promise<void> {
    const tags = this.ctx.getTags(ws);
    if (tags.includes("device")) {
      await this.heartbeat();
      if (typeof message === "string") {
        if (message.includes('"type":"status"')) await this.ctx.storage.put("status", message);
        this.broadcastViewers(message);
      } else {
        this.lastFrame = message;
        this.broadcastViewers(message);
      }
      return;
    }
    if (typeof message !== "string") return;
    let parsed: unknown;
    try { parsed = JSON.parse(message); } catch { return; }
    if (isCommand(parsed)) {
      for (const d of this.ctx.getWebSockets("device")) safeSend(d, message);
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    try { ws.close(code, reason); } catch { /* reserved code or already closed */ }
    this.afterClose(ws);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    this.afterClose(ws);
  }

  /** The closing socket can still be listed while its close handler runs: count without it. */
  private afterClose(ws: WebSocket): void {
    const tags = this.ctx.getTags(ws);
    if (tags.includes("viewer")) {
      const count = this.ctx.getWebSockets("viewer").filter((v) => v !== ws).length;
      const msg = JSON.stringify({ type: "viewers", count });
      for (const d of this.ctx.getWebSockets("device")) safeSend(d, msg);
    } else {
      const online = this.ctx.getWebSockets("device").some((d) => d !== ws);
      this.broadcastViewers(JSON.stringify({ type: "device", online }));
    }
  }
}

function isCommand(x: unknown): boolean {
  return typeof x === "object" && x !== null && (x as { type?: unknown }).type === "cmd";
}

function safeSend(ws: WebSocket, msg: string | ArrayBuffer): void {
  try { ws.send(msg); } catch { /* socket already gone */ }
}
