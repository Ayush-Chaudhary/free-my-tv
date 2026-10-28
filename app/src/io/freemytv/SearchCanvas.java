package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Search drawn on one canvas: keyboard on the left, results on the right, both themes.
 * The activity owns the TMDB request and the apps list.
 */
final class SearchCanvas extends View {
    static final class Hit {
        String title = "", meta = "";
        String pkg, label;
        boolean app, store, show;
        Recs.Pick pick;
    }

    interface Host {
        void submit(String query, boolean voice);
        void open(Hit hit);
        void ask(String query);
        void listen();
        void cancelListen();
    }

    private static final int KEY = 0, CHIP = 1, RESULT = 2, ACTION = 3;
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz1234567890";
    private static final int[][] PALETTE = {
            {0xFF1B3A5F, 0xFF4A7AB5},
            {0xFFC45C26, 0xFF2C3E6B},
            {0xFF6B1D5C, 0xFFC43B8C},
            {0xFF3A4450, 0xFF8A939E},
            {0xFF0E7490, 0xFF1AA6B0},
            {0xFF8E1010, 0xFFC81E1E}
    };

    private final Host host;
    private final List<Key> keys = new ArrayList<>();
    private final List<String> chips = new ArrayList<>();
    private final List<String> posters = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private final RectF tmp = new RectF();
    private final Spring spring = new Spring(0.22f, 0.82f, 1);
    private Paint ink, dim, keyText, title, meta, plate, fill, artText, fieldPaint, imageFill;
    private final Recs.Listener imagesReady = new Recs.Listener() {
        @Override public void onRecs() { if (!demo) postInvalidateOnAnimation(); }
    };
    private Shader bg;
    private float k = 1;
    private int w, h;
    private int zone = KEY, index;
    private String query = "";
    private String status = "";
    private String reply = "";
    private String demoShot = "";
    private boolean listening, night = true, demo;
    private long last;

    SearchCanvas(Context context, Host host) {
        super(context);
        this.host = host;
        setFocusable(true);
        imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    }

    void setNight(boolean night) {
        if (this.night == night && bg != null) return;
        this.night = night;
        bg = null;
        invalidate();
    }

    void setQuery(String q) {
        query = q == null ? "" : q;
        invalidate();
    }

    void setListening(boolean on, String partial) {
        listening = on;
        if (on && partial != null && !partial.isEmpty()) query = partial;
        status = on ? Ui.tr("Listening…") : "";
        invalidate();
    }

    void setIdle(List<String> recent, List<String> popular) {
        chips.clear();
        posters.clear();
        hits.clear();
        reply = "";
        status = "";
        listening = false;
        if (recent != null) for (String s : recent) if (s != null && !s.isEmpty()) chips.add(s);
        if (popular != null) for (String s : popular) if (s != null && !s.isEmpty() && posters.size() < 4) posters.add(s);
        zone = KEY;
        invalidate();
    }

    void show(List<Hit> found, String status, String reply) {
        hits.clear();
        chips.clear();
        posters.clear();
        listening = false;
        if (found != null) hits.addAll(found);
        this.status = status == null ? "" : status;
        this.reply = reply == null ? "" : reply;
        zone = hits.isEmpty() && query.isEmpty() ? KEY : hits.isEmpty() ? ACTION : RESULT;
        index = 0;
        spring.snap(1);
        invalidate();
    }

