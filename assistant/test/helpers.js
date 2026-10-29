// Fakes for the tests: an env (rate limit binding + daily Limiter), and a fetch that serves fixtures.
import { readFileSync } from 'node:fs';
import { sha256Hex } from '../src/auth.js';
import { Limiter } from '../src/limiter.js';

export const TOKEN = 'test-token-0123456789abcdefghijklmnopqrstuvwxyz';
export const SERVICES = ['netflix', 'prime', 'disney', 'max', 'hulu', 'appletv', 'paramount', 'youtube'];

export const fx = (name) => JSON.parse(readFileSync(new URL(`./fixtures/${name}.json`, import.meta.url), 'utf8'));

export async function makeEnv({ perMinute = 10, hash } = {}) {
  const store = new Map();
  const objects = new Map();
  let used = 0;
  return {
    DEVICE_TOKEN_HASH: hash ?? (await sha256Hex(TOKEN)),
    GROQ_KEY: 'groq-test-key',
    TMDB_KEY: 'tmdb-test-key',
    RATE: { limit: async () => ({ success: ++used <= perMinute }) },
    LIMITER: {
      idFromName: (n) => n,
      get: (name) => {
        if (!objects.has(name)) {
          const state = { storage: { get: async (k) => store.get(name + k), put: async (k, v) => store.set(name + k, v) } };
          objects.set(name, new Limiter(state));
        }
        const obj = objects.get(name);
        return { fetch: (url, init) => obj.fetch(new Request(url, init)) };
      },
    },
  };
}

const reply = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });

/**
 * A fetch for Groq and TMDB. `groq` is a list of answers used in order: a fixture name or an object (its JSON is wrapped as a chat
 * completion), a number (an error status), or "garbage" (a reply that is not JSON). `tmdb` maps a path to a fixture
 * name or a function(url) -> body. `calls` records every request.
 */
export function fakeFetch({ groq = [], tmdb = {} }) {
  const calls = [];
  const queue = [...groq];
  const fn = async (url, init = {}) => {
    const u = new URL(url);
    calls.push({ host: u.host, path: u.pathname, search: u.searchParams, body: init.body ? JSON.parse(init.body) : null, headers: init.headers });
    if (u.host === 'api.groq.com') {
      const next = queue.shift();
      if (next === undefined) return reply(500, { error: 'no more groq answers' });
      if (typeof next === 'number') return reply(next, { error: { message: 'x' } });
      if (next === 'garbage') return reply(200, { choices: [{ message: { content: 'not json at all' } }] });
      const content = typeof next === 'string' ? fx(next) : next;   // a fixture name, or the model's JSON inline
      return reply(200, { choices: [{ message: { content: JSON.stringify(content) } }] });
    }
    if (u.host === 'api.themoviedb.org') {
      const t = tmdb[u.pathname.replace('/3', '')];
      if (t === undefined) return reply(404, { status_message: 'not found' });
      if (typeof t === 'number') return reply(t, {});
      return reply(200, typeof t === 'function' ? t(u) : fx(t));
    }
    return reply(500, {});
  };
  fn.calls = calls;
  fn.groqLeft = () => queue.length;
  return fn;
}

/** The TMDB routes for the "funny movie" turn. */
export const funnyRoutes = () => ({
  '/watch/providers/movie': 'tmdb-providers-movie-US',
  '/discover/movie': 'tmdb-discover-movie-comedy',
  '/movie/587792/watch/providers': 'tmdb-wp-587792',
  '/movie/445571/watch/providers': 'tmdb-wp-445571',
  '/movie/4638/watch/providers': 'tmdb-wp-4638',
  '/movie/290250/watch/providers': 'tmdb-wp-290250',
  '/movie/481084/watch/providers': 'tmdb-wp-481084',
  '/movie/603/watch/providers': 'tmdb-wp-603',
});

export const ask = (body, token = TOKEN) => new Request('https://assistant.example/v1/turn', {
  method: 'POST',
  headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
  body: JSON.stringify({ v: 1, lang: 'en', region: 'US', services: SERVICES, history: [], ...body }),
});
