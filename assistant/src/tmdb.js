// TMDB calls, trimmed to the fields a turn needs (this keeps the CPU time of parsing small).
import { UpstreamError } from './groq.js';
import { serviceOf, similarity, simplify } from './match.js';

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

export function genreIds(names, kind) {
  const out = new Set();
  for (const n of names || []) {
    const key = String(n).toLowerCase().replace(/[^a-z]/g, '').replace(/^sci?fi.*|sciencefiction/, 'scifi');
    if (kind === 'tv' && key in TV_GENRES) out.add(TV_GENRES[key]);
    else if (key in GENRES && !(kind === 'tv' && ['horror', 'romance'].includes(key))) out.add(GENRES[key]);
  }
  return [...out];
}

export class Tmdb {
  constructor(env, fetchFn, stats, cache) {
    this.env = env;
    this.fetch = fetchFn;
    this.stats = stats;
    this.cache = cache;
  }

  async get(path, params = {}) {
    const url = new URL(BASE + path);
    for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== '') url.searchParams.set(k, String(v));
    const headers = { accept: 'application/json' };
    const key = this.env.TMDB_KEY || '';
    if (key.startsWith('eyJ')) headers.authorization = `Bearer ${key}`;   // a v4 read token
    else url.searchParams.set('api_key', key);
    this.stats.tmdb += 1;
    const res = await this.fetch(url.toString(), { headers });
    if (!res.ok) throw new UpstreamError(`tmdb ${res.status}`, 502);
    return res.json();
  }

  /** TMDB's provider ids, by service key, for the region; the list is cached for a while in the isolate. */
  async providerIds(region, services) {
    let list = this.cache.get(region);
    if (!list || list.at < Date.now() - 6 * 3600e3) {
      const data = await this.get('/watch/providers/movie', { watch_region: region });
      list = { at: Date.now(), items: (data.results || []).map((p) => [p.provider_id, serviceOf(p.provider_name, p.provider_id)]) };
      this.cache.set(region, list);
    }
    const want = new Set(services);
    return list.items.filter(([, key]) => key && want.has(key)).map(([id]) => id);
  }

  /** Titles matching what was said, best first. A term that finds nothing is tried again, loosened. */
  async search(term, lang) {
    const run = async (q) => {
      const data = await this.get('/search/multi', { query: q, language: lang, include_adult: 'false' });
      return (data.results || []).filter((r) => r.media_type === 'movie' || r.media_type === 'tv');
    };
    let found = await run(term);
    if (!found.length) {
      const loose = simplify(term);
      if (loose && loose !== term) found = await run(loose);
    }
    const score = (r) => Math.max(similarity(term, r.title || r.name), similarity(term, r.original_title || r.original_name));
    return found
      .map((r) => ({ r, s: score(r) }))
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
      with_genres: genreIds(d.genres, kind).join(','),
      'with_runtime.lte': Number.isFinite(d.runtime_lte) ? Math.round(d.runtime_lte) : undefined,
      with_original_language: /^[a-z]{2,3}$/.test(d.language || '') ? d.language : undefined,
      sort_by: rating ? 'vote_average.desc' : 'popularity.desc',
      'vote_count.gte': rating ? 300 : undefined,
    };
    if (Number.isFinite(d.year_gte)) params[movie ? 'primary_release_date.gte' : 'first_air_date.gte'] = `${Math.round(d.year_gte)}-01-01`;
    const data = await this.get(`/discover/${kind}`, params);
    return (data.results || []).slice(0, 10).map((r) => slim(r, kind));
  }

  async recommendations(kind, id, lang) {
    const data = await this.get(`/${kind}/${id}/recommendations`, { language: lang });
    return (data.results || []).slice(0, 6).map((r) => slim(r, kind));
  }

  /** The TV's own service that streams this title in the region (flat rate), or null. */
  async serviceFor(c, region, services) {
    try {
      const data = await this.get(`/${c.kind}/${c.id}/watch/providers`);
      const flat = ((data.results || {})[region] || {}).flatrate || [];
      const want = new Set(services);
      for (const p of flat) {
        const key = serviceOf(p.provider_name, p.provider_id);
        if (key && want.has(key)) return key;
      }
    } catch (e) {
      // one title's providers failing only means no service on its card
    }
    return null;
  }
}

function slim(r, kind) {
  const title = r.title || r.name || '';
  const date = r.release_date || r.first_air_date || '';
  return {
    id: r.id, kind, title, year: /^\d{4}/.test(date) ? Number(date.slice(0, 4)) : null,
    overview: String(r.overview || '').slice(0, 110),
    genres: (r.genre_ids || []).map((g) => NAMES.get(g)).filter(Boolean),
    lang: r.original_language || '', rating: r.vote_average ? Math.round(r.vote_average * 10) / 10 : null,
  };
}
