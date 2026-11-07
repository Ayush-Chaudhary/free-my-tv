import assert from 'node:assert/strict';
import test from 'node:test';
import { handle } from '../src/handler.js';
import { Tmdb } from '../src/tmdb.js';
import { ask, fakeFetch, funnyRoutes, makeEnv } from './helpers.js';

function memoryCache() {
  const store = new Map();
  return {
    store,
    async match(request) {
      const hit = store.get(request.url);
      if (!hit) return undefined;
      return new Response(hit.body, { headers: hit.headers });
    },
    async put(request, response) {
      store.set(request.url, {
        body: await response.text(),
        headers: { 'content-type': 'application/json', 'cache-control': response.headers.get('cache-control') || '' },
      });
    },
  };
}

const providers = (calls) => calls.filter((c) => /\/movie\/\d+\/watch\/providers$/.test(c.path));

test('a repeat title uses the cached flatrate list and skips the provider body', async () => {
  const httpCache = memoryCache();
  const env = await makeEnv();
  const first = fakeFetch({ groq: ['groq-plan-funny', 'groq-choose-funny'], tmdb: funnyRoutes() });
  const a = await handle(ask({ query: 'a funny movie' }), env, { fetch: first, cache: new Map(), httpCache });
  const aj = await a.json();
  assert.equal(a.status, 200);
  assert.equal(aj.picks.find((p) => p.id === 587792).service, 'hulu');
  const saved = [...httpCache.store.values()];
  assert.ok(saved.length >= 1);
  assert.ok(saved.every((row) => row.body.length < 400), 'the cache holds the flatrate names, not the country list');
  assert.ok(saved.every((row) => row.headers['cache-control'].includes('max-age=86400')));

  const second = fakeFetch({ groq: ['groq-plan-funny', 'groq-choose-funny'], tmdb: funnyRoutes() });
  const b = await handle(ask({ query: 'a funny movie' }), env, { fetch: second, cache: new Map(), httpCache });
  const bj = await b.json();
  assert.equal(bj.picks.find((p) => p.id === 587792).service, 'hulu');
  assert.equal(providers(second.calls).length, 0);
  assert.equal(providers(first.calls).length, 4);
});

test('a provider error is not cached', async () => {
  const httpCache = memoryCache();
  const tmdb = new Tmdb({ TMDB_KEY: 'k' }, async () => new Response('no', { status: 500 }), { tmdb: 0 }, new Map(), httpCache);
  assert.equal(await tmdb.serviceFor({ kind: 'movie', id: 1 }, 'US', ['netflix']), null);
  assert.equal(httpCache.store.size, 0);
});
