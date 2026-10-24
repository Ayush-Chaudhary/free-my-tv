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

/**
 * Search, as on Google TV's home screen: by voice (Google's own voice input; the remote's mic on the home screen comes
 * here) or by typing. Finds the TV's apps and TMDB's movies and shows, each marked with the app that has it; anything
 * else ("what's the weather") goes on to Google Assistant.
 */
public class SearchActivity extends Activity {
    /** Opens listening, as the remote's mic does. */
    static final String EXTRA_VOICE = "voice";
    private static final int VOICE = 1;
    private static final String KATNISS = "com.google.android.katniss";
    private static final long TYPING_PAUSE = 600;
    private static final int BG = 0xFF0B0B0D, FIELD = 0xFF1C1C1E, DIM = 0xFF98989D, INK = 0xFFF5F5F7, DARK = 0xFF1C1C1E;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };
    private float k;
    private EditText field;
    private TextView status, assist, appsTitle, titlesTitle;
    private LinearLayout appsRow, titlesRow;
    private List<String[]> apps = new ArrayList<>();
    private int generation;          // each search's answers are dropped once a newer search has started
    private boolean focusResults;    // after voice: the first result takes the focus when it comes
    private final Runnable typed = () -> run(field.getText().toString(), false);

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Ui.init(this);
        k = getResources().getDisplayMetrics().widthPixels / 1920f;
        setContentView(build());
        io.execute(() -> {
            List<String[]> list = HomeApps.launchable(this);
            handler.post(() -> apps = list);
        });
        if (getIntent().getBooleanExtra(EXTRA_VOICE, false)) listen();
        else field.requestFocus();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_VOICE, false)) listen();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            listen();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    // MARK: - Layout

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
        ImageView glass = symbol("search", DIM, 30);
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
                handler.postDelayed(typed, TYPING_PAUSE);
            }
        });
        field.setOnEditorActionListener((v, action, e) -> {
            handler.removeCallbacks(typed);
            run(field.getText().toString(), false);
            return false;
        });
        field.setOnFocusChangeListener((v, has) -> box.setBackground(round(has ? 0xFF2C2C2E : FIELD, px(42))));
        box.addView(field, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(84)));
        bar.addView(box, new LinearLayout.LayoutParams(0, px(84), 1));

        FrameLayout mic = new FrameLayout(this);
        mic.setFocusable(true);
        mic.setBackground(round(FIELD, px(42)));
        ImageView micIcon = symbol("mic", INK, 30);
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
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        scroll.addView(row);
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        at.leftMargin = -px(12);
        page.addView(scroll, at);
        return row;
    }

    private void show(boolean titles, boolean apps) {
        titlesTitle.setVisibility(titles ? View.VISIBLE : View.GONE);
        ((View) titlesRow.getParent()).setVisibility(titles ? View.VISIBLE : View.GONE);
        appsTitle.setVisibility(apps ? View.VISIBLE : View.GONE);
        ((View) appsRow.getParent()).setVisibility(apps ? View.VISIBLE : View.GONE);
    }

    // MARK: - Searching

    /** Google's voice input: what was said comes back to onActivityResult. */
    private void listen() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, Ui.tr("Search movies, shows and apps"));
        // Google's voice input (the Assistant's, on Google TV) without the "open with" chooser, when it's there.
        Intent google = new Intent(intent).setPackage(KATNISS);
        if (google.resolveActivity(getPackageManager()) != null) intent = google;
        try {
            startActivityForResult(intent, VOICE);
        } catch (ActivityNotFoundException e) {
            AppLog.w("no voice input on this TV: " + e.getMessage());
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
        handler.removeCallbacks(typed);     // setText's watcher: this search runs now, not after the pause
        run(text, true);
    }

    /** Apps right away, then TMDB's titles; "open Netflix" said or typed opens the app. */
    private void run(String raw, boolean spoken) {
        String query = raw.trim();
        int gen = ++generation;
        titlesRow.removeAllViews();
        appsRow.removeAllViews();
        if (query.isEmpty()) {
            status.setText("");
            assist.setVisibility(View.GONE);
            show(false, false);
            return;
        }
        assist.setVisibility(View.VISIBLE);
        String app = command(query);
        if (app != null) {
            for (String[] a : apps) {
                if (a[1].equalsIgnoreCase(app)) {
                    openApp(a[0], a[1], field);
                    return;
                }
            }
        }
        String q = query.toLowerCase(Locale.ROOT);
        for (String[] a : apps) {
            String name = a[1].toLowerCase(Locale.ROOT);
            if (name.contains(q) || (app != null && name.contains(app.toLowerCase(Locale.ROOT)))) appsRow.addView(appCard(a[0], a[1]));
        }
        show(false, appsRow.getChildCount() > 0);
        focusResults = spoken;
        if (spoken && appsRow.getChildCount() > 0) appsRow.getChildAt(0).requestFocus();
        status.setText(Ui.tr("Searching…"));
        String forTmdb = app != null ? app : query;
        io.execute(() -> {
            List<Recs.Pick> found;
            try {
                found = Recs.get(this).search(forTmdb);
            } catch (Exception e) {
                AppLog.w("search: " + e.getMessage());
                found = null;
            }
            List<Recs.Pick> result = found;
            handler.post(() -> {
                if (gen != generation) return;
                if (result == null) {
                    status.setText(Ui.tr("Couldn't search right now. Ask Google Assistant instead."));
                    return;
                }
                for (Recs.Pick p : result) titlesRow.addView(titleCard(p));
                show(!result.isEmpty(), appsRow.getChildCount() > 0);
                status.setText(result.isEmpty() && appsRow.getChildCount() == 0
                        ? Ui.tr("Nothing on your apps. Ask Google Assistant instead.") : "");
                if (focusResults) {
                    View first = titlesRow.getChildCount() > 0 ? titlesRow.getChildAt(0)
                            : appsRow.getChildCount() > 0 ? appsRow.getChildAt(0) : assist;
                    first.requestFocus();
                    focusResults = false;
                }
            });
        });
    }

    /** "open Netflix", "launch YouTube", "start Spotify": the app's name; anything else, null. */
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
            startActivity(new Intent("android.search.action.GLOBAL_SEARCH").putExtra("query", query)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        } catch (ActivityNotFoundException e) {
            status.setText(Ui.tr("Google Assistant isn't on this TV."));
        }
    }

    private void openApp(String pkg, String label, View from) {
        HomeApps.open(this, HomeApps.app(pkg, label), from, 0, 0, from.getWidth(), from.getHeight());
    }

    // MARK: - Cards

    private View appCard(String pkg, String label) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        FrameLayout frame = frame(px(256), px(144));
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(FIELD);
        frame.addView(image, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.addView(frame);
        TextView name = text(INK, 24, Ui.medium);
        name.setText(label);
        name.setPadding(0, px(10), 0, 0);
        card.addView(name);
        focusable(card, frame, px(256));
        card.setOnClickListener(v -> openApp(pkg, label, frame));
        io.execute(() -> {
            Bitmap b = banner(pkg);
            handler.post(() -> image.setImageBitmap(b));
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
        frame.addView(image, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
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
        card.setOnClickListener(v -> {
            if (p.pkg == null) {
                status.setText(Ui.trf("{title} isn't on your TV's apps.", "title", p.title));
                return;
            }
            HomeApps.open(this, HomeApps.app(p.pkg, Recs.serviceName(p), p.link), frame, 0, 0, frame.getWidth(), frame.getHeight());
        });
        String url = Recs.smallImage(p);
        Bitmap cached = images.get(url);
        if (cached != null) {
            image.setImageBitmap(cached);
        } else {
            io.execute(() -> {
                Bitmap b = download(url);
                if (b == null) return;
                images.put(url, b);
                handler.post(() -> image.setImageBitmap(b));
            });
        }
        return card;
    }

    /** A card's picture area: rounded, with a white ring when focused. */
    private FrameLayout frame(int w, int h) {
        FrameLayout frame = new FrameLayout(this);
        frame.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), px(14));
            }
        });
        frame.setClipToOutline(true);
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(px(14));
        ring.setStroke(px(4), INK);
        ring.setColor(0x00000000);
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
            if (has) v.getParent().requestChildFocus(v, v);
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

    private ImageView symbol(String name, int color, float size) {
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

    // MARK: - Pictures

    /** The app's TV banner, or its icon centered on the card color. */
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
            } else {
                d.setBounds(0, 0, w, h);
            }
            d.draw(c);
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap download(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            try (InputStream in = c.getInputStream()) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 1;
                return BitmapFactory.decodeStream(in, null, o);
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }
}