    /** Sample frames. No network. */
    void demo(String shot, boolean dark) {
        demo = true;
        night = dark;
        bg = null;
        demoShot = shot == null ? "idle" : shot;
        AppLog.w("demo search " + demoShot);
        if (demoShot.equals("listening")) {
            query = "funny shows about a small";
            setListening(true, query);
            applyDemoFocus();
            return;
        }
        if (demoShot.equals("empty")) {
            query = "qwzxv";
            show(new ArrayList<Hit>(), Ui.trf("No matches for “{query}”", "query", query), "");
            applyDemoFocus();
            return;
        }
        if (demoShot.equals("idle")) {
            query = "";
            List<String> recent = new ArrayList<>();
            recent.add("Hollow Pines");
            recent.add("Northbound");
            recent.add("Tubeview");
            List<String> popular = new ArrayList<>();
            popular.add("The Lantern Coast");
            popular.add("Velvet Static");
            popular.add("Silver Ridge");
            popular.add("Kite Season");
            setIdle(recent, popular);
            applyDemoFocus();
            return;
        }
        query = demoShot.equals("reply") ? "is tidewater nights a movie or a show" : "tide";
        List<Hit> list = new ArrayList<>();
        list.add(titleHit("Tidewater Nights", "Show · Limited series · 6 episodes · Streamly", true));
        list.add(titleHit("The Lantern Coast", "Show · Streamly", true));
        list.add(titleHit("Kite Season", "Movie · Orchard", false));
        Hit open = new Hit();
        open.title = "Tubeview";
        open.meta = Ui.tr("Open");
        open.app = true;
        open.label = "Tubeview";
        list.add(open);
        Hit store = new Hit();
        store.title = "Tidepool";
        store.meta = Ui.tr("Get from Play Store");
        store.store = true;
        store.label = "Tidepool";
        list.add(store);
        String line = demoShot.equals("reply")
                ? Ui.trf("It's a show: {title}.", "title", "Tidewater Nights") : "";
        String note = line.isEmpty() ? "" : Ui.tr("Also said aloud · the title is below");
        show(list, note, line);
        applyDemoFocus();
    }

    private static Hit titleHit(String title, String meta, boolean show) {
        Hit h = new Hit();
        h.title = title;
        h.meta = meta;
        h.show = show;
        return h;
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Recs.get(getContext()).addListener(imagesReady);
    }

    @Override protected void onDetachedFromWindow() {
        Recs.get(getContext()).removeListener(imagesReady);
        super.onDetachedFromWindow();
    }

    @Override protected void onSizeChanged(int width, int height, int oldw, int oldh) {
        w = width;
        h = height;
        k = w / 1920f;
        boolean dark = night;
        ink = Ui.text(Ui.semibold, Tokens.TYPE_BODY * k, Tokens.ink(dark));
        dim = Ui.text(Ui.medium, Tokens.TYPE_CAPTION * k, Tokens.inkSecondary(dark));
        keyText = Ui.text(Ui.medium, 32 * k, Tokens.ink(dark));
        keyText.setTextAlign(Paint.Align.CENTER);
        title = Ui.text(Ui.semibold, Tokens.TYPE_ROWTITLE * k, Tokens.ink(dark));
        meta = Ui.text(Ui.medium, Tokens.TYPE_CAPTION * k, Tokens.inkSecondary(dark));
        fieldPaint = Ui.text(Ui.medium, 40 * k, Tokens.ink(dark));
        artText = Ui.text(Ui.semibold, 26 * k, 0xFFFFFFFF);
        artText.setTextAlign(Paint.Align.LEFT);
        plate = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        layoutKeys();
        bg = null;
        applyDemoFocus();
    }

    private void applyDemoFocus() {
        if (!demo || keys.isEmpty() || demoShot.isEmpty()) return;
        if (demoShot.equals("idle")) { zone = KEY; index = LETTERS.indexOf('t'); }
        else if (demoShot.equals("empty")) { zone = ACTION; index = 0; }
        else if (demoShot.equals("listening")) { zone = KEY; index = keys.size(); }
        else { zone = RESULT; index = 0; }
    }

    private void layoutKeys() {
        keys.clear();
        float kw = Tokens.LAYOUT_SEARCHKEYW * k, kh = Tokens.LAYOUT_SEARCHKEYH * k;
        float sx = Tokens.LAYOUT_SEARCHSTRIDEX * k, sy = Tokens.LAYOUT_SEARCHSTRIDEY * k;
        float left = 80 * k, top = 220 * k;
        for (int i = 0; i < LETTERS.length(); i++) {
            int col = i % 6, row = i / 6;
            keys.add(new Key(String.valueOf(LETTERS.charAt(i)), Key.CHAR,
                    left + col * sx, top + row * sy, kw, kh));
        }
        float y = top + 6 * sy;
        keys.add(new Key("space", Key.SPACE, left, y, 312 * k, kh));
        keys.add(new Key("delete", Key.DELETE, left + 324 * k, y, 204 * k, kh));
        keys.add(new Key("clear", Key.CLEAR, left + 540 * k, y, 96 * k, kh));
    }

