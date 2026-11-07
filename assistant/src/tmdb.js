// TMDB calls, trimmed to the fields a turn needs (this keeps the CPU time of parsing small).
import { UpstreamError } from './groq.js';
import { rankRows, serviceOf, simplify } from './match.js';

const BASE = 'https://api.themoviedb.org/3';

// Movie genre ids; TV merges some (Action & Adventure, Sci-Fi & Fantasy, War & Politics) and has no horror/thriller/romance.
export const GENRES = {
  action: 28, adventure: 12, animation: 16, comedy: 35, crime: 80, documentary: 99, drama: 18, family: 10751,
  fantasy: 14, history: 36, horror: 27, music: 10402, mystery: 9648, romance: 10749, scifi: 878, thriller: 53,
  war: 10752, western: 37,
};
const TV_GENRES = { action: 10759, adventure: 10759, scifi: 10765, fantasy: 10765, war: 10768, thriller: 9648 };
const NAMES = new Map(Object.entries(GENRES).map(([k, v]) => [v, k]));
for (const [k, v] of Object.entries(TV_GENRES)) if (!NAMES.has(v)) NAMES.set(v, k);
NAMES.set(10759, 'action'); NAMES.set(10765, 'scifi'); NAMES.set(10768, 'war');

function asNames(names) {
  if (Array.isArray(names)) return names;
  if (typeof names === 'string') return names.split(/[,|/]/);
  if (typeof names === 'number') return [names];
  if (names && typeof names === 'object') {
    const vals = Object.values(names).filter((v) => typeof v === 'string' || typeof v === 'number');
    return vals.length ? vals : Object.keys(names);
  }
  return [];
}

export function genreIds(names, kind) {
  const out = new Set();
  for (const n of asNames(names)) {
    const key = String(n).toLowerCase().replace(/[^a-z]/g, '').replace(/^sci?fi.*|sciencefiction/, 'scifi');
    if (kind === 'tv' && key in TV_GENRES) out.add(TV_GENRES[key]);
    else if (key in GENRES && !(kind === 'tv' && ['horror', 'romance'].includes(key))) out.add(GENRES[key]);
  }
  return [...out];
}

const utf8 = new TextEncoder();
const utf8dec = new TextDecoder();

export class Tmdb {
  constructor(env, fetchFn, stats, cache, httpCache) {
    this.env = env;
    // Workers' fetch throws if it is called as a method (`this` would be the Tmdb instance).
    const impl = fetchFn ?? globalThis.fetch;
    this.fetch = (...args) => impl(...args);
    this.stats = stats;
    this.cache = cache;
    this.httpCache = httpCache || null;
  }

  request(path, params = {}) {
    const url = new URL(BASE + path);
    for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') url.searchParams.set(k, String(v));
    const headers = { accept: 'application/json' };
    const key = this.env.TMDB_KEY || '';
    if (key.startsWith('eyJ')) headers.authorization = `Bearer ${key}`;   // a v4 read token
    else url.searchParams.set('api_key', key);
    return { url: url.toString(), headers };
  }

  async text(path, params = {}) {
    const { url, headers } = this.request(path, params);
    this.stats.tmdb += 1;
    const res = await this.fetch(url, { headers });
    if (!res.ok) throw new UpstreamError(`tmdb ${res.status}`, 502);
    return res.text();
  }

  async bytes(path, params = {}) {
    const { url, headers } = this.request(path, params);
    this.stats.tmdb += 1;
    const res = await this.fetch(url, { headers });
    if (!res.ok) throw new UpstreamError(`tmdb ${res.status}`, 502);
    return new Uint8Array(await res.arrayBuffer());
  }

  async get(path, params = {}) {
    try {
      const data = JSON.parse(await this.text(path, params));
      return data && typeof data === 'object' ? data : {};
    } catch (e) {
      if (e instanceof UpstreamError) throw e;
      throw new UpstreamError('tmdb: not json', 502);
    }
  }

  /** TMDB's provider ids, by service key, for the region; the list is cached for a while in the isolate. */
  async providerIds(region, services) {
    let list = this.cache.get(region);
    if (!list || list.at < Date.now() - 6 * 3600e3) {
      const data = await this.get('/watch/providers/movie', { watch_region: region });
      const rows = Array.isArray(data.results) ? data.results : [];
      list = { at: Date.now(), items: rows.filter((p) => p && typeof p === 'object').map((p) => [p.provider_id, serviceOf(p.provider_name, p.provider_id)]) };
      this.cache.set(region, list);
    }
    const want = new Set(services);
    return list.items.filter(([, key]) => key && want.has(key)).map(([id]) => id);
  }

  /** Titles matching what was said, best first. A term that finds nothing is tried again, loosened. */
  async search(term, lang) {
    const run = async (q) => {
      const data = await this.get('/search/multi', { query: q, language: lang, include_adult: 'false' });
      const rows = Array.isArray(data.results) ? data.results : [];
      return rows.filter((r) => r && (r.media_type === 'movie' || r.media_type === 'tv'));
    };
    let found = await run(term);
    if (!found.length) {
      const loose = simplify(term);
      if (loose && loose !== term) found = await run(loose);
    }
    return rankRows(term, found)
      .sort((a, b) => b.s - a.s || (b.r.popularity || 0) - (a.r.popularity || 0))
      .slice(0, 3).map((x) => slim(x.r, x.r.media_type));
  }

