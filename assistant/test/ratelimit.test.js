import assert from 'node:assert/strict';
import test from 'node:test';
import { DAY_MAX, handle } from '../src/handler.js';
import { sha256Hex } from '../src/auth.js';
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

test('the 301st turn in a day gets 429; the next UTC day starts again', async () => {
  const env = await makeEnv({ perMinute: 1e9 });
  const f = chat();
  let t = Date.UTC(2026, 9, 29, 12);
  for (let i = 0; i < DAY_MAX; i++) assert.equal((await handle(ask({ query: 'hello' }), env, { fetch: f, cache: new Map(), now: () => t })).status, 200);
  assert.equal((await handle(ask({ query: 'hello' }), env, { fetch: f, cache: new Map(), now: () => t })).status, 429);
  t = Date.UTC(2026, 9, 30, 0, 1);
  assert.equal((await handle(ask({ query: 'hello' }), env, { fetch: f, cache: new Map(), now: () => t })).status, 200);
});

test('a turn refused for the minute limit does not use up the daily count', async () => {
  const env = await makeEnv({ perMinute: 1 });
  const f = chat();
  await handle(ask({ query: 'hello' }), env, { fetch: f, cache: new Map() });
  for (let i = 0; i < 5; i++) await handle(ask({ query: 'hello' }), env, { fetch: f, cache: new Map() });
  const stub = env.LIMITER.get(await sha256Hex(TOKEN));
  const res = await stub.fetch('https://limiter/hit', { method: 'POST', body: JSON.stringify({ max: 300, day: new Date().toISOString().slice(0, 10) }) });
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