    @Override protected void onDraw(Canvas c) {
        if (w == 0 || ink == null) return;
        long now = System.nanoTime();
        float dt = last == 0 ? 1 / 60f : Math.min(0.05f, (now - last) / 1e9f);
        last = now;
        if (spring.step(dt) || listening) postInvalidateOnAnimation();
        if (bg == null) bg = new LinearGradient(0, 0, 0, h, Tokens.bgTop(night), Tokens.bgBottom(night), Shader.TileMode.CLAMP);
        fill.setShader(bg);
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
        ink.setColor(Tokens.ink(night));
        dim.setColor(Tokens.inkSecondary(night));
        keyText.setColor(Tokens.ink(night));
        title.setColor(Tokens.ink(night));
        meta.setColor(Tokens.inkSecondary(night));
        fieldPaint.setColor(Tokens.ink(night));
        drawField(c);
        boolean dimKb = listening || !hits.isEmpty() || !reply.isEmpty();
        if (dimKb) c.saveLayerAlpha(0, 0, w, h, 82);
        for (int i = 0; i < keys.size(); i++) drawKey(c, keys.get(i), !dimKb && zone == KEY && i == index);
        if (dimKb) c.restore();
        drawMic(c, listening || (zone == KEY && index == keys.size()));
        if (listening) {
            drawOrb(c, now);
            return;
        }
        if (query.isEmpty()) drawIdle(c);
        else drawResults(c);
    }

    private void drawField(Canvas c) {
        float top = 72 * k;
        tmp.set(80 * k, top, 80 * k + 1636 * k, top + 100 * k);
        plate.setStyle(Paint.Style.FILL);
        plate.setColor(Tokens.glassTop(night));
        c.drawRoundRect(tmp, 50 * k, 50 * k, plate);
        if (listening) {
            plate.setStyle(Paint.Style.STROKE);
            plate.setStrokeWidth(4 * k);
            plate.setColor(0xFFFFFFFF);
            c.drawRoundRect(tmp, 50 * k, 50 * k, plate);
            plate.setStyle(Paint.Style.FILL);
        }
        Symbols.draw(getContext(), c, "search", tmp.left + 54 * k, tmp.centerY(), 36 * k, Tokens.inkSecondary(night));
        String shown = query.isEmpty() ? Ui.tr("Search movies, shows and apps") : query;
        Paint p = query.isEmpty() ? dim : fieldPaint;
        if (query.isEmpty()) p.setColor(Tokens.inkTertiary(night));
        float tx = tmp.left + 96 * k;
        c.drawText(TextUtils.ellipsize(shown, new android.text.TextPaint(p), tmp.width() - 140 * k, TextUtils.TruncateAt.END).toString(),
                tx, tmp.centerY() + Ui.halfCap(p), p);
        if (!query.isEmpty()) {
            float cx = tx + p.measureText(query.length() > 42 ? shown.substring(0, Math.min(shown.length(), 42)) : shown);
            plate.setColor(Tokens.ink(night));
            c.drawRoundRect(cx + 6 * k, tmp.centerY() - 23 * k, cx + 9 * k, tmp.centerY() + 23 * k, 2 * k, 2 * k, plate);
        }
    }

    private void drawMic(Canvas c, boolean on) {
        float cx = 1790 * k, cy = 122 * k, r = 50 * k;
        plate.setStyle(Paint.Style.FILL);
        plate.setColor(on ? 0xFFFFFFFF : Tokens.glassTop(night));
        c.drawCircle(cx, cy, r, plate);
        Symbols.draw(getContext(), c, "mic", cx, cy, 40 * k, on ? Tokens.INK_LIGHT : Tokens.ink(night));
    }

