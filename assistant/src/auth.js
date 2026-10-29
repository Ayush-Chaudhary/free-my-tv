// Bearer token check: the Worker keeps only the SHA-256 (hex) of the device token.

const enc = new TextEncoder();

export async function sha256Hex(text) {
  const buf = await crypto.subtle.digest('SHA-256', enc.encode(text));
  return [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

/** Compares two strings without stopping at the first difference. Lengths are not secret (both are hashes). */
export function safeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

/** The token's hash when `Authorization: Bearer <token>` matches DEVICE_TOKEN_HASH, otherwise null. */
export async function authorize(request, env) {
  const expected = (env.DEVICE_TOKEN_HASH || '').trim().toLowerCase();
  const header = request.headers.get('authorization') || '';
  const m = /^Bearer\s+(\S+)$/i.exec(header);
  if (!expected || !m) return null;
  const hash = await sha256Hex(m[1]);
  return safeEqual(hash, expected) ? hash : null;
}
