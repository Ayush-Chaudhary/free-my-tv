package io.freemytv;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Search: an on-screen keyboard, the apps on the TV, and one TMDB search. Voice uses the speech recognizer when the
 * mic is allowed, and the system's voice screen otherwise. "open Netflix" opens that app.
 */
public class SearchActivity extends Activity implements SearchCanvas.Host {
    static final String EXTRA_VOICE = "voice";

    /** Opens this screen and starts listening. Safe from the key process (its own task). */
    static void startVoice(android.content.Context c) {
        c.startActivity(front(new Intent(c, SearchActivity.class)).putExtra(EXTRA_VOICE, true));
    }

    /**
     * From outside the app: the app's task, with this screen on top and nothing above it. NEW_TASK alone only brings that
     * task back as it was, so a voice screen left open in it (Google's, after an update restarted the app) came back
     * instead, and this screen never heard the press.
     */
    static Intent front(Intent intent) {
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static final String EXTRA_QUERY = "query";
    static final String EXTRA_NOTE = "note";
    private static final int VOICE = 1, MIC = 2;
    private static final String KATNISS = "com.google.android.katniss";
    private static final long TYPING_PAUSE = 700;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private SearchCanvas canvas;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private List<String[]> apps = new ArrayList<>();
    private boolean appsReady, demo;
    private volatile int generation;
    /** Background work of the latest query. A new query cancels it: queued tasks never start, running ones are interrupted. */
    private final List<Future<?>> inflight = new ArrayList<>();
    private ExecutorService lookups;
    /** The turn on screen and its cards, so a provider lookup that lands late can still fill in its badge. */
    private AssistantTurn shown;
    private final java.util.Map<AssistantTurn.Pick, SearchCanvas.Hit> shownHits = new java.util.HashMap<>();
    private String note = "";
    private String pending = "";
    /** Why this turn is plain search though the assistant is set up (shown once, in the status line). */
    private String assistNote = "";
    private long listenedAt;
    /** Voice language: Indian English and Hinglish first; en-US if the recognizer rejects it. */
    private String voiceLang = "en-IN";

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        if (getIntent().getBooleanExtra("demo", false)) {
            String theme = getIntent().getStringExtra("theme");
            if ("light".equals(theme) || "dark".equals(theme)) Prefs.setTheme(theme);
            demo = true;
        }
        canvas = new SearchCanvas(this, this);
        boolean night = HomeData.isNight(Prefs.theme(), System.currentTimeMillis(), 0, 0);
        canvas.setNight(night);
        setContentView(canvas);
        if (demo) {
            canvas.demo(getIntent().getStringExtra("shot"), night);
            return;
        }
        io.execute(() -> {
            List<String[]> list = HomeApps.launchable(this);
            handler.post(() -> {
                apps = list;
                appsReady = true;
                if (!pending.isEmpty()) run(pending, true);
                else showIdle();
            });
        });
        if (!searchFor(getIntent()) && getIntent().getBooleanExtra(EXTRA_VOICE, false)) listen();
    }

