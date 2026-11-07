// Bearer token check: the Worker keeps only the SHA-256 (hex) of the device token.

const enc = new TextEncoder();
const HEX = '0123456789abcdef';
// The device hash doesn't change for the life of an isolate. Decode it once per env object.
const expectedCache = new WeakMap();

function bytesToHex(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i++) s += HEX[bytes[i] >> 4] + HEX[bytes[i] & 15];
  return s;
}

function hexToBytes(hex) {
  if (!hex || hex.length % 2) return null;
  const out = new Uint8Array(hex.length >> 1);
  for (let i = 0; i < out.length; i++) {
    const v = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
    if (Number.isNaN(v)) return null;
    out[i] = v;
  }
  return out;
}

export async function sha256Hex(text) {
  const buf = new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(text)));
  return bytesToHex(buf);
}

/** Compares two strings without stopping at the first difference. Lengths are not secret (both are hashes). */
export function safeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

function expectedBytes(env) {
  const raw = (env && env.DEVICE_TOKEN_HASH) || '';
  if (!env || typeof env !== 'object') return hexToBytes(String(raw).trim().toLowerCase());
  const hit = expectedCache.get(env);
  if (hit && hit.raw === raw) return hit.bytes;
  const bytes = hexToBytes(String(raw).trim().toLowerCase());
  expectedCache.set(env, { raw, bytes });
  return bytes;
}

function bytesEqual(a, b) {
  if (!a || !b || a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
  return diff === 0;
}

/** The token's hash when `Authorization: Bearer <token>` matches DEVICE_TOKEN_HASH, otherwise null. */
export async function authorize(request, env) {
  const expected = expectedBytes(env);
  const header = request.headers.get('authorization') || '';
  const m = /^Bearer\s+(\S+)$/i.exec(header);
  if (!expected || !m) return null;
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', enc.encode(m[1])));
  return bytesEqual(digest, expected) ? bytesToHex(digest) : null;
}
