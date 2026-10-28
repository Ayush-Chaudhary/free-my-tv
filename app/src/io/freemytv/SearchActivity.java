package io.freemytv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognizerIntent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Search, as on Google TV: voice (the remote's mic, or the button here) or typing. Apps on the TV match at once.
 * Movies and shows come from one TMDB search, then each title's app is filled in alongside the others; a title's own
 * page is looked up only when it is opened. Anything else can go on to Google Assistant.
 */
public class SearchActivity extends Activity {
    static final String EXTRA_VOICE = "voice";

    /** Opens this screen and starts listening. Safe from the key process (its own task). */
    static void startVoice(android.content.Context c) {
        c.startActivity(new Intent(c, SearchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(EXTRA_VOICE, true));
    }
    static final String EXTRA_QUERY = "query";
    static final String EXTRA_NOTE = "note";
    private static final int VOICE = 1;
    private static final String KATNISS = "com.google.android.katniss";
    private static final long TYPING_PAUSE = 700;
    private static final int BG = 0xFF0B0B0D, FIELD = 0xFF1C1C1E, DIM = 0xFF98989D, INK = 0xFFF5F5F7, DARK = 0xFF1C1C1E;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(4);
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private float k;
    private EditText field;
    private TextView status, assist, appsTitle, titlesTitle;
    private LinearLayout appsRow, titlesRow;
    private List<String[]> apps = new ArrayList<>();
    private boolean appsReady;
    private int generation;
    private boolean focusResults;
    private String note = "";
    private long listenedAt;
    private final Runnable typed = () -> run(field.getText().toString(), false);

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        setContentView(build());
        io.execute(() -> {
            List<String[]> list = HomeApps.launchable(this);
            handler.post(() -> {
                apps = list;
                appsReady = true;
                if (field.getText().length() > 0) run(field.getText().toString(), focusResults);
            });
        });
        if (!searchFor(getIntent()) && getIntent().getBooleanExtra(EXTRA_VOICE, false)) listen();
        else if (getIntent().getStringExtra(EXTRA_QUERY) == null) field.requestFocus();
    }

    private boolean searchFor(Intent intent) {
        String query = intent.getStringExtra(EXTRA_QUERY);
        if (query == null || query.trim().isEmpty()) return false;
        note = intent.getBooleanExtra(EXTRA_NOTE, false)
                ? Ui.trf("Google TV couldn't load “{query}”. Here it is on your apps.", "query", query.trim()) : "";
        handler.removeCallbacks(typed);
        field.setText(query);
        field.setSelection(field.getText().length());
        focusResults = true;
        if (appsReady) run(query, true);
        return true;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (!searchFor(intent) && intent.getBooleanExtra(EXTRA_VOICE, false)) listen();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (RemoteKeys.voiceKey(keyCode)) {
            listen();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private View build() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BG);
        page.setPadding(px(80), px(64), px(80), 0);
        page.setClipChildren(false);
        page.setClipToPadding(false);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setClipChildren(false);

        FrameLayout box = new FrameLayout(this);
        box.setBackground(round(FIELD, px(42)));
        ImageView glass = symbol("search", DIM);
        FrameLayout.LayoutParams glassAt = new FrameLayout.LayoutParams(px(34), px(34), Gravity.CENTER_VERTICAL | Gravity.START);
        glassAt.leftMargin = px(32);
        box.addView(glass, glassAt);
        field = new EditText(this);
        field.setBackground(null);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setTextColor(INK);
        field.setHintTextColor(DIM);
        field.setHint(Ui.tr("Search movies, shows and apps"));
        field.setTypeface(Ui.medium);
        field.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(32));
        field.setPadding(px(84), 0, px(28), 0);
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                handler.removeCallbacks(typed);
                showApps(s.toString());
                if (s.toString().trim().length() >= 2) handler.postDelayed(typed, TYPING_PAUSE);
                else titlesRow.removeAllViews();
            }
        });
        field.setOnEditorActionListener((v, action, e) -> {
            handler.removeCallbacks(typed);
            run(field.getText().toString(), false);
            return true;
        });
        field.setOnFocusChangeListener((v, has) -> box.setBackground(round(has ? 0xFF2C2C2E : FIELD, px(42))));
        box.addView(field, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(84)));
        bar.addView(box, new LinearLayout.LayoutParams(0, px(84), 1));

        FrameLayout mic = new FrameLayout(this);
        mic.setFocusable(true);
        mic.setBackground(round(FIELD, px(42)));
        ImageView micIcon = symbol("mic", INK);
        mic.addView(micIcon, new FrameLayout.LayoutParams(px(24), px(34), Gravity.CENTER));
        mic.setContentDescription(Ui.tr("Search by voice"));
        mic.setOnClickListener(v -> listen());
        mic.setOnFocusChangeListener((v, has) -> {
            mic.setBackground(round(has ? INK : FIELD, px(42)));
            micIcon.setColorFilter(has ? DARK : INK, PorterDuff.Mode.SRC_IN);
            grow(v, has);
        });
        LinearLayout.LayoutParams micAt = new LinearLayout.LayoutParams(px(84), px(84));
        micAt.leftMargin = px(20);
        bar.addView(mic, micAt);

        assist = pill(Ui.tr("Ask Google Assistant"));
        assist.setOnClickListener(v -> askAssistant());
        assist.setVisibility(View.GONE);
        LinearLayout.LayoutParams assistAt = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(84));
        assistAt.leftMargin = px(20);
        bar.addView(assist, assistAt);
        page.addView(bar);

        status = text(DIM, 26, Ui.regular);
        LinearLayout.LayoutParams statusAt = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusAt.topMargin = px(28);
        page.addView(status, statusAt);
        titlesTitle = section(page, Ui.tr("Movies & Shows"));
        titlesRow = row(page);
        appsTitle = section(page, Ui.tr("Apps"));
        appsRow = row(page);
        show(false, false);
        return page;
    }

    private TextView section(LinearLayout page, String name) {
        TextView t = text(INK, 28, Ui.semibold);
        t.setText(name);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        at.topMargin = px(26);
        at.bottomMargin = px(14);
        page.addView(t, at);
        return t;
    }

    private LinearLayout row(LinearLayout page) {
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(px(12), px(12), px(12), px(12));
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setClipChildren(false);
        scroll.addView(line);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        at.leftMargin = -px(12);
        page.addView(scroll, at);
        return line;
    }

    private void show(boolean titles, boolean appsOn) {
        titlesTitle.setVisibility(titles ? View.VISIBLE : View.GONE);
        ((View) titlesRow.getParent()).setVisibility(titles ? View.VISIBLE : View.GONE);
        appsTitle.setVisibility(appsOn ? View.VISIBLE : View.GONE);
        ((View) appsRow.getParent()).setVisibility(appsOn ? View.VISIBLE : View.GONE);
    }

    /** The TV's speech recognizer (Google's, when it's there). No recognizer: the field stays so they can type. */
    private void listen() {
        long now = SystemClock.uptimeMillis();
        if (now - listenedAt < 800) return;
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, Ui.tr("Search movies, shows and apps"));
        Intent google = new Intent(intent).setPackage(KATNISS);
        if (google.resolveActivity(pm) != null) intent = google;
        else if (intent.resolveActivity(pm) == null) {
            status.setText(Ui.tr("Voice search isn't on this TV."));
            field.requestFocus();
            return;
        }
        try {
            startActivityForResult(intent, VOICE);
            listenedAt = now;
        } catch (ActivityNotFoundException | SecurityException e) {
            AppLog.w("voice search: " + e.getMessage());
            status.setText(Ui.tr("Voice search isn't on this TV."));
            field.requestFocus();
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != VOICE) return;
        ArrayList<String> said = data == null ? null : data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        if (result != RESULT_OK || said == null || said.isEmpty()) {
            field.requestFocus();
            return;
        }
        String text = said.get(0);
        handler.removeCallbacks(typed);
        field.setText(text);
        field.setSelection(text.length());
        run(text, true);
    }

    /** Apps right away. Titles wait for a pause in typing, then one search. "open Netflix" opens that app. */
    private void run(String raw, boolean spoken) {
        String query = raw.trim();
        int gen = ++generation;
        focusResults = spoken;
        if (query.isEmpty()) {
            status.setText("");
            assist.setVisibility(View.GONE);
            titlesRow.removeAllViews();
            appsRow.removeAllViews();
            show(false, false);
            return;
        }
        assist.setVisibility(View.VISIBLE);
        String opened = command(query);
        if (opened != null) {
            for (String[] a : apps) {
                if (a[1].equalsIgnoreCase(opened)) {
                    openApp(a[0], a[1], field);
                    return;
                }
            }
        }
        showApps(query);
        if (query.length() < 2) {
            status.setText("");
            titlesRow.removeAllViews();
            show(false, appsRow.getChildCount() > 0);
            return;
        }
        status.setText(Ui.tr("Searching…"));
        String forTmdb = opened != null ? opened : query;
        io.execute(() -> {
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
                titlesRow.removeAllViews();
                if (hits == null) {
                    status.setText(Ui.tr("Couldn't search right now. Ask Google Assistant instead."));
                    show(false, appsRow.getChildCount() > 0);
                    return;
                }
                if (hits.isEmpty()) {
                    finishTitles(gen, spoken);
                    return;
                }
                show(true, appsRow.getChildCount() > 0);
                AtomicInteger left = new AtomicInteger(hits.size());
                for (Recs.Pick p : hits) {
                    io.execute(() -> {
                        try {
                            Recs.get(SearchActivity.this).fillService(p);
                        } catch (Exception e) {
                            AppLog.w("search: " + e.getMessage());
                        }
                        handler.post(() -> {
                            if (gen != generation || isDestroyed()) return;
                            if (p.detailed) titlesRow.addView(titleCard(p), p.pkg != null ? 0 : titlesRow.getChildCount());
                            if (left.decrementAndGet() == 0) finishTitles(gen, spoken);
                        });
                    });
                }
            });
        });
    }

    private void finishTitles(int gen, boolean spoken) {
        if (gen != generation) return;
        show(titlesRow.getChildCount() > 0, appsRow.getChildCount() > 0);
        status.setText(titlesRow.getChildCount() == 0 && appsRow.getChildCount() == 0
                ? Ui.tr("Nothing on your apps. Ask Google Assistant instead.") : note);
        if (spoken) {
            View first = titlesRow.getChildCount() > 0 ? titlesRow.getChildAt(0)
                    : appsRow.getChildCount() > 0 ? appsRow.getChildAt(0) : assist;
            first.requestFocus();
        }
    }

    private void showApps(String raw) {
        appsRow.removeAllViews();
        String query = raw.trim();
        if (query.isEmpty()) {
            show(titlesRow.getChildCount() > 0, false);
            return;
        }
        String q = query.toLowerCase(Locale.ROOT);
        String opened = command(query);
        for (String[] a : apps) {
            String name = a[1].toLowerCase(Locale.ROOT);
            if (name.contains(q) || (opened != null && name.contains(opened.toLowerCase(Locale.ROOT)))) appsRow.addView(appCard(a[0], a[1]));
        }
        show(titlesRow.getChildCount() > 0, appsRow.getChildCount() > 0);
    }

    private static String command(String query) {
        String q = query.toLowerCase(Locale.ROOT);
        for (String verb : new String[] {"open ", "launch ", "start ", "go to "}) {
            if (q.startsWith(verb) && q.length() > verb.length()) return query.substring(verb.length()).trim();
        }
        return null;
    }

    private void askAssistant() {
        String query = field.getText().toString().trim();
        try {
            startActivity(new Intent("android.search.action.GLOBAL_SEARCH").putExtra("query", query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        } catch (ActivityNotFoundException e) {
            status.setText(Ui.tr("Google Assistant isn't on this TV."));
        }
    }

    private void openApp(String pkg, String label, View from) {
        HomeApps.open(this, HomeApps.app(pkg, label), from, 0, 0, from.getWidth(), from.getHeight());
    }

    private View appCard(String pkg, String label) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        FrameLayout frame = frame(px(256), px(144));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(FIELD);
        frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(frame);
        TextView name = text(INK, 24, Ui.medium);
        name.setText(label);
        name.setPadding(0, px(10), 0, 0);
        card.addView(name);
        focusable(card, frame, px(256));
        card.setOnClickListener(v -> openApp(pkg, label, frame));
        io.execute(() -> {
            Bitmap b = banner(pkg);
            handler.post(() -> { if (!isDestroyed()) image.setImageBitmap(b); });
        });
        return card;
    }

    private View titleCard(Recs.Pick p) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        FrameLayout frame = frame(px(400), px(225));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(FIELD);
        frame.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(frame);
        TextView name = text(INK, 24, Ui.medium);
        name.setText(p.title);
        name.setPadding(0, px(10), 0, 0);
        name.setMaxWidth(px(400));
        card.addView(name);
        TextView where = text(DIM, 21, Ui.regular);
        where.setText(p.pkg != null ? Recs.serviceName(p) : Ui.tr("Not on your apps"));
        where.setMaxWidth(px(400));
        card.addView(where);
        if (p.pkg == null) frame.setAlpha(0.45f);
        focusable(card, frame, px(400));
        card.setOnClickListener(v -> openTitle(p, frame));
        loadPhoto(image, Recs.smallImage(p), Recs.posterImage(p));
        return card;
    }

    /** The title's own page, looked up now if it isn't remembered yet, then the app. */
    private void openTitle(Recs.Pick p, View from) {
        if (p.pkg == null) {
            status.setText(Ui.trf("{title} isn't on your TV's apps.", "title", p.title));
            return;
        }
        io.execute(() -> {
            Recs.get(this).ensureLink(p);
            handler.post(() -> {
                if (isDestroyed()) return;
                HomeApps.open(this, HomeApps.app(p.pkg, Recs.serviceName(p), p.link), from, 0, 0, from.getWidth(), from.getHeight());
            });
        });
    }

    private FrameLayout frame(int w, int h) {
        FrameLayout frame = new FrameLayout(this);
        frame.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(14));
            }
        });
        // Clip only once the card has a size. An empty outline (applied before layout) draws nothing, so the photo
        // never appears. Rounded corners stay.
        frame.setClipToOutline(false);
        frame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            boolean sized = r - l > 0 && b - t > 0;
            if (v.getClipToOutline() != sized) v.setClipToOutline(sized);
            v.invalidateOutline();
        });
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(14));
        ring.setStroke(px(4), INK);
        ring.setColor(0);
        frame.setForeground(ring);
        frame.getForeground().setAlpha(0);
        return frame;
    }

    private void focusable(LinearLayout card, FrameLayout frame, int width) {
        card.setFocusable(true);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT);
        at.rightMargin = px(32);
        card.setLayoutParams(at);
        card.setOnFocusChangeListener((v, has) -> {
            frame.getForeground().setAlpha(has ? 255 : 0);
            grow(v, has);
            if (has && v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).requestChildFocus(v, v);
        });
    }

    private void grow(View v, boolean focused) {
        v.animate().scaleX(focused ? 1.06f : 1).scaleY(focused ? 1.06f : 1).setDuration(150).start();
    }

    private TextView pill(String label) {
        TextView t = text(INK, 26, Ui.medium);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setPadding(px(34), 0, px(34), 0);
        t.setFocusable(true);
        t.setBackground(round(FIELD, px(42)));
        t.setOnFocusChangeListener((v, has) -> {
            t.setBackground(round(has ? INK : FIELD, px(42)));
            t.setTextColor(has ? DARK : INK);
            grow(v, has);
        });
        return t;
    }

    private TextView text(int color, float size, android.graphics.Typeface face) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(size));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private ImageView symbol(String name, int color) {
        ImageView v = new ImageView(this);
        v.setImageBitmap(Symbols.get(this, name));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setColorFilter(color, PorterDuff.Mode.SRC_IN);
        return v;
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int px(float v) {
        return Math.round(v * k);
    }

    private Bitmap banner(String pkg) {
        PackageManager pm = getPackageManager();
        try {
            Drawable d = pm.getApplicationBanner(pkg);
            boolean icon = d == null;
            if (icon) d = pm.getApplicationIcon(pkg);
            int w = px(256), h = px(144);
            Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            if (icon) {
                c.drawColor(FIELD);
                int s = px(88);
                d.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
            } else d.setBounds(0, 0, w, h);
            d.draw(c);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    /** Backdrop first, then the poster. Failures go to logcat (`adb logcat -s FreeMyTV` and look for `search image`). */
    private void loadPhoto(ImageView image, String url, String fallback) {
        Bitmap cached = url.isEmpty() ? null : images.get(url);
        if (cached != null) {
            image.setImageBitmap(cached);
            return;
        }
        io.execute(() -> {
            Bitmap b = download(url);
            String used = url;
            if (b == null && fallback != null && !fallback.isEmpty() && !fallback.equals(url)) {
                b = download(fallback);
                used = fallback;
            }
            if (b == null) {
                AppLog.w("search image: no picture" + (url.isEmpty() ? "" : " for " + url));
                return;
            }
            AppLog.w("search image: " + b.getWidth() + "x" + b.getHeight());
            images.put(used, b);
            Bitmap show = b;
            handler.post(() -> { if (!isDestroyed()) image.setImageBitmap(show); });
        });
    }

    /**
     * Same read as the home screen's photos: the body, not the status line first. Checking the status before the body
     * came back empty on some TVs, the decode then returned nothing, and the miss was thrown away.
     */
    private Bitmap download(String url) {
        if (url == null || url.isEmpty()) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            byte[] bytes;
            try (InputStream in = c.getInputStream()) {
                bytes = readBytes(in);
            }
            if (bytes.length == 0) {
                AppLog.w("search image: empty response");
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
            BitmapFactory.Options o = new BitmapFactory.Options();
            int sample = 1;
            while (bounds.outWidth / sample > 800 && sample < 16) sample *= 2;
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (b == null) AppLog.w("search image: undecodable " + bytes.length + " bytes");
            return b;
        } catch (Exception e) {
            AppLog.w("search image: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readBytes(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
