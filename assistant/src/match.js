// Matching: TMDB provider names to the TV's service keys, and titles to what was said (fuzzy, accent-tolerant).

// The service keys the app sends (Recs.SERVICES) and the TMDB provider name each one matches, as the app does.
const NAME = {
  netflix: 'netflix', prime: 'prime video', disney: 'disney', hulu: 'hulu', paramount: 'paramount',
  peacock: 'peacock', globoplay: 'globoplay', max: 'max', appletv: 'apple tv', hotstar: 'hotstar',
  crunchyroll: 'crunchyroll', now: 'now tv', oneplay: 'oneplay', ard: 'ard mediathek', zdf: 'zdf',
  arte: 'arte', joyn: 'joyn', rtl: 'rtl+', wow: 'wow', magenta: 'magenta', okko: 'okko',
  kinopoisk: 'kinopoisk', premier: 'premier', amediateka: 'amediateka', wink: 'more tv', youtube: 'youtube',
};
const WHOLE_NAME = new Set(['wow', 'arte']);
const MAX_WORD = /\bmax\b/;
// Built once: serviceOf used to allocate Object.entries and a fresh /max/ test on every provider.
const RULES = Object.entries(NAME).map(([key, name]) => ({
  key,
  apple: key === 'appletv',
  hit: key === 'max' ? (n) => MAX_WORD.test(n) : WHOLE_NAME.has(key) ? (n) => n === name : (n) => n.includes(name),
}));

export const KNOWN_SERVICES = Object.keys(NAME);

/** The service key for a TMDB provider, or null. "HBO Max Amazon Channel" and the like are sold inside another app. */
export function serviceOf(providerName, providerId) {
  const n = String(providerName || '').toLowerCase();
  if (n.includes(' channel')) return null;
  for (const rule of RULES) {
    if (rule.apple && providerId !== 350) continue;   // 350 is the streaming service; 2 is the Apple TV Store
    if (rule.hit(n)) return rule.key;
  }
  return null;
}

const MARKS = /[̀-ͯ]/g;
const NOT_WORD = /[^\p{L}\p{N} ]+/gu;
const SPACES = /\s+/g;
const RE_PH = /ph/g;
const RE_W = /w/g;
const RE_Z = /z/g;
const RE_ASP = /(k|g|c|j|t|d|b|p)h/g;
const RE_TH = /th/g;
const RE_AA = /aa/g;
const RE_EE = /ee/g;
const RE_OO = /oo/g;
const RE_OU = /ou/g;
const RE_EYI = /[eyi]+/g;
const RE_OU2 = /[ou]+/g;
const RE_A = /a+/g;
const RE_DUP = /(.)\1+/g;
const RE_GAP = / +/g;

export function norm(s) {
  return String(s || '').toLowerCase().normalize('NFKD').replace(MARKS, '')
    .replace(NOT_WORD, ' ').replace(SPACES, ' ').trim();
}

/** Sounds-alike folding for Indian English speech: w/v, ph/f, aspirates, doubled and long vowels. */
function foldFromNorm(n) {
  return n
    .replace(RE_PH, 'f').replace(RE_W, 'v').replace(RE_Z, 'j')
    .replace(RE_ASP, '$1').replace(RE_TH, 't')
    .replace(RE_AA, 'a').replace(RE_EE, 'i').replace(RE_OO, 'u').replace(RE_OU, 'u')
    .replace(RE_EYI, 'i').replace(RE_OU2, 'u').replace(RE_A, 'a')
    .replace(RE_DUP, '$1').replace(RE_GAP, ' ').trim();
}

export function fold(s) {
  return foldFromNorm(norm(s));
}

function grams(s) {
  const t = ` ${s} `;
  const m = new Map();
  for (let i = 0, n = t.length - 1; i < n; i++) {
    const g = t[i] + t[i + 1];
    m.set(g, (m.get(g) || 0) + 1);
  }
  return m;
}

function dicePrepared(left, right) {
  if (!right) return 0;
  const y = grams(right);
  let hit = 0, total = 0;
  for (const [g, n] of left) { hit += Math.min(n, y.get(g) || 0); total += n; }
  for (const n of y.values()) total += n;
  return total ? (2 * hit) / total : 0;
}

function nospace(s) {
  return s.indexOf(' ') >= 0 ? s.replace(/ /g, '') : s;
}

/** Bigrams of a search term, built once per search and reused for every TMDB row. */
export function prepareTitle(term) {
  const n = norm(term);
  const f = foldFromNorm(n);
  const ns = nospace(n);
  const fs = nospace(f);
  return { n, ns, gn: grams(n), gf: grams(f), gns: grams(ns), gfs: grams(fs) };
}

function simPrepared(prep, title) {
  const y = norm(title);
  if (!y) return 0;
  if (y === prep.n) return 1;
  const plain = Math.max(dicePrepared(prep.gn, y), dicePrepared(prep.gns, nospace(y)));
  if (plain === 1) return 1;
  const fy = foldFromNorm(y);
  const folded = Math.max(dicePrepared(prep.gf, fy), dicePrepared(prep.gfs, nospace(fy)));
  return plain > folded ? plain : folded;
}

/** 0..1: how alike two titles read, ignoring spaces ("dilwale" / "dil wale") and Indian-English spelling differences. */
export function similarity(a, b) {
  return simPrepared(prepareTitle(a), b);
}

export const SCORE_CAP = 12;

/** Best-first scores for up to SCORE_CAP rows. The query's grams are built once. */
export function rankRows(term, rows) {
  const prep = prepareTitle(term);
  const pool = rows.length > SCORE_CAP ? rows.slice(0, SCORE_CAP) : rows;
  const scored = new Array(pool.length);
  for (let i = 0; i < pool.length; i++) scored[i] = { r: pool[i], s: simPrepared(prep, pool[i].title || pool[i].name || '') };
  // A second pass only for rows that are not already an exact title hit: original_title can still win.
  for (let i = 0; i < pool.length; i++) {
    if (scored[i].s === 1) continue;
    const orig = pool[i].original_title || pool[i].original_name;
    if (!orig) continue;
    const s2 = simPrepared(prep, orig);
    if (s2 > scored[i].s) scored[i].s = s2;
  }
  return scored;
}

const STOP = new Set(['the', 'a', 'an', 'movie', 'film', 'show', 'series', 'web', 'season', 'dikhao', 'lagao', 'chalao']);

/** A looser version of a search term for a second try: without filler words, or without its last word. */
export function simplify(term) {
  const base = norm(term).split(' ').filter(Boolean);
  const words = base.filter((w) => !STOP.has(w));
  if (words.length && words.length < base.length) return words.join(' ');
  return words.length > 2 ? words.slice(0, -1).join(' ') : '';
}
