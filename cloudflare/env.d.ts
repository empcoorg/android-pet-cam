// Runtime bindings, secrets and configuration for the Worker. Declared here (not generated) so a
// fresh clone type-checks with just `npm install && npm run check`. Secrets are set with
// `npx wrangler secret put <NAME>`; everything else lives in wrangler.jsonc — see
// wrangler.jsonc.template for what each one does.
interface Env {
  // Bindings
  BUCKET: R2Bucket;
  DB: D1Database;
  ROOM: DurableObjectNamespace;
  /** Optional — remove the "ai" binding from wrangler.jsonc to run without vision labelling. */
  AI?: Ai;
  /** Optional — remove the "send_email" binding to disable email entirely. */
  EMAIL?: SendEmail;

  // Secrets (wrangler secret put …)
  DEVICE_TOKEN: string;
  DEVICE_TOKEN_APP?: string;
  SESSION_SECRET: string;
  VIEWER_PASSWORD: string;

  // Vars (wrangler.jsonc)
  MAX_STORAGE_BYTES: string;
  SESSION_TTL_SECONDS: string;
  DIGEST_TO?: string;
  DIGEST_FROM?: string;
  PUBLIC_URL: string;
  CAMERA_UTC_OFFSET_MINUTES?: string;
  OFFLINE_ALERT_MINUTES?: string;
  CAMERA_LABEL?: string;
}