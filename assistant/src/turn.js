// One turn: plan (LLM) -> fetch (TMDB, parallel) -> choose (LLM) -> check. See claude/research/ai-assistant.md, section 4.
import { chatJson, UpstreamError } from './groq.js';
import { KNOWN_SERVICES } from './match.js';
import { chooseSystem, planSystem } from './prompts.js';
import { Tmdb } from './tmdb.js';

export const REPLY_MAX = 220;
const MAX_CANDIDATES = 15;
const INTENTS = new Set(['recommend', 'search', 'open_app', 'chat']);

export class BadRequest extends Error {}

/** Checks the v1 request and returns a clean copy. */
export function parseRequest(body) {
  if (!body || typeof body !== 'object' || body.v !== 1) throw new BadRequest('unsupported version');
  const query = typeof body.query === 'string' ? body.query.trim() : '';
  if (!query || query.length > 400) throw new BadRequest('query');
  const lang = /^[a-z]{2,3}(-[A-Za-z]{2,4})?$/.test(body.lang || '') ? body.lang : 'en';
  const region = /^[A-Za-z]{2}$/.test(body.region || '') ? body.region.toUpperCase() : 'US';
  const services = [...new Set((Array.isArray(body.services) ? body.services : []).filter((s) => KNOWN_SERVICES.includes(s)))].slice(0, 20);
  const history = (Array.isArray(body.history) ? body.history : []).slice(-6).map((h) => ({
    said: String((h && h.said) || '').slice(0, 200),
    reply: String((h && h.reply) || '').slice(0, 220),
    picks: (Array.isArray(h && h.picks) ? h.picks : []).slice(0, 5).map((p, i) => ({
      n: Number.isInteger(p && p.n) ? p.n : i + 1, id: Number(p && p.id) || 0,
      kind: p && p.kind === 'tv' ? 'tv' : 'movie', title: String((p && p.title) || '').slice(0, 80),
      year: Number.isInteger(p && p.year) ? p.year : null,
    })).filter((p) => p.id > 0),
  }));
  return { query, lang, region, services, history };
}

function capReply(text) {
  let s = String(text || '').replace(/\s+/g, ' ').trim();
  if (s.length <= REPLY_MAX) return s;
  s = s.slice(0, REPLY_MAX);
  const stop = Math.max(s.lastIndexOf('. '), s.lastIndexOf('! '), s.lastIndexOf('? '));
  if (stop > 60) return s.slice(0, stop + 1);
  const space = s.lastIndexOf(' ');
  return (space > 60 ? s.slice(0, space) : s.slice(0, REPLY_MAX - 1)).replace(/[ ,;:]+$/, '') + '…';
}

function historyText(history) {
  return history.map((h) => `- said: ${h.said}\n  reply: ${h.reply}\n  picks: ${JSON.stringify(h.picks)}`).join('\n');
}

