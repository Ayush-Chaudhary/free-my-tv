package io.freemytv;

import android.app.ActivityManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A YouTube trailer in the focused card, popping out to a 16:9 window. The WebView's renderer is another process:
 * if it dies, {@link #onGone} drops the player and returns true, so the home screen (and the watchdog) stay up.
 * On a low-memory TV the player is never created. The page only talks to YouTube, and the bridge only starts and stops.
 */
final class TrailerPlayer {
    private static final long TRAILER_DELAY = 1500;
    private static final long POP_MS = 520, POP_AFTER = 900, PLAYER_IDLE = 30_000;
    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{6,20}");
    private static final PathInterpolator EASE = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    private static final String CSS_EASE = "cubic-bezier(.32,.72,0,1)";

    private final HomeView home;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RectF window = new RectF();
    private final RectF bezel = new RectF();
    private final RectF cardAt = new RectF();
    private FrameLayout box;
    private WebView web;
    private View scrim, info;
    private String forKey, loadedId, pendingCall;
    private Recs.Pick popPick;
    private boolean pageReady, popped, asleep, releasePending, gaveUp, memoryNoted, dead, soundOn, demoMode;
    private float progress;
    private final TextPaint fit = new TextPaint();
    private float backdrop;
    private int blurStep = -1;
    private android.animation.ValueAnimator backdropAnim;
    private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint popTitle, popMeta;
    private TextPaint popAbout;
    private StaticLayout popAboutLayout;

    TrailerPlayer(HomeView home) {
        this.home = home;
    }

    /** The focused pick, or null when nothing should play. A different pick restarts the wait. */
    void onFocus(Recs.Pick pick) {
        String want = pick == null ? null : Recs.key(pick);
        if (want != null && want.equals(forKey)) return;
        stop(home.homeRunning());
        forKey = want;
        if (pick == null || gaveUp || demoMode) return;
        if ("off".equals(Prefs.trailersEffective())) return;
        soundOn = "on".equals(Prefs.trailers());
        handler.postDelayed(start, TRAILER_DELAY);
    }

    /** Up toggles sound. Left and right move to the next title. True when the key was for the popped trailer. */
    boolean onMove(int dx, int dy) {
        if (!popped) return false;
        if (dy < 0 && dx == 0) {
            soundOn = !soundOn;
            pushSound();
            if (info != null) info.invalidate();
            return true;
        }
        if (dx != 0 && dy == 0) {
            home.trailerStep(dx);
            return true;
        }
        return false;
    }

    /** A trailer is in the card, not yet popped. */
    boolean resting() {
        return !popped && (demoMode || loadedId != null);
    }

    /** Back while the trailer is popped out: close it and leave the card's photo. True if that consumed Back. */
    boolean onBack() {
        if (!popped) return false;
        String keep = forKey;
        stop(home.homeRunning());
        forKey = keep;
        return true;
    }

    void stop() { stop(false); }

    void destroy() {
        dead = true;
        stop(false);
        dropPlayer();
    }

    /** The system is low on memory: the player goes, and it is not started again this session. */
    void onTrim() {
        gaveUp = true;
        destroy();
        AppLog.w("trailer player released: low memory");
    }

    void measure(int width, int height) {
        if (scrim != null) spec(scrim, width, height);
        if (info != null) spec(info, width, height);
        if (box != null) spec(box, Math.round(window.width()), Math.round(window.height()));
    }

    void layout() {
        if (scrim != null) scrim.layout(0, 0, home.getWidth(), home.getHeight());
        if (info != null) info.layout(0, 0, home.getWidth(), home.getHeight());
        if (box == null) return;
        int w = Math.round(window.width()), h = Math.round(window.height());
        spec(box, w, h);
        box.layout(Math.round(window.left), Math.round(window.top), Math.round(window.left) + w, Math.round(window.top) + h);
    }

    private final Runnable start = this::start;
    private final Runnable pop = this::pop;
    private final Runnable sleep = this::sleep;
    private final Runnable release = this::dropPlayer;

    private void start() {
        Recs.Pick pick = focused();
        if (pick == null || !Recs.key(pick).equals(forKey)) return;
        if (!memoryAllows()) return;
        new Thread(() -> {
            String id = Recs.get(home.getContext()).trailer(pick);
            home.post(() -> play(id));
        }, "trailer").start();
    }

    private void play(String id) {
        Recs.Pick now = focused();
        if (dead || id == null || !YOUTUBE_ID.matcher(id).matches() || now == null || !Recs.key(now).equals(forKey)) return;
        if (!memoryAllows()) return;
        ensure();
        if (web == null) return;
        wake();
        loadedId = id;
        placeOnCard();
        String call = String.format(Locale.US, "play('%s',%s)", id, soundOn ? "true" : "false");
        if (pageReady) web.evaluateJavascript(call, null);
        else pendingCall = call;
    }

    private Recs.Pick focused() {
        return home.trailerPick();
    }

    private void stop(boolean animate) {
        handler.removeCallbacks(start);
        handler.removeCallbacks(pop);
        forKey = null;
        pendingCall = null;
        boolean was = loadedId != null;
        loadedId = null;
        popped = false;
        if (box != null) {
            box.animate().cancel();
            if (animate && box.getAlpha() > 0) {
                box.animate().scaleX(cardScale()).scaleY(cardScale())
                        .translationX(cardAt.centerX() - window.centerX()).translationY(cardAt.centerY() - window.centerY())
                        .alpha(0).setDuration(300).setInterpolator(EASE)
                        .withEndAction(() -> { if (loadedId == null && web != null) web.evaluateJavascript("stop()", null); })
                        .start();
            } else {
                box.setAlpha(0);
                if (was && web != null) web.evaluateJavascript("stop()", null);
            }
        }
        fade(0, animate ? 300 : 0);
        if (web != null && !releasePending) {
            handler.postDelayed(sleep, 500);
            handler.postDelayed(release, PLAYER_IDLE);
            releasePending = true;
        }
    }

    private void sleep() {
        if (web == null || loadedId != null || asleep) return;
        web.onPause();
        web.pauseTimers();
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true);
        asleep = true;
    }

    private void wake() {
        handler.removeCallbacks(sleep);
        handler.removeCallbacks(release);
        releasePending = false;
        if (web != null) web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        if (!asleep || web == null) return;
        web.onResume();
        web.resumeTimers();
        asleep = false;
    }

    private void ensure() {
        if (web != null || gaveUp || dead) return;
        box = new FrameLayout(home.getContext());
        box.setAlpha(0);
        box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        try {
            web = new WebView(home.getContext());
        } catch (RuntimeException e) {
            AppLog.w("trailer player unavailable: " + e.getMessage());
            gaveUp = true;
            box = null;
            return;
        }
        web.setBackgroundColor(0);
        web.setFocusable(false);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        web.addJavascriptInterface(new Bridge(), "FreeMyTV");
        web.setWebViewClient(new android.webkit.WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (pendingCall != null && web != null) web.evaluateJavascript(pendingCall, null);
                pendingCall = null;
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request == null || !allowed(request.getUrl());
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !allowed(url == null ? null : Uri.parse(url));
            }

            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                AppLog.w("trailer renderer gone (" + (detail != null && detail.didCrash() ? "crash" : "system") + "): home stays up");
                handler.post(() -> dropPlayer());
                return true;
            }
        });
        box.addView(web, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        scrim = new View(home.getContext());
        scrim.setBackgroundColor(0xFF000000);
        scrim.setAlpha(0);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        info = new View(home.getContext()) {
            @Override protected void onDraw(Canvas c) { drawInfo(c); }
        };
        info.setAlpha(0);
        info.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int at = home.layerAboveTiles();
        home.addView(scrim, at);
        home.addView(info, home.indexOfChild(scrim) + 1);
        home.addView(box, home.indexOfChild(info) + 1);
        web.loadDataWithBaseURL("https://freemytv.io/", PAGE, "text/html", "utf-8", null);
    }

    /** YouTube, its image and script hosts, and our own page. Anything else is not loaded. */
    private static boolean allowed(Uri uri) {
        if (uri == null || uri.getHost() == null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https")) return false;
        return host.equals("freemytv.io") || host.equals("youtube.com") || host.endsWith(".youtube.com")
                || host.endsWith(".youtube-nocookie.com") || host.endsWith(".ytimg.com")
                || host.endsWith(".googlevideo.com") || host.endsWith(".gstatic.com") || host.equals("www.google.com");
    }

    private boolean memoryAllows() {
        if ("off".equals(Prefs.trailersEffective())) return false;
        ActivityManager am = home.getContext().getSystemService(ActivityManager.class);
        if (am == null) return false;
        if (am.isLowRamDevice()) {
            if (!memoryNoted) AppLog.w("trailer skipped: this TV is low on memory");
            memoryNoted = true;
            gaveUp = true;
            return false;
        }
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(info);
        if (info.lowMemory || info.availMem < 180L * 1024 * 1024) {
            if (!memoryNoted) AppLog.w("trailer skipped: not enough free memory");
            memoryNoted = true;
            return false;
        }
        memoryNoted = false;
        return true;
    }

    private void placeOnCard() {
        RectF base = home.focusedCardRect();
        float k = home.uiScale();
        float inset = Tokens.LAYOUT_VIDEOINSET * k;
        float s = Tokens.FOCUS_CARD, cx = base.centerX(), cy = base.centerY() - Tokens.FOCUS_LIFT * k;
        cardAt.set(cx - base.width() * s / 2, cy - base.height() * s / 2, cx + base.width() * s / 2, cy + base.height() * s / 2);
        cardAt.inset(inset, inset);
        float vw = Tokens.LAYOUT_POPW * k, vh = Tokens.LAYOUT_POPH * k, pad = Tokens.LAYOUT_BEZELPAD * k;
        float extra = Tokens.LAYOUT_BEZELEXTRA * k;
        float outerW = vw + pad * 2, outerH = vh + pad * 2 + extra;
        int w = home.screenPx();
        float left = (w - outerW) / 2, top = Tokens.LAYOUT_POPTOP * k;
        bezel.set(left, top, left + outerW, top + outerH);
        window.set(left + pad, top + pad, left + pad + vw, top + pad + vh);
        layout();
        if (box == null) return;
        box.setPivotX(window.width() / 2);
        box.setPivotY(window.height() / 2);
        box.setScaleX(cardScale());
        box.setScaleY(cardScale());
        box.setTranslationX(cardAt.centerX() - window.centerX());
        box.setTranslationY(cardAt.centerY() - window.centerY());
    }

    private float cardScale() {
        return window.width() > 0 ? cardAt.width() / window.width() : 1;
    }

    private float cardInset() {
        float shown = cardAt.height() / cardScale() / Math.max(1, window.height());
        return Math.max(0, (1 - shown) / 2 * 100);
    }

    private float cardRadius() {
        return 18 * home.uiScale() * 1.05f / cardScale() / home.getResources().getDisplayMetrics().density;
    }

    private void pop() {
        if (box == null || loadedId == null || web == null || !home.homeRunning()) return;
        popped = true;
        popPick = focused();
        popAboutLayout = null;
        info.invalidate();
        box.animate().scaleX(1).scaleY(1).translationX(0).translationY(0).alpha(1)
                .setDuration(POP_MS).setInterpolator(EASE).start();
        fade(1, POP_MS);
    }

    private void fade(float to, long ms) {
        if (backdropAnim != null) backdropAnim.cancel();
        backdropAnim = null;
        if (scrim == null || backdrop == to) {
            if (scrim == null) applyBackdrop(0);
            return;
        }
        if (ms <= 0) {
            applyBackdrop(to);
            return;
        }
        backdropAnim = android.animation.ValueAnimator.ofFloat(backdrop, to);
        backdropAnim.setDuration(ms);
        backdropAnim.setInterpolator(EASE);
        backdropAnim.addUpdateListener(a -> applyBackdrop((float) a.getAnimatedValue()));
        backdropAnim.start();
    }

    private void applyBackdrop(float level) {
        backdrop = level;
        float dim = Build.VERSION.SDK_INT >= 31 ? Tokens.SCRIM_POP : Tokens.SCRIM_POP_NO_BLUR;
        if (scrim != null) scrim.setAlpha(dim * level);
        if (info != null) info.setAlpha(level);
        if (Build.VERSION.SDK_INT < 31) return;
        int step = Math.round(Tokens.LAYOUT_POPBLUR * home.uiScale() * level);
        if (step == blurStep) return;
        blurStep = step;
        home.setBackdropBlur(step);
    }

    private void drawInfo(Canvas c) {
        Recs.Pick p = popPick;
        if (p == null) return;
        float k = home.uiScale();
        if (popTitle == null) {
            popTitle = Ui.text(Ui.semibold, Tokens.TYPE_HEADLINE * k, 0xFFFFFFFF);
            popMeta = Ui.text(Ui.medium, Tokens.TYPE_CAPTION * k, 0xFFB4B4BA);
            popAbout = new TextPaint(Ui.text(Ui.medium, Tokens.TYPE_CAPTION * k, 0xFFD6D6DC));
        }
        plate.setColor(0xF01C1C1E);
        float radius = Tokens.LAYOUT_BEZELRADIUS * k;
        c.drawRoundRect(bezel, radius, radius, plate);
        float barW = window.width() * Math.max(0.04f, Math.min(1, progress));
        plate.setColor(0xFFFFFFFF);
        float barTop = window.bottom + 8 * k;
        c.drawRoundRect(window.left, barTop, window.left + barW, barTop + 4 * k, 2 * k, 2 * k, plate);
        String sound = soundOn ? Ui.tr("Sound on") : Ui.tr("Muted");
        popMeta.setTextAlign(Paint.Align.RIGHT);
        c.drawText(sound, bezel.right, bezel.top - 12 * k, popMeta);
        popMeta.setTextAlign(Paint.Align.LEFT);
        float left = bezel.left, width = bezel.width(), y = bezel.bottom + 48 * k;
        fit.set(popTitle);
        c.drawText(TextUtils.ellipsize(p.title == null ? "" : p.title, fit, width, TextUtils.TruncateAt.END).toString(), left, y, popTitle);
        if (p.meta != null && !p.meta.isEmpty()) {
            y += 38 * k;
            fit.set(popMeta);
            c.drawText(TextUtils.ellipsize(p.meta, fit, width, TextUtils.TruncateAt.END).toString(), left, y, popMeta);
        }
        if (p.overview != null && !p.overview.isEmpty()) {
            if (popAboutLayout == null) {
                popAboutLayout = StaticLayout.Builder.obtain(p.overview, 0, p.overview.length(), popAbout, Math.max(1, Math.round(width)))
                        .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(5 * k, 1).setIncludePad(false).build();
            }
            c.save();
            c.translate(left, y + 20 * k);
            popAboutLayout.draw(c);
            c.restore();
        }
    }

    private void pushSound() {
        if (web != null) web.evaluateJavascript("setMuted(" + (soundOn ? "false" : "true") + ")", null);
    }

    /** A stand-in frame for screenshots. No WebView and no network. */
    void demo(Recs.Pick pick, boolean poppedOut) {
        demoMode = true;
        gaveUp = true;
        popPick = pick;
        popped = poppedOut;
        soundOn = false;
        progress = 0.42f;
        forKey = pick == null ? null : Recs.key(pick);
        if (box == null) {
            box = new FrameLayout(home.getContext());
            box.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            View stand = new View(home.getContext()) {
                @Override protected void onDraw(Canvas c) {
                    plate.setColor(0xFF14344A);
                    c.drawRect(0, 0, getWidth(), getHeight(), plate);
                    plate.setColor(0xFF2A6FA8);
                    c.drawCircle(getWidth() / 2f, getHeight() / 2f, Math.min(getWidth(), getHeight()) * 0.18f, plate);
                }
            };
            box.addView(stand, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            scrim = new View(home.getContext());
            scrim.setBackgroundColor(0xFF000000);
            scrim.setAlpha(0);
            info = new View(home.getContext()) {
                @Override protected void onDraw(Canvas c) { drawInfo(c); }
            };
            info.setAlpha(0);
            int at = home.layerAboveTiles();
            home.addView(scrim, at);
            home.addView(info, home.indexOfChild(scrim) + 1);
            home.addView(box, home.indexOfChild(info) + 1);
        }
        placeOnCard();
        box.setAlpha(1);
        if (poppedOut) {
            box.setScaleX(1);
            box.setScaleY(1);
            box.setTranslationX(0);
            box.setTranslationY(0);
            fade(1, 0);
        } else {
            box.setScaleX(cardScale());
            box.setScaleY(cardScale());
            box.setTranslationX(cardAt.centerX() - window.centerX());
            box.setTranslationY(cardAt.centerY() - window.centerY());
        }
    }

    /** The card's rectangle exists only after layout. Demo frames are placed again then. */
    void followLayout() {
        if (!demoMode || box == null || home.screenPx() <= 0) return;
        placeOnCard();
        if (popped) {
            box.setScaleX(1);
            box.setScaleY(1);
            box.setTranslationX(0);
            box.setTranslationY(0);
            if (scrim != null) scrim.setAlpha(1);
            if (info != null) info.setAlpha(1);
        }
    }

    private void dropPlayer() {
        releasePending = false;
        handler.removeCallbacks(sleep);
        handler.removeCallbacks(release);
        handler.removeCallbacks(pop);
        applyBackdrop(0);
        blurStep = -1;
        WebView dead = web;
        web = null;
        pageReady = false;
        asleep = false;
        popped = false;
        loadedId = null;
        pendingCall = null;
        if (box != null) home.removeView(box);
        if (scrim != null) home.removeView(scrim);
        if (info != null) home.removeView(info);
        box = null;
        scrim = null;
        info = null;
        if (dead != null) {
            try {
                dead.removeJavascriptInterface("FreeMyTV");
                dead.destroy();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void spec(View v, int width, int height) {
        v.measure(View.MeasureSpec.makeMeasureSpec(Math.max(width, 1), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.max(height, 1), View.MeasureSpec.EXACTLY));
    }

    private final class Bridge {
        @JavascriptInterface public void playing(String id) {
            if (id == null || !YOUTUBE_ID.matcher(id).matches()) return;
            handler.post(() -> {
                if (loadedId == null || !loadedId.equals(id) || box == null) return;
                handler.postDelayed(() -> {
                    if (!id.equals(loadedId) || box == null || box.getAlpha() >= 1) return;
                    box.animate().alpha(1).setDuration(400).setInterpolator(EASE).start();
                    handler.removeCallbacks(pop);
                    handler.postDelayed(pop, POP_AFTER);
                }, 700);
            });
        }

        @JavascriptInterface public void ended(String id) {
            handler.post(() -> {
                if (loadedId == null || !loadedId.equals(id)) return;
                String keep = forKey;
                stop(home.homeRunning());
                forKey = keep;
            });
        }

        // Strings, not floats: d8 from build-tools 34 crashes on a JavascriptInterface float.
        @JavascriptInterface public void progress(String at, String duration) {
            float pos, length;
            try {
                pos = Float.parseFloat(at);
                length = Float.parseFloat(duration);
            } catch (RuntimeException ignored) {
                return;
            }
            if (length <= 0) return;
            handler.post(() -> {
                progress = pos / length;
                if (info != null) info.invalidate();
            });
        }

        @JavascriptInterface public void failed() {
            handler.post(() -> {
                if (loadedId == null) return;
                AppLog.w("trailer can't play here");
                String keep = forKey;
                stop(home.homeRunning());
                forKey = keep;
            });
        }
    }

    /**
     * YouTube's player at full volume, so the TV's own volume sets how loud it is. shape() crops the 16:9 video to the
     * card; with no crop and no mask the TV composites the video itself (a mask made a TCL redraw every frame).
     */
    private static final String PAGE = "<!doctype html><html><head><style>"
            + "html,body{margin:0;background:transparent;overflow:hidden;width:100%;height:100%}"
            + "#c{position:absolute;left:0;top:0;width:100%;height:100%;overflow:hidden;background:#000}"
            + "#p{position:absolute;border:0}</style></head><body><div id=c><div id=p></div></div>"
            + "<script src='https://www.youtube.com/iframe_api'></script><script>"
            + "var player=null,ready=false,pending=null;addEventListener('resize',fit);"
            + "function fit(){var W=innerWidth,H=innerHeight,vw=Math.max(W,H*16/9),vh=vw*9/16,f=document.getElementById('p');"
            + "if(!f)return;f.style.width=vw+'px';f.style.height=vh+'px';f.style.left=((W-vw)/2)+'px';f.style.top=((H-vh)/2)+'px';}"
            + "function onYouTubeIframeAPIReady(){ready=true;if(pending){var a=pending;pending=null;play.apply(null,a);}}"
            + "var muted=true;function sound(p){if(!p)return;if(muted){p.mute();p.setVolume(0);}else{p.unMute();p.setVolume(100);}}"
            + "function setMuted(m){muted=!!m;if(player)sound(player);}"
            + "function play(k,withSound){muted=!withSound;if(!ready){pending=[k,withSound];return;}fit();"
            + "if(!player){player=new YT.Player('p',{videoId:k,playerVars:{autoplay:1,controls:0,disablekb:1,fs:0,rel:0,playsinline:1,iv_load_policy:3,cc_load_policy:0},events:{"
            + "onReady:function(e){fit();sound(e.target);e.target.playVideo();},"
            + "onStateChange:function(e){var id=(player&&player.getVideoData)?player.getVideoData().video_id:'';"
            + "if(e.data==1){sound(player);FreeMyTV.playing(id);}if(e.data==0)FreeMyTV.ended(id);},"
            + "onError:function(){FreeMyTV.failed();}}});}else{player.loadVideoById(k);sound(player);}}"
            + "setInterval(function(){if(player&&player.getDuration){var d=player.getDuration();if(d>0)FreeMyTV.progress(''+player.getCurrentTime(),''+d);}},500);"
            + "function stop(){pending=null;if(player&&player.stopVideo)player.stopVideo();}</script></body></html>";

}
