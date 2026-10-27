import { SELF } from "cloudflare:test";
import { afterEach, describe, expect, it } from "vitest";
import { closeAll, connect, deviceHeaders, viewerHeaders } from "./helpers";

describe("live-view room", () => {
  afterEach(closeAll);

  it("rejects a phone with the wrong device token", async () => {
    const res = await SELF.fetch("https://camera.test/api/device/ws", {
      headers: { Upgrade: "websocket", Authorization: "Bearer not-the-token" },
    });
    expect(res.status).toBe(401);
  });

  it("rejects a viewer without a signed session", async () => {
    const res = await SELF.fetch("https://camera.test/ws", { headers: { Upgrade: "websocket" } });
    expect(res.status).toBe(401);
  });

  it("tells viewers when the camera connects, reports, and leaves", async () => {
    const viewer = await connect("/ws", await viewerHeaders());
    expect(await viewer.next()).toEqual({ type: "device", online: false });

    const phone = await connect("/api/device/ws", deviceHeaders);
    expect(await phone.next()).toEqual({ type: "viewers", count: 1 });
    expect(await viewer.next()).toMatchObject({ type: "device", online: true });

    phone.ws.send(JSON.stringify({ type: "status", label: "test", battery: 80 }));
    expect(await viewer.next()).toMatchObject({ type: "status", label: "test", battery: 80 });

    phone.ws.close(1000, "bye");
    expect(await viewer.next()).toMatchObject({ type: "device", online: false });
  });

  it("replays the last status to a viewer who joins later", async () => {
    const phone = await connect("/api/device/ws", deviceHeaders);
    phone.ws.send(JSON.stringify({ type: "status", label: "later" }));
    // Give the room a moment to store it before the viewer asks.
    await new Promise((r) => setTimeout(r, 50));
    const viewer = await connect("/ws", await viewerHeaders());
    expect(await viewer.next()).toMatchObject({ type: "status", label: "later" });
    expect(await viewer.next()).toMatchObject({ type: "device", online: true });
  });

  it("forwards viewer commands to the phone and nothing else", async () => {
    const phone = await connect("/api/device/ws", deviceHeaders);
    await phone.next(); // viewers: 0
    const viewer = await connect("/ws", await viewerHeaders());
    await viewer.next(); // device: online
    expect(await phone.next()).toEqual({ type: "viewers", count: 1 });

    viewer.ws.send(JSON.stringify({ type: "chatter", name: "ignored" }));
    viewer.ws.send(JSON.stringify({ type: "cmd", name: "snapshot" }));
    expect(await phone.next()).toEqual({ type: "cmd", name: "snapshot" });
  });
});
