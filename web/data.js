/*
 * What the home screen shows, kept in localStorage so it opens with numbers:
 *  - the picks: what's trending this week and recent releases, only from the streaming apps on this TV (TMDB, with
 *    the person's own free key from config.properties), 3 per hour (up to 6 in Home Settings);
 *  - weather, sunrise and sunset (Open-Meteo, every 30 min) and optional crypto prices (Binance, every 5 min).
 * Settings changed on the TV (Prefs) win over config.properties, as on Android. Built without a city (--generic), the
 * TV's time zone gives the city, and its country the currency and the region (zones.js).
 */
var CONFIG = window.CONFIG || {};

var Place = (function () {
  'use strict';
  var EURO = /^(AT|BE|BG|CY|DE|EE|ES|FI|FR|GR|HR|IE|IT|LT|LU|LV|MT|NL|PT|SI|SK)$/;

  /** A time zone's main city: {country, lat, lon, name}, or null. */
  function zone(name) {
    var all = window.ZONES || {}, v = all[name], hops = 0;
    while (v && v.charAt(0) === '=' && hops++ < 3) { name = v.slice(1); v = all[name]; }
    if (!v) return null;
    var p = v.split(',');
    return {country: p[0], lat: p[1], lon: p[2], name: p[3] || name.slice(name.lastIndexOf('/') + 1).replace(/_/g, ' ')};
  }

  var tz = zone(TV.zone()), set = CONFIG.latitude !== '' && CONFIG.latitude !== undefined;
  var country = (CONFIG.tmdbRegion || (tz ? tz.country : '') || 'US').toUpperCase();
  return {
    lat: set ? CONFIG.latitude : tz ? tz.lat : '40.7128',
    lon: set ? CONFIG.longitude : tz ? tz.lon : '-74.0060',
    city: CONFIG.city || (!set && tz ? tz.name : ''),
    country: country,
    currency: (CONFIG.currency || (country === 'BR' ? 'BRL' : EURO.test(country) ? 'EUR' : 'USD')).toUpperCase(),
    /** Fahrenheit for TVs in the US (by their time zone, or their language when it doesn't tell). */
    fahrenheit: tz ? tz.country === 'US' : /-US$/i.test(navigator.language || '')
  };
})();

var Prefs = (function () {
  'use strict';
  var KEY = 'fmtv.prefs';
  var v = {};
  try { v = JSON.parse(localStorage.getItem(KEY)) || {}; } catch (e) {}
  function save() { try { localStorage.setItem(KEY, JSON.stringify(v)); } catch (e) {} }
  function pick(name, fallback) { return v[name] === undefined ? fallback : v[name]; }
  return {
    version: 0,
    language: function () { return pick('language', CONFIG.language || 'auto'); },
    temperature: function () { return pick('temperature', CONFIG.temperature || 'auto'); },
    /** Dark or Light. Anything older ('auto', a missing value) is Dark, the default. */
    theme: function () {
      var t = pick('theme', CONFIG.theme || 'dark');
      return t === 'light' ? 'light' : 'dark';
    },
    weather: function () { return pick('weather', true); },
    /** OK on the screensaver opens the title it shows, in its app; any other key only closes it (off by default). */
    saverWatch: function () { return pick('saverWatch', false) === true; },
    /** The weather card's forecast: 'hours' (the next 6 hours) or 'days' (the next 5 days). */
    forecast: function () { return pick('forecast', 'hours') === 'days' ? 'days' : 'hours'; },
    quotes: function () { return pick('quotes', CONFIG.showCrypto !== false); },
    coin: function (i) { var c = pick('coins', null); return c ? c[i] !== false : true; },
    /** Picks per tab: 3 (as it was) up to 6. */
    cards: function () { var n = pick('cards', 3); return n < 3 ? 3 : n > 6 ? 6 : n; },
    shelf: function () { return pick('shelf', null); },
    /** The shelf_apps of the build that last set the shelf, so a new one wins once over the TV's choice. */
    shelfConfig: function () { return pick('shelfConfig', ''); },
    /** The weather's city, picked on the TV (Home Settings → City), or config.properties' (or the time zone's). */
    city: function () { return pick('city', Place.city); },
    latitude: function () { return pick('latitude', Place.lat); },
    longitude: function () { return pick('longitude', Place.lon); },
    /** The TMDB key typed on the TV (Home Settings → TMDB key), or config.properties' ('' for none). */
    tmdbKey: function () { return String(pick('tmdbKey', CONFIG.tmdbKey || '')).trim(); },
    /** True when the key in use was typed on the TV (Remove takes it back to config.properties'). */
    tmdbKeyOnTV: function () { return v.tmdbKey !== undefined; },
    setCity: function (name, lat, lon) { v.city = name; v.latitude = lat; v.longitude = lon; save(); this.version++; },
    /** Streaming services (keys like "netflix") whose titles are left out of the picks. */
    serviceOff: function (key) { return pick('servicesOff', []).indexOf(key) >= 0; },
    setServiceOff: function (key, off) {
      var list = pick('servicesOff', []).filter(function (k) { return k !== key; });
      if (off) list.push(key);
      this.set('servicesOff', list);
    },
    set: function (name, value) { v[name] = value; save(); this.version++; },
    setCoin: function (i, on) {
      var c = (pick('coins', null) || [true, true, true, true]).slice();
      c[i] = on;
      this.set('coins', c);
    }
  };
})();