export async function runTurn(req, env, deps) {
  const { fetch, now, cache } = deps;
  const stats = { llm: 0, tmdb: 0, groq: null };
  const ctx = { env, fetch, now, stats, deadline: now() + 5500 };
  const tmdb = new Tmdb(env, fetch, stats, cache);
  const lang = req.lang;
  const out = (body) => ({ body, stats });

  // 1. Plan
  const plan = await chatJson([
    { role: 'system', content: planSystem(req.services, req.region) },
    { role: 'user', content: `History (oldest first):\n${historyText(req.history) || '(none)'}\n\nThe person said: ${JSON.stringify(req.query)}` },
  ], ctx);
  const intent = INTENTS.has(plan.intent) ? plan.intent : 'recommend';

  if (intent === 'open_app') {
    if (req.services.includes(plan.app)) return out({ reply: capReply(plan.reply) || 'Opening it.', intent, app: plan.app, picks: [] });
    return out({ reply: "That app isn't on this TV.", intent: 'chat', picks: [] });
  }
  if (intent === 'chat') return out({ reply: capReply(plan.reply) || "I can help you find something to watch.", intent, picks: [] });

  // 2. Fetch (in parallel)
  const known = new Map();   // "kind:id" -> candidate: the only ids this turn may return
  const add = (list) => { for (const c of list) if (c.id && !known.has(`${c.kind}:${c.id}`)) known.set(`${c.kind}:${c.id}`, c); };
  const searches = (Array.isArray(plan.searches) ? plan.searches : []).filter((s) => typeof s === 'string' && s.trim()).slice(0, 4);
  const jobs = searches.map((t) => tmdb.search(t, lang));
  const like = plan.like && req.history.some((h) => h.picks.some((p) => p.id === plan.like.id && p.kind === plan.like.kind)) ? plan.like : null;
  if (like) jobs.push(tmdb.recommendations(like.kind, like.id, lang).then((l) => l.map((c) => ({ ...c, rec: true }))));
  const d = plan.discover && typeof plan.discover === 'object' ? plan.discover : null;
  if (d) {
    jobs.push(tmdb.providerIds(req.region, req.services).then((ids) => {
      const kinds = d.kind === 'tv' ? ['tv'] : d.kind === 'movie' ? ['movie'] : ['movie', 'tv'];
      return Promise.all(kinds.map((k) => tmdb.discover(k, d, lang, req.region, ids))).then((r) => r.flat());
    }));
  }
  const results = await Promise.all(jobs);
  // Title matches first (what the person named), then "more like", then the filtered list, interleaved by kind.
  const named = results.slice(0, searches.length).flat();
  const rest = results.slice(searches.length);
  add(named.slice(0, 8));
  for (let i = 0; i < 12 && known.size < MAX_CANDIDATES; i++) for (const list of rest) if (list[i] && known.size < MAX_CANDIDATES) add([list[i]]);

  const candidates = [...known.values()].slice(0, MAX_CANDIDATES);
  if (!candidates.length) return out({ reply: "I couldn't find anything for that.", intent: 'search', picks: [] });
  await Promise.all(candidates.map(async (c) => { c.service = await tmdb.serviceFor(c, req.region, req.services); }));

  // 3. Choose
  const view = candidates.map((c) => ({
    id: c.id, kind: c.kind, title: c.title, year: c.year, genres: c.genres, rating: c.rating,
    service: c.service, about: c.overview,
  }));
  const chosen = await chatJson([
    { role: 'system', content: chooseSystem(req.lang) },
    { role: 'user', content: `History (oldest first):\n${historyText(req.history) || '(none)'}\n\nThe person said: ${JSON.stringify(req.query)}\n`
      + (d && Number.isFinite(d.runtime_lte) ? `Titles were filtered to at most ${Math.round(d.runtime_lte)} minutes.\n` : '')
      + `\nCandidates:\n${JSON.stringify(view)}` },
  ], ctx);

  // 4. Check: only this turn's own ids, once each, at most 5; the app only when this region lists it
  const picks = [];
  const seen = new Set();
  for (const p of Array.isArray(chosen.picks) ? chosen.picks : []) {
    const c = candidates.find((x) => x.id === Number(p && p.id));
    if (!c || seen.has(c.id)) continue;
    seen.add(c.id);
    picks.push(toPick(c, p.why));
    if (picks.length === 5) break;
  }
  if (picks.length < 3) {   // top up from the candidates, ones on the TV's apps first
    for (const c of [...candidates].sort((a, b) => Number(!!b.service) - Number(!!a.service))) {
      if (picks.length >= 3) break;
      if (!seen.has(c.id)) { seen.add(c.id); picks.push(toPick(c, '')); }
    }
  }
  return out({ reply: capReply(chosen.reply) || 'Here is what I found.', intent, picks });
}

function toPick(c, why) {
  return { id: c.id, kind: c.kind, title: c.title, year: c.year, service: c.service || null, why: String(why || '').trim().slice(0, 60) };
}

export { UpstreamError };
