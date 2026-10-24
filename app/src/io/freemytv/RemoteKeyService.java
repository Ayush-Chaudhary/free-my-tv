package io.freemytv;

import android.accessibilityservice.AccessibilityService;
import android.app.role.RoleManager;
import android.content.Intent;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

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
    private static final String GOOGLE_HOME_SCREEN = "com.google.android.apps.tv.launcherx.home.HomeActivity";

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
        // Google TV's home shown anyway (after the TV starts, leaving an app): back to ours. Its other screens (a
        // title's page the Assistant opened) stay.
        if (Config.KEEP_GOOGLE_HOME && GOOGLE_HOME_SCREEN.contentEquals(event.getClassName() == null ? "" : event.getClassName())
                && ourHome()) {
            AppLog.w("Google TV's home showed: back to Free My TV");
            goHome();
        }
    }

    @Override public void onInterrupt() {}
}