  async discover(kind, d, lang, region, providerIds) {
    const movie = kind === 'movie';
    const rating = d.sort === 'rating';
    const params = {
      language: lang, include_adult: 'false', watch_region: region,
      with_watch_monetization_types: 'flatrate',
      with_watch_providers: providerIds.join('|'),
      with_genres: genreIds(d && d.genres, kind).join(','),
      'with_runtime.lte': finite(d && d.runtime_lte),
      with_original_language: /^[a-z]{2,3}$/.test(String((d && d.language) || '')) ? String(d.language) : undefined,
      sort_by: rating ? 'vote_average.desc' : 'popularity.desc',
      'vote_count.gte': rating ? 300 : undefined,
    };
    const year = finite(d && d.year_gte);
    if (year !== undefined) params[movie ? 'primary_release_date.gte' : 'first_air_date.gte'] = `${year}-01-01`;
    const data = await this.get(`/discover/${kind}`, params);
    return rowsOf(data).slice(0, 10).map((r) => slim(r, kind)).filter(Boolean);
  }

  async recommendations(kind, id, lang) {
    const data = await this.get(`/${kind}/${id}/recommendations`, { language: lang });
    return rowsOf(data).slice(0, 6).map((r) => slim(r, kind)).filter(Boolean);
  }

  /** The TV's own service that streams this title in the region (flat rate), or null. */
  async serviceFor(c, region, services) {
    const want = services instanceof Set ? services : new Set(services);
    const cacheUrl = `https://cache.freemytv.internal/wp/${c.kind}/${c.id}/${region}`;
    try {
      const cached = await this.cachedJson(cacheUrl);
      if (Array.isArray(cached)) return serviceFromFlat(cached, want);
      // Bytes, not text(): workerd charges CPU to decode a ~92 KB body into a string.
      const buf = await this.bytes(`/${c.kind}/${c.id}/watch/providers`);
      const flat = flatrateFromBytes(buf, region).map((p) => ({
        provider_id: p.provider_id, provider_name: p.provider_name,
      }));
      await this.storeJson(cacheUrl, flat, 86400);
      return serviceFromFlat(flat, want);
    } catch (e) {
      // one title's providers failing only means no service on its card
    }
    return null;
  }

  async cachedJson(url) {
    if (!this.httpCache) return null;
    try {
      const hit = await this.httpCache.match(new Request(url));
      if (!hit) return null;
      return await hit.json();
    } catch {
      return null;
    }
  }

  async storeJson(url, value, seconds) {
    if (!this.httpCache) return;
    try {
      await this.httpCache.put(new Request(url), new Response(JSON.stringify(value), {
        headers: { 'content-type': 'application/json', 'cache-control': `public, max-age=${seconds}` },
      }));
    } catch {
      // a cache that refuses the write just means the next turn fetches again
    }
  }
}

function serviceFromFlat(flat, want) {
  for (const p of flat) {
    const key = serviceOf(p.provider_name, p.provider_id);
    if (key && want.has(key)) return key;
  }
  return null;
}

/** The flatrate array for `region` inside a watch-providers body, without parsing the other countries. */
export function flatrateIn(text, region) {
  return flatrateFromBytes(utf8.encode(String(text || '')), region);
}

/** Same slice as `flatrateIn`, over the raw body so only the region object is decoded. */
export function flatrateFromBytes(buf, region) {
  if (!buf || !/^[A-Za-z]{2}$/.test(region || '')) return [];
  const marker = utf8.encode(`"${region}":`);
  let from = 0;
  while (from <= buf.length - marker.length) {
    const at = findBytes(buf, marker, from);
    if (at < 0) return [];
    const prev = at === 0 ? 0 : buf[at - 1];
    // A key sits after `{` (123) or `,` (44). The same letters inside a string do not.
    if (prev !== 123 && prev !== 44) { from = at + 1; continue; }
    let j = at + marker.length;
    while (buf[j] === 32 || buf[j] === 10 || buf[j] === 13 || buf[j] === 9) j++;
    if (buf[j] !== 123) { from = at + 1; continue; }
    const end = matchBraceBytes(buf, j);
    if (end < 0) return [];
    try {
      const block = JSON.parse(utf8dec.decode(buf.subarray(j, end + 1)));
      return Array.isArray(block.flatrate) ? block.flatrate : [];
    } catch {
      return [];
    }
  }
  return [];
}

function findBytes(buf, needle, from) {
  const n0 = needle[0];
  outer: for (let i = from; i <= buf.length - needle.length; i++) {
    if (buf[i] !== n0) continue;
    for (let k = 1; k < needle.length; k++) if (buf[i + k] !== needle[k]) continue outer;
    return i;
  }
  return -1;
}

function matchBraceBytes(buf, open) {
  let depth = 0, inStr = false, esc = false;
  for (let i = open; i < buf.length; i++) {
    const c = buf[i];
    if (inStr) {
      if (esc) esc = false;
      else if (c === 92) esc = true;
      else if (c === 34) inStr = false;
      continue;
    }
    if (c === 34) inStr = true;
    else if (c === 123) depth++;
    else if (c === 125) {
      depth--;
      if (depth === 0) return i;
    }
  }
  return -1;
}

function finite(v) {
  if (v === null || v === undefined || v === '') return undefined;
  const n = Number(v);
  return Number.isFinite(n) ? Math.round(n) : undefined;
}

function rowsOf(data) {
  return Array.isArray(data && data.results) ? data.results.filter((r) => r && typeof r === 'object') : [];
}

function slim(r, kind) {
  if (!r || typeof r !== 'object' || !r.id) return null;
  const title = r.title || r.name || '';
  const date = r.release_date || r.first_air_date || '';
  const genreIds = Array.isArray(r.genre_ids) ? r.genre_ids : [];
  return {
    id: r.id, kind, title, year: /^\d{4}/.test(date) ? Number(date.slice(0, 4)) : null,
    overview: String(r.overview || '').slice(0, 110),
    genres: genreIds.map((g) => NAMES.get(g)).filter(Boolean),
    lang: r.original_language || '', rating: r.vote_average ? Math.round(r.vote_average * 10) / 10 : null,
  };
}
