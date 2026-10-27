import { defineConfig } from "vitest/config";
import { cloudflareTest } from "@cloudflare/vitest-plugin";

// Tests run inside the Workers runtime (workerd) with the bindings from wrangler.test.jsonc, so the
// Durable Object, D1 and R2 behave as they do in production. Storage is isolated per test.
export default defineConfig({
  plugins: [
    cloudflareTest({
      wrangler: { configPath: "./wrangler.test.jsonc" },
      miniflare: {
        bindings: {
          DEVICE_TOKEN: "test-device-token",
          SESSION_SECRET: "test-session-secret",
          VIEWER_PASSWORD: "test-viewer-pw",
        },
      },
    }),
  ],
  test: { include: ["test/**/*.test.ts"] },
});
