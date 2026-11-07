import assert from 'node:assert/strict';
import test from 'node:test';
import { safeEqual, sha256Hex } from '../src/auth.js';
import { handle } from '../src/handler.js';
import { ask, fakeFetch, makeEnv, TOKEN } from './helpers.js';

const status = async (req, env, fetch = fakeFetch({})) => (await handle(req, env, { fetch, cache: new Map() })).status;

test('sha256Hex matches a known digest', async () => {
  assert.equal(await sha256Hex('abc'), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
});

test('safeEqual compares whole strings', () => {
  assert.equal(safeEqual('abcd', 'abcd'), true);
  assert.equal(safeEqual('abcd', 'abce'), false);
  assert.equal(safeEqual('abcd', 'abc'), false);
});

test('a missing token gets 401', async () => {
  const env = await makeEnv();
  const req = new Request('https://assistant.example/v1/turn', { method: 'POST', body: '{}' });
  assert.equal(await status(req, env), 401);
});

test('a wrong token gets 401, and nothing upstream is called', async () => {
  const env = await makeEnv();
  const f = fakeFetch({});
  assert.equal(await status(ask({ query: 'a movie' }, 'wrong-token'), env, f), 401);
  assert.equal(f.calls.length, 0);
});

test('a token with a different scheme or extra parts gets 401', async () => {
  const env = await makeEnv();
  for (const h of [`Basic ${TOKEN}`, `Bearer ${TOKEN} extra`, TOKEN, 'Bearer ']) {
    const req = new Request('https://assistant.example/v1/turn', { method: 'POST', headers: { authorization: h }, body: '{}' });
    assert.equal(await status(req, env), 401, h);
  }
});

test('with no DEVICE_TOKEN_HASH set, every request gets 401', async () => {
  const env = await makeEnv({ hash: '' });
  assert.equal(await status(ask({ query: 'a movie' }), env), 401);
});

test('the right token passes auth (bad body then gets 400, not 401)', async () => {
  const env = await makeEnv();
  const req = new Request('https://assistant.example/v1/turn', {
    method: 'POST', headers: { authorization: `Bearer ${TOKEN}` }, body: 'not json',
  });
  assert.equal(await status(req, env), 400);
});

test('only POST /v1/turn is served; /health is open', async () => {
  const env = await makeEnv();
  assert.equal(await status(new Request('https://assistant.example/v1/turn'), env), 405);
  assert.equal(await status(new Request('https://assistant.example/other', { method: 'POST' }), env), 404);
  assert.equal(await status(new Request('https://assistant.example/health'), env), 200);
});
