package io.freemytv;

import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * The remote's Assistant / mic button on TCL. TCL's window manager takes those keys (219, 231) before any key filter,
 * so RemoteKeyService never sees them, and asks Android for a web search (android.speech.action.WEB_SEARCH, sent by the
 * system), which only Google's app answers on a stock TV. With the Assistant button set to Search (Remote Buttons, or
 * assistant_button in config.properties), this activity answers it too: the next press shows Android's chooser, and
 * Free My TV → Always makes the button ours. Set back to Google Assistant, the activity is turned off and Android
 * forgets that choice with it.
 *
 * It shows nothing. In Free My TV mode it opens our search, listening; otherwise (Original) the press goes on to
 * Google's app as before.
 */
public class VoiceKeyActivity extends Activity {
    private static final String ASSISTANT_PACKAGE = "com.google.android.katniss";

    /** Only TCL is known to send the button this way; elsewhere the row isn't offered and the activity stays off. */
    static boolean offered() {
        return RemoteKeys.TCL;
    }

    static boolean on() {
        return offered() && Prefs.assistButton();
    }

    /** Turns the activity on or off to match the setting. Cheap when nothing changes: called at every start. */
    static void apply(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            ComponentName me = new ComponentName(c, VoiceKeyActivity.class);
            int want = on() ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            if (pm.getComponentEnabledSetting(me) != want) pm.setComponentEnabledSetting(me, want, PackageManager.DONT_KILL_APP);
        } catch (RuntimeException e) {
            AppLog.w("assistant button: " + e.getMessage());
        }
    }

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        Intent in = getIntent();
        Bundle extras = in.getExtras();
        // Which extras the button sends (names only): a TV test can tell a tap from a hold by them, if TCL marks it.
        AppLog.w("assistant button: " + in.getAction() + (extras == null ? "" : " " + extras.keySet()));
        if (on() && HomeRole.held(this)) {
            String query = in.getStringExtra(SearchManager.QUERY);
            Intent search = new Intent(this, SearchActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (query != null && !query.trim().isEmpty()) search.putExtra(SearchActivity.EXTRA_QUERY, query.trim());
            else search.putExtra(SearchActivity.EXTRA_VOICE, true);
            startActivity(search);
        } else {
            toGoogle(in);
        }
        finish();
    }

    /** The same request, to Google's app. Without it, our search (the button still does something). */
    private void toGoogle(Intent in) {
        try {
            startActivity(new Intent(in).setComponent(null).setPackage(ASSISTANT_PACKAGE)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            SearchActivity.startVoice(this);
        }
    }
}