    private void drawOrb(Canvas c, long now) {
        float cx = 1320 * k, cy = 520 * k;
        float pulse = 1 + 0.03f * (float) Math.sin(now / 2.5e8);
        int[] rings = {300, 230, 166};
        for (int i = 0; i < rings.length; i++) {
            float d = rings[i] * k * (i == 2 ? 1 : pulse);
            if (i == 2) plate.setColor(0xFFFFFFFF);
            else plate.setColor(night ? (i == 0 ? 0x14FFFFFF : 0x29FFFFFF) : (i == 0 ? 0x0A1D1D1F : 0x121D1D1F));
            c.drawCircle(cx, cy, d / 2f, plate);
        }
        Symbols.draw(getContext(), c, "mic", cx, cy, 64 * k, Tokens.INK_LIGHT);
        title.setTextAlign(Paint.Align.CENTER);
        title.setTextSize(44 * k);
        title.setColor(Tokens.ink(night));
        c.drawText(Ui.tr("Listening…"), cx, 744 * k, title);
        title.setTextSize(Tokens.TYPE_ROWTITLE * k);
        title.setTextAlign(Paint.Align.LEFT);
        dim.setTextAlign(Paint.Align.CENTER);
        c.drawText(Ui.tr("Speak now · Back to cancel"), cx, 800 * k, dim);
        dim.setTextAlign(Paint.Align.LEFT);
    }

    private void drawIdle(Canvas c) {
        float x0 = 800 * k;
        dim.setColor(Tokens.inkSecondary(night));
        c.drawText(Ui.tr("Recent"), x0, 248 * k, dim);
        float x = x0;
        for (int i = 0; i < chips.size(); i++) {
            boolean on = zone == CHIP && i == index;
            float icon = 28 * k;
            float tw = dim.measureText(chips.get(i));
            float wChip = tw + icon + 36 * k + 48 * k;
            tmp.set(x, 278 * k, x + wChip, 278 * k + 76 * k);
            plate.setColor(on ? 0xFFFFFFFF : Tokens.glassTop(night));
            c.drawRoundRect(tmp, 38 * k, 38 * k, plate);
            int color = on ? Tokens.INK_LIGHT : Tokens.inkSecondary(night);
            Symbols.draw(getContext(), c, "sync", tmp.left + 28 * k + icon / 2, tmp.centerY(), icon, color);
            Paint p = on ? ink : dim;
            int was = p.getColor();
            p.setColor(on ? Tokens.INK_LIGHT : Tokens.ink(night));
            c.drawText(chips.get(i), tmp.left + 28 * k + icon + 12 * k, tmp.centerY() + Ui.halfCap(p), p);
            p.setColor(was);
            x = tmp.right + 16 * k;
        }
        if (!posters.isEmpty()) {
            c.drawText(Ui.tr("Popular on your apps"), x0, 448 * k, dim);
            for (int i = 0; i < posters.size(); i++) {
                float px = x0 + i * 262 * k;
                tmp.set(px, 470 * k, px + 230 * k, 470 * k + 345 * k);
                poster(c, tmp, posters.get(i), false, i);
            }
        }
        float hy = 900 * k;
        Symbols.draw(getContext(), c, "mic", x0 + 15 * k, hy - 8 * k, 30 * k, Tokens.inkSecondary(night));
        c.drawText(Ui.tr("Or hold the remote's mic: “funny shows for tonight”"), x0 + 44 * k, hy, dim);
    }

