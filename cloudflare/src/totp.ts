// RFC 4648 base32 (no padding) + RFC 6238 TOTP, implemented on Web Crypto so no npm crypto deps are needed.

const BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

export function base32Encode(bytes: Uint8Array): string {
  let bits = 0;
  let value = 0;
  let out = "";
  for (const b of bytes) {
    value = (value << 8) | b;
    bits += 8;
    while (bits >= 5) {
      out += BASE32_ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += BASE32_ALPHABET[(value << (5 - bits)) & 31];
  return out;
}

export function base32Decode(input: string): Uint8Array {
  const clean = input.toUpperCase().replace(/[^A-Z2-7]/g, "");
  let bits = 0;
  let value = 0;
  const out: number[] = [];
  for (const c of clean) {
    const idx = BASE32_ALPHABET.indexOf(c);
    if (idx < 0) continue;
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return new Uint8Array(out);
}

/** A fresh random 160-bit TOTP secret (the usual default size for authenticator apps). */
export function generateTotpSecret(): Uint8Array {
  const bytes = new Uint8Array(20);
  crypto.getRandomValues(bytes);
  return bytes;
}

async function hmacSha1(key: Uint8Array, message: Uint8Array): Promise<Uint8Array> {
  const cryptoKey = await crypto.subtle.importKey("raw", key, { name: "HMAC", hash: "SHA-1" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", cryptoKey, message);
  return new Uint8Array(sig);
}

/** RFC 6238 TOTP code for a given 30s step counter. */
async function totpAt(secret: Uint8Array, counter: number, digits = 6): Promise<string> {
  const counterBytes = new Uint8Array(8);
  let c = counter;
  for (let i = 7; i >= 0; i--) {
    counterBytes[i] = c & 0xff;
    c = Math.floor(c / 256);
  }
  // HMAC-SHA1 is always 20 bytes and offset is always 0-15, so these four bytes are always in bounds.
  const hmac = await hmacSha1(secret, counterBytes);
  const offset = hmac[hmac.length - 1]! & 0x0f;
  const binary =
    ((hmac[offset]! & 0x7f) << 24) | ((hmac[offset + 1]! & 0xff) << 16) | ((hmac[offset + 2]! & 0xff) << 8) | (hmac[offset + 3]! & 0xff);
  return String(binary % 10 ** digits).padStart(digits, "0");
}

/** Verify a user-entered code, tolerating ±1 step (30s) of clock drift. */
export async function verifyTotp(secret: Uint8Array, code: string, step = 30, window = 1): Promise<boolean> {
  const clean = code.replace(/\D/g, "");
  if (clean.length !== 6) return false;
  const counter = Math.floor(Date.now() / 1000 / step);
  for (let delta = -window; delta <= window; delta++) {
    const candidate = await totpAt(secret, counter + delta);
    if (await timingSafeEqualStringsLocal(candidate, clean)) return true;
  }
  return false;
}

async function timingSafeEqualStringsLocal(a: string, b: string): Promise<boolean> {
  const enc = new TextEncoder();
  const [ha, hb] = await Promise.all([crypto.subtle.digest("SHA-256", enc.encode(a)), crypto.subtle.digest("SHA-256", enc.encode(b))]);
  return crypto.subtle.timingSafeEqual(ha, hb);
}

export function otpauthUri(secretBase32: string, accountLabel: string, issuer: string): string {
  const label = encodeURIComponent(`${issuer}:${accountLabel}`);
  return `otpauth://totp/${label}?secret=${secretBase32}&issuer=${encodeURIComponent(issuer)}&algorithm=SHA1&digits=6&period=30`;
}
