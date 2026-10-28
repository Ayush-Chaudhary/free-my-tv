package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 *
 * With keep_google_home (and only on a TV where this service actually runs), Google TV's home app stays installed so
 * Assistant can open a title page in it. Home is taken back here, and a page that fails to load becomes our search.
 * Window content is requested only by a build that turns that on (build.sh); the text is used for that search and not stored.
 */
public class RemoteKeyService extends AccessibilityService {
    private static final String GOOGLE_PACKAGE = "com.google.android.apps.tv.launcherx";
    private static final String GOOGLE_HOME_SCREEN = GOOGLE_PACKAGE + ".home.HomeActivity";
    private static final String GOOGLE_TITLE_PAGE = GOOGLE_PACKAGE + ".entity.EntityActivity";
    private static final String ASSISTANT_PACKAGE = "com.google.android.katniss";
    /** Opening a title's page, Google TV's app also starts its home behind it (about half a second later on a TCL G10). */
    private static final long GOOGLE_PAGE_GRACE = 3000;
    private static final long GOOGLE_RECHECK = 1500;
    /**
     * Google TV's failure text. It is English on the TV this was tried on; other languages keep Google's page until the
     * phrase is added here (a loose match would steal a page that loaded).
     */
    private static final String[] LOAD_ERRORS = {"Unable to load details"};
    private static final int WATCH_ASSISTANT = 1, WATCH_TITLE = 2;
    private static final long WATCH_EVERY = 400, WATCH_FOR = 15_000;
    private static final int WALK_BUDGET = 80;

    /** The window in front ("package/class"), for the mic key. No window content: the event names the window. */
    static volatile String front = "";

    private int watching;
    private long watchUntil, googlePageAt;
    /** What was last asked of Google Assistant, kept only until the title page fails or the watch ends. */
    private String asked = "";
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (homeKey(event)) return true;
        return RemoteKeys.onKey(this, event);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
        CharSequence cls = event.getClassName();
        front = event.getPackageName() + "/" + (cls == null ? "" : cls);
        if (!Config.KEEP_GOOGLE_HOME) return;
        boolean googleHome = cls != null && GOOGLE_HOME_SCREEN.contentEquals(cls);
        long now = SystemClock.uptimeMillis();
        if (!googleHome && GOOGLE_PACKAGE.contentEquals(event.getPackageName())) googlePageAt = now;
        if (ASSISTANT_PACKAGE.contentEquals(event.getPackageName())) watch(WATCH_ASSISTANT);
        else if (cls != null && GOOGLE_TITLE_PAGE.contentEquals(cls)) watch(WATCH_TITLE);
        if (!googleHome || !ourHome()) return;
        handler.removeCallbacks(recheck);
        if (now - googlePageAt > GOOGLE_PAGE_GRACE) goHome();
        else handler.postDelayed(recheck, GOOGLE_RECHECK);
    }

    private final Runnable recheck = () -> {
        if (front.endsWith("/" + GOOGLE_HOME_SCREEN) && ourHome()) goHome();
    };

    /** While Free My TV holds Home, the Home key comes here. A system launcher would otherwise win the key. */
    private boolean homeKey(KeyEvent e) {
        if (!RemoteKeys.keepGoogleHome() || e.getKeyCode() != KeyEvent.KEYCODE_HOME || !ourHome()) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) goHome();
        return true;
    }

    private boolean ourHome() {
        return HomeRole.held(this);
    }

    private void goHome() {
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClassName(this, HomeActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void watch(int what) {
        if (!RemoteKeys.keepGoogleHome() || !ourHome()) return;
        if (what == WATCH_ASSISTANT && watching != WATCH_ASSISTANT) asked = "";
        watching = what;
        watchUntil = SystemClock.uptimeMillis() + WATCH_FOR;
        handler.removeCallbacks(look);
        handler.post(look);
    }

    private final Runnable look = this::lookOnce;

    private void lookOnce() {
        if (watching == 0 || SystemClock.uptimeMillis() > watchUntil) {
            watching = 0;
            asked = "";
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        try {
            CharSequence pkg = root == null ? null : root.getPackageName();
            if (watching == WATCH_ASSISTANT && ASSISTANT_PACKAGE.contentEquals(pkg == null ? "" : pkg)) {
                String said = longestText(root, 0, new int[] {WALK_BUDGET});
                if (said.length() > 1 && said.length() < 200) asked = said;
            } else if (watching == WATCH_TITLE && GOOGLE_PACKAGE.contentEquals(pkg == null ? "" : pkg)) {
                String text = longestText(root, 0, new int[] {WALK_BUDGET});
                if (failed(text)) {
                    watching = 0;
                    String query = asked.length() > 120 ? asked.substring(0, 120) : asked;
                    asked = "";
                    AppLog.w("Google TV couldn't load a title page: Free My TV's search instead");
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    handler.postDelayed(() -> startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                            .setClassName(this, HomeActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .putExtra(HomeActivity.EXTRA_SEARCH, true).putExtra(SearchActivity.EXTRA_QUERY, query)
                            .putExtra(SearchActivity.EXTRA_NOTE, !query.isEmpty())), 300);
                    return;
                }
            }
        } finally {
            if (root != null) root.recycle();
        }
        handler.postDelayed(look, WATCH_EVERY);
    }

    private static boolean failed(String text) {
        for (String error : LOAD_ERRORS) if (text.contains(error)) return true;
        return false;
    }

    /** The longest text in the window, bounded so a big tree can't stall the key process. Children are recycled. */
    private static String longestText(AccessibilityNodeInfo node, int depth, int[] budget) {
        if (node == null || depth > 6 || budget[0] <= 0) return "";
        budget[0]--;
        CharSequence t = node.getText();
        String best = t == null ? "" : t.toString().trim();
        for (int i = 0, n = node.getChildCount(); i < n && budget[0] > 0; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            String found = longestText(child, depth + 1, budget);
            if (child != null) child.recycle();
            if (found.length() > best.length()) best = found;
        }
        return best;
    }

    @Override public void onInterrupt() {}

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        asked = "";
        super.onDestroy();
    }
}
