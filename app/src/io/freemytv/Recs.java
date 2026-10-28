package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Shader;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Okko, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). 3 per hour, or up to 6 (Home Settings).
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key: given on the TV (Home Settings > TMDB key, which the APK on GitHub's
 * releases needs), else config.properties' "tmdb_key", built into the app. It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, poster, meta = "", overview = "", app, pkg;   // poster: the portrait image of the narrow cards
        String link;            // opens the title in its app (looked up with Watchmode or Wikidata), or null: the app's home
        boolean detailed;
        java.io.File photo;     // one of your own photos (Photos): no title, no caption
    }

    /** One horizontal row on Movies or Shows (Top 10, new, or a genre). Picks are shared with the hourly pool when they match. */
    static final class Row {
        final String title;
        final List<Pick> picks;

        Row(String title, List<Pick> picks) {
            this.title = title;
            this.picks = picks;
        }
    }

    interface Listener { void onRecs(); }

    // TMDB's own mirror of api.themoviedb.org, on the images' domain: some ISPs block the other name (Jio, #327)
    private static final String API = "https://api.tmdb.org/3";
    /** The most cards per tab and hour (Prefs.cards() picks how many, 3 by default); the home screen shows 3 at a time and scrolls. */
    static final int MAX_CARDS = 6;

    /** The backdrops: on a 720p UI, TMDB's 1280-wide copy shows the same and is lighter to download and decode. */
    private static final String IMAGES = "https://image.tmdb.org/t/p/" + (Backdrop.W <= 1280 ? "w1280" : "original");
    private static final String POSTERS = "https://image.tmdb.org/t/p/w500";
    /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
    private static final int ANIMATION = 16;
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 6;
    /** Genres that get their own row, in order. Movies: Action, Comedy, Drama, Thriller, Horror, Science Fiction, Romance, Animation. */
    private static final int[] MOVIE_GENRES = {28, 35, 18, 53, 27, 878, 10749, 16};
    /** Shows: Action & Adventure, Comedy, Drama, Crime, Sci-Fi & Fantasy, Mystery, Animation, Reality. */
    private static final int[] TV_GENRES = {10759, 35, 18, 80, 10765, 9648, 16, 10764};
    /** A row keeps a few more titles than it shows: some turn out not to be on an app once their details arrive. */
    private static final int ROW_EXTRA = 4;
    /** Watchmode's free plan is 2,500 requests a month. Stop short of it; two requests look up one title. */
    private static final int WATCHMODE_CAP = 2000;
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"prime video", "prime", "com.amazon.firebat"},
        {"prime video", "prime", "com.amazon.avod"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"hulu", "hulu", "com.hulu.plus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"paramount", "paramount", "com.cbs.ca"},      // Paramount+ outside the US (a Sony in the UK; Canada)
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"peacock", "peacock", "com.peacock.peacockfiretv"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"max", "max", "com.hbo.hbonow"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"apple tv", "appletv", "com.apple.atve.sony.appletv"},   // Sony's own build
        {"apple tv", "appletv", "com.apple.atve.amazon.appletv"},
        {"hotstar", "hotstar", "in.startv.hotstar"},   // TMDB: "JioHotstar" (earlier "Disney Plus Hotstar")
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"now tv", "now", "com.bskyb.nowtv.beta"},     // UK: Sky's NOW (TMDB: "Now TV", "Now TV Cinema")
        {"oneplay", "oneplay", "net.cme.voyo.cz.tvapp"},    // Czechia: Voyo and O2 TV became Oneplay, same app
        // Germany (TMDB's watch providers for DE)
        {"ard mediathek", "ard", "de.swr.avp.ard.tv"},
        {"zdf", "zdf", "com.zdf.android.mediathek"},
        {"arte", "arte", "tv.arte.plus7"},
        {"joyn", "joyn", "de.prosiebensat1digital.seventv"},   // TMDB: "Joyn" and "Joyn Plus", same app
        {"rtl+", "rtl", "de.rtli.tvnow"},
        {"wow", "wow", "de.sky.online"},                       // Sky's WOW (was Sky Ticket)
        {"magenta", "magenta", "de.telekom.magentatv.tv"},     // TMDB: "MagentaTV" and "Magenta TV+"
        // Russia (TMDB's watch providers for RU)
        {"okko", "okko", "ru.more.play"},
        {"kinopoisk", "kinopoisk", "ru.kinopoisk.tv"},
        {"premier", "premier", "gpm.tnt_premier"},
        {"amediateka", "amediateka", "ru.amediateka"},
        {"more tv", "wink", "ru.rt.video.app.tv"},   // TMDB: "More TV" (more.tv is part of Rostelecom's Wink now)
    };
    /** Services matched by their whole name, where a part would catch another: "WOW Presents Plus", "Belas Artes à La Carte". */
    private static final Set<String> WHOLE_NAME = new HashSet<>(Arrays.asList("wow", "arte"));
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
        SERVICE_NAME.put("hotstar", "JioHotstar");
        SERVICE_NAME.put("okko", "Okko");
        SERVICE_NAME.put("crunchyroll", "Crunchyroll");
        SERVICE_NAME.put("now", "NOW");
        SERVICE_NAME.put("oneplay", "Oneplay");
        SERVICE_NAME.put("ard", "ARD Mediathek");
        SERVICE_NAME.put("zdf", "ZDF");
        SERVICE_NAME.put("arte", "ARTE");
        SERVICE_NAME.put("joyn", "Joyn");
        SERVICE_NAME.put("rtl", "RTL+");
        SERVICE_NAME.put("wow", "WOW");
        SERVICE_NAME.put("magenta", "MagentaTV");
        SERVICE_NAME.put("kinopoisk", "Кинопоиск");
        SERVICE_NAME.put("premier", "Premier");
        SERVICE_NAME.put("amediateka", "Амедиатека");
        SERVICE_NAME.put("wink", "Wink");
        SERVICE_NAME.put("plex", "Plex");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    static final String REGION = Place.region();
    /** The errors the settings say in words: TMDB lists none of the TV's apps in REGION, or the TV has none we know. */
    static final String NO_APPS = "none of the TV's streaming services are on TMDB";
    static final String NO_KNOWN_APPS = "none of the TV's streaming apps can give picks";

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    /** A narrow card's portrait and its shader: the shader holds the bitmap, so they leave together. */
    static final class Poster {
        final Bitmap bitmap;
        final BitmapShader shader;

        Poster(Bitmap bitmap) {
            this.bitmap = bitmap;
            shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
    }

    private final Context context;
    private final SharedPreferences cache;
    /** Title links, apart from the pick list: a search result is not wiped when the rows are saved again. */
    private final SharedPreferences links;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Poster> posters = new LinkedHashMap<>();   // narrow-card portraits, same keys as images
    private final Map<String, Backdrop> images = new LinkedHashMap<>(16, 0.75f, true);   // this hour's, built and ready to draw
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile List<List<Row>> rows = new ArrayList<>();   // 0 movies, 1 shows
    private volatile int activeTab, activeRow;                   // the row on screen, so its details come first
    private final Map<String, List<Pick>> searchCache = new LinkedHashMap<String, List<Pick>>(24, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<Pick>> e) { return size() > 20; }
    };
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp, linkRetryAt;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        links = c.getSharedPreferences("links", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            String savedRows = cache.getString("rows", null);
            if (savedRows != null) rows = parseRows(new JSONArray(savedRows));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception ignored) {
        }
        // Right after the TV starts, the first fetch runs before the Wi-Fi is up and fails, which would hold the old
        // picks for the 10 minutes after a failure: as soon as the network comes, try again.
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            if (cm != null) cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    if (failedAt == 0) return;
                    failedAt = 0;   // the next screen to show fetches at once
                    main.postDelayed(() -> {
                        if (listeners.isEmpty()) return;   // nothing showing: no downloads under another app
                        AppLog.w("recommendations: network up, fetching again");
                        refreshIfStale();
                    }, 2000);   // the routes and DNS settle just after
                }
            });
        } catch (RuntimeException e) {
            AppLog.w("recommendations: no network callback: " + e.getMessage());
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !key().isEmpty();
    }

    private static volatile String cachedKey = "";
    private static volatile int keyVersion = -1;

    /**
     * The key given on the TV, else the one built in from config.properties ("" for neither). The home screen asks
     * while drawing: read again only when a setting changed (Prefs.version).
     */
    static String key() {
        int version = Prefs.version;
        if (version != keyVersion) {
            String given = Prefs.tmdbKey();
            cachedKey = given.isEmpty() ? Config.TMDB_KEY.trim() : given;
            keyVersion = version;
        }
        return cachedKey;
    }

    /**
     * A key as pasted (spaces, line breaks taken out), or "" when it can't be one: an API key (32 hex digits) or an API
     * Read Access Token (a JWT, "eyJ…").
     */
    static String cleanKey(String pasted) {
        String key = pasted == null ? "" : pasted.replaceAll("\\s+", "");
        return key.matches("[A-Za-z0-9._-]{16,1024}") ? key : "";
    }

    /** Asks TMDB whether a key works, before it's saved: null if it does, else why not, for the screen. Never logged. */
    static String checkKey(String key) {
        try {
            request("/configuration", key);
            return null;
        } catch (IOException e) {
            if (REJECTED.equals(e.getMessage())) return Ui.tr("TMDB refused this key.");
            return Ui.trf("Couldn't reach TMDB: {error}", "error", e.getMessage() == null ? e.toString() : e.getMessage());
        } catch (org.json.JSONException e) {
            return Ui.trf("Couldn't reach TMDB: {error}", "error", e.getMessage());
        } catch (RuntimeException e) {   // never out of KeyPage's thread (the watchdog counts it); the name only: no header value
            return Ui.trf("Couldn't reach TMDB: {error}", "error", e.getClass().getSimpleName());
        }
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        int p = period(System.currentTimeMillis());
        return p == MANHA ? Ui.tr("THIS MORNING") : p == TARDE ? Ui.tr("THIS AFTERNOON") : Ui.tr("TONIGHT");
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** This hour's picks, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        Map<String, Boolean> have = new HashMap<>();   // one look per app, not per title (this runs every 15 s)
        for (Pick p : picks) {
            if ((kind == null || kind.equals(p.kind))
                    && (!p.detailed || (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)))) list.add(p);   // app missing or switched off: skip
        }
        if (list.isEmpty()) return list;
        long hour = System.currentTimeMillis() / 3600_000L;
        // Prefs.cards() per hour, never two from the same franchise ("Law & Order" and "Law & Order: Special…").
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int cards = Prefs.cards();
        boolean plex = false;
        for (int i = 0; i < list.size() && out.size() < cards; i++) {
            Pick p = list.get((int) ((hour * cards + i) % list.size()));
            if (p.id < 0 && plex) continue;   // a Plex title is the surprise: one per tab, not the movie and the show together
            if (seen.add(franchise(p.title))) {
                out.add(p);
                plex |= p.id < 0;
            }
        }
        return out;
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /**
     * Movies or shows ("movie" / "tv"): each row, up to the home screen's card count, whose app is on the TV
     * (a title not looked up yet stays, so the row isn't empty while its details arrive).
     */
    List<Row> rows(String kind) {
        List<Row> out = new ArrayList<>();
        int which = "movie".equals(kind) ? 0 : "tv".equals(kind) ? 1 : -1;
        List<List<Row>> all = rows;
        if (!hasKey(context) || which < 0 || which >= all.size()) return out;
        int n = Prefs.cards();
        Map<String, Boolean> have = new HashMap<>();
        for (Row r : all.get(which)) {
            List<Pick> list = new ArrayList<>();
            for (Pick p : r.picks) {
                if (list.size() >= n) break;
                if (!p.detailed || (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed))) list.add(p);
            }
            if (!list.isEmpty()) out.add(new Row(r.title, list));
        }
        return out;
    }

    /** The row on screen. Its titles, and the next row's, are fetched ahead of the others. */
    void setActive(String kind, int row) {
        int tab = "tv".equals(kind) ? 1 : 0;
        if (tab == activeTab && row == activeRow) return;
        activeTab = tab;
        activeRow = row;
        refreshIfStale();
    }

    /**
     * Movies and shows matching a search, without their details (one TMDB request). The same query is remembered for
     * this session. Blocking: call it off the main thread.
     */
    List<Pick> searchHits(String query) throws Exception {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) return new ArrayList<>();
        String cacheKey = lang() + "|" + REGION + "|" + q.toLowerCase(Locale.ROOT);
        synchronized (searchCache) {
            List<Pick> hit = searchCache.get(cacheKey);
            if (hit != null) return hit;
        }
        JSONArray found = get("/search/multi?include_adult=false&language=" + lang() + "&query="
                + URLEncoder.encode(q, "UTF-8")).getJSONArray("results");
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < found.length() && out.size() < 8; i++) {
            JSONObject o = found.optJSONObject(i);
            if (o == null) continue;
            String kind = o.optString("media_type");
            if (!kind.equals("movie") && !kind.equals("tv")) continue;
            List<Pick> one = new ArrayList<>();
            add(one, o, kind);
            if (!one.isEmpty()) out.add(one.get(0));
        }
        synchronized (searchCache) { searchCache.put(cacheKey, out); }
        return out;
    }

    /** Which app has this title, if it isn't known yet. Blocking. Does not spend a Watchmode or Wikidata lookup. */
    void fillService(Pick p) throws Exception {
        if (p == null || p.detailed || p.id <= 0) return;
        fetchDetails(p);
    }

    /** The title's own page, from the link remembered earlier or one lookup. Blocking. */
    void ensureLink(Pick p) {
        findLink(p);
    }

    /** A title's wide photo for search (backdrop, or the poster when the wide one is missing). */
    static String smallImage(Pick p) {
        if (p == null) return "";
        String path = imagePath(p.backdrop);
        if (path.isEmpty()) path = imagePath(p.poster);
        return path.isEmpty() ? "" : "https://image.tmdb.org/t/p/w780" + path;
    }

    /** The portrait, when the wide photo failed to load. */
    static String posterImage(Pick p) {
        if (p == null) return "";
        String path = imagePath(p.poster);
        return path.isEmpty() ? "" : "https://image.tmdb.org/t/p/w500" + path;
    }

    private static String imagePath(String path) {
        if (path == null) return "";
        path = path.trim();
        if (path.isEmpty() || "null".equals(path)) return "";
        return path.charAt(0) == '/' ? path : "/" + path;
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key, country, animation choice or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language);
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick) || needsLink(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) fetchList(p);
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.pkg != null) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
            posters.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && have.computeIfAbsent(p.pkg, this::installed) && hasImage(p)
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This hour's picks for the three tabs (Explore, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {null, "movie", "tv"}) {
            for (Pick p : now(kind)) if (seen.add(key(p))) out.add(p);
        }
        // The row on screen, the one under it, and the other tab's first row. Genre rows wait until they're reached.
        addRow(out, seen, activeTab, activeRow);
        addRow(out, seen, activeTab, activeRow + 1);
        addRow(out, seen, 1 - activeTab, 0);
        return out;
    }

    private void addRow(List<Pick> out, java.util.Set<String> seen, int tab, int row) {
        if (tab < 0 || tab >= rows.size()) return;
        List<Row> tabRows = rows.get(tab);
        if (row < 0 || row >= tabRows.size()) return;
        for (Pick p : tabRows.get(row).picks) if (seen.add(key(p))) out.add(p);
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        // None of the services we know is on the TV (or hide_services hides them all): no region would change that.
        boolean any = false;
        for (String[] s : SERVICES) any |= installed(s[2]);
        if (!any) throw new IOException(NO_KNOWN_APPS);
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"), o.optInt("provider_id"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException(NO_APPS);
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String common = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8")
                + (Config.HIDE_ANIMATION ? "&without_genres=" + ANIMATION : "");
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now), only what is
        //    on one of the TV's services: the details say where (and are then ready).
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length() && i < 20; i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg != null) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
            }
        }
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        JSONArray movies = get("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray shows = get("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
                + common).getJSONArray("results");
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
        for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
        // One title of your Plex library per kind (one of the newest 8, another each time the list is made) among the
        // trending ones: a surprise, not half the row. Its images come now, so a server that sleeps later leaves no gray card.
        if (plexOn()) {
            try {
                long turn = System.currentTimeMillis() / 3600_000L;
                hotMovies = interleave(hotMovies, withImages(oneOf(plexPicks("movie"), (int) turn)));
                hotShows = interleave(hotShows, withImages(oneOf(plexPicks("tv"), (int) turn)));
            } catch (Exception e) {
                AppLog.w("plex: " + e);
            }
        } else if (!Config.PLEX_URL.trim().isEmpty()) {
            AppLog.w("plex: plex_url must be an https://….plex.direct address (and plex_token set): ignored");
        }
        // Trending first, then the new releases; movies and shows alternating, without repeats, up to 24.
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(list, seen, hotMovies, hotShows, 24);
        alternate(list, seen, newMovies, newShows, 24);
        List<List<Row>> built = buildRows(hotMovies, hotShows, newMovies, newShows, common, list);
        if (list.isEmpty() && built.get(0).isEmpty() && built.get(1).isEmpty()) throw new IOException("TMDB returned no recommendations");
        rows = built;
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    /** Language of titles and genres (TMDB's "fr-FR", "zh-CN"…); the services come from the region. */
    private static String lang() {
        String lang = Ui.language;
        if (lang.equals("en")) return "en-US";
        if (lang.equals("pt")) return "pt-BR";
        if (lang.equals("es")) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (lang.equals("de")) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        if (lang.indexOf('-') > 0) return lang;
        return lang + "-" + Ui.locale().getCountry();
    }

    /** A list of just one of `list`, the one this hour's period points at (empty for an empty list). */
    private static List<Pick> oneOf(List<Pick> list, int period) {
        List<Pick> out = new ArrayList<>();
        if (!list.isEmpty()) out.add(list.get(Math.floorMod(period, list.size())));
        return out;
    }

    /** The picks whose backdrop could be downloaded (the poster is a bonus: without it the card crops the backdrop). */
    private List<Pick> withImages(List<Pick> list) {
        List<Pick> out = new ArrayList<>();
        for (Pick pick : list) {
            try {
                download(pick);
                out.add(pick);
            } catch (IOException e) {
                AppLog.w("plex image: " + e.getMessage());
            }
        }
        return out;
    }

    private static List<Pick> interleave(List<Pick> a, List<Pick> b) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i < a.size()) out.add(a.get(i));
            if (i < b.size()) out.add(b.get(i));
        }
        return out;
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** Top 10, what's new, then a row per genre. Titles already in the hourly pool are the same objects. */
    private List<List<Row>> buildRows(List<Pick> hotMovies, List<Pick> hotShows, List<Pick> newMovies, List<Pick> newShows,
            String common, List<Pick> pool) {
        Map<String, Pick> all = new LinkedHashMap<>();
        for (Pick p : pool) all.put(key(p), p);
        for (Pick p : hotMovies) all.putIfAbsent(key(p), p);
        for (Pick p : hotShows) all.putIfAbsent(key(p), p);
        for (Pick p : newMovies) all.putIfAbsent(key(p), p);
        for (Pick p : newShows) all.putIfAbsent(key(p), p);
        List<List<Row>> built = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            boolean movie = kind.equals("movie");
            List<Row> tabRows = new ArrayList<>();
            List<Pick> top = new ArrayList<>();
            take(top, all, movie ? hotMovies : hotShows);
            if (top.size() < MAX_CARDS) discoverInto(top, all, kind, "&vote_count.gte=" + (movie ? 200 : 100), common);
            if (!top.isEmpty()) tabRows.add(new Row(movie ? Ui.tr("Top 10 Movies") : Ui.tr("Top 10 Shows"), top));
            List<Pick> fresh = new ArrayList<>();
            take(fresh, all, movie ? newMovies : newShows);
            if (fresh.isEmpty()) {
                discoverInto(fresh, all, kind, movie
                        ? "&vote_count.gte=30&primary_release_date.gte=" + day(-365) + "&primary_release_date.lte=" + day(0)
                        : "&vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0), common);
            }
            if (!fresh.isEmpty()) tabRows.add(new Row(Ui.tr("New on your apps"), fresh));
            Map<Integer, String> names;
            try {
                names = genreNames(kind);
            } catch (Exception e) {
                AppLog.w("recommendations: genres: " + e.getMessage());
                names = new HashMap<>();
            }
            for (int g : movie ? MOVIE_GENRES : TV_GENRES) {
                if (Config.HIDE_ANIMATION && g == ANIMATION) continue;
                String name = names.get(g);
                if (name == null) continue;
                List<Pick> genreRow = new ArrayList<>();
                discoverInto(genreRow, all, kind, "&vote_count.gte=" + (movie ? 80 : 40) + "&with_genres=" + g, common);
                if (!genreRow.isEmpty()) tabRows.add(new Row(genre(name), genreRow));
            }
            built.add(tabRows);
        }
        return built;
    }

    private static void take(List<Pick> row, Map<String, Pick> all, List<Pick> from) {
        for (Pick p : from) {
            if (row.size() >= MAX_CARDS + ROW_EXTRA) return;
            Pick known = all.get(key(p));
            row.add(known == null ? p : known);
        }
    }

    private void discoverInto(List<Pick> row, Map<String, Pick> all, String kind, String filters, String common) {
        try {
            fill(row, all, get("/discover/" + kind + "?page=1" + filters + common).getJSONArray("results"), kind);
        } catch (Exception e) {
            AppLog.w("recommendations: " + kind + " row: " + e.getMessage());
        }
    }

    /** Adds results to a row, reusing a title that already exists so its details are fetched once. */
    private static void fill(List<Pick> row, Map<String, Pick> all, JSONArray results, String kind) {
        java.util.Set<String> inRow = new java.util.HashSet<>();
        for (Pick p : row) inRow.add(key(p));
        for (int i = 0; results != null && i < results.length() && row.size() < MAX_CARDS + ROW_EXTRA; i++) {
            JSONObject o = results.optJSONObject(i);
            if (o == null) continue;
            String k = kind + ":" + o.optInt("id");
            Pick p = all.get(k);
            if (p == null) {
                List<Pick> one = new ArrayList<>();
                add(one, o, kind);
                if (one.isEmpty()) continue;
                p = one.get(0);
                all.put(k, p);
            }
            if (inRow.add(k)) row.add(p);
        }
    }

    private Map<Integer, String> genreNames(String kind) throws Exception {
        Map<Integer, String> out = new HashMap<>();
        JSONArray list = get("/genre/" + kind + "/list?language=" + lang()).getJSONArray("genres");
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null) out.put(o.getInt("id"), o.optString("name"));
        }
        return out;
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        if (Config.HIDE_ANIMATION && animated(o)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        p.poster = o.isNull("poster_path") ? "" : o.optString("poster_path");
        p.overview = o.optString("overview").trim();
        list.add(p);
    }

    private static boolean animated(JSONObject o) {
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return true;
        return false;
    }

    private static String runtime(int minutes) {
        if (minutes <= 0) return null;
        return minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60) : Ui.trf("{m}m", "m", minutes);
    }

    private static String seasons(int n) {
        return n <= 0 ? null : Ui.plural("plural|one:{n} season|other:{n} seasons", n);
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images&include_image_language=null," + Ui.language.replaceAll("-.*", "") + ",en");
        p.title = title(p, o);
        String poster = o.optString("poster_path", "");
        if (!poster.isEmpty() && !"null".equals(poster)) p.poster = poster;
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(genre(name));
        }
        String length = p.kind.equals("movie") ? runtime(o.optInt("runtime")) : seasons(o.optInt("number_of_seasons"));
        if (length != null) parts.add(length);
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"), flat.getJSONObject(i).optInt("provider_id"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
        p.meta = String.join(" · ", parts);
        String about = o.optString("overview").trim();
        if (!about.isEmpty()) p.overview = about;
        p.detailed = true;
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String original = o.optString("original_language");
        String lang = Ui.language.replaceAll("-.*", "");   // TMDB's original_language has no country: "zh", not "zh-CN"
        if (lang.equals("en") || own.isEmpty() || !own.equals(o.optString("original_" + field))
                || lang.equals(original) || "en".equals(original)) return own;
        try {
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (Exception e) {
            return own;
        }
    }

    /** TV genres that TMDB returns in English even in other languages (pt-BR, es, de…). */
    private static String genre(String name) {
        switch (name) {
            case "Action & Adventure": return Ui.tr("genre|Action & Adventure");
            case "Sci-Fi & Fantasy": return Ui.tr("genre|Sci-Fi & Fantasy");
            case "War & Politics": return Ui.tr("genre|War & Politics");
            case "Kids": return Ui.tr("genre|Kids");
            case "News": return Ui.tr("genre|News");
            case "Soap": return Ui.tr("genre|Soap");
            case "Talk": return Ui.tr("genre|Talk");
            default: return name;
        }
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
     * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920) + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    /** The SERVICES row for a TMDB name: the installed one first, when a service has more than one package. */
    private String[] service(String name, int providerId) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        // "HBO Max Amazon Channel", "Apple TV Amazon Channel"...: a subscription sold inside another app, not this one.
        if (n.contains(" channel")) return null;
        for (String[] s : SERVICES) {
            // Apple TV: only the streaming service (TMDB provider 350); provider 2 is the Apple TV Store (rent or buy).
            if (s[1].equals("appletv") && providerId != 350) continue;
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : WHOLE_NAME.contains(s[0]) ? n.equals(s[0]) : n.contains(s[0]))) continue;
            if (installed(s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    /** The streaming services whose app is on this TV, as {key, name}, for Home Settings (Picks from). */
    static List<String[]> installedServices(Context c) {
        List<String[]> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String[] s : SERVICES) {
            if (HomeApps.launchIntent(c.getPackageManager(), s[2]) != null && seen.add(s[1])) out.add(new String[] {s[1], SERVICE_NAME.get(s[1])});
        }
        return out;
    }

    /**
     * The service's app is on the TV, and the person hasn't switched the service off (Home Settings > Picks from; the
     * hide_services line of config.properties is where those switches start: an app the TV ships without a subscription).
     */
    private boolean installed(String pkg) {
        for (String[] s : SERVICES) if (s[2].equals(pkg) && Prefs.serviceOff(s[1])) return false;
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private long sourceStamp() {
        long stamp = ((key().hashCode() * 31L + REGION.hashCode()) * 31L + (Config.HIDE_ANIMATION ? 1 : 0)) * 31L + Config.PLEX_URL.hashCode();
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        return stamp;
    }

    // MARK: - Watchmode (the title's own page in its app; the free plan's web addresses are what the apps open)

    private static String linkKey(Pick p) {
        return "wm2:" + p.kind + p.id + p.app;   // wm2: links found before the channel check are looked up again
    }

    /** Links are looked up when a title is opened, not while the rows load: the free plan is 2,500 requests a month. */
    private boolean needsLink(Pick p) {
        return false;
    }

    /** A key is set and Watchmode hasn't refused it (a refused key isn't tried again until it changes). */
    private boolean linksOn() {
        String key = Config.WATCHMODE_KEY.trim();
        return !key.isEmpty() && cache.getInt("wm:refused", 0) != key.hashCode();
    }

    /**
     * Sets p.link from a link remembered earlier, Watchmode (2 requests, under the monthly cap), or Wikidata
     * (Netflix, Prime Video, Disney+). An empty string means "looked up, nothing": the app's home is used.
     */
    private void findLink(Pick p) {
        if (p.id <= 0 || p.pkg == null || p.link != null) return;
        String saved = links.getString(linkKey(p), null);
        if (saved != null) {
            p.link = saved.isEmpty() ? null : saved;
            return;
        }
        if (System.currentTimeMillis() < linkRetryAt) return;
        String link = "";
        try {
            if (linksOn() && watchmodeRoom()) link = watchmodeLink(p);
        } catch (Exception e) {
            if ("Watchmode returned HTTP 401".equals(e.getMessage())) {
                cache.edit().putInt("wm:refused", Config.WATCHMODE_KEY.trim().hashCode()).apply();
            }
            linkRetryAt = System.currentTimeMillis() + 10 * 60_000L;
            AppLog.w("watchmode: " + e.getMessage());
            return;   // don't cache a miss caused by the network or the quota
        }
        if (link.isEmpty()) {
            try { link = wikidataLink(p); }
            catch (Exception e) { AppLog.w("wikidata: " + e.getMessage()); return; }
        }
        links.edit().putString(linkKey(p), link).apply();
        p.link = link.isEmpty() ? null : link;
    }

    /** Two requests per title; stop at 2,000 of the free plan's 2,500 in a calendar month. */
    private boolean watchmodeRoom() {
        String month = String.format(Locale.US, "%d-%02d", Calendar.getInstance().get(Calendar.YEAR),
                Calendar.getInstance().get(Calendar.MONTH) + 1);
        if (!month.equals(links.getString("wm:month", ""))) {
            links.edit().putString("wm:month", month).putInt("wm:count", 0).apply();
        }
        return links.getInt("wm:count", 0) + 2 <= WATCHMODE_CAP;
    }

    private String watchmodeLink(Pick p) throws Exception {
        JSONArray found = new JSONObject(watchmode("/search/?search_field=tmdb_" + p.kind + "_id&search_value=" + p.id))
                .optJSONArray("title_results");
        String link = "";
        if (found != null && found.length() > 0) {
            JSONArray sources = new JSONArray(watchmode("/title/" + found.getJSONObject(0).getInt("id") + "/sources/?regions=" + REGION));
            String[] service = null;
            for (String[] s : SERVICES) if (s[1].equals(p.app)) service = s;
            for (int i = 0; service != null && i < sources.length() && link.isEmpty(); i++) {
                JSONObject s = sources.getJSONObject(i);
                String name = s.optString("name").toLowerCase(Locale.ROOT).replace(" ", "");
                String want = service[0].replace(" ", "");
                boolean resold = name.contains("channel") || name.contains("(via");
                boolean same = !resold && (want.equals("max") || WHOLE_NAME.contains(want) ? name.equals(want) : name.contains(want));
                if (same && s.optString("type").equals("sub") && s.optString("web_url").startsWith("https://")) link = s.optString("web_url");
            }
        }
        links.edit().putInt("wm:count", links.getInt("wm:count", 0) + 2).apply();
        return link;
    }

    /**
     * Wikidata's Netflix, Prime Video and Disney+ id for this TMDB title, as the https address those apps open.
     * One SPARQL request, no key. Empty when Wikidata has no id.
     */
    private static String wikidataLink(Pick p) throws Exception {
        String property = "netflix".equals(p.app) ? "P1874" : "prime".equals(p.app) ? "P8055" : "disney".equals(p.app) ? "P7595" : "";
        if (property.isEmpty()) return "";
        String tmdb = p.kind.equals("tv") ? "P4983" : "P4947";
        String query = "SELECT ?id WHERE { ?item wdt:" + tmdb + " \"" + p.id + "\" . ?item wdt:" + property + " ?id } LIMIT 1";
        HttpURLConnection c = (HttpURLConnection) new URL("https://query.wikidata.org/sparql?format=json&query="
                + URLEncoder.encode(query, "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setRequestProperty("Accept", "application/sparql-results+json");
        c.setRequestProperty("User-Agent", "FreeMyTV/1.0 (https://freemytv.io)");
        String id;
        try {
            if (c.getResponseCode() != 200) return "";
            JSONArray bindings = new JSONObject(read(c.getInputStream())).getJSONObject("results").getJSONArray("bindings");
            if (bindings.length() == 0) return "";
            id = bindings.getJSONObject(0).getJSONObject("id").optString("value");
        } finally {
            c.disconnect();
        }
        if (id.isEmpty() || id.indexOf('/') >= 0 || id.indexOf('?') >= 0) return "";
        if ("netflix".equals(p.app)) return "https://www.netflix.com/title/" + id;
        if ("prime".equals(p.app)) return "https://app.primevideo.com/detail?gti=" + id;
        return "https://www.disneyplus.com/browse/entity-" + id;
    }

    /** YouTube id of a trailer, or "" (one TMDB request, remembered with the title's link). Blocking. */
    String trailer(Pick p) {
        if (p == null || p.id <= 0) return "";
        String key = "yt:" + p.kind + p.id;
        String saved = links.getString(key, null);
        if (saved != null) return saved;
        String id = "";
        try {
            JSONArray videos = get("/" + p.kind + "/" + p.id + "/videos?language=" + lang()).optJSONArray("results");
            String any = "";
            for (int i = 0; videos != null && i < videos.length(); i++) {
                JSONObject v = videos.optJSONObject(i);
                if (v == null || !"YouTube".equals(v.optString("site"))) continue;
                String vid = v.optString("key");
                if (vid.isEmpty()) continue;
                if (any.isEmpty()) any = vid;
                if ("Trailer".equals(v.optString("type"))) { id = vid; break; }
            }
            if (id.isEmpty()) id = any;
        } catch (Exception e) {
            AppLog.w("trailer: " + e.getMessage());
            return "";
        }
        links.edit().putString(key, id).apply();
        return id;
    }

    private static String watchmode(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.watchmode.com/v1" + path + (path.contains("?") ? "&" : "?")
                + "apiKey=" + URLEncoder.encode(Config.WATCHMODE_KEY.trim(), "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Watchmode returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = key();
        if (key.isEmpty()) throw new IOException("no TMDB key");
        return request(path, key);
    }

    private static final String REJECTED = "TMDB rejected the key";

    private static JSONObject request(String path, String key) throws IOException, org.json.JSONException {
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException(REJECTED);
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    // MARK: - Plex (your own library: config.properties "plex_url" and "plex_token")

    /** Only Plex's own https://….plex.direct address (it has a valid certificate): a typo never gets the account token. */
    private static boolean plexOn() {
        String url = Config.PLEX_URL.trim();
        if (Config.PLEX_TOKEN.trim().isEmpty() || !url.startsWith("https://")) return false;
        try {
            String host = new java.net.URI(url).getHost();
            return host != null && host.endsWith(".plex.direct");
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    /** The connection to the server, with the usual certificate checks: plex_url is Plex's own https://...plex.direct address. */
    private static HttpURLConnection plexOpen(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(Config.PLEX_URL.trim().replaceAll("/+$", "") + path).openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(false);   // HttpURLConnection keeps custom headers across hosts: the token must not follow a redirect
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-Plex-Token", Config.PLEX_TOKEN.trim());
        return c;
    }

    private static JSONObject plexGet(String path) throws Exception {
        HttpURLConnection c = plexOpen(path);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Plex returned HTTP " + code);
            return new JSONObject(read(c.getInputStream())).getJSONObject("MediaContainer");
        } finally {
            c.disconnect();
        }
    }

    /** The newest movies ("movie") or shows ("tv") in the Plex libraries, up to 8. Negative id: apart from TMDB's. */
    private List<Pick> plexPicks(String kind) throws Exception {
        List<Pick> out = new ArrayList<>();
        String server = plexGet("/identity").getString("machineIdentifier");
        JSONArray sections = plexGet("/library/sections").getJSONArray("Directory");
        for (int i = 0; i < sections.length(); i++) {
            JSONObject sec = sections.getJSONObject(i);
            if (!sec.optString("type").equals(kind.equals("movie") ? "movie" : "show")) continue;
            JSONArray items = plexGet("/library/sections/" + sec.getString("key")
                    + "/all?sort=addedAt:desc&X-Plex-Container-Start=0&X-Plex-Container-Size=8").optJSONArray("Metadata");
            for (int j = 0; items != null && j < items.length(); j++) {
                JSONObject m = items.getJSONObject(j);
                if (m.optString("art").isEmpty()) continue;
                Pick p = new Pick();
                p.id = -m.getInt("ratingKey");
                p.kind = kind;
                p.title = m.optString("title");
                p.backdrop = m.getString("art");     // the path carries a version: a new image is a new file
                p.poster = m.optString("thumb");
                // Plex's own route to the title page (a movie's info, a show's seasons); the slug only decorates it.
                p.link = "plex://libraries/" + server + "/" + (kind.equals("movie") ? "movie" : "show") + "/"
                        + p.title.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-") + "/" + m.getInt("ratingKey");
                p.app = "plex";
                p.pkg = "com.plexapp.android";
                List<String> parts = new ArrayList<>();
                if (kind.equals("tv")) parts.add(Ui.tr("Series"));
                JSONArray genres = m.optJSONArray("Genre");
                if (genres != null && genres.length() > 0) {
                    String name = genres.getJSONObject(0).optString("tag");
                    parts.add(genre(name));
                }
                String length = kind.equals("movie") ? runtime(m.optInt("duration") / 60000) : seasons(m.optInt("childCount"));
                if (length != null) parts.add(length);
                parts.add("Plex");
                p.meta = String.join(" · ", parts);
                p.detailed = true;
                out.add(p);
            }
        }
        return out.size() > 8 ? out.subList(0, 8) : out;
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (!f.exists()) fetchFile(f, p.id < 0
                ? plexOpen("/photo/:/transcode?width=1920&height=1080&minSize=1&url=" + URLEncoder.encode(p.backdrop, "UTF-8"))
                : (HttpURLConnection) new URL(IMAGES + p.backdrop).openConnection());
        File poster = posterFile(p);
        if (poster == null || poster.exists()) return;
        try {   // the narrow cards: without a poster they crop the backdrop
            fetchFile(poster, p.id < 0
                    ? plexOpen("/photo/:/transcode?width=500&height=750&minSize=1&url=" + URLEncoder.encode(p.poster, "UTF-8"))
                    : (HttpURLConnection) new URL(POSTERS + p.poster).openConnection());
        } catch (IOException e) {
            AppLog.w("poster: " + e.getMessage());
        }
    }

    private File posterFile(Pick p) {
        return p.poster == null || p.poster.isEmpty() ? null : new File(new File(context.getCacheDir(), "recs"), "p" + p.poster.replace("/", ""));
    }

    private void fetchFile(File f, HttpURLConnection c) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the photo (≥ the UI's width) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight + " → " + Backdrop.W + "×" + Backdrop.H);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            Poster poster = forLight ? loadPoster(p) : null;
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                if (poster != null) posters.put(p.backdrop, poster);
                while (images.size() > (forLight ? Prefs.cards() + 7 : 4)) images.remove(images.keySet().iterator().next());
                posters.keySet().retainAll(images.keySet());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    /** The poster at the narrow card's size, or null (not downloaded yet or unreadable). */
    private Poster loadPoster(Pick p) {
        File f = posterFile(p);
        if (f == null || !f.exists()) return null;
        Bitmap full = BitmapFactory.decodeFile(f.getPath());
        if (full == null) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
        Bitmap card = Backdrop.card(full, Backdrop.TALL_W, Backdrop.TALL_H);
        if (card != full && !full.isRecycled()) full.recycle();   // the decoded 500 × 750 poster is 1.5 MB
        return new Poster(card);
    }

    Poster poster(Pick p) {
        synchronized (images) {
            return p == null || p.backdrop == null ? null : posters.get(p.backdrop);
        }
    }

    void trim() {
        // Explore's picks stay: the home screen opens on that tab, so its cards are never gray when you come back.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Pick p : now(null)) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) {
            images.keySet().retainAll(keep);
            posters.keySet().retainAll(keep);
        }
    }

    private static void prune(File dir) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= 60) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 60; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                        .put("poster", p.poster == null ? "" : p.poster).put("link", p.link == null ? "" : p.link)
                        .put("meta", p.meta).put("overview", p.overview == null ? "" : p.overview)
                        .put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed));
            }
            JSONArray rowsJson = new JSONArray();
            for (List<Row> tab : rows) {
                JSONArray tabJson = new JSONArray();
                for (Row r : tab) {
                    JSONArray list = new JSONArray();
                    for (Pick p : r.picks) list.put(pickJson(p));
                    tabJson.put(new JSONObject().put("title", r.title).put("picks", list));
                }
                rowsJson.put(tabJson);
            }
            cache.edit().putString("picks", a.toString()).putString("rows", rowsJson.toString())
                    .putInt("period", period).putLong("listAt", listAt)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION).apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.poster = o.optString("poster");
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            p.overview = o.optString("overview");
            out.add(p);
        }
        return out;
    }

    private static JSONObject pickJson(Pick p) throws org.json.JSONException {
        return new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop)
                .put("poster", p.poster == null ? "" : p.poster).put("link", p.link == null ? "" : p.link)
                .put("meta", p.meta).put("overview", p.overview == null ? "" : p.overview)
                .put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                .put("detailed", p.detailed);
    }

    /** Each row's titles, saved in full: they are not all in the hourly pool. */
    private static List<List<Row>> parseRows(JSONArray tabs) {
        List<List<Row>> out = new ArrayList<>();
        for (int t = 0; t < tabs.length(); t++) {
            JSONArray tab = tabs.optJSONArray(t);
            List<Row> tabRows = new ArrayList<>();
            if (tab != null) {
                for (int i = 0; i < tab.length(); i++) {
                    JSONObject o = tab.optJSONObject(i);
                    if (o == null) continue;
                    JSONArray list = o.optJSONArray("picks");
                    List<Pick> picks = list == null ? new ArrayList<>() : parse(list);
                    if (!picks.isEmpty()) tabRows.add(new Row(o.optString("title"), picks));
                }
            }
            out.add(tabRows);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
