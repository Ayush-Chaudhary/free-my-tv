// The request handler (kept out of index.js: a Worker's entry file may export only its handlers and Durable Objects).
import { authorize } from './auth.js';
import { UpstreamError } from './groq.js';
import { takeDaily } from './limiter.js';
import { BadRequest, parseRequest, runTurn } from './turn.js';

export const DAY_MAX = 300;
const sharedCache = new Map();

const json = (status, body, headers = {}) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json', 'cache-control': 'no-store', ...headers } });

export async function handle(request, env, deps = {}) {
  const now = deps.now || Date.now;
  const started = now();
  const url = new URL(request.url);
  const done = (res, extra = {}) => {
    // Status and timings only: never the request text, the token or the picks.
    console.log(JSON.stringify({ status: res.status, ms: now() - started, ...extra }));
    return res;
  };

  if (url.pathname === '/health') return json(200, { ok: true });
  if (url.pathname !== '/v1/turn') return done(json(404, { error: 'not_found' }));
  if (request.method !== 'POST') return done(json(405, { error: 'method' }, { allow: 'POST' }));

  const token = await authorize(request, env);
  if (!token) return done(json(401, { error: 'unauthorized' }));

  const perMinute = await env.RATE.limit({ key: token });
  if (!perMinute.success || !(await takeDaily(env, token, DAY_MAX, now()))) {
    return done(json(429, { error: 'rate_limited' }, { 'retry-after': perMinute.success ? '3600' : '60' }));
  }

  let req;
  try {
    const text = await request.text();
    if (text.length > 16000) throw new BadRequest('too large');
    req = parseRequest(JSON.parse(text));
  } catch (e) {
    return done(json(400, { error: 'bad_request' }));
  }

  try {
    const fetchImpl = deps.fetch ?? fetch;
    const http = (...args) => fetchImpl(...args);
    // caches.default is the Workers Cache API. Tests pass their own. A missing cache just fetches every time.
    const httpCache = deps.httpCache !== undefined ? deps.httpCache : (typeof caches !== 'undefined' && caches.default);
    const { body, stats } = await runTurn(req, env, { fetch: http, now, cache: deps.cache || sharedCache, httpCache });
    return done(json(200, body), { llm: stats.llm, tmdb: stats.tmdb, picks: body.picks.length, intent: body.intent });
  } catch (e) {
    // Name, message and stack only. Never the query, the token or a request body.
    console.error(e && e.name, e && String(e.message || '').slice(0, 300));
    if (e && e.stack) console.error(String(e.stack).slice(0, 1500));
    const status = e instanceof UpstreamError ? e.status : 500;
    return done(json(status, { error: status === 504 ? 'timeout' : status === 503 ? 'llm_unavailable' : status === 502 ? 'upstream' : 'error' }));
  }
}
