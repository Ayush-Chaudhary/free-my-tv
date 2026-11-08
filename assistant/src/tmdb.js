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

export class Tmdb {
  constructor(env, fetchFn, stats, cache) {
    this.env = env;
    // Workers' fetch throws if it is called as a method (`this` would be the Tmdb instance).
    const impl = fetchFn ?? globalThis.fetch;
    this.fetch = (...args) => impl(...args);
    this.stats = stats;
    this.cache = cache;
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
