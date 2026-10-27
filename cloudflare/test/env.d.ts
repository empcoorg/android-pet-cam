// The bindings tests receive (`env` from "cloudflare:test") are the Worker's own, declared in
// ../env.d.ts, plus the test secrets set in vitest.config.ts.
type WorkerEnv = Env;
declare namespace Cloudflare {
  interface Env extends WorkerEnv {}
}

declare module "*.sql?raw" {
  const sql: string;
  export default sql;
}