    private void drawResults(Canvas c) {
        float x0 = 800 * k;
        if (!reply.isEmpty()) {
            tmp.set(x0, 200 * k, x0 + 1040 * k, 200 * k + 140 * k);
            plate.setColor(Tokens.glassTop(night));
            c.drawRoundRect(tmp, Tokens.RADIUS_PANEL * k, Tokens.RADIUS_PANEL * k, plate);
            Symbols.draw(getContext(), c, "speaker", tmp.left + 58 * k, tmp.top + 52 * k, 44 * k, Tokens.ink(night));
            title.setTextSize(36 * k);
            title.setColor(Tokens.ink(night));
            c.drawText(TextUtils.ellipsize(reply, new android.text.TextPaint(title), tmp.width() - 150 * k, TextUtils.TruncateAt.END).toString(),
                    tmp.left + 108 * k, tmp.top + 58 * k, title);
            title.setTextSize(Tokens.TYPE_ROWTITLE * k);
            String sub = status.isEmpty() ? Ui.tr("Also said aloud · the title is below") : status;
            c.drawText(sub, tmp.left + 108 * k, tmp.top + 104 * k, dim);
        }
        List<Hit> titles = new ArrayList<>();
        List<Hit> apps = new ArrayList<>();
        for (Hit h : hits) if (h.app || h.store) apps.add(h); else titles.add(h);
        float rowTop = reply.isEmpty() ? 212 * k : 366 * k;
        int focusTitle = zone == RESULT && index < titles.size() ? index : -1;
        if (!titles.isEmpty()) {
            c.drawText(Ui.tr("Movies & Shows"), x0, rowTop + 28 * k, title);
            float cardTop = rowTop + 48 * k;
            float x = x0;
            float nameX = x0, nameY = cardTop;
            String name = "", cap = "";
            for (int i = 0; i < titles.size(); i++) {
                boolean focused = i == focusTitle;
                float cw = (focused ? Tokens.CARD_WIDEW : Tokens.CARD_NARROWW) * k;
                float ch = Tokens.CARD_ROWH * k;
                if (x > w - 40 * k) break;
                tmp.set(x, cardTop, Math.min(w, x + cw), cardTop + ch);
                drawHitArt(c, tmp, titles.get(i), focused, i);
                if (focused) {
                    plate.setStyle(Paint.Style.STROKE);
                    plate.setStrokeWidth(Tokens.FOCUS_RING * k);
                    plate.setColor(0xFFFFFFFF);
                    c.drawRoundRect(tmp, Tokens.RADIUS_CARD * k, Tokens.RADIUS_CARD * k, plate);
                    plate.setStrokeWidth(Tokens.LAYOUT_FOCUSEDGE * k);
                    plate.setColor(Tokens.focusRingedge(night));
                    c.drawRoundRect(tmp, Tokens.RADIUS_CARD * k, Tokens.RADIUS_CARD * k, plate);
                    plate.setStyle(Paint.Style.FILL);
                    name = titles.get(i).title;
                    cap = titles.get(i).meta == null ? "" : titles.get(i).meta;
                    nameX = x;
                    nameY = cardTop + ch + 44 * k;
                } else if (tmp.bottom + 36 * k < h) {
                    meta.setColor(Tokens.inkSecondary(night));
                    c.drawText(TextUtils.ellipsize(titles.get(i).meta == null ? "" : titles.get(i).meta,
                            new android.text.TextPaint(meta), cw, TextUtils.TruncateAt.END).toString(), x, cardTop + ch + 32 * k, meta);
                }
                x += cw + Tokens.SPACE_GAP_CARD * k;
            }
            if (!name.isEmpty()) {
                title.setColor(Tokens.ink(night));
                c.drawText(name, nameX, nameY, title);
                meta.setColor(Tokens.inkSecondary(night));
                c.drawText(cap, nameX, nameY + 36 * k, meta);
            }
            rowTop = cardTop + Tokens.CARD_ROWH * k + 120 * k;
        }
        if (!apps.isEmpty() && reply.isEmpty() && rowTop + 160 * k < h - 24 * k) {
            c.drawText(Ui.tr("Apps"), x0, rowTop + 8 * k, title);
            float ax = x0;
            for (int i = 0; i < apps.size(); i++) {
                int at = titles.size() + i;
                boolean focused = zone == RESULT && index == at;
                drawApp(c, apps.get(i), ax, rowTop + 28 * k, focused);
                ax += 250 * k;
            }
        }
        if (titles.isEmpty() && apps.isEmpty() && !status.isEmpty()) drawEmpty(c);
    }

    private void drawEmpty(Canvas c) {
        float x0 = 800 * k;
        Symbols.draw(getContext(), c, "search", x0 + 48 * k, 300 * k, 96 * k, Tokens.inkTertiary(night));
        title.setTextSize(48 * k);
        title.setColor(Tokens.ink(night));
        String no = Ui.trf("No matches for “{query}”", "query", query);
        String headline = status.isEmpty() ? no : status;
        c.drawText(TextUtils.ellipsize(headline, new android.text.TextPaint(title), 1000 * k, TextUtils.TruncateAt.END).toString(),
                x0, 430 * k, title);
        title.setTextSize(Tokens.TYPE_ROWTITLE * k);
        if (headline.equals(no)) c.drawText(Ui.tr("Check the spelling, or try a title, an actor or an app."), x0, 490 * k, dim);
        drawAction(c, Ui.tr("Ask Google Assistant"), x0, 540 * k, zone == ACTION && index == 0);
        drawAction(c, Ui.tr("Clear search"), x0 + 480 * k, 540 * k, zone == ACTION && index == 1);
        dim.setColor(Tokens.inkSecondary(night));
        c.drawText(Ui.tr("Assistant can answer things like “what's on tonight” or “turn up the volume”."), x0, 680 * k, dim);
    }