var Data = (function () {
  'use strict';

  function store(key, value) { try { localStorage.setItem('fmtv.' + key, JSON.stringify(value)); } catch (e) {} }
  function stored(key) { try { return JSON.parse(localStorage.getItem('fmtv.' + key)); } catch (e) { return null; } }

  /** GET a JSON document; done(error, json). */
  function get(url, headers, done) {
    var x = new XMLHttpRequest();
    var over = false;
    x.open('GET', url, true);
    x.timeout = 10000;
    for (var h in headers) if (headers.hasOwnProperty(h)) x.setRequestHeader(h, headers[h]);
    x.onreadystatechange = function () {
      if (x.readyState !== 4 || over) return;
      over = true;
      if (x.status !== 200) { done(new Error(x.status === 401 ? 'TMDB rejected the key' : 'HTTP ' + x.status)); return; }
      var json;
      try { json = JSON.parse(x.responseText); } catch (e) { done(e); return; }
      done(null, json);
    };
    x.ontimeout = x.onerror = function () { if (!over) { over = true; done(new Error('no connection')); } };
    x.send();
  }

  var listeners = [];
  function changed() { for (var i = 0; i < listeners.length; i++) listeners[i](); }

  // MARK: - Weather

  function place() { return Prefs.latitude() + ',' + Prefs.longitude(); }
  // Both forecasts in one answer, the next 48 hours and today plus 5 days: Home Settings → Forecast switches without
  // asking again, and offline too.
  var QUERY = '&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset,weather_code'
      + '&hourly=temperature_2m,weather_code,is_day&timezone=auto&forecast_days=6&forecast_hours=48';
  var weather = null, weatherAt = 0, fetchingWeather = false;
  (function () {
    var w = stored('weather');
    // Fetched with another query (an older version): shown, and fetched again now.
    if (w && w.place === place()) { weather = parseWeather(w.body); weatherAt = w.query === QUERY ? w.at : 0; }
  })();

  /** "2026-10-20T18:31" at the location (Open-Meteo's local time) → epoch ms. */
  function localTime(iso, offsetSeconds) {
    var m = /^(\d+)-(\d+)-(\d+)T(\d+):(\d+)/.exec(iso || '');
    if (!m) return 0;
    return Date.UTC(+m[1], m[2] - 1, +m[3], +m[4], +m[5]) - offsetSeconds * 1000;
  }

  function parseWeather(o) {
    if (!o || !o.current) return null;
    var off = o.utc_offset_seconds || 0, dl = o.daily, now = Date.now();
    var w = {
      temp: o.current.temperature_2m, code: o.current.weather_code, isDay: o.current.is_day !== 0,
      max: dl.temperature_2m_max[0], min: dl.temperature_2m_min[0],
      sunrise: localTime(dl.sunrise[0], off), sunset: localTime(dl.sunset[0], off), hours: [], days: []
    };
    var hr = o.hourly;
    for (var i = 0; hr && i < hr.time.length && w.hours.length < 6; i++) {
      var at = localTime(hr.time[i], off);
      if (at <= now) continue;
      w.hours.push({at: at, temp: hr.temperature_2m[i], code: hr.weather_code[i], day: !hr.is_day || hr.is_day[i] !== 0});
    }
    // The next 5 days, from tomorrow (none in a forecast cached before the days). Only days that haven't started at
    // the city: today, and past days in a forecast cached offline, are left out.
    for (var j = 0; dl.weather_code && j < dl.time.length && w.days.length < 5; j++) {
      if (localTime(dl.time[j] + 'T12:00', off) - 12 * 3600000 <= now) continue;
      var p = dl.time[j].split('-');   // named by its noon on the TV's clock, right even with the city far away
      w.days.push({at: new Date(+p[0], p[1] - 1, +p[2], 12).getTime(), max: dl.temperature_2m_max[j],
        min: dl.temperature_2m_min[j], code: dl.weather_code[j]});
    }
    return w;
  }

  function refreshWeather() {
    var now = Date.now();
    if (fetchingWeather || (now - weatherAt < 30 * 60000 && now >= weatherAt)) return;
    fetchingWeather = true;
    var at = place();
    var url = 'https://api.open-meteo.com/v1/forecast?latitude=' + Prefs.latitude() + '&longitude=' + Prefs.longitude() + QUERY;
    get(url, {}, function (err, body) {
      fetchingWeather = false;
      if (at !== place()) { refreshWeather(); return; }   // another city was picked meanwhile
      if (err) return;
      weather = parseWeather(body);
      weatherAt = Date.now();
      store('weather', {place: at, body: body, at: weatherAt, query: QUERY});
      changed();
    });
  }

  /** Places with this name (Open-Meteo, in the app's language): done(error, [{label, city, lat, lon}]). */
  function findCity(name, done) {
    var lang = I18N.lang.replace(/-.*/, '');   // Open-Meteo takes the language without the country
    get('https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=' + lang + '&name='
        + encodeURIComponent(name.trim()), {}, function (err, body) {
      if (err) { done(err); return; }
      var results = (body && body.results) || [], out = [], seen = {};
      for (var i = 0; i < results.length; i++) {
        var r = results[i], city = r.name || '';
        if (!city || typeof r.latitude !== 'number' || typeof r.longitude !== 'number') continue;
        var label = city, parts = [r.admin1, r.country];
        for (var j = 0; j < parts.length; j++) if (parts[j] && parts[j] !== city) label += ', ' + parts[j];
        if (seen['#' + label]) continue;   // places with the same name, state and country read the same
        seen['#' + label] = true;
        out.push({label: label, city: city, lat: r.latitude.toFixed(4), lon: r.longitude.toFixed(4)});
      }
      done(null, out);
    });
  }

  // MARK: - Prices

  var COINS = ['BTC', 'ETH', 'SOL', 'USDT'];
  var CURRENCY = Place.currency;
  var QUOTE = CURRENCY === 'USD' ? 'USDT' : CURRENCY;
  /** Binance refuses the whole request when it has no pairs in the currency (INR...): the prices go in dollars then. */
  function inDollars() {
    store('dollarsFor', CURRENCY);
    CURRENCY = 'USD';
    QUOTE = 'USDT';
  }
  /** A pair with no trade for a day: Binance halted it and keeps answering with the frozen last price (AUD since 2026-10-06). */
  function halted(list) {
    for (var i = 0; list && i < list.length; i++) if (list[i].closeTime > 0 && Date.now() - list[i].closeTime > 864e5) return true;
    return false;
  }
  if (stored('dollarsFor') === CURRENCY && CURRENCY !== 'USD') {
    CURRENCY = 'USD';
    QUOTE = 'USDT';
  }
  var prices = [NaN, NaN, NaN, NaN], changes = [NaN, NaN, NaN, NaN], pricesAt = 0, fetchingPrices = false;

  /** Indexes in COINS of the coins that are on (the dollar only in Brazilian reais, where Binance lists it). */
  function activeCoins() {
    var out = [];
    if (!Prefs.quotes()) return out;
    for (var i = 0; i < COINS.length; i++) if (Prefs.coin(i) && (i < 3 || CURRENCY === 'BRL')) out.push(i);
    return out;
  }

  function parsePrices(list) {
    var found = 0;
    for (var i = 0; list && i < list.length; i++) {
      for (var k = 0; k < COINS.length; k++) {
        if (list[i].symbol !== COINS[k] + QUOTE) continue;
        found++;
        var last = parseFloat(list[i].lastPrice), open = parseFloat(list[i].openPrice);
        prices[k] = last;
        changes[k] = open > 0 ? (last - open) / open * 100 : NaN;
      }
    }
    return found;
  }

  (function () {
    var p = stored('prices');
    if (p && p.quote === QUOTE && parsePrices(p.body)) pricesAt = p.at;
  })();

  function refreshPrices() {
    var coins = activeCoins(), now = Date.now();
    if (!coins.length || fetchingPrices || (now - pricesAt < 5 * 60000 && now >= pricesAt)) return;
    fetchingPrices = true;
    var symbols = coins.map(function (i) { return '%22' + COINS[i] + QUOTE + '%22'; }).join(',');
    var path = '/api/v3/ticker/24hr?type=MINI&symbols=%5B' + symbols + '%5D';
    // Binance.com refuses the US (HTTP 451); Binance.US answers the same call in dollars.
    var hosts = ['https://api.binance.com', 'https://api.binance.us'];
    (function next(h, refused) {
      get(hosts[h] + path, {}, function (err, body) {
        // A pair Binance doesn't list (HTTP 400): the prices go in dollars. Binance.US has only dollar pairs: its 400
        // counts only where Binance.com refuses the US (HTTP 451), not after a timeout or a 5xx there.
        if (err && err.message === 'HTTP 400' && QUOTE !== 'USDT' && (h === 0 || refused)) { fetchingPrices = false; inDollars(); refreshPrices(); return; }
        // Halted: dollars for this run, not saved like the 400's (the market may open again).
        if (!err && QUOTE !== 'USDT' && halted(body)) { fetchingPrices = false; CURRENCY = 'USD'; QUOTE = 'USDT'; refreshPrices(); return; }
        if (err || !parsePrices(body)) {
          if (h + 1 < hosts.length && !(err && err.message === 'HTTP 400')) next(h + 1, err && err.message === 'HTTP 451');
          else fetchingPrices = false;
          return;
        }
        fetchingPrices = false;
        pricesAt = Date.now();
        store('prices', {quote: QUOTE, body: body, at: pricesAt});
        changed();
      });
    })(0);
  }

  // MARK: - Picks (TMDB)

  var KEY = Prefs.tmdbKey();
  var demoOn = false, demoAll = [];
  var REGION = Place.country;
  var API = 'https://api.tmdb.org/3';   // TMDB's own mirror of api.themoviedb.org: some ISPs block the other name (#327)

  /** Service name on TMDB (part of it, lowercase) → our key, how to find its app on the TV (id, name), its name. */
  var SERVICES = [
    ['netflix', 'netflix', /^netflix$/, /netflix/i, 'Netflix'],
    ['prime video', 'prime', /^amazon$/, /prime video|amazon/i, 'Prime Video'],
    ['disney', 'disney', /disney/, /disney/i, 'Disney+'],
    ['hulu', 'hulu', /^hulu/, /hulu/i, 'Hulu'],
    ['paramount', 'paramount', /paramount|cbs/, /paramount/i, 'Paramount+'],
    ['peacock', 'peacock', /peacock/, /peacock/i, 'Peacock'],
    ['globoplay', 'globoplay', /globoplay/, /globoplay/i, 'Globoplay'],
    ['max', 'max', /hbo|wbd|\.max/, /^(hbo )?max$|hbo max/i, 'Max'],
    ['apple tv', 'appletv', /appletv|apple\.tv/, /apple tv/i, 'Apple TV'],
    ['hotstar', 'hotstar', /hotstar/, /hotstar/i, 'JioHotstar'],
    ['crunchyroll', 'crunchyroll', /crunchyroll/, /crunchyroll/i, 'Crunchyroll'],
    ['now tv', 'now', /nowtv|now\.tv/, /^now( tv)?$/i, 'NOW']
  ];

  var installed = [];   // the TV's apps, from TV.apps()

  /** A service whose app is on the TV and that the person hasn't switched off in Home Settings (Picks from). */
  function usable(key) { return !!appFor(key) && !Prefs.serviceOff(key); }

  /** The streaming services whose app is on this TV, as {key, name}, for Home Settings. */
  function services() {
    var out = [];
    for (var s = 0; s < SERVICES.length; s++) if (appFor(SERVICES[s][1])) out.push({key: SERVICES[s][1], name: SERVICES[s][4]});
    return out;
  }

  /** The installed app for a service key, or null. */
  function appFor(key) {
    for (var s = 0; s < SERVICES.length; s++) {
      if (SERVICES[s][1] !== key) continue;
      for (var i = 0; i < installed.length; i++) {
        if (SERVICES[s][2].test(installed[i].id) || SERVICES[s][3].test(installed[i].title)) return installed[i];
      }
    }
    return null;
  }

  /** "… Amazon Channel" is sold inside another app; Apple TV is only the streaming service (350; 2 is the store). */
  function service(providerName, providerId) {
    var n = (providerName || '').toLowerCase();
    if (n.indexOf(' channel') >= 0) return null;
    for (var i = 0; i < SERVICES.length; i++) {
      var s = SERVICES[i];
      if (s[1] === 'appletv' && providerId !== 350) continue;
      if (s[0] === 'max' ? /\bmax\b/.test(n) : n.indexOf(s[0]) >= 0) return s;
    }
    return null;
  }

  function tmdb(path, done, key) {
    key = key || KEY;
    var bearer = key.indexOf('eyJ') === 0;   // read access token (v4) or API key (v3)
    var url = API + path + (bearer ? '' : (path.indexOf('?') >= 0 ? '&' : '?') + 'api_key=' + encodeURIComponent(key));
    get(url, bearer ? {Authorization: 'Bearer ' + key, Accept: 'application/json'} : {Accept: 'application/json'}, done);
  }

  /** Asks TMDB whether a key works before it's saved: done(error), with error.rejected when TMDB said no. */
  function checkKey(key, done) {
    tmdb('/configuration', function (err) {
      if (err && /rejected/.test(err.message)) err.rejected = true;
      done(err || null);
    }, key);
  }

  var cache = stored('recs') || {};
  var picks = cache.picks || [], listAt = cache.listAt || 0, period = cache.period, listLang = cache.lang || '', stamp = cache.stamp || '';
  var movieRows = cache.movieRows || [], tvRows = cache.tvRows || [];
  var rowsLoaded = cache.movieRows !== undefined;
  var fetchingRecs = false, failedAt = 0, recsError = '';

  function saveRecs() {
    store('recs', {picks: picks, listAt: listAt, period: period, lang: listLang, stamp: stamp, movieRows: movieRows, tvRows: tvRows});
  }

  function hourPeriod(d) {
    var h = d.getHours();
    return h >= 5 && h < 12 ? 0 : h >= 12 && h < 18 ? 1 : 2;
  }

  function sourceStamp() {
    var s = KEY.length + ':' + KEY.slice(-6) + ':' + REGION + (CONFIG.hideAnimation ? ':no-animation' : '');
    for (var i = 0; i < SERVICES.length; i++) s += usable(SERVICES[i][1]) ? '1' : '0';
    return s;
  }

  function franchise(title) {
    var t = (title || '').toLowerCase(), cut = t.indexOf(':');
    if (cut < 0) cut = t.indexOf(' - ');
    return (cut > 0 ? t.slice(0, cut) : t).replace(/^\s+|\s+$/g, '');
  }

  /** This hour's picks (Prefs.cards()): movies ("movie"), shows ("tv") or both (null). */
  function now(kind) {
    var list = [];
    if (!KEY && !demoOn) return list;
    for (var i = 0; i < picks.length; i++) {
      var p = picks[i];
      if ((kind === null || kind === p.kind) && (!p.detailed || (p.app && usable(p.app)))) list.push(p);
    }
    if (!list.length) return list;
    var hour = Math.floor(Date.now() / 3600000), out = [], seen = {}, n = Prefs.cards();
    for (var j = 0; j < list.length && out.length < n; j++) {
      var q = list[(hour * n + j) % list.length], f = franchise(q.title);
      if (!seen[f]) { seen[f] = true; out.push(q); }
    }
    return out;
  }

  /** TMDB's language, as on Android: Latin American Spanish unless the TV is in Spain, and Germany's German. */
  function lang() {
    if (I18N.lang === 'es') return /-ES\b/i.test(navigator.language || '') ? 'es-ES' : 'es-MX';
    if (I18N.lang === 'de') return 'de-DE';   // TMDB's de-AT and de-CH have few translations of their own
    return I18N.locale();
  }

  function day(offset) {
    var d = new Date(Date.now() + offset * 86400000);
    function two(n) { return (n < 10 ? '0' : '') + n; }
    return d.getFullYear() + '-' + two(d.getMonth() + 1) + '-' + two(d.getDate());
  }

  /** TMDB's genres that it leaves in English in some languages. */
  function genre(name) {
    switch (name) {
      case 'Action & Adventure': return tr('genre|Action & Adventure');
      case 'Sci-Fi & Fantasy': return tr('genre|Sci-Fi & Fantasy');
      case 'War & Politics': return tr('genre|War & Politics');
      case 'Kids': return tr('genre|Kids');
      case 'News': return tr('genre|News');
      case 'Soap': return tr('genre|Soap');
      case 'Talk': return tr('genre|Talk');
      default: return name;
    }
  }

  /** The best backdrop: no text over it, the largest, then the best rated. */
  function bestBackdrop(images) {
    var list = images && images.backdrops, best = null, score = -1;
    for (var i = 0; list && i < list.length; i++) {
      var b = list[i];
      if (!b.file_path) continue;
      var s = (b.iso_639_1 === null ? 1e7 : 0) + Math.min(b.width || 0, 3840) * 100 + (b.vote_average || 0);
      if (s > score) { score = s; best = b.file_path; }
    }
    return best;
  }

  /** Genre, runtime or seasons, and the service it's on among the TV's apps. */
  function details(p, done) {
    tmdb('/' + p.kind + '/' + p.id + '?language=' + lang() + '&append_to_response=watch/providers,images&include_image_language=null,'
        + I18N.base + ',en', function (err, o) {
      if (err) { done(err); return; }
      var best = bestBackdrop(o.images);
      if (best) p.backdrop = best;
      if (o.poster_path) p.poster = o.poster_path;
      var parts = [];
      if (p.kind === 'tv') parts.push(tr('Series'));
      if (o.genres && o.genres.length) {
        parts.push(genre(o.genres[0].name));
      }
      if (p.kind === 'movie' && o.runtime > 0) {
        var h = Math.floor(o.runtime / 60), m = o.runtime % 60;
        parts.push(h ? trf('{h}h {m}m', {h: h, m: m}) : trf('{m}m', {m: m}));
      } else if (p.kind === 'tv' && o.number_of_seasons > 0) {
        parts.push(plural('plural|one:{n} season|other:{n} seasons', o.number_of_seasons));
      }
      var wp = o['watch/providers'], here = wp && wp.results && wp.results[REGION], flat = here && here.flatrate;
      p.app = null;
      for (var i = 0; flat && i < flat.length && !p.app; i++) {
        var s = service(flat[i].provider_name, flat[i].provider_id);
        if (s && usable(s[1])) { p.app = s[1]; p.service = s[4]; }
      }
      if (p.service && p.app) parts.push(p.service);
      p.meta = parts.join(' · ');
      title(p, o, function () {
        p.detailed = true;
        done(null);
      });
    });
  }

  /**
   * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
   * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
   */
  function title(p, o, done) {
    var field = p.kind === 'movie' ? 'title' : 'name', own = o[field] || p.title;
    p.title = own;
    if (I18N.lang === 'en' || !own || own !== o['original_' + field] || o.original_language === I18N.base
        || o.original_language === 'en') { done(); return; }
    tmdb('/' + p.kind + '/' + p.id + '?language=en-US', function (err, en) {
      if (!err && en && en[field]) p.title = en[field];
      done();
    });
  }

  /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
  var ANIMATION = 16;

  function animated(o) {
    for (var i = 0; o.genre_ids && i < o.genre_ids.length; i++) if (o.genre_ids[i] === ANIMATION) return true;
    return false;
  }

  function addPick(list, o, kind) {
    if (!o.backdrop_path) return;
    if (CONFIG.hideAnimation && animated(o)) return;
    list.push({id: o.id, kind: kind, title: kind === 'movie' ? o.title : o.name, backdrop: o.backdrop_path, poster: o.poster_path || '', meta: '', app: null, detailed: false});
  }

  function keyOf(p) { return p.kind + ':' + p.id; }

  function alternate(out, seen, a, b, max) {
    for (var i = 0; i < Math.max(a.length, b.length) && out.length < max; i++) {
      if (i < a.length && !seen[keyOf(a[i])]) { seen[keyOf(a[i])] = true; out.push(a[i]); }
      if (i < b.length && out.length < max && !seen[keyOf(b[i])]) { seen[keyOf(b[i])] = true; out.push(b[i]); }
    }
  }

  /** Runs fn on each item one after the other (the TV's network is shared with what's playing). */
  function each(items, fn, done) {
    var i = 0;
    (function next() {
      if (i >= items.length) { done(); return; }
      fn(items[i++], next);
    })();
  }

  /** A row keeps the ten the home screen can show (Top 10). Genres match the Android list. */
  var ROW_CAP = 10;
  var MOVIE_GENRES = [28, 35, 18, 53, 27, 878, 10749, 16];
  var TV_GENRES = [10759, 35, 18, 80, 10765, 9648, 16, 10764];

  /**
   * Top 10, New on your apps, then one row per genre. Titles already in the hourly pool are the same objects,
   * so their details are fetched once. Requests run one after another.
   */
  function buildAllRows(common, hot, fresh, done) {
    var byKey = {};
    function remember(list) {
      for (var i = 0; list && i < list.length; i++) {
        var k = keyOf(list[i]);
        if (!byKey[k]) byKey[k] = list[i];
      }
    }
    remember(picks);
    remember(hot.movie);
    remember(hot.tv);
    remember(fresh.movie);
    remember(fresh.tv);
    function adopt(list) {
      var out = [], seen = {};
      for (var i = 0; list && i < list.length && out.length < ROW_CAP; i++) {
        var k = keyOf(list[i]);
        if (seen[k]) continue;
        seen[k] = true;
        if (!byKey[k]) byKey[k] = list[i];
        out.push(byKey[k]);
      }
      return out;
    }
    function discoverRow(kind, filters, then) {
      tmdb('/discover/' + kind + '?page=1' + filters, function (err, res) {
        var list = [];
        var results = !err && res && res.results ? res.results : [];
        for (var i = 0; i < results.length && list.length < ROW_CAP; i++) addPick(list, results[i], kind);
        then(adopt(list));
      });
    }
    function genreMap(kind, then) {
      tmdb('/genre/' + kind + '/list?language=' + lang(), function (err, res) {
        var names = {};
        var list = !err && res && res.genres ? res.genres : [];
        for (var i = 0; i < list.length; i++) names[list[i].id] = list[i].name;
        then(names);
      });
    }
    genreMap('movie', function (movieNames) {
      genreMap('tv', function (tvNames) {
        var built = {movie: [], tv: []};
        each(['movie', 'tv'], function (kind, nextKind) {
          var movie = kind === 'movie';
          var rows = [];
          var names = movie ? movieNames : tvNames;
          function afterTop(topList) {
            if (topList.length) rows.push({title: movie ? tr('Top 10 Movies') : tr('Top 10 Shows'), top10: true, picks: topList});
            var freshList = adopt(movie ? fresh.movie : fresh.tv);
            function afterFresh(list) {
              if (list.length) rows.push({title: tr('New on your apps'), top10: false, picks: list});
              var ids = movie ? MOVIE_GENRES : TV_GENRES;
              each(ids, function (g, nextG) {
                if ((CONFIG.hideAnimation && g === ANIMATION) || !names[g]) { nextG(); return; }
                discoverRow(kind, '&vote_count.gte=' + (movie ? 80 : 40) + '&with_genres=' + g + common, function (row) {
                  if (row.length) rows.push({title: genre(names[g]), top10: false, picks: row});
                  nextG();
                });
              }, function () {
                built[kind] = rows;
                nextKind();
              });
            }
            if (freshList.length) afterFresh(freshList);
            else discoverRow(kind, (movie
              ? '&vote_count.gte=30&primary_release_date.gte=' + day(-365) + '&primary_release_date.lte=' + day(0)
              : '&vote_count.gte=20&air_date.gte=' + day(-90) + '&air_date.lte=' + day(0)) + common, afterFresh);
          }
          var top = adopt(movie ? hot.movie : hot.tv);
          if (top.length >= ROW_CAP) afterTop(top);
          else discoverRow(kind, '&vote_count.gte=' + (movie ? 200 : 100) + common, function (more) {
            afterTop(adopt(top.concat(more)));
          });
        }, function () {
          movieRows = built.movie;
          tvRows = built.tv;
          rowsLoaded = true;
          done();
        });
      });
    });
  }

  var warmToken = '';

  /** Details for the row on screen (the wide card first), then one refresh so the caption can appear. */
  function warmRow(kind, index) {
    if (demoOn) return;
    var rows = kind === 'tv' ? tvRows : movieRows;
    var row = rows[index];
    if (!row || !row.picks.length) return;
    var token = kind + ':' + index + ':' + keyOf(row.picks[0]) + ':' + row.picks.length;
    if (token === warmToken) return;
    warmToken = token;
    var first = row.picks[0];
    function rest() {
      var todo = [];
      for (var i = 0; i < row.picks.length; i++) if (!row.picks[i].detailed) todo.push(row.picks[i]);
      if (!todo.length) return;
      each(todo, function (p, next) { details(p, function () { next(); }); }, function () { saveRecs(); changed(); });
    }
    if (!first.detailed) details(first, function () { changed(); rest(); });
    else rest();
  }

  function fetchList(done) {
    tmdb('/watch/providers/movie?language=' + lang() + '&watch_region=' + REGION, function (err, res) {
      if (err) { done(err); return; }
      var ids = [];
      for (var i = 0; res.results && i < res.results.length; i++) {
        var s = service(res.results[i].provider_name, res.results[i].provider_id);
        if (s && usable(s[1])) ids.push(res.results[i].provider_id);
      }
      if (!ids.length) { done(new Error("none of the TV's streaming apps are on TMDB (or switched on in Picks from)")); return; }
      var common = '&language=' + lang() + '&watch_region=' + REGION + '&with_watch_monetization_types=flatrate&include_adult=false'
          + '&sort_by=popularity.desc&with_watch_providers=' + encodeURIComponent(ids.join('|'))
          + (CONFIG.hideAnimation ? '&without_genres=' + ANIMATION : '');
      var hot = {movie: [], tv: []}, fresh = {movie: [], tv: []};
      each(['movie', 'tv'], function (kind, next) {
        tmdb('/trending/' + kind + '/week?language=' + lang(), function (e, r) {
          var candidates = [];
          for (var j = 0; !e && j < r.results.length && j < 16; j++) addPick(candidates, r.results[j], kind);
          // Trending is only kept when it's on one of the TV's apps: the details say where.
          each(candidates, function (p, n) {
            details(p, function (de) { if (!de && p.app) hot[kind].push(p); n(); });
          }, next);
        });
      }, function () {
        tmdb('/discover/movie?vote_count.gte=30&primary_release_date.gte=' + day(-365) + '&primary_release_date.lte=' + day(0) + common, function (e1, r1) {
          for (var j = 0; !e1 && j < r1.results.length; j++) addPick(fresh.movie, r1.results[j], 'movie');
          tmdb('/discover/tv?vote_count.gte=20&air_date.gte=' + day(-90) + '&air_date.lte=' + day(0) + common, function (e2, r2) {
            for (var k = 0; !e2 && k < r2.results.length; k++) addPick(fresh.tv, r2.results[k], 'tv');
            var list = [], seen = {};
            alternate(list, seen, hot.movie, hot.tv, 24);
            alternate(list, seen, fresh.movie, fresh.tv, 24);
            if (!list.length) { done(new Error('TMDB returned no recommendations')); return; }
            picks = list;
            buildAllRows(common, hot, fresh, function () { done(null); });
          });
        });
      });
    });
  }

  /** Fetches the list when stale (6 h, a new period of the day, language, key or apps), then this hour's details. */
  function refreshRecs() {
    if (!KEY || fetchingRecs || !installed.length) return;
    var t = Date.now(), p = hourPeriod(new Date()), st = sourceStamp();
    if (st === stamp && failedAt && t - failedAt < 10 * 60000 && t >= failedAt) return;
    var stale = st !== stamp || p !== period || t - listAt > 6 * 3600000 || t < listAt || !picks.length || listLang !== I18N.lang || !rowsLoaded;
    var wanted = all(), missing = false;
    for (var i = 0; i < wanted.length; i++) missing = missing || !wanted[i].detailed;
    if (!stale && !missing) return;
    fetchingRecs = true;
    stamp = st;
    function finish(err) {
      fetchingRecs = false;
      if (err) { recsError = err.message; failedAt = Date.now(); } else { recsError = ''; failedAt = 0; }
      saveRecs();
      changed();
    }
    function detailWanted() {
      var todo = all().filter(function (x) { return !x.detailed; });
      each(todo, function (x, next) { details(x, function () { next(); }); }, function () { finish(null); });
    }
    if (stale) {
      fetchList(function (err) {
        if (err) { finish(err); return; }
        listAt = Date.now();
        period = p;
        listLang = I18N.lang;
        detailWanted();
      });
    } else {
      detailWanted();
    }
  }

  /** This hour's picks for the three tabs, without repeats (the screensaver shows them too). */
  function all() {
    var out = [], seen = {}, kinds = [null, 'movie', 'tv'];
    for (var k = 0; k < kinds.length; k++) {
      var list = now(kinds[k]);
      for (var i = 0; i < list.length; i++) if (!seen[keyOf(list[i])]) { seen[keyOf(list[i])] = true; out.push(list[i]); }
    }
    return out;
  }

  var searchCache = {};

  /** Movies and shows for a typed search: one TMDB request, remembered for this session. */
  function searchHits(query, done) {
    var q = String(query || '').replace(/^\s+|\s+$/g, '');
    if (q.length < 2) { done(null, []); return; }
    var cacheKey = (I18N.lang || 'en') + '|' + REGION + '|' + q.toLowerCase();
    if (searchCache[cacheKey]) { done(null, searchCache[cacheKey]); return; }
    if (demoOn || !KEY) {
      var found = [], needle = q.toLowerCase();
      for (var i = 0; i < demoAll.length && found.length < 8; i++) {
        if (demoAll[i].title.toLowerCase().indexOf(needle) >= 0) found.push(demoAll[i]);
      }
      searchCache[cacheKey] = found;
      done(null, found);
      return;
    }
    tmdb('/search/multi?include_adult=false&language=' + lang() + '&query=' + encodeURIComponent(q), function (err, res) {
      if (err) { done(err); return; }
      var out = [], results = res && res.results ? res.results : [];
      for (var j = 0; j < results.length && out.length < 8; j++) {
        var o = results[j], kind = o.media_type;
        if (kind !== 'movie' && kind !== 'tv') continue;
        var one = [];
        addPick(one, o, kind);
        if (one.length) out.push(one[0]);
      }
      searchCache[cacheKey] = out;
      done(null, out);
    });
  }

  function visibleRows(kind) {
    var list = kind === 'movie' ? movieRows : kind === 'tv' ? tvRows : [];
    var out = [];
    for (var r = 0; r < list.length; r++) {
      var src = list[r].picks, kept = [];
      for (var i = 0; i < src.length && kept.length < ROW_CAP; i++) {
        var p = src[i];
        // Until the details arrive, keep the title. After that, only if its app is still on.
        if (!p.detailed || (p.app && usable(p.app))) kept.push(p);
      }
      if (kept.length) out.push({title: list[r].title, top10: !!list[r].top10, picks: kept});
    }
    return out;
  }

  /**
   * Browser preview with no TMDB key: the same row shape as a TV that has one, with invented titles
   * (gradient art, no images to decode). A real TV without a key keeps the quiet clock home.
   */
  function seedDemo() {
    demoOn = true;
    var show = 12, c = Place.fahrenheit ? (show - 32) * 5 / 9 : show;
    weather = {temp: c, code: 0, isDay: true, max: c + 3, min: c - 3, sunrise: 0, sunset: 0, hours: [], days: []};
    function pick(id, kind, title, meta, app, service, a, b, col) {
      return {id: id, kind: kind, title: title, meta: meta, app: app, service: service, art: [a, b, col],
        backdrop: '', poster: '', detailed: true, overview: ''};
    }
    var movies = [
      pick(1, 'movie', 'Paper Satellites', 'Sci-fi · 2 h 04 m · Netflix', 'netflix', 'Netflix', '#1B1446', '#6C4BD8', '#F2B8E8'),
      pick(2, 'movie', 'Copper Hour', 'Thriller · 1 h 52 m · Max', 'max', 'Max', '#3A1A0E', '#C4572B', '#F7C08A'),
      pick(3, 'movie', 'A Quiet Orbit', 'Drama · 1 h 41 m · Disney+', 'disney', 'Disney+', '#121418', '#4A5568', '#D9E2EC'),
      pick(4, 'movie', 'Saffron Lane', 'Comedy · 1 h 38 m · Prime Video', 'prime', 'Prime Video', '#4A1C05', '#E08A1E', '#FFE0A3'),
      pick(5, 'movie', 'Northbound', 'Adventure · 2 h 11 m · Max', 'max', 'Max', '#14213D', '#5C7CBA', '#E5ECF6'),
      pick(6, 'movie', 'Velvet Static', 'Music · 1 h 47 m · Netflix', 'netflix', 'Netflix', '#2B0A2E', '#B0306F', '#FFB3C7'),
      pick(7, 'movie', 'The Marsh Hours', 'Thriller · 1 h 58 m · Prime Video', 'prime', 'Prime Video', '#1D2B1F', '#6E8F5C', '#E3EBC8'),
      pick(8, 'movie', 'Kite Season', 'Family · 1 h 33 m · Apple TV', 'appletv', 'Apple TV', '#0A3440', '#21A1A8', '#C6F1EE'),
      pick(9, 'movie', 'Midnight Orchid', 'Romance · 1 h 49 m · Disney+', 'disney', 'Disney+', '#170A2A', '#5B2A86', '#E0C3FC'),
      pick(10, 'movie', 'Harbor Letters', 'Drama · 1 h 44 m · Netflix', 'netflix', 'Netflix', '#1A2744', '#3D5A80', '#E0FBFC')
    ];
    var shows = [
      pick(11, 'tv', 'The Lantern Coast', 'Mystery · 3 seasons · Netflix', 'netflix', 'Netflix', '#0F3B57', '#E7A35B', '#F6D7A7'),
      pick(12, 'tv', 'Hollow Pines', 'Drama · 3 seasons · Prime Video', 'prime', 'Prime Video', '#0D2A1E', '#2F7D57', '#BFE3C6'),
      pick(13, 'tv', 'Tidewater Nights', 'Limited series · 6 episodes · Netflix', 'netflix', 'Netflix', '#071C33', '#2A6FA8', '#9FD3F2'),
      pick(14, 'tv', 'Glasshouse', 'Thriller · 2 seasons · Apple TV', 'appletv', 'Apple TV', '#1E1B18', '#8C7B6B', '#EDE3D9'),
      pick(15, 'tv', 'Ember & Ash', 'Fantasy · 1 season · Disney+', 'disney', 'Disney+', '#2A0E0A', '#A3341F', '#FFB089'),
      pick(16, 'tv', 'Ninth Floor', 'Comedy · 4 seasons · Max', 'max', 'Max', '#101820', '#36506B', '#C9D6E3'),
      pick(17, 'tv', 'Silver Ridge', 'Western · 2 seasons · Netflix', 'netflix', 'Netflix', '#1F2430', '#8794A8', '#F0F3F8'),
      pick(18, 'tv', 'Camp Meridian', 'Drama · 1 season · Prime Video', 'prime', 'Prime Video', '#24301C', '#6B8F71', '#E4EBD4'),
      pick(19, 'tv', 'Winter Gate', 'Mystery · 1 season · Max', 'max', 'Max', '#1B2430', '#6E7C93', '#D5DDEA'),
      pick(20, 'tv', 'Ferry Road', 'Drama · 2 seasons · Disney+', 'disney', 'Disney+', '#10243A', '#3E7CB1', '#C5DFF0')
    ];
    function some(list, ids) {
      var out = [];
      for (var i = 0; i < ids.length; i++) out.push(list[ids[i]]);
      return out;
    }
    movieRows = [
      {title: tr('Top 10 Movies'), top10: true, picks: movies},
      {title: tr('New on your apps'), top10: false, picks: some(movies, [4, 8, 7, 5, 6, 3, 1])},
      {title: 'Thriller', top10: false, picks: some(movies, [1, 6, 4, 2, 5, 0, 7])}
    ];
    tvRows = [
      {title: tr('Top 10 Shows'), top10: true, picks: shows},
      {title: tr('New on your apps'), top10: false, picks: some(shows, [2, 4, 6, 1, 5, 3, 0])},
      {title: 'Drama', top10: false, picks: some(shows, [1, 7, 9, 2, 5, 0, 4])}
    ];
    picks = [];
    for (var i = 0; i < 6; i++) { picks.push(movies[i]); picks.push(shows[i]); }
    demoAll = movies.concat(shows);
    rowsLoaded = true;
  }
  if (TV.name === 'browser' && !KEY) seedDemo();

  // MARK: - Formatting

  function fmt(v, digits) {
    // Old WebKit (Tizen 2) has no Intl: toLocaleString ignores its options and prints every digit, without failing.
    // English separators there; a per-language table if someone asks.
    if (typeof Intl === 'undefined') return v.toFixed(digits).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    try { return v.toLocaleString(I18N.locale(), {minimumFractionDigits: digits, maximumFractionDigits: digits}); } catch (e) { return v.toFixed(digits); }
  }

  var SYMBOLS = {USD: '$', BRL: 'R$ ', EUR: '€', GBP: '£', JPY: '¥'};

  return {
    COINS: COINS,
    get city() { return Prefs.city(); },
    currency: CURRENCY,
    onChange: function (fn) { listeners.push(fn); },
    setApps: function (apps) { installed = apps; },
    appFor: appFor,
    usable: usable,
    services: services,
    refresh: function () { refreshWeather(); refreshPrices(); refreshRecs(); },
    settingsChanged: function () { pricesAt = 0; refreshPrices(); changed(); },
    findCity: findCity,
    /** Another city picked: its weather now (none shown until it comes). */
    cityChanged: function () { weather = null; weatherAt = 0; refreshWeather(); changed(); },
    get weather() { return weather; },
    get weatherAt() { return weatherAt; },
    prices: prices,
    changes: changes,
    get pricesAt() { return pricesAt; },
    activeCoins: activeCoins,
    hasKey: function () { return !!KEY; },
    /** Browser stand-in: sample rows so the screen can be reviewed without a key or a TV. */
    demo: function () { return demoOn; },
    rows: visibleRows,
    warm: warmRow,
    search: searchHits,
    recent: function () {
      var list = stored('recent');
      if (list && list.length) return list;
      return demoOn ? ['Hollow Pines', 'Northbound', 'YouTube'] : [];
    },
    remember: function (q) {
      q = String(q || '').replace(/^\s+|\s+$/g, '');
      if (q.length < 2) return;
      var list = stored('recent') || [], next = [q];
      for (var i = 0; i < list.length && next.length < 8; i++) {
        if (list[i].toLowerCase() !== q.toLowerCase()) next.push(list[i]);
      }
      store('recent', next);
    },
    checkKey: checkKey,
    /** A key typed (or removed) on the TV: the picks come again with it, or go. */
    keyChanged: function () { KEY = Prefs.tmdbKey(); recsError = ''; failedAt = 0; refreshRecs(); changed(); },
    recsError: function () { return recsError; },
    picks: now,
    showcase: all,
    image: function (path, size) { return path ? 'https://image.tmdb.org/t/p/' + (size || 'w1280') + path : ''; },

    /** Night at the location: from sunset to sunrise (the last forecast; without one, 6:00 and 18:00). */
    /** Home Settings → Theme. Dark is the default; Light is the only other look. */
    isNight: function () { return Prefs.theme() !== 'light'; },

    compact: function (v) {
      if (isNaN(v)) return '—';
      if (v >= 1000000) return fmt(v / 1000000, 2) + 'M';
      if (v >= 10000) return fmt(v / 1000, 1) + 'k';
      if (v >= 1000) return fmt(v / 1000, 2) + 'k';
      if (v >= 100) return fmt(v, 0);
      return fmt(v, 2);
    },
    money: function (v) {
      var s = SYMBOLS[CURRENCY] || CURRENCY + ' ';
      if (isNaN(v)) return s + '—';
      return s + (v >= 1000 ? fmt(v, 0) : fmt(v, 2));
    },
    change: function (v) { return isNaN(v) ? '' : (v >= 0 ? '+' : '−') + fmt(Math.abs(v), 1) + '%'; },
    degrees: function (v) {
      if (v === undefined || v === null || isNaN(v)) return '—';
      var unit = Prefs.temperature();
      var f = unit === 'F' || (unit === 'auto' && Place.fahrenheit);
      return Math.round(f ? v * 9 / 5 + 32 : v) + '°';
    },
    /** "3 PM", "15:00", "15 Uhr": the hourly forecast. */
    hour: function (at) { return formatDate('format|h a', at); },
    /** "Seg", "Mon", "Lun", "Mo", "Пн": the short day of the week, capitalized and without the abbreviation's dot. */
    weekday: function (at) {
      var s = formatDate('format|EEE', at).replace(/\.$/, '');
      return s.charAt(0).toUpperCase() + s.substring(1);
    },
    /** "5:21 AM", "05:21": the clock, sunrise and sunset. */
    time: function (at) { return formatDate('format|h:mm a', at); },
    /** "12 min ago" when the data is old (no internet), or empty. */
    staleness: function (at, every) {
      if (!at) return '';
      var age = Date.now() - at;
      if (age < every * 2) return '';
      var m = Math.floor(age / 60000), h = Math.floor(m / 60);
      return m < 60 ? trf('{n} min ago', {n: m}) : trf('{n} h ago', {n: h});
    },
    condition: function (code, day) {
      if (code === undefined || code === null || code < 0) return '';
      if (code === 0) return day ? tr('Clear') : tr('night|Clear');
      if (code === 1) return day ? tr('Mostly Sunny') : tr('Mostly Clear');
      if (code === 2) return tr('Partly Cloudy');
      if (code === 3) return tr('Cloudy');
      if (code === 45 || code === 48) return tr('Fog');
      if (code >= 51 && code <= 57) return tr('Drizzle');
      if (code === 65 || code === 67 || code === 82) return tr('Heavy Rain');
      if ((code >= 71 && code <= 77) || code === 85 || code === 86) return tr('Snow');
      if (code >= 95) return tr('Thunderstorms');
      return tr('Rain');
    },
    symbol: function (code, day) {
      if (code <= 1) return day ? 'sunny' : 'clear_night';
      if (code === 2) return day ? 'partly_cloudy_day' : 'partly_cloudy_night';
      if (code === 3) return 'cloud';
      if (code === 45 || code === 48) return 'foggy';
      if ((code >= 71 && code <= 77) || code === 85 || code === 86) return 'snowflake';
      if (code >= 95) return 'thunderstorm';
      return 'rainy';
    },
    coinName: function (i) { return i === 3 ? tr('Dollar') : ['Bitcoin', 'Ethereum', 'Solana'][i]; }
  };
})();
