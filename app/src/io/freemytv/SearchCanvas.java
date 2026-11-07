package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Search on one canvas, in the Cinema layout: a glass pill, the focused title's backdrop, and a poster row.
 * The keyboard is hidden until OK on the pill, so results use the full width. Both themes.
 * The activity owns the TMDB request. Nothing here calls the assistant Worker.
 */
final class SearchCanvas extends View {
    static final class Hit {
        String title = "", meta = "";
        String pkg, label;
        boolean app, store, show;
        Recs.Pick pick;
        int year;
        /** Null when a title is on none of the TV's apps. */
        String service;
        String why = "", length = "";
    }

    interface Host {
        void submit(String query, boolean voice);
        /** Run the query now. Enter, the keypad enter and the search key. Not a voice turn. */
        void commit(String query);
        void open(Hit hit);
        void ask(String query);
        void listen();
        void cancelListen();
    }

    private static final int BAR = 0, KEY = 1, CHIP = 2, BUTTON = 3, POSTER = 4;
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyz1234567890";
    private static final int[] PALETTE = {
            0xFF1B3A5F, 0xFFC45C26, 0xFF6B1D5C, 0xFF3A4450, 0xFF0E7490, 0xFF8E1010
    };
    /**
     * Cinema listening bars (D1): short grey marks, a few taller clusters, dots on the centre line.
     * About 280 px wide on a 1024-wide frame; the stage scales that up.
     */
    private static final float[] BARS = {
            0.05f, 0.08f, 0.14f, 0.06f, 0.22f, 0.48f, 0.28f, 0.08f, 0.05f,
            0.12f, 0.62f, 0.9f, 0.4f, 0.1f, 0.05f, 0.07f, 0.18f, 0.36f, 0.12f, 0.05f,
            0.08f, 0.7f, 1f, 0.55f, 0.16f, 0.06f, 0.05f, 0.2f, 0.44f, 0.18f, 0.06f,
            0.05f, 0.1f, 0.3f, 0.14f, 0.05f
    };
    private static final float WAVE_W = 280f * (1920f / 1024f);