    /**
     * Title art the same way the home rows draw it: the backdrop, or the poster when the wide photo is missing.
     * The card already has a size here, so the rounded shader is applied to that rect (an outline clipped before
     * layout would draw nothing).
     */
    private void drawHitArt(Canvas c, RectF r, Hit hit, boolean wide, int tint) {
        Recs.Pick p = demo || hit == null ? null : hit.pick;
        if (p != null) {
            Recs recs = Recs.get(getContext());
            Backdrop b = recs.backdrop(p);
            Recs.Poster poster = recs.poster(p);
            Bitmap image = null;
            BitmapShader shader = null;
            if (wide && b != null && b.wide != null && b.wideShader != null) {
                image = b.wide;
                shader = b.wideShader;
            } else if (poster != null && poster.bitmap != null && poster.shader != null) {
                image = poster.bitmap;
                shader = poster.shader;
            } else if (b != null && b.wide != null && b.wideShader != null) {
                image = b.wide;
                shader = b.wideShader;
            }
            if (image != null && shader != null) {
                cover(c, image, shader, r, Tokens.RADIUS_CARD * k);
                return;
            }
        }
        poster(c, r, hit == null ? "" : hit.title, wide, tint);
    }

    /** The image cropped to the card, corners rounded by the shader. The rect is already laid out. */
    private void cover(Canvas c, Bitmap image, BitmapShader shader, RectF cell, float radius) {
        float iw = image.getWidth(), ih = image.getHeight();
        if (iw < 1 || ih < 1 || cell.width() < 1 || cell.height() < 1) return;
        float sw = Math.min(iw, cell.width() / cell.height() * ih), sl = (iw - sw) / 2;
        float sx = cell.width() / sw, sy = cell.height() / ih;
        c.save();
        c.translate(cell.left, cell.top);
        c.scale(sx, sy);
        c.translate(-sl, 0);
        imageFill.setShader(shader);
        c.drawRoundRect(sl, 0, sl + sw, ih, radius / sx, radius / sy, imageFill);
        imageFill.setShader(null);
        c.restore();
    }

    private void poster(Canvas c, RectF r, String name, boolean wide, int tint) {
        int[] pal = PALETTE[Math.floorMod(tint, PALETTE.length)];
        fill.setShader(new LinearGradient(r.left, r.top, r.right, r.bottom, pal[0], pal[1], Shader.TileMode.CLAMP));
        c.drawRoundRect(r, Tokens.RADIUS_CARD * k, Tokens.RADIUS_CARD * k, fill);
        fill.setShader(null);
        fill.setColor(0x66FFFFFF);
        c.drawCircle(r.centerX(), r.top + r.height() * 0.36f, Math.min(r.width(), r.height()) * 0.16f, fill);
        if (!wide && name != null) {
            artText.setTextSize(Math.max(22 * k, 20));
            String line = TextUtils.ellipsize(name, new android.text.TextPaint(artText), r.width() - 24 * k, TextUtils.TruncateAt.END).toString();
            c.drawText(line, r.left + 16 * k, r.bottom - 22 * k, artText);
        }
    }