    private boolean searchFor(Intent intent) {
        String query = intent == null ? null : intent.getStringExtra(EXTRA_QUERY);
        if (query == null || query.trim().isEmpty()) return false;
        note = intent.getBooleanExtra(EXTRA_NOTE, false)
                ? Ui.trf("Google TV couldn't load “{query}”. Here it is on your apps.", "query", query.trim()) : "";
        pending = query.trim();
        canvas.setQuery(pending);
        if (appsReady) run(pending, true);
        return true;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra("demo", false)) {
            demo = true;
            String theme = intent.getStringExtra("theme");
            if ("light".equals(theme) || "dark".equals(theme)) Prefs.setTheme(theme);
            boolean night = HomeData.isNight(Prefs.theme(), System.currentTimeMillis(), 0, 0);
            canvas.setNight(night);
            canvas.demo(intent.getStringExtra("shot"), night);
            return;
        }
        if (demo) return;
        if (!searchFor(intent) && intent.getBooleanExtra(EXTRA_VOICE, false)) listen();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) recognizer.destroy();
        if (tts != null) tts.shutdown();
        io.shutdownNow();
        super.onDestroy();
    }

    /**
     * Back leaves the search. If another app's screen is under it in this task (Google's voice screen, left there when an
     * update restarted the app), the whole task goes, so that screen can't come back. In the home screen's task, only
     * the search closes.
     */
    @Override public void onBackPressed() {
        android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
        if (am != null) {
            for (android.app.ActivityManager.AppTask t : am.getAppTasks()) {
                android.app.ActivityManager.RecentTaskInfo info;
                try {
                    info = t.getTaskInfo();
                } catch (IllegalArgumentException e) {
                    continue;   // a task that just went away
                }
                if (info.id != getTaskId() || info.baseActivity == null) continue;
                if (!getPackageName().equals(info.baseActivity.getPackageName())) {
                    AppLog.w("search: closing a task that " + info.baseActivity.getPackageName() + " was left in");
                    t.finishAndRemoveTask();   // the task's, not the activity's: that one only goes with the task's root
                    return;
                }
            }
        }
        super.onBackPressed();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event != null && RemoteKeys.voiceKey(event.getKeyCode())) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) listen();
            return true;
        }
        if (canvas != null && canvas.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override public void commit(String query) {
        if (demo) return;
        handler.removeCallbacks(typed);
        pending = query == null ? "" : query.trim();
        canvas.setQuery(query == null ? "" : query);
        if (pending.isEmpty()) {
            showIdle();
            return;
        }
        run(pending, false);
    }

    @Override public void submit(String query, boolean voice) {
        if (demo) return;
        handler.removeCallbacks(typed);
        pending = query == null ? "" : query.trim();
        canvas.setQuery(query == null ? "" : query);
        if (pending.isEmpty()) {
            showIdle();
            return;
        }
        if (pending.length() >= 2) handler.postDelayed(typed, voice ? 0 : TYPING_PAUSE);
        else canvas.show(appHits(pending), "", "");
    }

    private final Runnable typed = () -> run(pending, false);

    /** Drops the old query's requests, so fast typing doesn't leave all the background threads busy with stale ones. */
    private void cancelWork() {
        for (Future<?> f : inflight) f.cancel(true);
        inflight.clear();
        if (lookups != null) lookups.shutdownNow();
        lookups = null;
        shown = null;
        shownHits.clear();
    }

    /** Runs on {@code io} and is cancelled by the next query. Main thread only. */
    private void work(Runnable r) {
        inflight.removeIf(Future::isDone);
        inflight.add(io.submit(r));
    }

    private void showIdle() {
        List<String> popular = new ArrayList<>();
        for (Recs.Pick p : Recs.get(this).now(null)) {
            if (p.title != null && popular.size() < 6) popular.add(p.title);
        }
        canvas.setIdle(Prefs.recentSearches(), popular);
    }

    private void run(String raw, boolean voice) {
        String query = raw == null ? "" : raw.trim();
        int gen = ++generation;
        cancelWork();
        if (query.isEmpty()) {
            showIdle();
            return;
        }
        Prefs.rememberSearch(query);
        String opened = command(query);
        if (opened != null) {
            for (String[] a : apps) {
                if (a[1].equalsIgnoreCase(opened)) {
                    openApp(a[0], a[1]);
                    return;
                }
            }
        }
        if (query.length() < 2) return;
        canvas.beginThinking();
        assistNote = "";
        // A spoken request goes to the assistant when it is set up; "open Netflix" and typed searches stay plain.
        if (voice && opened == null && Assistant.configured()) {
            askAssistant(query, gen);
            return;
        }
        plain(query, voice, gen, opened != null ? opened : query);
    }

    /** The plain TMDB title search with the TV's apps: what the screen did before the assistant, and its fallback. */
    private void plain(String query, boolean voice, int gen, String forTmdb) {
        work(() -> {
            if (gen != generation) return;
            List<Recs.Pick> found;
            try {
                found = Recs.get(this).searchHits(forTmdb);
            } catch (Exception e) {
                AppLog.w("search: " + e.getMessage());
                found = null;
            }
            List<Recs.Pick> hits = found;
            handler.post(() -> {
                if (gen != generation || isDestroyed()) return;
                if (hits == null) {
                    canvas.show(appHits(query), Ui.tr("Couldn't search right now. Ask Google Assistant instead."), "");
                    return;
                }
                AtomicInteger left = new AtomicInteger(hits.size());
                List<SearchCanvas.Hit> cards = appHits(query);
                if (hits.isEmpty()) {
                    finish(gen, voice, query, cards);
                    return;
                }
                for (Recs.Pick p : hits) {
                    work(() -> {
                        if (gen != generation) return;
                        try {
                            Recs.get(SearchActivity.this).fillService(p);
                        } catch (Exception e) {
                            AppLog.w("search: " + e.getMessage());
                        }
                        handler.post(() -> {
                            if (gen != generation || isDestroyed()) return;
                            if (p.detailed) cards.add(p.pkg != null ? 0 : cards.size(), hit(p));
                            if (left.decrementAndGet() == 0) finish(gen, voice, query, cards);
                        });
                    });
                }
            });
        });
    }

    /**
     * One spoken request to the Worker. It answers whole (recommend or search, open an app, or just a reply); the TV
     * then finds which installed app streams each pick. Any failure, or about 6 s, leaves the plain search.
     */
    private void askAssistant(String query, int gen) {
        String lang = Ui.language != null && Ui.language.matches("[a-z]{2,3}(-[A-Za-z]{2,4})?") ? Ui.language : "en";
        work(() -> {
            if (gen != generation) return;
            List<String> services = new ArrayList<>();
            for (String[] s : Recs.installedServices(this)) if (!Prefs.serviceOff(s[0])) services.add(s[0]);
            Assistant.Result r = Assistant.turn(query, lang, Recs.REGION, services);
            if (r.turn == null) {
                AppLog.w("assistant: no answer (" + (r.timeout ? "timeout" : "HTTP " + r.status) + "), plain search");
                String why = r.status == 401 ? Ui.tr("The assistant didn't accept this TV's token. Here's a plain search.")
                        : r.status == 429 ? Ui.tr("The assistant is resting for now. Here's a plain search.")
                        : Ui.tr("The assistant isn't answering. Here's a plain search.");
                handler.post(() -> {
                    if (gen != generation || isDestroyed()) return;
                    assistNote = why;
                    plain(query, true, gen, query);
                });
                return;
            }
            if (gen != generation) return;
            fillApps(r.turn, gen);
            handler.post(() -> {
                if (gen != generation || isDestroyed()) return;
                present(r.turn);
            });
        });
    }

    /**
     * Sets each pick's installed app from TMDB (Recs.fillApp), a few at a time, and waits up to 4 s for them (each call
     * gives up after about 3 s). A lookup that is still running then keeps going, and {@link #lateApp} fills in its
     * card if it lands after the picks are on screen. A title it can't place gets none.
     */
    private void fillApps(AssistantTurn turn, int gen) {
        if (turn.picks.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(5, turn.picks.size()));
        handler.post(() -> {
            if (gen != generation || isDestroyed()) pool.shutdownNow();
            else lookups = pool;
        });
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(turn.picks.size());
        for (AssistantTurn.Pick p : turn.picks) {
            pool.execute(() -> {
                try {
                    Recs.Pick one = new Recs.Pick();
                    one.id = p.id;
                    one.kind = "tv".equals(p.kind) ? "tv" : "movie";
                    Recs.get(this).fillApp(one);
                    if (one.pkg != null) {
                        p.service = Recs.serviceName(one);   // before pkg: a reader that sees pkg sees the service
                        p.pkg = one.pkg;
                        handler.post(() -> lateApp(turn, p));
                    }
                } catch (Exception e) {
                    AppLog.w("assistant: apps for a pick: " + e.getMessage());
                } finally {
                    done.countDown();
                }
            });
        }
        try {
            done.await(4, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            return;
        }
        pool.shutdown();   // lets a slow lookup finish; the next query's cancelWork() stops it
    }

    /** A lookup that landed after the cards were drawn: give that card its badge and "Open in {service}". */
    private void lateApp(AssistantTurn turn, AssistantTurn.Pick p) {
        if (turn != shown || isDestroyed()) return;
        SearchCanvas.Hit h = shownHits.get(p);
        String pkg = p.pkg;
        if (h == null || pkg == null || h.pkg != null) return;
        applyApp(h, p.service, pkg);
        canvas.hitChanged();
    }

    private static void applyApp(SearchCanvas.Hit h, String service, String pkg) {
        h.service = service;
        h.pkg = pkg;
        h.label = service;
        if (h.pick != null) {
            h.pick.pkg = pkg;
            h.pick.app = Recs.serviceKeyOf(pkg);
        }
    }

    private void finish(int gen, boolean voice, String query, List<SearchCanvas.Hit> cards) {
        if (gen != generation) return;
        addStore(query, cards);
        String reply = "";
        String status = note.isEmpty() ? assistNote : note;
        assistNote = "";
        cards.sort((a, b) -> Recs.searchOrder(query, a.title, a.year, a.pick == null ? 0 : a.pick.popularity,
                onApps(a), b.title, b.year, b.pick == null ? 0 : b.pick.popularity, onApps(b)));
        if (cards.isEmpty()) status = Ui.trf("No matches for “{query}”", "query", query);
        else if (voice) {
            for (SearchCanvas.Hit h : cards) {
                if (h.pick != null) {
                    reply = h.show ? Ui.trf("It's a show: {title}.", "title", h.title)
                            : Ui.trf("It's a movie: {title}.", "title", h.title);
                    break;
                }
            }
            if (!reply.isEmpty()) speak(reply);
        }
        canvas.show(cards, status, reply);
    }

    private void speak(String line) {
        if (tts == null) tts = new TextToSpeech(this, ok -> {
            if (ok == TextToSpeech.SUCCESS && tts != null) tts.speak(line, TextToSpeech.QUEUE_FLUSH, null, "search");
        });
        else tts.speak(line, TextToSpeech.QUEUE_FLUSH, null, "search");
    }

    /**
     * Show one Worker turn. No network here: the TV has already filled each pick's {@code service} and {@code pkg}
     * (null when it isn't on an installed app, and then the card has no badge). {@code open_app} launches a matching
     * app when one is installed.
     */
    public void present(AssistantTurn turn) {
        if (demo || turn == null) return;
        handler.removeCallbacks(typed);
        generation++;
        shown = null;
        shownHits.clear();
        String reply = AssistantTurn.clip(turn.reply);
        if (AssistantTurn.OPEN_APP.equals(turn.intent)) {
            String[] app = appForService(turn.app);
            if (app != null) {
                openApp(app[0], app[1]);
                return;
            }
        }
        List<SearchCanvas.Hit> cards = new ArrayList<>();
        if (!AssistantTurn.CHAT.equals(turn.intent)) {
            for (AssistantTurn.Pick p : turn.picks) {
                SearchCanvas.Hit h = new SearchCanvas.Hit();
                h.title = p.title == null ? "" : p.title;
                h.year = p.year;
                h.why = p.why == null ? "" : p.why;
                String pkg = p.pkg, service = p.service;   // a late lookup may set them any time; pkg last
                h.service = pkg == null ? null : service;
                h.quiet = true;
                h.show = "tv".equals(p.kind);
                Recs.Pick pick = new Recs.Pick();
                pick.id = p.id;
                pick.kind = p.kind == null || p.kind.isEmpty() ? "movie" : p.kind;
                pick.title = h.title;
                pick.year = p.year;
                h.pick = pick;
                if (pkg != null) applyApp(h, service, pkg);
                shownHits.put(p, h);
                cards.add(h);
            }
            shown = turn;
        }
        if (!reply.isEmpty()) speak(reply);
        canvas.show(cards, "", reply);
    }

    /** Match a Worker service key ("prime", "Prime Video") to an installed app, or null. */
    private String[] appForService(String service) {
        if (service == null || service.isEmpty() || apps == null) return null;
        String s = service.toLowerCase(Locale.ROOT);
        for (String[] a : apps) {
            if (a[1] == null) continue;
            String name = a[1].toLowerCase(Locale.ROOT);
            if (name.contains(s) || s.contains(name)) return a;
        }
        return null;
    }

    private List<SearchCanvas.Hit> lastHits = new ArrayList<>();

    private List<SearchCanvas.Hit> appHits(String query) {
        List<SearchCanvas.Hit> out = new ArrayList<>();
        String q = query.toLowerCase(Locale.ROOT);
        String opened = command(query);
        for (String[] a : apps) {
            String name = a[1].toLowerCase(Locale.ROOT);
            if (name.contains(q) || (opened != null && name.contains(opened.toLowerCase(Locale.ROOT)))) {
                SearchCanvas.Hit h = new SearchCanvas.Hit();
                h.title = a[1];
                h.label = a[1];
                h.pkg = a[0];
                h.app = true;
                h.meta = Ui.tr("Open");
                h.art = HomeApps.searchPoster(this, a[0]);
                out.add(h);
            }
        }
        lastHits = out;
        return out;
    }

    /** Known services that are not installed, only when the typed name matches. */
    private static boolean onApps(SearchCanvas.Hit h) {
        return h.app || (h.service != null && !h.service.isEmpty() && !h.store);
    }

    private void addStore(String query, List<SearchCanvas.Hit> cards) {
        String q = query.toLowerCase(Locale.ROOT);
        for (String[] s : Recs.catalog(this)) {
            if (s[1] == null || !s[1].toLowerCase(Locale.ROOT).contains(q)) continue;
            SearchCanvas.Hit h = new SearchCanvas.Hit();
            h.title = s[1];
            h.meta = Ui.tr("Get from Play Store");
            h.store = true;
            cards.add(h);
            if (cards.size() > 8) break;
        }
    }

    private static SearchCanvas.Hit hit(Recs.Pick p) {
        SearchCanvas.Hit h = new SearchCanvas.Hit();
        h.pick = p;
        h.title = p.title == null ? "" : p.title;
        h.pkg = p.pkg;
        h.show = "tv".equals(p.kind);
        h.year = p.year;
        h.length = p.length == null ? "" : p.length;
        h.service = p.pkg != null ? Recs.serviceName(p) : null;
        h.meta = h.service != null ? h.service : Ui.tr("Not on your apps");
        return h;
    }

    private static String command(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        for (String verb : new String[] {"open ", "launch ", "start ", "go to "}) {
            if (q.startsWith(verb) && q.length() > verb.length()) return query.substring(verb.length()).trim();
        }
        return null;
    }

    @Override public void open(SearchCanvas.Hit hit) {
        if (demo || hit == null) return;
        if (hit.store) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=" + android.net.Uri.encode(hit.title)))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                canvas.show(lastHits, Ui.tr("Nothing on your apps. Ask Google Assistant instead."), "");
            }
            return;
        }
        if (hit.app) {
            openApp(hit.pkg, hit.label);
            return;
        }
        if (hit.pick == null || hit.pick.pkg == null) {
            canvas.show(lastHits, Ui.trf("{title} isn't on your TV's apps.", "title", hit.title), "");
            return;
        }
        Recs.Pick p = hit.pick;
        io.execute(() -> {
            Recs.get(this).ensureLink(p);
            handler.post(() -> {
                if (isDestroyed()) return;
                HomeApps.open(this, HomeApps.app(p.pkg, Recs.serviceName(p), p.link), null);
            });
        });
    }

    private void openApp(String pkg, String label) {
        HomeApps.open(this, HomeApps.app(pkg, label), null);
    }

    @Override public void ask(String query) {
        if (demo) return;
        try {
            startActivity(new Intent("android.search.action.GLOBAL_SEARCH").putExtra("query", query == null ? "" : query.trim())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        } catch (ActivityNotFoundException e) {
            canvas.show(lastHits, Ui.tr("Google Assistant isn't on this TV."), "");
        }
    }

    @Override public void listen() {
        if (demo) return;
        long now = SystemClock.uptimeMillis();
        if (now - listenedAt < 800) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            listenOverlay();
            return;
        }
        if (recognizer != null) recognizer.destroy();
        recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) { canvas.setListening(true, ""); }
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) { canvas.setLevel(rmsdB); }
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onError(int error) {
                // ERROR_LANGUAGE_NOT_SUPPORTED (12) and _UNAVAILABLE (13) are API 31: no en-IN here, so once, listen again in en-US instead of going quiet.
                if (error == 12 || error == 13) {
                    if ("en-IN".equals(voiceLang)) {
                        AppLog.w("voice: en-IN refused (" + error + "), trying en-US");
                        voiceLang = "en-US";
                        listenedAt = 0;
                        handler.post(SearchActivity.this::listen);
                        return;
                    }
                }
                canvas.setListening(false, "");
            }
            @Override public void onResults(Bundle results) { heard(results); }
            @Override public void onPartialResults(Bundle partial) {
                ArrayList<String> said = partial == null ? null : partial.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (said != null && !said.isEmpty()) canvas.setListening(true, said.get(0));
            }
            @Override public void onEvent(int eventType, Bundle params) {}
        });
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, voiceLang)                  // Indian English and Hinglish
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, voiceLang)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        canvas.setListening(true, "");
        listenedAt = SystemClock.uptimeMillis();
        recognizer.startListening(intent);
    }

    private void heard(Bundle results) {
        canvas.setListening(false, "");
        ArrayList<String> said = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (said == null || said.isEmpty()) return;
        pending = said.get(0);
        canvas.setQuery(pending);
        run(pending, true);
    }

    @Override public void cancelListen() {
        if (recognizer != null) recognizer.cancel();
        canvas.setListening(false, "");
    }

    /** The TV's speech screen (Google's, when it's there). No recognizer: they can still type. */
    private void listenOverlay() {
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, Ui.tr("Search movies, shows and apps"));
        Intent google = new Intent(intent).setPackage(KATNISS);
        if (google.resolveActivity(pm) != null) intent = google;
        else if (intent.resolveActivity(pm) == null) {
            canvas.show(lastHits, Ui.tr("Voice search isn't on this TV."), "");
            return;
        }
        try {
            startActivityForResult(intent, VOICE);
            listenedAt = SystemClock.uptimeMillis();
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("voice search: " + e.getMessage());
            canvas.show(lastHits, Ui.tr("Voice search isn't on this TV."), "");
        }
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request != MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) listen();
        else listenOverlay();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE || result != RESULT_OK || data == null) return;
        ArrayList<String> said = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (said == null || said.isEmpty()) return;
        pending = said.get(0);
        canvas.setQuery(pending);
        run(pending, true);
    }
}
