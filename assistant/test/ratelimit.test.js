import assert from 'node:assert/strict';
import test from 'node:test';
import { DAY_MAX, handle } from '../src/handler.js';
import { sha256Hex } from '../src/auth.js';
import { nyDay } from '../src/limiter.js';
import { ask, fakeFetch, makeEnv, TOKEN } from './helpers.js';

const chat = () => fakeFetch({ groq: Array(DAY_MAX + 20).fill('groq-plan-chat') });

test('the 11th turn within a minute gets 429 with Retry-After', async () => {
  const env = await makeEnv({ perMinute: 10 });
  const f = chat();
  for (let i = 0; i < 10; i++) assert.equal((await handle(ask({ query: 'hello' }), env, { fetch: f, cache: new Map() })).status, 200);
  const res = await handle(ask({ query: 'hello' }), env, { fetch: f, cache: new Map() });
  assert.equal(res.status, 429);
  assert.equal(res.headers.get('retry-after'), '60');
});

test('the 301st turn in a day gets 429; it resets at midnight in New York, not UTC', async () => {
  const env = await makeEnv({ perMinute: 1e9 });
  const f = chat();
  const go = (t) => handle(ask({ query: 'hello' }), env, { fetch: f, cache: new Map(), now: () => t });
  let t = Date.UTC(2026, 9, 29, 12);   // 8 AM EDT on 29 Oct
  for (let i = 0; i < DAY_MAX; i++) assert.equal((await go(t)).status, 200);
  assert.equal((await go(t)).status, 429);
  assert.equal((await go(Date.UTC(2026, 9, 30, 0, 1))).status, 429, 'UTC midnight is still 29 Oct in New York');
  assert.equal((await go(Date.UTC(2026, 9, 30, 3, 59))).status, 429, '11:59 PM EDT');
  assert.equal((await go(Date.UTC(2026, 9, 30, 4, 1))).status, 200, '12:01 AM EDT on 30 Oct');
});

test('the New York day follows daylight saving time', () => {
  // EDT is UTC-4, EST is UTC-5. DST ends 1 Nov 2026 and starts 8 Mar 2026.
  assert.equal(nyDay(Date.UTC(2026, 9, 30, 3, 59)), '2026-10-29');
  assert.equal(nyDay(Date.UTC(2026, 9, 30, 4, 0)), '2026-10-30');
  assert.equal(nyDay(Date.UTC(2026, 10, 2, 4, 30)), '2026-11-01');   // 11:30 PM EST
  assert.equal(nyDay(Date.UTC(2026, 10, 2, 5, 0)), '2026-11-02');    // midnight EST
  assert.equal(nyDay(Date.UTC(2026, 2, 8, 4, 59)), '2026-03-07');    // 11:59 PM EST
  assert.equal(nyDay(Date.UTC(2026, 2, 8, 5, 0)), '2026-03-08');
  assert.equal(nyDay(Date.UTC(2026, 2, 9, 3, 59)), '2026-03-08');    // 11:59 PM EDT
  assert.equal(nyDay(Date.UTC(2026, 2, 9, 4, 0)), '2026-03-09');
});

test('a turn refused for the minute limit does not use up the daily count', async () => {
  const env = await makeEnv({ perMinute: 1 });
  const f = chat();
  await handle(ask({ query: 'hello' }), env, { fetch: f, cache: new Map() });
  for (let i = 0; i < 5; i++) await handle(ask({ query: 'hello' }), env, { fetch: f, cache: new Map() });
  const stub = env.LIMITER.get(await sha256Hex(TOKEN));
  const res = await stub.fetch('https://limiter/hit', { method: 'POST', body: JSON.stringify({ max: 300, day: nyDay() }) });
  assert.equal((await res.json()).n, 2);   // the one turn that ran, plus this probe
});

test('rejected requests are not logged with their text', async () => {
  const env = await makeEnv({ perMinute: 0 });
  const lines = [];
  const orig = console.log;
  console.log = (s) => lines.push(String(s));
  try {
    await handle(ask({ query: 'my secret request text' }), env, { fetch: chat(), cache: new Map() });
  } finally {
    console.log = orig;
  }
  assert.ok(lines.length >= 1);
  assert.ok(lines.every((l) => !l.includes('secret') && !l.includes('Bearer')));
});