    private void drawApp(Canvas c, Hit hit, float x, float y, boolean focused) {
        int[] pal = PALETTE[Math.floorMod(hit.title.hashCode(), PALETTE.length)];
        tmp.set(x, y, x + 210 * k, y + 118 * k);
        if (focused) {
            float s = 1.06f;
            float cx = tmp.centerX(), cy = tmp.centerY();
            tmp.set(cx - tmp.width() * s / 2, cy - tmp.height() * s / 2, cx + tmp.width() * s / 2, cy + tmp.height() * s / 2);
        }
        fill.setShader(new LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom, pal[1], pal[0], Shader.TileMode.CLAMP));
        c.drawRoundRect(tmp, Tokens.RADIUS_TILE * k, Tokens.RADIUS_TILE * k, fill);
        fill.setShader(null);
        if (focused) {
            plate.setStyle(Paint.Style.STROKE);
            plate.setStrokeWidth(Tokens.LAYOUT_TILERING * k);
            plate.setColor(0xFFFFFFFF);
            c.drawRoundRect(tmp, Tokens.RADIUS_TILE * k, Tokens.RADIUS_TILE * k, plate);
            plate.setStyle(Paint.Style.FILL);
        }
        artText.setTextAlign(Paint.Align.CENTER);
        artText.setTextSize(28 * k);
        String word = hit.title == null ? "" : hit.title;
        c.drawText(TextUtils.ellipsize(word, new android.text.TextPaint(artText), tmp.width() - 16 * k, TextUtils.TruncateAt.END).toString(),
                tmp.centerX(), tmp.centerY() + Ui.halfCap(artText), artText);
        artText.setTextAlign(Paint.Align.LEFT);
        meta.setColor(Tokens.inkSecondary(night));
        meta.setTextAlign(Paint.Align.CENTER);
        c.drawText(hit.meta == null ? "" : hit.meta, tmp.centerX(), tmp.bottom + 36 * k, meta);
        meta.setTextAlign(Paint.Align.LEFT);
    }

    private void drawKey(Canvas c, Key key, boolean focused) {
        float s = focused ? 1 + (Tokens.FOCUS_KEY - 1) * Ui.clamp(spring.value) + 0.02f : 1;
        float cx = key.box.centerX(), cy = key.box.centerY();
        tmp.set(cx - key.box.width() * s / 2, cy - key.box.height() * s / 2,
                cx + key.box.width() * s / 2, cy + key.box.height() * s / 2);
        plate.setStyle(Paint.Style.FILL);
        plate.setColor(focused ? 0xFFFFFFFF : (night ? 0x12FFFFFF : Tokens.glassTop(false)));
        c.drawRoundRect(tmp, 18 * k, 18 * k, plate);
        int color = focused ? Tokens.INK_LIGHT : Tokens.ink(night);
        if (key.action == Key.DELETE) {
            String label = Ui.tr("delete");
            keyText.setTextSize(28 * k);
            keyText.setColor(color);
            float tw = keyText.measureText(label);
            float icon = 28 * k;
            float start = cx - (icon + 8 * k + tw) / 2;
            c.save();
            c.scale(-1, 1, start + icon / 2, cy);
            Symbols.draw(getContext(), c, "chevron_right", start + icon / 2, cy, icon, color);
            c.restore();
            c.drawText(label, start + icon + 8 * k + tw / 2, cy + Ui.halfCap(keyText), keyText);
            return;
        }
        String label = key.action == Key.SPACE ? Ui.tr("space") : key.action == Key.CLEAR ? Ui.tr("clear") : key.label;
        keyText.setColor(color);
        keyText.setTextSize((key.action == Key.CHAR ? 32 : 28) * k);
        c.drawText(label, cx, cy + Ui.halfCap(keyText), keyText);
    }

    private void drawAction(Canvas c, String label, float x, float y, boolean focused) {
        dim.setTextSize(30 * k);
        float tw = dim.measureText(label) + 72 * k;
        tmp.set(x, y, x + tw, y + 76 * k);
        plate.setColor(focused ? 0xFFFFFFFF : Tokens.glassTop(night));
        c.drawRoundRect(tmp, 38 * k, 38 * k, plate);
        dim.setTextSize(30 * k);
        dim.setColor(focused ? Tokens.INK_LIGHT : Tokens.ink(night));
        c.drawText(label, tmp.left + 36 * k, tmp.centerY() + Ui.halfCap(dim), dim);
        dim.setColor(Tokens.inkSecondary(night));
        dim.setTextSize(Tokens.TYPE_CAPTION * k);
    }

    boolean onKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return e.getKeyCode() == KeyEvent.KEYCODE_BACK && listening;
        if (listening) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                host.cancelListen();
                return true;
            }
            return true;
        }
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            activate();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL) {
            backspace();
            return true;
        }
        if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z && zone == KEY) {
            type(String.valueOf((char) ('a' + code - KeyEvent.KEYCODE_A)));
            return true;
        }
        if (code == KeyEvent.KEYCODE_SPACE) {
            type(" ");
            return true;
        }
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (dx == 0 && dy == 0) return false;
        move(dx, dy);
        return true;
    }

    private void move(int dx, int dy) {
        spring.snap(0);
        spring.target = 1;
        int mic = keys.size();
        if (zone == KEY) {
            if (index == mic) {
                if (dy > 0) index = 0;
            } else if (index < LETTERS.length()) {
                int col = index % 6, row = index / 6;
                if (dx != 0) {
                    int nc = col + dx;
                    if (nc >= 0 && nc < 6) index = row * 6 + nc;
                }
                if (dy < 0) index = row == 0 ? mic : (row - 1) * 6 + col;
                if (dy > 0) {
                    if (row < 5) index = (row + 1) * 6 + col;
                    else index = LETTERS.length() + (col < 2 ? 0 : col < 5 ? 1 : 2);
                }
            } else {
                int b = index - LETTERS.length();
                if (dx < 0) index = LETTERS.length() + Math.max(0, b - 1);
                if (dx > 0) index = LETTERS.length() + Math.min(2, b + 1);
                if (dy < 0) index = 30 + (b == 0 ? 1 : b == 1 ? 3 : 5);
                if (dy > 0) goDown();
            }
        } else if (zone == CHIP) {
            if (dy < 0) { zone = KEY; index = LETTERS.length(); }
            else if (dx != 0) index = Math.max(0, Math.min(chips.size() - 1, index + dx));
        } else if (zone == RESULT) {
            if (dy < 0) { zone = KEY; index = 0; }
            else if (dx != 0) index = Math.max(0, Math.min(hits.size() - 1, index + dx));
        } else {
            if (dy < 0) { zone = KEY; index = LETTERS.length() + 1; }
            else if (dx > 0) index = 1;
            else if (dx < 0) index = 0;
        }
        invalidate();
    }

    private void goDown() {
        if (query.isEmpty() && !chips.isEmpty()) { zone = CHIP; index = 0; }
        else if (!hits.isEmpty()) { zone = RESULT; index = 0; }
        else if (!query.isEmpty()) { zone = ACTION; index = 0; }
    }

    private void activate() {
        if (zone == KEY && index == keys.size()) host.listen();
        else if (zone == KEY && index < keys.size()) {
            Key key = keys.get(index);
            if (key.action == Key.CHAR) type(key.label);
            else if (key.action == Key.SPACE) type(" ");
            else if (key.action == Key.DELETE) backspace();
            else if (key.action == Key.CLEAR) { query = ""; host.submit("", false); }
        } else if (zone == CHIP && index < chips.size()) {
            query = chips.get(index);
            host.submit(query, false);
        } else if (zone == RESULT && index < hits.size()) {
            int t = 0;
            for (Hit h : hits) if (!h.app && !h.store) { if (t++ == index) { host.open(h); return; } }
            for (Hit h : hits) if (h.app || h.store) { if (t++ == index) { host.open(h); return; } }
        } else if (zone == ACTION) {
            if (index == 0) host.ask(query);
            else { query = ""; host.submit("", false); }
        }
        invalidate();
    }

    private void type(String s) {
        if (query.length() >= 40) return;
        query += s;
        host.submit(query, false);
        invalidate();
    }

    private void backspace() {
        if (query.isEmpty()) return;
        query = query.substring(0, query.length() - 1);
        host.submit(query, false);
        invalidate();
    }

    private static final class Key {
        static final int CHAR = 0, DELETE = 1, SPACE = 2, CLEAR = 5;
        final String label;
        final int action;
        final RectF box = new RectF();

        Key(String label, int action, float x, float y, float w, float h) {
            this.label = label;
            this.action = action;
            box.set(x, y, x + w, y + h);
        }
    }
}
