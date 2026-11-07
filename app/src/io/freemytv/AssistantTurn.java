package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One reply from the assistant Worker (v1), with no network of its own.
 * Parses the POST /v1/turn body with {@link #fromJson} and hands it to {@code SearchActivity.present}.
 * The TV fills {@link Pick#service} and {@link Pick#pkg} itself before that (null when the title is on none of the installed apps).
 * {@code reply} is plain text, at most {@link #REPLY_MAX} characters, and arrives all at once: the screen types it out.
 */
final class AssistantTurn {
    static final int REPLY_MAX = 220;
    static final String SEARCH = "search";
    static final String CHAT = "chat";
    static final String OPEN_APP = "open_app";
    /** The Worker also says "recommend". The screen treats it as {@link #SEARCH}. */
    static final String RECOMMEND = "recommend";

    String reply = "";
    String intent = SEARCH;
    /** Set when {@link #intent} is {@link #OPEN_APP}. A service key such as "netflix", or empty. */
    String app = "";
    final List<Pick> picks = new ArrayList<>();

    static final class Pick {
        int id;
        String kind = "movie";
        String title = "";
        int year;
        /** Set by the TV (Recs.fillApp), never by the Worker: the installed app's service key and package, or null. */
        String service, pkg;
        String why = "";
    }

    static String clip(String reply) {
        if (reply == null) return "";
        String s = reply.trim();
        return s.length() <= REPLY_MAX ? s : s.substring(0, REPLY_MAX);
    }

    /** Worker JSON. Missing fields stay empty. The Worker sends no {@code service}; one that does is ignored. */
    static AssistantTurn fromJson(JSONObject o) {
        AssistantTurn t = new AssistantTurn();
        if (o == null) return t;
        t.reply = clip(o.optString("reply", ""));
        String intent = o.optString("intent", SEARCH);
        t.intent = intent == null || intent.isEmpty() ? SEARCH : intent;
        t.app = o.optString("app", "");
        JSONArray list = o.optJSONArray("picks");
        if (list == null) return t;
        for (int i = 0; i < list.length(); i++) {
            JSONObject one = list.optJSONObject(i);
            if (one == null) continue;
            Pick p = new Pick();
            p.id = one.optInt("id");
            p.kind = one.optString("kind", "movie");
            p.title = one.optString("title", "");
            p.year = one.optInt("year");
            p.why = one.optString("why", "");
            t.picks.add(p);
        }
        return t;
    }
}
