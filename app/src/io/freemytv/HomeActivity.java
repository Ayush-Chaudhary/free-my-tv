package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    /** Opens All Apps (a remote's All Apps button, RemoteKeys). */
    static final String EXTRA_ALL_APPS = "io.freemytv.ALL_APPS";
    /** Opens search (the key service, when Google TV could not load a title page). */
    static final String EXTRA_SEARCH = "io.freemytv.SEARCH";
    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over
    private long voiceAt;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        SleepTimer.listen(this);
        SleepTimer.check(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        if (getIntent().getBooleanExtra("demo", false)) {
            String theme = getIntent().getStringExtra("theme");
            if ("light".equals(theme) || "dark".equals(theme)) Prefs.setTheme(theme);
        }
        home = new HomeView(this);
        setContentView(home);
        if (getIntent().getBooleanExtra("demo", false)) {
            String search = getIntent().getStringExtra("search");
            if (search != null && !search.isEmpty()) {
                startActivity(new Intent(this, SearchActivity.class)
                        .putExtra("demo", true)
                        .putExtra("theme", getIntent().getStringExtra("theme"))
                        .putExtra("shot", search));
            } else home.applyDemo(getIntent().getStringExtra("shot"));
        }
        prefsVersion = Prefs.version;
        getWindow().setBackgroundDrawable(null);
        if (getIntent().getBooleanExtra(EXTRA_ALL_APPS, false)) home.showAllApps();
        openSearch(getIntent());
    }

    private void openSearch(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_SEARCH, false)) return;
        intent.removeExtra(EXTRA_SEARCH);
        startActivity(new Intent(this, SearchActivity.class)
                .putExtra(SearchActivity.EXTRA_QUERY, intent.getStringExtra(SearchActivity.EXTRA_QUERY))
                .putExtra(SearchActivity.EXTRA_NOTE, intent.getBooleanExtra(SearchActivity.EXTRA_NOTE, false)));
    }

    @Override protected void onResume() {
        super.onResume();
        // TCL's Android 11 records the home activity as translucent (occludesParent=false) whatever its theme says:
        // Android then keeps the app just left visible, and home tasks are layered under all others, so that app stays
        // on screen over ours. Opaque again on every resume (setTranslucent is public from Android 11).
        if (RemoteKeys.TCL && Build.VERSION.SDK_INT >= 30) setTranslucent(false);
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
        Updates.tellOnce(this);   // an update the week's check got ready: said once
    }

    @Override protected void onPause() {
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (home == null) return;
        if (intent.getBooleanExtra("demo", false)) {
            String theme = intent.getStringExtra("theme");
            if ("light".equals(theme) || "dark".equals(theme)) Prefs.setTheme(theme);
            prefsVersion = Prefs.version;
            String search = intent.getStringExtra("search");
            if (search != null && !search.isEmpty()) {
                startActivity(new Intent(this, SearchActivity.class)
                        .putExtra("demo", true)
                        .putExtra("theme", theme)
                        .putExtra("shot", search));
            } else home.applyDemo(intent.getStringExtra("shot"));
            return;
        }
        if (intent.getBooleanExtra(EXTRA_ALL_APPS, false)) home.showAllApps();
        else if (!intent.getBooleanExtra(EXTRA_SEARCH, false)) home.reset(true);
        openSearch(intent);
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    /** The framework's search key (KEYCODE_SEARCH), when it doesn't arrive as a normal key. */
    @Override public boolean onSearchRequested() {
        openVoiceSearch();
        return true;
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (voiceKey(event)) return true;
        if (RemoteKeys.onKey(this, event)) return true;
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    /**
     * Mic button while this screen is in front (the key service takes it first when that service is on). A tap opens
     * search and listens. With keep_google_home the service handles the key, and a long press there is Google Assistant.
     */
    private boolean voiceKey(KeyEvent e) {
        if (home == null || !RemoteKeys.voiceKey(e.getKeyCode())) return false;
        if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) openVoiceSearch();
        return true;
    }

    private void openVoiceSearch() {
        long now = SystemClock.uptimeMillis();
        if (now - voiceAt < 600) return;
        voiceAt = now;
        SearchActivity.startVoice(this);
    }

}
