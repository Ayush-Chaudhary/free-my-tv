// The daily cap: a Durable Object per token (named by the token's hash), counting turns per UTC day. Requests to a
// Durable Object run one at a time, so the read-then-write below can't race.

export class Limiter {
  constructor(state) {
    this.state = state;
  }

  async fetch(request) {
    const { max, day } = await request.json();
    let rec = (await this.state.storage.get('d')) || { day, n: 0 };
    if (rec.day !== day) rec = { day, n: 0 };
    let ok = rec.n < max;
    if (ok) {
      rec.n += 1;
      await this.state.storage.put('d', rec);
    }
    return new Response(JSON.stringify({ ok, n: rec.n }), { headers: { 'content-type': 'application/json' } });
  }
}

/** Counts one turn for this token today. true while under `max`. */
export async function takeDaily(env, tokenHash, max, now = Date.now()) {
  const stub = env.LIMITER.get(env.LIMITER.idFromName(tokenHash));
  const day = new Date(now).toISOString().slice(0, 10);
  const res = await stub.fetch('https://limiter/hit', { method: 'POST', body: JSON.stringify({ max, day }) });
  return (await res.json()).ok;
}
