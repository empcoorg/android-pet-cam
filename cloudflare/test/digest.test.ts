import { env } from "cloudflare:test";
import { beforeEach, describe, expect, it } from "vitest";
import schema from "../schema.sql?raw";
import { sendDigest } from "../src/intel";

/** The Worker's Env with a fake mailbox, so a digest is observed rather than sent. */
function withMailbox(sent: unknown[]): Env {
  const EMAIL = { send: async (m: unknown) => { sent.push(m); } } as unknown as SendEmail;
  return { ...env, EMAIL, DIGEST_TO: "owner@example.test", DIGEST_FROM: "camera@example.test" };
}

describe("daily digest", () => {
  beforeEach(async () => {
    for (const stmt of schema.split(";")) {
      if (stmt.trim()) await env.DB.prepare(stmt).run();
    }
  });

  it("is disabled when email is not configured", async () => {
    expect(await sendDigest(env)).toMatch(/disabled/);
  });

  it("is skipped, not sent, when nothing was flagged in the last 24 h", async () => {
    const sent: unknown[] = [];
    await env.DB.prepare(
      "INSERT INTO important (key, type, ts, score, labels, description, created) VALUES (?, ?, ?, 1, '[]', '', ?)",
    ).bind("photos/old.jpg", "photo", Date.now() - 48 * 3600_000, Date.now()).run();

    expect(await sendDigest(withMailbox(sent))).toMatch(/skipped/);
    expect(sent).toHaveLength(0);
  });

  it("sends one email listing what was flagged", async () => {
    const sent: Array<{ subject: string; to: string }> = [];
    await env.DB.prepare(
      "INSERT INTO important (key, type, ts, score, labels, description, created) VALUES (?, ?, ?, 1, ?, ?, ?)",
    ).bind("photos/2026-10-01_12-00-00_photo.jpg", "photo", Date.now() - 3600_000, '["cat"]', "A cat on the sofa", Date.now()).run();

    expect(await sendDigest(withMailbox(sent))).toMatch(/sent: 1 item/);
    expect(sent).toHaveLength(1);
    expect(sent[0]!.to).toBe("owner@example.test");
    expect(sent[0]!.subject).toContain("1 important item");
  });
});