    private final Host host;
    private final List<Key> keys = new ArrayList<>();
    private final List<String> chips = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private final RectF tmp = new RectF();
    private final RectF screen = new RectF();
    private final TextPaint replyTp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private Paint ink, dim, keyText, hero, meta, plate, fill, imageFill, labelPaint;
    private final Recs.Listener imagesReady = new Recs.Listener() {
        @Override public void onRecs() { if (!demo) postInvalidateOnAnimation(); }
    };
    private Shader bg, scrimX, scrimY;
    private float k = 1;
    private int w, h;
    private int zone = BAR, index, titleIndex;
    private String query = "";
    private String status = "";
    private String reply = "";
    private String listenCaption = "", thinkingLabel = "";
    private int labelGen = -1;
    private String lineKey = "\u0000", line1 = "", lineHead = "", lineTail = "";
    private String openLabel = "", emptyLine = "";
    private int openFor = -2;
    private String yearLabel = "";
    private int yearFor = Integer.MIN_VALUE;
    private String demoShot = "";
    private boolean listening, thinking, night = true, demo, keysOn;
    private float mic;
    private int slideDir = 1;
    private long listenAt, thinkAt, revealAt, focusAt, keysAt, plateAt;
    private boolean plateFading;
    private Bitmap plateNow, plateNext;
    private StaticLayout replyLayout;
    private int replyBuilt = -1;
    private boolean replyNight;
    private float replyWidth;

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
        scrimX = null;
        replyBuilt = -1;
        invalidate();
    }

    void setQuery(String q) {
        query = q == null ? "" : q;
        invalidate();
    }

    /** SpeechRecognizer RMS, about -2 to 10 dB. Smoothed with an exponential average (alpha 0.25). */
    void setLevel(float rmsdB) {
        float n = (rmsdB + 2f) / 12f;
        if (n < 0f) n = 0f;
        else if (n > 1f) n = 1f;
        mic += 0.25f * (n - mic);
    }

    void setListening(boolean on, String partial) {
        if (on && !listening) listenAt = SystemClock.uptimeMillis();
        listening = on;
        if (on) thinking = false;
        if (on && partial != null && !partial.isEmpty()) query = partial;
        if (!on) mic = 0;
        invalidate();
    }

    /** Skeleton and the thin progress line, for the wait before a reply (at most about 5.5 s). */
    void beginThinking() {
        thinking = true;
        listening = false;
        thinkAt = SystemClock.uptimeMillis();
        hits.clear();
        reply = "";
        status = "";
        replyBuilt = -1;
        if (!keysOn) {
            zone = BAR;
            index = 0;
        }
        invalidate();
    }

    void setIdle(List<String> recent, List<String> popular) {
        chips.clear();
        hits.clear();
        reply = "";
        status = "";
        listening = false;
        thinking = false;
        if (recent != null) for (String s : recent) if (s != null && !s.isEmpty() && chips.size() < 4) chips.add(s);
        zone = BAR;
        index = 0;
        revealAt = 0;
        invalidate();
    }

    void show(List<Hit> found, String status, String reply) {
        hits.clear();
        chips.clear();
        listening = false;
        thinking = false;
        if (found != null) hits.addAll(found);
        this.status = status == null ? "" : status;
        this.reply = reply == null ? "" : AssistantTurn.clip(reply);
        replyBuilt = -1;
        emptyLine = "";
        openFor = -2;
        yearFor = Integer.MIN_VALUE;
        lineKey = "\u0000";
        titleIndex = 0;
        if (!keysOn) {
            if (!hits.isEmpty()) {
                zone = POSTER;
                index = 0;
            } else if (!query.isEmpty()) {
                zone = BUTTON;
                index = 0;
            } else zone = BAR;
        }
        revealAt = SystemClock.uptimeMillis();
        focusAt = revealAt;
        prefetchAround(0);
        invalidate();
    }

    /** Sample frames. No network. */
    void demo(String shot, boolean dark) {
        demo = true;
        night = dark;
        bg = null;
        scrimX = null;
        demoShot = shot == null ? "idle" : shot;
        AppLog.w("demo search " + demoShot);
        if (demoShot.equals("listening")) {
            query = "funny sci-fi movies like Galaxy Quest";
            setListening(true, query);
            mic = 0.55f;
            applyDemoFocus();
            return;
        }
        if (demoShot.equals("thinking")) {
            query = "Funny sci-fi movies like Galaxy Quest";
            beginThinking();
            thinkAt = SystemClock.uptimeMillis() - 1800;
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
            setIdle(recent, null);
            applyDemoFocus();
            return;
        }
        query = "Funny sci-fi movies like Galaxy Quest";
        List<Hit> list = new ArrayList<>();
        list.add(sample("Galaxy Quest", 1999, "1h 42m", "Prime Video", "Sci-fi fans become the crew for real", false));
        list.add(sample("Palm Springs", 2020, "1h 30m", "Hulu", "A time-loop wedding weekend", false));
        list.add(sample("Paul", 2011, "1h 44m", "Peacock", "Two nerds and a small green guest", false));
        list.add(sample("The Hitchhiker's Guide to the Galaxy", 2005, "1h 49m", "Disney+", "Don't panic", false));
        Hit missing = sample("Spaceballs", 1987, "1h 36m", null, "A parody that isn't on your apps right now", false);
        list.add(missing);
        show(list, "", "Here are five sci-fi comedies with Galaxy Quest's mix of heart and parody.");
        applyDemoFocus();
    }

    private static Hit sample(String title, int year, String length, String service, String why, boolean show) {
        Hit h = new Hit();
        h.title = title;
        h.year = year;
        h.length = length;
        h.service = service;
        h.why = why;
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
        screen.set(0, 0, w, h);
        boolean dark = night;
        ink = Ui.text(Ui.semibold, Tokens.TYPE_BODY * k, Tokens.ink(dark));
        dim = Ui.text(Ui.medium, Tokens.TYPE_CAPTION * k, Tokens.inkSecondary(dark));
        keyText = Ui.text(Ui.medium, 32 * k, Tokens.ink(dark));
        keyText.setTextAlign(Paint.Align.CENTER);
        hero = Ui.text(Ui.semibold, Tokens.LAYOUT_CINEMA_TITLE * k, Tokens.ink(dark));
        hero.setLetterSpacing(-0.02f);
        meta = Ui.text(Ui.medium, Tokens.TYPE_CAPTION * k, Tokens.inkSecondary(dark));
        labelPaint = Ui.text(Ui.medium, 26 * k, Tokens.ink(dark));
        plate = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        imageFill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        replyTp.setTypeface(Ui.medium);
        replyTp.setTextSize(Tokens.LAYOUT_CINEMA_REPLY * k);
        replyBuilt = -1;
        layoutKeys();
        bg = null;
        scrimX = null;
        applyDemoFocus();
    }

    private void applyDemoFocus() {
        if (!demo || demoShot.isEmpty()) return;
        if (demoShot.equals("empty")) { zone = BUTTON; index = 0; }
        else if (demoShot.equals("listening") || demoShot.equals("thinking") || demoShot.equals("idle")) {
            zone = BAR;
            index = 0;
        } else { zone = POSTER; index = 0; titleIndex = 0; }
    }

    private void layoutKeys() {
        keys.clear();
        float kw = Tokens.LAYOUT_SEARCHKEYW * k, kh = Tokens.LAYOUT_SEARCHKEYH * k;
        float sx = Tokens.LAYOUT_SEARCHSTRIDEX * k, sy = Tokens.LAYOUT_SEARCHSTRIDEY * k;
        float left = 80 * k, top = 220 * k;
        for (int i = 0; i < LETTERS.length(); i++) {
            int col = i % 6, row = i / 6;
            keys.add(new Key(String.valueOf(LETTERS.charAt(i)), Key.CHAR, left + col * sx, top + row * sy, kw, kh));
        }
        float y = top + 6 * sy;
        keys.add(new Key("space", Key.SPACE, left, y, 312 * k, kh));
        keys.add(new Key("delete", Key.DELETE, left + 324 * k, y, 204 * k, kh));
        keys.add(new Key("clear", Key.CLEAR, left + 540 * k, y, 96 * k, kh));
    }

    @Override protected void onDraw(Canvas c) {
        if (w == 0 || ink == null) return;
        long now = SystemClock.uptimeMillis();
        if (labelGen != Ui.generation) {
            labelGen = Ui.generation;
            listenCaption = Ui.tr("Release the mic button to search").toUpperCase(Ui.locale());
            thinkingLabel = Ui.tr("Thinking").toUpperCase(Ui.locale());
            lineKey = "\u0000";
            openFor = -2;
            yearFor = Integer.MIN_VALUE;
            emptyLine = "";
        }
        ensureBackground();
        fill.setShader(bg);
        c.drawRect(screen, fill);
        fill.setShader(null);
        boolean alive = false;
        if (listening || thinking || !hits.isEmpty()) alive |= drawPlates(c, now);
        if (listening) alive |= drawDim(c, now);
        ink.setColor(Tokens.ink(night));
        dim.setColor(Tokens.inkSecondary(night));
        hero.setColor(Tokens.ink(night));
        meta.setColor(Tokens.inkSecondary(night));
        labelPaint.setColor(Tokens.ink(night));
        drawPill(c, now);
        if (listening) alive = true;
        else if (thinking) {
            drawThinking(c, now);
            alive = now - thinkAt < Tokens.LAYOUT_CINEMA_THINKMS;
        } else if (!hits.isEmpty() || !reply.isEmpty() || !status.isEmpty()) alive |= drawAnswer(c, now);
        else drawIdle(c);
        alive |= drawKeys(c, now);
        if (alive) postInvalidateOnAnimation();
    }

    private void ensureBackground() {
        if (bg == null) bg = new LinearGradient(0, 0, 0, h, Tokens.bgTop(night), Tokens.bgBottom(night), Shader.TileMode.CLAMP);
        if (scrimX != null) return;
        int edge = Tokens.cinemaScrim(night);
        int clear = edge & 0x00FFFFFF;
        scrimX = new LinearGradient(0, 0, w * 0.78f, 0, edge, clear, Shader.TileMode.CLAMP);
        scrimY = new LinearGradient(0, h * 0.42f, 0, h, clear, edge, Shader.TileMode.CLAMP);
    }

    /** Focused backdrop, cross-fading to the next one. Returns true while the fade is still moving. */
    private boolean drawPlates(Canvas c, long now) {
        Hit focus = focusedHit();
        Bitmap want = plateFor(focus);
        if (want != null && want != plateNow && want != plateNext) {
            if (plateNow == null) plateNow = want;
            else {
                plateNext = want;
                plateAt = now;
                plateFading = true;
            }
        }
        int a = 255;
        if (!listening && !thinking && revealAt > 0) {
            float t = ease((now - revealAt) / Tokens.LAYOUT_CINEMA_BACKDROPMS);
            a = (int) (255 * t);
        }
        drawPlate(c, plateNow, a);
        boolean moving = false;
        if (plateFading && plateNext != null) {
            float t = ease((now - plateAt) / Tokens.LAYOUT_CINEMA_FADEMS);
            drawPlate(c, plateNext, (int) (a * t));
            if (t >= 1f) {
                plateNow = plateNext;
                plateNext = null;
                plateFading = false;
            } else moving = true;
        }
        if (plateNow != null && !listening) {
            fill.setShader(scrimX);
            c.drawRect(screen, fill);
            fill.setShader(scrimY);
            c.drawRect(screen, fill);
            fill.setShader(null);
        }
        return moving || (revealAt > 0 && now - revealAt < Tokens.LAYOUT_CINEMA_BACKDROPMS);
    }

    private void drawPlate(Canvas c, Bitmap b, int alpha) {
        if (b == null || alpha <= 0) return;
        imageFill.setAlpha(alpha);
        c.drawBitmap(b, null, screen, imageFill);
        imageFill.setAlpha(255);
    }

    private Bitmap plateFor(Hit hit) {
        if (demo || hit == null || hit.pick == null) return null;
        Recs recs = Recs.get(getContext());
        int neighbour = titleIndex + (slideDir >= 0 ? 1 : -1);
        if (neighbour >= 0 && neighbour < hits.size()) recs.prefetchCinema(hits.get(neighbour).pick);
        return recs.cinemaPlate(hit.pick);
    }

    private boolean drawDim(Canvas c, long now) {
        float t = ease((now - listenAt) / 200f);
        plate.setColor(night ? 0xFF000000 : 0xFFFFFFFF);
        plate.setAlpha((int) (0.28f * 255 * t));
        c.drawRect(screen, plate);
        plate.setAlpha(255);
        return t < 1f;
    }

    private void drawPill(Canvas c, long now) {
        String shown = query.isEmpty() ? Ui.tr("Search movies, shows and apps") : query;
        String side = listening ? Ui.tr("Listening…") : thinking ? Ui.tr("Searching…") : Ui.tr("OK to edit");
        labelPaint.setTextSize(26 * k);
        float sideW = query.isEmpty() && !listening && !thinking ? 0 : labelPaint.measureText(side);
        float pad = 28 * k;
        float dot = 14 * k;
        float gap = 16 * k;
        float textMax = Math.min(820 * k, w - 200 * k);
        float barW = pad + dot + gap + Math.min(textMax, labelPaint.measureText(shown)) + (sideW > 0 ? gap + sideW : 0) + pad;
        barW = Math.max(420 * k, Math.min(barW, w - 160 * k));
        float bh = Tokens.LAYOUT_CINEMA_BARH * k;
        float top = Tokens.LAYOUT_CINEMA_BARTOP * k;
        float left = (w - barW) / 2f;
        tmp.set(left, top, left + barW, top + bh);
        plate.setColor(Tokens.cinemaPill(night));
        c.drawRoundRect(tmp, bh / 2f, bh / 2f, plate);
        plate.setStyle(Paint.Style.STROKE);
        plate.setStrokeWidth(Math.max(1f, k));
        plate.setColor(Tokens.cinemaTrack(night));
        c.drawRoundRect(tmp, bh / 2f, bh / 2f, plate);
        plate.setStyle(Paint.Style.FILL);
        if (zone == BAR && !keysOn) {
            plate.setStrokeWidth(Tokens.FOCUS_RING * k);
            plate.setStyle(Paint.Style.STROKE);
            plate.setColor(Tokens.ink(night));
            c.drawRoundRect(tmp, bh / 2f, bh / 2f, plate);
            plate.setStyle(Paint.Style.FILL);
        }
        float cx = tmp.left + pad + dot / 2f;
        float breathe = listening ? (float) (0.5 + 0.5 * Math.sin(now * 2 * Math.PI / Tokens.LAYOUT_CINEMA_BREATHEMS)) : 1f;
        float s = listening ? 1f + 0.15f * breathe : 1f;
        plate.setColor(Tokens.ink(night));
        plate.setAlpha(listening ? (int) (180 + 75 * breathe) : 255);
        c.drawCircle(cx, tmp.centerY(), dot / 2f * s, plate);
        plate.setAlpha(255);
        float textLeft = cx + dot / 2f + gap;
        float textRight = tmp.right - pad - (sideW > 0 ? sideW + gap : 0);
        labelPaint.setColor(query.isEmpty() ? Tokens.inkTertiary(night) : Tokens.ink(night));
        c.save();
        c.clipRect(textLeft, tmp.top, textRight, tmp.bottom);
        c.drawText(shown, textLeft, tmp.centerY() + Ui.halfCap(labelPaint), labelPaint);
        c.restore();
        if (sideW > 0) {
            labelPaint.setColor(Tokens.inkSecondary(night));
            c.drawText(side, tmp.right - pad - sideW, tmp.centerY() + Ui.halfCap(labelPaint), labelPaint);
        }
        if (listening) drawListening(c, now);
    }

    private void drawListening(Canvas c, long now) {
        float x = Tokens.SPACE_MARGIN * k;
        float y = 300 * k;
        hero.setTextSize(Tokens.LAYOUT_CINEMA_TITLE * k);
        hero.setColor(Tokens.ink(night));
        splitQuery(hero, w - 2 * x);
        c.drawText(line1, x, y, hero);
        if (!lineTail.isEmpty() || !lineHead.isEmpty()) {
            float tx = x;
            if (!lineHead.isEmpty()) {
                c.drawText(lineHead, tx, y + 68 * k, hero);
                tx += hero.measureText(lineHead);
            }
            int full = hero.getAlpha();
            hero.setAlpha(140);
            c.drawText(lineTail, tx, y + 68 * k, hero);
            hero.setAlpha(full);
        }
        boolean second = !lineHead.isEmpty() || !lineTail.isEmpty();
        drawWave(c, x, y + (second ? 120 * k : 56 * k), now);
        dim.setColor(Tokens.inkSecondary(night));
        dim.setLetterSpacing(0.12f);
        dim.setTextSize(18 * k);
        c.drawText(listenCaption, x, y + (second ? 176 * k : 110 * k), dim);
        dim.setLetterSpacing(0);
        dim.setTextSize(Tokens.TYPE_CAPTION * k);
    }

    /** Vertical bars on one centre line. Quiet: a slow idle sway. Louder: the smoothed mic level lifts them. */
    private void drawWave(Canvas c, float x, float y, long now) {
        int n = BARS.length;
        float width = WAVE_W * k;
        float step = width / n;
        float thick = Math.max(1.25f, 1.6f * k);
        float maxH = 28 * k;
        boolean quiet = mic < 0.08f;
        float level = quiet ? 0.28f : 0.3f + 0.7f * mic;
        for (int i = 0; i < n; i++) {
            float sway = quiet ? 0.82f + 0.18f * (float) Math.sin(now / 520.0 + i * 0.55) : 1f;
            float h = Math.max(thick, BARS[i] * sway * level * maxH * 2f);
            float left = x + step * i;
            plate.setColor(BARS[i] > 0.4f ? Tokens.ink(night) : Tokens.inkSecondary(night));
            c.drawRoundRect(left, y - h / 2f, left + thick, y + h / 2f, thick, thick, plate);
        }
    }

    private void drawThinking(Canvas c, long now) {
        float x = contentLeft();
        float y = 240 * k;
        dim.setLetterSpacing(0.16f);
        dim.setTextSize(18 * k);
        dim.setColor(Tokens.inkSecondary(night));
        c.drawText(thinkingLabel, x, y, dim);
        dim.setLetterSpacing(0);
        dim.setTextSize(Tokens.TYPE_CAPTION * k);
        plate.setColor(Tokens.cinemaBone(night));
        float room = Math.max(80 * k, contentRight() - x);
        float[] widths = {Math.min(640 * k, room), Math.min(560 * k, room * 0.85f), Math.min(280 * k, room * 0.45f)};
        float lineY = y + 36 * k;
        for (int i = 0; i < widths.length; i++) {
            tmp.set(x, lineY, x + widths[i], lineY + 18 * k);
            c.drawRoundRect(tmp, 9 * k, 9 * k, plate);
            lineY += 34 * k;
        }
        float span = Tokens.LAYOUT_CINEMA_THINKMS;
        float p = ease(Math.min(1f, (now - thinkAt) / span));
        plate.setColor(Tokens.inkTertiary(night));
        c.drawRect(x, lineY + 8 * k, x + 360 * k * p, lineY + 11 * k, plate);
        drawPosterSkeletons(c);
    }

    private void drawPosterSkeletons(Canvas c) {
        float pw = Tokens.LAYOUT_CINEMA_POSTERW * k;
        float ph = Tokens.LAYOUT_CINEMA_POSTERH * k;
        float gap = 22 * k;
        float y = Tokens.LAYOUT_CINEMA_POSTERTOP * k;
        float x = Math.max(contentLeft(), contentRight() - 5 * pw - 4 * gap);
        plate.setColor(Tokens.cinemaBone(night));
        for (int i = 0; i < 5; i++) {
            tmp.set(x, y, x + pw, y + ph);
            c.drawRoundRect(tmp, Tokens.RADIUS_CARD * k, Tokens.RADIUS_CARD * k, plate);
            x += pw + gap;
        }
    }

    /** Reply, title, buttons and the poster row. True while the entrance, the type-out or the focus scale is moving. */
    private boolean drawAnswer(Canvas c, long now) {
        float x = contentLeft();
        float slide = (1f - focusEase(now)) * Tokens.LAYOUT_CINEMA_SLIDE * k * slideDir;
        float top = 200 * k + slide;
        boolean moving = false;
        if (!reply.isEmpty()) moving |= drawReply(c, x, top, now);
        Hit hit = focusedHit();
        float y = reply.isEmpty() ? top + 40 * k : top + 150 * k;
        if (hit != null) {
            hero.setTextSize(Tokens.LAYOUT_CINEMA_TITLE * k);
            hero.setColor(Tokens.ink(night));
            c.save();
            c.clipRect(x, y - 70 * k, contentRight(), y + 20 * k);
            c.drawText(hit.title == null ? "" : hit.title, x, y, hero);
            c.restore();
            y += 36 * k;
            y = drawMeta(c, hit, x, y);
            if (hit.why != null && !hit.why.isEmpty()) {
                meta.setColor(Tokens.inkSecondary(night));
                c.save();
                c.clipRect(x, y - 30 * k, contentRight(), y + 10 * k);
                c.drawText(hit.why, x, y, meta);
                c.restore();
                y += 28 * k;
            }
            drawResultButtons(c, x, y + 16 * k, now);
        } else if (hits.isEmpty() && reply.isEmpty()) drawEmpty(c, x, Math.max(y, 360 * k));
        else if (hits.isEmpty()) drawEmptyActions(c, x, Math.max(y, 360 * k));
        if (!hits.isEmpty()) moving |= drawPosters(c, now);
        float fade = Tokens.LAYOUT_CINEMA_FADEMS;
        float stagger = (hits.size() - 1) * Tokens.LAYOUT_CINEMA_STAGGER;
        float type = reply.length() * Tokens.LAYOUT_CINEMA_TYPEMS;
        moving |= now - revealAt < fade + stagger + type;
        moving |= now - focusAt < fade;
        return moving;
    }

    private boolean drawReply(Canvas c, float x, float y, long now) {
        int n = reply.length();
        if (revealAt > 0) n = (int) Math.min(reply.length(), (now - revealAt) / Tokens.LAYOUT_CINEMA_TYPEMS);
        float width = Math.max(200 * k, contentRight() - x);
        if (replyLayout == null || replyBuilt != n || replyNight != night || replyWidth != width) {
            replyBuilt = n;
            replyNight = night;
            replyWidth = width;
            replyTp.setColor(Tokens.ink(night));
            replyTp.setTextSize(Tokens.LAYOUT_CINEMA_REPLY * k);
            String slice = n >= reply.length() ? reply : reply.substring(0, Math.max(0, n));
            replyLayout = StaticLayout.Builder.obtain(slice, 0, slice.length(), replyTp, (int) width)
                    .setMaxLines(3).setAlignment(Layout.Alignment.ALIGN_NORMAL).build();
        }
        c.save();
        c.translate(x, y);
        replyLayout.draw(c);
        c.restore();
        return n < reply.length();
    }

    private float drawMeta(Canvas c, Hit hit, float x, float y) {
        meta.setColor(Tokens.inkSecondary(night));
        float cursor = x;
        if (hit.year != yearFor) {
            yearFor = hit.year;
            yearLabel = hit.year > 0 ? String.valueOf(hit.year) : "";
        }
        if (!yearLabel.isEmpty()) {
            c.drawText(yearLabel, cursor, y, meta);
            cursor += meta.measureText(yearLabel);
        }
        if (hit.length != null && !hit.length.isEmpty()) {
            if (cursor > x) cursor = dot(c, cursor, y);
            c.drawText(hit.length, cursor, y, meta);
            cursor += meta.measureText(hit.length);
        }
        String badge = badge(hit);
        if (!badge.isEmpty()) {
            if (cursor > x) cursor += 14 * k;
            drawBadge(c, badge, cursor, y);
        }
        return y + 40 * k;
    }

    private float dot(Canvas c, float x, float y) {
        meta.setColor(Tokens.inkTertiary(night));
        c.drawText("  ·  ", x, y, meta);
        float wDot = meta.measureText("  ·  ");
        meta.setColor(Tokens.inkSecondary(night));
        return x + wDot;
    }

    private void drawBadge(Canvas c, String text, float x, float y) {
        labelPaint.setTextSize(22 * k);
        float tw = labelPaint.measureText(text);
        float h = 36 * k;
        tmp.set(x, y - h + 8 * k, x + tw + 24 * k, y + 8 * k);
        boolean missing = Ui.tr("Not on your apps").equals(text);
        plate.setColor(missing ? Tokens.cinemaBone(night) : Tokens.cinemaPill(night));
        c.drawRoundRect(tmp, 10 * k, 10 * k, plate);
        labelPaint.setColor(Tokens.ink(night));
        c.drawText(text, tmp.left + 12 * k, tmp.centerY() + Ui.halfCap(labelPaint), labelPaint);
    }

    private String badge(Hit hit) {
        if (hit.service != null && !hit.service.isEmpty()) return hit.service;
        if (hit.app) return hit.meta == null ? "" : hit.meta;
        if (hit.store) return Ui.tr("Get from Play Store");
        if (hit.pick != null || !demo) return Ui.tr("Not on your apps");
        return Ui.tr("Not on your apps");
    }

    private void drawResultButtons(Canvas c, float x, float y, long now) {
        if (titleIndex != openFor) {
            openFor = titleIndex;
            Hit hit = focusedHit();
            openLabel = hit != null && hit.service != null && !hit.service.isEmpty()
                    ? Ui.trf("Open in {service}", "service", hit.service) : Ui.tr("Open");
        }
        float used = drawButton(c, openLabel, x, y, true, zone == BUTTON && index == 0, now);
        drawButton(c, Ui.tr("More like this"), x + used + 16 * k, y, false, zone == BUTTON && index == 1, now);
    }

    private void drawEmpty(Canvas c, float x, float y) {
        hero.setTextSize(Tokens.TYPE_HEADLINE * k);
        hero.setColor(Tokens.ink(night));
        if (status.isEmpty() && emptyLine.isEmpty()) emptyLine = Ui.trf("No matches for “{query}”", "query", query);
        String line = status.isEmpty() ? emptyLine : status;
        c.save();
        c.clipRect(x, y - 60 * k, contentRight(), y + 16 * k);
        c.drawText(line, x, y, hero);
        c.restore();
        hero.setTextSize(Tokens.LAYOUT_CINEMA_TITLE * k);
        if (status.isEmpty() || status.contains("“")) {
            meta.setColor(Tokens.inkSecondary(night));
            c.drawText(Ui.tr("Check the spelling, or try a title, an actor or an app."), x, y + 48 * k, meta);
        }
        float bx = x;
        drawEmptyActions(c, bx, y + 88 * k);
    }

    private void drawEmptyActions(Canvas c, float x, float y) {
        float bx = x;
        String[] labels = {Ui.tr("Try again by voice"), Ui.tr("Ask Google Assistant"), Ui.tr("Clear search")};
        for (int i = 0; i < labels.length; i++) {
            float used = drawButton(c, labels[i], bx, y, i == 0, zone == BUTTON && index == i, 0);
            bx += used + 16 * k;
        }
    }

    private float drawButton(Canvas c, String label, float x, float y, boolean primary, boolean focused, long now) {
        labelPaint.setTextSize(28 * k);
        float tw = labelPaint.measureText(label);
        float bw = tw + 56 * k;
        float bh = 64 * k;
        float s = focused ? 1f + (Tokens.LAYOUT_CINEMA_FOCUS - 1f) * focusEase(now) : 1f;
        float cx = x + bw / 2f, cy = y + bh / 2f;
        tmp.set(cx - bw * s / 2f, cy - bh * s / 2f, cx + bw * s / 2f, cy + bh * s / 2f);
        int fillColor = primary ? (night ? 0xFFFFFFFF : 0xFF1D1D1F) : Tokens.cinemaPill(night);
        int text = primary ? (night ? Tokens.INK_LIGHT : 0xFFFFFFFF) : Tokens.ink(night);
        plate.setColor(fillColor);
        c.drawRoundRect(tmp, bh / 2f, bh / 2f, plate);
        if (focused) {
            plate.setStyle(Paint.Style.STROKE);
            plate.setStrokeWidth(Tokens.FOCUS_RING * k);
            plate.setColor(Tokens.ink(night));
            c.drawRoundRect(tmp, bh / 2f, bh / 2f, plate);
            plate.setStyle(Paint.Style.FILL);
        }
        labelPaint.setColor(text);
        c.drawText(label, tmp.left + (tmp.width() - tw) / 2f, tmp.centerY() + Ui.halfCap(labelPaint), labelPaint);
        return bw;
    }

    private boolean drawPosters(Canvas c, long now) {
        float pw = Tokens.LAYOUT_CINEMA_POSTERW * k;
        float ph = Tokens.LAYOUT_CINEMA_POSTERH * k;
        float gap = 22 * k;
        float avail = contentRight() - contentLeft();
        int n = Math.min(hits.size(), 8);
        while (n > 1 && n * pw + (n - 1) * gap > avail) n--;
        int start = 0;
        if (titleIndex >= start + n) start = titleIndex - n + 1;
        float rowW = n * pw + (n - 1) * gap;
        float x = Math.max(contentLeft(), contentRight() - rowW);
        float baseY = Tokens.LAYOUT_CINEMA_POSTERTOP * k;
        boolean moving = false;
        for (int i = 0; i < n; i++) {
            float delay = i * Tokens.LAYOUT_CINEMA_STAGGER;
            float t = revealAt == 0 ? 1f : ease((now - revealAt - delay) / Tokens.LAYOUT_CINEMA_FADEMS);
            if (t < 1f) moving = true;
            int hitAt = start + i;
            boolean focused = zone == POSTER && hitAt == index;
            float s = focused ? 1f + (Tokens.LAYOUT_CINEMA_FOCUS - 1f) * focusEase(now) : 1f;
            float rise = (1f - t) * Tokens.LAYOUT_CINEMA_RISE * k;
            float cx = x + pw / 2f;
            float cy = baseY + ph / 2f + rise;
            tmp.set(cx - pw * s / 2f, cy - ph * s / 2f, cx + pw * s / 2f, cy + ph * s / 2f);
            int save = imageFill.getAlpha();
            imageFill.setAlpha((int) (255 * t));
            plate.setAlpha((int) (255 * t));
            drawCard(c, hits.get(hitAt), hitAt);
            if (focused && t > 0.2f) {
                plate.setStyle(Paint.Style.STROKE);
                plate.setStrokeWidth(Tokens.FOCUS_RING * k);
                plate.setColor(Tokens.ink(night));
                c.drawRoundRect(tmp, Tokens.RADIUS_CARD * k, Tokens.RADIUS_CARD * k, plate);
                plate.setStyle(Paint.Style.FILL);
            }
            imageFill.setAlpha(save);
            plate.setAlpha(255);
            x += pw + gap;
        }
        return moving;
    }

    private void drawCard(Canvas c, Hit hit, int tint) {
        Bitmap poster = null;
        if (!demo && hit.pick != null) poster = Recs.get(getContext()).cinemaPoster(hit.pick);
        if (poster != null) {
            imageFill.setShader(null);
            c.save();
            c.clipPath(round(tmp, Tokens.RADIUS_CARD * k));
            c.drawBitmap(poster, null, tmp, imageFill);
            c.restore();
        } else {
            fill.setColor(PALETTE[Math.floorMod(tint, PALETTE.length)]);
            c.drawRoundRect(tmp, Tokens.RADIUS_CARD * k, Tokens.RADIUS_CARD * k, fill);
        }
        String badge = badge(hit);
        if (!badge.isEmpty()) {
            labelPaint.setTextSize(18 * k);
            float tw = labelPaint.measureText(badge);
            float bw = Math.min(tmp.width() - 16 * k, tw + 16 * k);
            RectF box = tmp;
            float top = box.top + 12 * k;
            plate.setColor(0xCC101014);
            c.drawRoundRect(box.left + 10 * k, top, box.left + 10 * k + bw, top + 28 * k, 8 * k, 8 * k, plate);
            labelPaint.setColor(0xFFFFFFFF);
            c.save();
            c.clipRect(box.left + 10 * k, top, box.left + 10 * k + bw, top + 28 * k);
            c.drawText(badge, box.left + 18 * k, top + 14 * k + Ui.halfCap(labelPaint), labelPaint);
            c.restore();
        }
    }

    private final Path clip = new Path();

    private Path round(RectF r, float radius) {
        clip.rewind();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        return clip;
    }

    private void drawIdle(Canvas c) {
        float x = contentLeft();
        float y = 280 * k;
        dim.setColor(Tokens.inkSecondary(night));
        c.drawText(Ui.tr("Or hold the remote's mic: “funny shows for tonight”"), x, y, dim);
        if (chips.isEmpty()) return;
        float cx = x;
        float cy = y + 48 * k;
        for (int i = 0; i < chips.size(); i++) {
            float used = drawButton(c, chips.get(i), cx, cy, false, zone == CHIP && index == i, 0);
            cx += used + 16 * k;
        }
    }

    private boolean drawKeys(Canvas c, long now) {
        float t = ease((now - keysAt) / Tokens.LAYOUT_CINEMA_FADEMS);
        float alpha = keysOn ? t : (keysAt == 0 ? 0 : 1f - t);
        if (alpha <= 0.01f) return false;
        int a = (int) (255 * alpha);
        plate.setColor(night ? 0xE6101014 : 0xE6F5F5F7);
        plate.setAlpha(a);
        c.drawRoundRect(48 * k, 180 * k, 760 * k, h - 48 * k, Tokens.RADIUS_PANEL * k, Tokens.RADIUS_PANEL * k, plate);
        plate.setAlpha(255);
        for (int i = 0; i < keys.size(); i++) {
            boolean on = keysOn && zone == KEY && i == index;
            drawKey(c, keys.get(i), on, a);
        }
        return (keysOn && alpha < 0.99f) || (!keysOn && alpha > 0.01f);
    }

    private void drawKey(Canvas c, Key key, boolean focused, int alpha) {
        float s = focused ? Tokens.FOCUS_KEY : 1f;
        float cx = key.box.centerX(), cy = key.box.centerY();
        tmp.set(cx - key.box.width() * s / 2, cy - key.box.height() * s / 2,
                cx + key.box.width() * s / 2, cy + key.box.height() * s / 2);
        // Focused: solid light key, dark label (dark theme) or solid dark key, light label.
        // Resting: a quiet fill. setAlpha() would replace the fill's own alpha and turn it solid white.
        int fill = focused ? (night ? 0xFFFFFFFF : 0xFF1D1D1F) : (night ? 0x1FFFFFFF : 0xFFF2F2F4);
        int color = focused ? (night ? 0xFF1D1D1F : 0xFFFFFFFF) : Tokens.ink(night);
        plate.setColor(scaleAlpha(fill, alpha));
        c.drawRoundRect(tmp, 18 * k, 18 * k, plate);
        if (!focused) {
            plate.setStyle(Paint.Style.STROKE);
            plate.setStrokeWidth(Math.max(1f, k));
            plate.setColor(scaleAlpha(Tokens.cinemaTrack(night), alpha));
            c.drawRoundRect(tmp, 18 * k, 18 * k, plate);
            plate.setStyle(Paint.Style.FILL);
        }
        keyText.setColor(scaleAlpha(color, alpha));
        String label = key.action == Key.SPACE ? Ui.tr("space") : key.action == Key.DELETE ? Ui.tr("delete")
                : key.action == Key.CLEAR ? Ui.tr("clear") : key.label;
        keyText.setTextSize((key.action == Key.CHAR ? 32 : 26) * k);
        c.drawText(label, cx, cy + Ui.halfCap(keyText), keyText);
        keyText.setAlpha(255);
    }

    /** The left edge of titles, chips and posters. The keyboard occupies everything left of this. */
    private float contentLeft() {
        return keysOn ? 800 * k : Tokens.SPACE_MARGIN * k;
    }

    private float contentRight() {
        return w - Tokens.SPACE_MARGIN * k;
    }

    private static int scaleAlpha(int color, int alpha) {
        int a = ((color >>> 24) * alpha) / 255;
        return (a << 24) | (color & 0x00FFFFFF);
    }

    private Hit focusedHit() {
        if (hits.isEmpty()) return null;
        int i = titleIndex;
        if (i < 0 || i >= hits.size()) i = 0;
        return hits.get(i);
    }

    private void prefetchAround(int i) {
        if (demo) return;
        Recs recs = Recs.get(getContext());
        for (int d = -1; d <= 1; d++) {
            int at = i + d;
            if (at >= 0 && at < hits.size() && hits.get(at).pick != null) recs.prefetchCinema(hits.get(at).pick);
        }
    }

    private float focusEase(long now) {
        if (focusAt == 0) return 1f;
        return ease((now - focusAt) / Tokens.LAYOUT_CINEMA_FADEMS);
    }

    private static float ease(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        float u = 1f - t;
        return 1f - u * u * u;
    }

    /** Split the spoken line once per change: the last word of the second line is drawn lighter. */
    private void splitQuery(Paint p, float max) {
        String text = query.isEmpty() ? Ui.tr("Listening…") : query;
        if (text.equals(lineKey)) return;
        lineKey = text;
        int split = breakAt(text, p, max);
        line1 = split < 0 ? text : text.substring(0, split).trim();
        String second = split < 0 ? "" : text.substring(split).trim();
        int last = second.lastIndexOf(' ');
        lineHead = last < 0 ? "" : second.substring(0, last + 1);
        lineTail = last < 0 ? second : second.substring(last + 1);
    }

    /** Index of the first character that doesn't fit, or -1 when the whole string fits. */
    private static int breakAt(String text, Paint p, float max) {
        if (p.measureText(text) <= max) return -1;
        int cut = text.lastIndexOf(' ');
        while (cut > 0 && p.measureText(text.substring(0, cut)) > max) cut = text.lastIndexOf(' ', cut - 1);
        return cut < 0 ? -1 : cut;
    }

    boolean onKey(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return e.getKeyCode() == KeyEvent.KEYCODE_BACK && (listening || keysOn);
        if (listening) {
            if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) host.cancelListen();
            return true;
        }
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (keysOn) {
                closeKeys();
                return true;
            }
            return false;
        }
        if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_SEARCH) {
            if (keysOn) closeKeys();
            host.commit(query);
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER) {
            activate();
            return true;
        }
        if (code == KeyEvent.KEYCODE_DEL || code == KeyEvent.KEYCODE_FORWARD_DEL) {
            backspace();
            return true;
        }
        int typed = e.getUnicodeChar();
        if (typed >= 32 && !Character.isISOControl(typed)) {
            type(String.valueOf((char) typed));
            return true;
        }
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (dx == 0 && dy == 0) return false;
        move(dx, dy);
        return true;
    }

    private void closeKeys() {
        keysOn = false;
        keysAt = SystemClock.uptimeMillis();
        zone = BAR;
        index = 0;
        invalidate();
    }

    private void openKeys() {
        if (keys.isEmpty()) layoutKeys();
        keysOn = true;
        keysAt = SystemClock.uptimeMillis();
        zone = KEY;
        index = 0;
        invalidate();
    }

    private void move(int dx, int dy) {
        focusAt = SystemClock.uptimeMillis();
        if (dx != 0) slideDir = dx;
        if (zone == KEY) moveKeys(dx, dy);
        else if (zone == BAR) {
            if (dy > 0) downFromBar();
        } else if (zone == CHIP) {
            if (dy < 0) { zone = BAR; index = 0; }
            else if (dx != 0) index = Math.max(0, Math.min(chips.size() - 1, index + dx));
        } else if (zone == BUTTON) {
            int n = buttonCount();
            if (dy < 0) { zone = BAR; index = 0; }
            else if (dy > 0 && !hits.isEmpty()) { zone = POSTER; index = titleIndex; }
            else if (dx != 0) index = Math.max(0, Math.min(n - 1, index + dx));
        } else if (zone == POSTER) {
            if (dy < 0) { zone = BUTTON; index = 0; }
            else if (dx != 0 && !hits.isEmpty()) {
                index = Math.max(0, Math.min(hits.size() - 1, index + dx));
                titleIndex = index;
                prefetchAround(titleIndex);
            }
        }
        invalidate();
    }

    private void moveKeys(int dx, int dy) {
        if (index >= LETTERS.length()) {
            int b = index - LETTERS.length();
            if (dx < 0) index = LETTERS.length() + Math.max(0, b - 1);
            if (dx > 0) index = LETTERS.length() + Math.min(2, b + 1);
            if (dy < 0) index = 30 + (b == 0 ? 1 : b == 1 ? 3 : 5);
            return;
        }
        int col = index % 6, row = index / 6;
        if (dx != 0) {
            int nc = col + dx;
            if (nc >= 0 && nc < 6) index = row * 6 + nc;
        }
        if (dy < 0 && row > 0) index = (row - 1) * 6 + col;
        if (dy > 0) {
            if (row < 5) index = (row + 1) * 6 + col;
            else index = LETTERS.length() + (col < 2 ? 0 : col < 5 ? 1 : 2);
        }
    }

    private void downFromBar() {
        if (!hits.isEmpty()) { zone = BUTTON; index = 0; }
        else if (!query.isEmpty()) { zone = BUTTON; index = 0; }
        else if (!chips.isEmpty()) { zone = CHIP; index = 0; }
    }

    private int buttonCount() {
        return hits.isEmpty() ? 3 : 2;
    }

    private void activate() {
        if (zone == BAR) openKeys();
        else if (zone == KEY && index < keys.size()) {
            Key key = keys.get(index);
            if (key.action == Key.CHAR) type(key.label);
            else if (key.action == Key.SPACE) type(" ");
            else if (key.action == Key.DELETE) backspace();
            else if (key.action == Key.CLEAR) { query = ""; host.submit("", false); }
        } else if (zone == CHIP && index < chips.size()) {
            query = chips.get(index);
            host.submit(query, false);
        } else if (zone == POSTER && titleIndex < hits.size()) host.open(hits.get(titleIndex));
        else if (zone == BUTTON) activateButton();
        invalidate();
    }

    private void activateButton() {
        if (!hits.isEmpty()) {
            Hit hit = focusedHit();
            if (index == 0 && hit != null) host.open(hit);
            else if (hit != null && hit.title != null && !hit.title.isEmpty()) {
                query = hit.title;
                host.submit(hit.title, false);
            }
            return;
        }
        if (index == 0) host.listen();
        else if (index == 1) host.ask(query);
        else { query = ""; host.submit("", false); }
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
