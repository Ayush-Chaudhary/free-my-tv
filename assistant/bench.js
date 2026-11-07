// Relative CPU of runTurn (Node, not Workers). Fetch is mocked and returns immediately,
// so the time is our own JS: parse, prompts, ranking.
import { readFileSync } from 'node:fs';
import { performance } from 'node:perf_hooks';
import { runTurn } from './src/turn.js';

const fx = (name) => JSON.parse(readFileSync(new URL(`./test/fixtures/${name}.json`, import.meta.url), 'utf8'));
const planFunny = fx('groq-plan-funny');
const planHinglish = fx('groq-plan-hinglish');

const PROVIDER_NAMES = ['Netflix','Amazon Prime Video','Disney Plus','Hulu','Max','Apple TV Plus','Paramount Plus','Crunchyroll','YouTube','Peacock','Hotstar','Globoplay'];

function movie(i, extra = {}) {
  return {
    adult: false,
    backdrop_path: `/backdrop${i}${'x'.repeat(40)}.jpg`,
    genre_ids: [35, 18, (i * 7) % 10000],
    id: 1000 + i,
    original_language: i % 5 === 0 ? 'hi' : 'en',
    original_title: i === 3 ? 'Dilwale Dulhania Le Jayenge' : `Original Title Number ${i} With Extra Words`,
    overview: `A reasonably long overview for title ${i} that looks like the text TMDB returns, with a plot sentence and another one after it so JSON.parse has real work. `.repeat(2),
    popularity: 80 - i,
    poster_path: `/poster${i}.jpg`,
    release_date: `${1990 + (i % 30)}-0${(i % 9) + 1}-15`,
    title: i === 3 ? 'Dilwale Dulhania Le Jayenge' : `Title Number ${i}`,
    video: false,
    vote_average: 6 + (i % 30) / 10,
    vote_count: 500 + i * 13,
    media_type: 'movie',
    ...extra,
  };
}

function page(n, kind) {
  const results = [];
  for (let i = 0; i < n; i++) {
    const row = movie(i);
    if (kind === 'multi' && i % 7 === 0) {
      results.push({ id: 9000 + i, media_type: 'person', name: `Person ${i}`, popularity: 10, known_for: [movie(i + 50)] });
    }
    results.push(row);
  }
  return { page: 1, total_pages: 4, total_results: n * 4, results };
}

function providersCatalog() {
  const results = [];
  for (let i = 0; i < 180; i++) {
    results.push({
      display_priority: i,
      logo_path: `/logo${i}${'y'.repeat(24)}.jpg`,
      provider_name: i < PROVIDER_NAMES.length ? PROVIDER_NAMES[i] : `Extra Channel Service ${i}`,
      provider_id: 10 + i,
    });
  }
  results.push({ provider_id: 8, provider_name: 'Netflix', display_priority: 0 });
  results.push({ provider_id: 1825, provider_name: 'Max Amazon Channel', display_priority: 50 });
  return { results };
}

const chooseEnLive = { reply: 'Here are a few comedies.', picks: [1000, 1001, 1002, 1003].map((id) => ({ id, why: 'a short comedy' })) };
const chooseHiLive = { reply: 'Here is that film.', picks: [1003, 1000, 1004].map((id) => ({ id, why: 'the one you named' })) };
const BODIES = {
  planEn: JSON.stringify({ choices: [{ message: { content: JSON.stringify(planFunny) } }] }),
  planHi: JSON.stringify({ choices: [{ message: { content: JSON.stringify(planHinglish) } }] }),
  chooseEn: JSON.stringify({ choices: [{ message: { content: JSON.stringify(chooseEnLive) } }] }),
  chooseHi: JSON.stringify({ choices: [{ message: { content: JSON.stringify(chooseHiLive) } }] }),
  discover: JSON.stringify(page(20, 'discover')),
  search: JSON.stringify(page(20, 'multi')),
  empty: JSON.stringify({ page: 1, results: [] }),
  catalog: JSON.stringify(providersCatalog()),
};

function response(body, status = 200) {
  return new Response(body, { status, headers: { 'content-type': 'application/json' } });
}

function mockFetch(mode) {
  let groq = 0;
  const fn = async (url) => {
    const u = new URL(url);
    fn.n = (fn.n || 0) + 1;
    if (u.host === 'api.groq.com') {
      groq += 1;
      const plan = groq % 2 === 1;
      if (mode === 'en') return response(plan ? BODIES.planEn : BODIES.chooseEn);
      return response(plan ? BODIES.planHi : BODIES.chooseHi);
    }
    const path = u.pathname;
    if (path.endsWith('/watch/providers/movie')) return response(BODIES.catalog);
    if (path.endsWith('/discover/movie')) return response(BODIES.discover);
    if (path.endsWith('/search/multi')) {
      const q = u.searchParams.get('query') || '';
      if (q === 'DDLJ') return response(BODIES.empty);
      return response(BODIES.search);
    }
    return response(BODIES.empty);
  };
  return fn;
}

const env = { TMDB_KEY: 'tmdb-bench', GROQ_KEY: 'groq-bench' };
const english = { query: 'something funny for tonight', lang: 'en', region: 'US', services: ['netflix', 'prime', 'disney', 'hulu', 'max'], history: [] };
const hinglish = { query: 'dil vale dulhania le jayange dikhao', lang: 'en', region: 'US', services: ['netflix', 'prime', 'hotstar'], history: [] };

async function time(mode, iters) {
  const fetch = mockFetch(mode);
  const req = mode === 'en' ? english : hinglish;
  const cache = new Map();
  for (let i = 0; i < 3; i++) await runTurn(req, env, { fetch, now: () => 0, cache });
  const cpu0 = process.cpuUsage();
  const t0 = performance.now();
  for (let i = 0; i < iters; i++) await runTurn(req, env, { fetch, now: () => 0, cache });
  const cpu = process.cpuUsage(cpu0);
  const wall = performance.now() - t0;
  const cpuMs = (cpu.user + cpu.system) / 1000;
  return { cpuPer: cpuMs / iters, wallPer: wall / iters };
}

function cpuMs(delta) {
  return (delta.user + delta.system) / 1000;
}

function timeParse(iters) {
  const discover0 = process.cpuUsage();
  for (let i = 0; i < iters; i++) JSON.parse(BODIES.discover);
  const discover = process.cpuUsage(discover0);
  const groq0 = process.cpuUsage();
  for (let i = 0; i < iters; i++) JSON.parse(BODIES.chooseEn);
  const groq = process.cpuUsage(groq0);
  return {
    discoverParseMs: +(cpuMs(discover) / iters).toFixed(3),
    discoverBytes: BODIES.discover.length,
    groqParseMs: +(cpuMs(groq) / iters).toFixed(3),
  };
}

const iters = Number(process.env.BENCH_ITERS || 40);
const countFetch = mockFetch('en');
const one = await runTurn(english, env, { fetch: countFetch, now: () => 0, cache: new Map() });
const hiFetch = mockFetch('hi');
await runTurn(hinglish, env, { fetch: hiFetch, now: () => 0, cache: new Map() });
const en = await time('en', iters);
const hi = await time('hi', iters);
console.log(JSON.stringify({
  iters,
  english: { cpuMs: +en.cpuPer.toFixed(3), wallMs: +en.wallPer.toFixed(3), calls: countFetch.n, picks: one.body.picks.length },
  hinglish: { cpuMs: +hi.cpuPer.toFixed(3), wallMs: +hi.wallPer.toFixed(3), calls: hiFetch.n },
  parse: timeParse(iters),
}, null, 2));
