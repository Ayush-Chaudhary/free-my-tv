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

export const KNOWN_SERVICES = Object.keys(NAME);

/** The service key for a TMDB provider, or null. "HBO Max Amazon Channel" and the like are sold inside another app. */
export function serviceOf(providerName, providerId) {
  const n = String(providerName || '').toLowerCase();
  if (n.includes(' channel')) return null;
  for (const [key, name] of Object.entries(NAME)) {
    if (key === 'appletv' && providerId !== 350) continue;   // 350 is the streaming service; 2 is the Apple TV Store
    const hit = key === 'max' ? /\bmax\b/.test(n) : WHOLE_NAME.has(key) ? n === name : n.includes(name);
    if (hit) return key;
  }
  return null;
}

export function norm(s) {
  return String(s || '').toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '')
    .replace(/[^\p{L}\p{N} ]+/gu, ' ').replace(/\s+/g, ' ').trim();
}

/** Sounds-alike folding for Indian English speech: w/v, ph/f, aspirates, doubled and long vowels. */
export function fold(s) {
  return norm(s)
    .replace(/ph/g, 'f').replace(/w/g, 'v').replace(/z/g, 'j')
    .replace(/(k|g|c|j|t|d|b|p)h/g, '$1').replace(/th/g, 't')
    .replace(/aa/g, 'a').replace(/ee/g, 'i').replace(/oo/g, 'u').replace(/ou/g, 'u')
    .replace(/[eyi]+/g, 'i').replace(/[ou]+/g, 'u').replace(/a+/g, 'a')
    .replace(/(.)\1+/g, '$1').replace(/ +/g, ' ').trim();
}

function dice(a, b) {
  const grams = (s) => {
    const t = ` ${s} `, m = new Map();
    for (let i = 0; i < t.length - 1; i++) m.set(t.slice(i, i + 2), (m.get(t.slice(i, i + 2)) || 0) + 1);
    return m;
  };
  if (!a || !b) return 0;
  const x = grams(a), y = grams(b);
  let hit = 0, total = 0;
  for (const [g, n] of x) { hit += Math.min(n, y.get(g) || 0); total += n; }
  for (const n of y.values()) total += n;
  return total ? (2 * hit) / total : 0;
}

/** 0..1: how alike two titles read, ignoring spaces ("dilwale" / "dil wale") and Indian-English spelling differences. */
export function similarity(a, b) {
  const x = norm(a), y = norm(b);
  const plain = Math.max(dice(x, y), dice(x.replace(/ /g, ''), y.replace(/ /g, '')));
  const fx = fold(a), fy = fold(b);
  const folded = Math.max(dice(fx, fy), dice(fx.replace(/ /g, ''), fy.replace(/ /g, '')));
  return Math.max(plain, folded);
}

const STOP = new Set(['the', 'a', 'an', 'movie', 'film', 'show', 'series', 'web', 'season', 'dikhao', 'lagao', 'chalao']);

/** A looser version of a search term for a second try: without filler words, or without its last word. */
export function simplify(term) {
  const words = norm(term).split(' ').filter((w) => w && !STOP.has(w));
  const base = norm(term).split(' ').filter(Boolean);
  if (words.length && words.length < base.length) return words.join(' ');
  return words.length > 2 ? words.slice(0, -1).join(' ') : '';
}
