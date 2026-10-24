package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.app.role.RoleManager;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Sees the remote's keys before any app does, so the app buttons work from inside Netflix, Disney and the rest. The
 * system waits for it on every key (up to half a second), so it runs in a process of its own and does next to nothing.
 */
public class RemoteKeyService extends AccessibilityService {
    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (homeKey(event)) return true;
        return RemoteKeys.onKey(this, event);
    }

    /** Google TV's home screen, when keep_google_home leaves its app on (for Google Assistant's movie and show pages). */
    private static final String GOOGLE_PACKAGE = "com.google.android.apps.tv.launcherx";
    private static final String GOOGLE_HOME_SCREEN = GOOGLE_PACKAGE + ".home.HomeActivity";
    /** Opening a title's page, Google TV's app also starts its home behind it, about half a second later (TCL G10). */
    private static final long GOOGLE_PAGE_GRACE = 3000;
    private static final long GOOGLE_RECHECK = 1500;
    private static final String ASSISTANT_PACKAGE = "com.google.android.katniss";
    private static final String GOOGLE_TITLE_PAGE = GOOGLE_PACKAGE + ".entity.EntityActivity";
    /** Google TV's page for a title it can't load (English; another language just keeps Google's page). */
    private static final String GOOGLE_LOAD_ERROR = "Unable to load details";
    private static final int WATCH_ASSISTANT = 1, WATCH_TITLE = 2;
    private static final long WATCH_EVERY = 400, WATCH_FOR = 15_000;
    private int watching;
    private long watchUntil;
    /** What was last asked of Google Assistant: the text its window shows. */
    private String asked = "";

    /**
     * keep_google_home: while the Assistant listens, keeps the text of the request; on a Google TV title page, looks
     * for its load error. Reads only those two apps' windows, a few times a second, for a few seconds.
     */
    private void watch(int what) {
        if (!Config.KEEP_GOOGLE_HOME || !ourHome()) return;
        if (what == WATCH_ASSISTANT && watching != WATCH_ASSISTANT) asked = "";
        watching = what;
        watchUntil = android.os.SystemClock.uptimeMillis() + WATCH_FOR;
        handler.removeCallbacks(look);
        handler.post(look);
    }

    private final Runnable look = this::lookOnce;

    private void lookOnce() {
        if (watching == 0 || android.os.SystemClock.uptimeMillis() > watchUntil) {
            watching = 0;
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        CharSequence pkg = root == null ? null : root.getPackageName();
        if (watching == WATCH_ASSISTANT && ASSISTANT_PACKAGE.contentEquals(pkg == null ? "" : pkg)) {
            String said = longestText(root);
            if (!said.isEmpty()) asked = said;
        } else if (watching == WATCH_TITLE && GOOGLE_PACKAGE.contentEquals(pkg == null ? "" : pkg)) {
            List<AccessibilityNodeInfo> error = root.findAccessibilityNodeInfosByText(GOOGLE_LOAD_ERROR);
            if (error != null && !error.isEmpty()) {
                watching = 0;
                AppLog.w("Google TV couldn't load a title page: Free My TV's search instead");
                performGlobalAction(GLOBAL_ACTION_BACK);
                String query = asked;
                handler.postDelayed(() -> startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .setClassName(this, HomeActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(HomeActivity.EXTRA_SEARCH, true).putExtra(SearchActivity.EXTRA_QUERY, query)
                        .putExtra(SearchActivity.EXTRA_NOTE, true)), 300);
                return;
            }
        }
        handler.postDelayed(look, WATCH_EVERY);
    }

    /** The longest text in a window: the Assistant's window shows the request and little else. */
    private static String longestText(AccessibilityNodeInfo node) {
        if (node == null) return "";
        CharSequence t = node.getText();
        String best = t == null ? "" : t.toString().trim();
        for (int i = 0; i < node.getChildCount(); i++) {
            String child = longestText(node.getChild(i));
            if (child.length() > best.length()) best = child;
        }
        return best;
    }
    private static long googlePageAt;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable recheck = () -> {
        if (front.endsWith("/" + GOOGLE_HOME_SCREEN) && ourHome()) {
            AppLog.w("Google TV's home stayed: back to Free My TV");
            goHome();
        }
    };

    /**
     * With keep_google_home, the TV gives the Home button to Google TV's home (a system app outranks the HOME role on
     * this TCL): while Free My TV is the home screen, Home comes here instead.
     */
    private boolean homeKey(KeyEvent e) {
        if (!Config.KEEP_GOOGLE_HOME || e.getKeyCode() != KeyEvent.KEYCODE_HOME || !ourHome()) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) goHome();
        return true;
    }

    /** Free My TV mode: we hold the HOME role (Original gives it back, and then nothing here runs). */
    private boolean ourHome() {
        RoleManager roles = getSystemService(RoleManager.class);
        return roles != null && roles.isRoleHeld(RoleManager.ROLE_HOME);
    }

    private void goHome() {
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClassName(this, HomeActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** The window in front ("package/class"), from the window changes this service listens to. */
    static volatile String front = "";

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getPackageName() == null) return;
        front = event.getPackageName() + "/" + event.getClassName();
        if (!Config.KEEP_GOOGLE_HOME) return;
        boolean googleHome = GOOGLE_HOME_SCREEN.contentEquals(event.getClassName() == null ? "" : event.getClassName());
        long now = android.os.SystemClock.uptimeMillis();
        if (!googleHome && GOOGLE_PACKAGE.contentEquals(event.getPackageName())) googlePageAt = now;
        if (ASSISTANT_PACKAGE.contentEquals(event.getPackageName())) watch(WATCH_ASSISTANT);
        else if (GOOGLE_TITLE_PAGE.contentEquals(event.getClassName() == null ? "" : event.getClassName())) watch(WATCH_TITLE);
        // Google TV's home shown anyway (after the TV starts, leaving an app): back to ours. Its other screens (a
        // title's page the Assistant opened) stay, and so does the home it opens right behind such a page.
        if (!googleHome || !ourHome()) return;
        handler.removeCallbacks(recheck);
        if (now - googlePageAt > GOOGLE_PAGE_GRACE) {
            AppLog.w("Google TV's home showed: back to Free My TV");
            goHome();
        } else {
            handler.postDelayed(recheck, GOOGLE_RECHECK);   // still there once a page had its moment: it's the home
        }
    }

    @Override public void onInterrupt() {}
}
