package io.freemytv;

import android.content.Intent;
import android.net.Uri;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The intent that opens a title inside its Android TV app. Watchmode and Wikidata give a web address. On a TCL G10
 * (Android 12) a plain https VIEW with the package set is enough, except Netflix and Disney+, which need a fresh task
 * when they are already open. Prime Video, Max and Hulu drop to a profile picker or their home if the task is cleared.
 * No process is force-stopped. The measured commands are in REVIEW.md.
 */
final class TitleLinks {
    private TitleLinks() {}

    private static final Pattern NETFLIX_ID = Pattern.compile("(?:title|watch)/(\\d{4,12})");
    private static final Pattern YOUTUBE_ID = Pattern.compile("(?:[?&]v=|youtu\\.be/|embed/|shorts/)([A-Za-z0-9_-]{6,20})");
    private static final Pattern PRIME_ASIN = Pattern.compile("/detail/(?:[^/?#]+/)?([A-Z0-9]{10})(?:[/?#]|$)");
    private static final Pattern PRIME_GTI = Pattern.compile("(?:gti|asin)=([A-Za-z0-9.]{6,80})");

    /** A VIEW intent for this app and address, or null when the address is not one we should hand to an app. */
    static Intent view(String pkg, String uri) {
        if (pkg == null || uri == null) return null;
        Uri parsed = Uri.parse(uri);
        String scheme = parsed.getScheme();
        if (scheme == null) return null;
        scheme = scheme.toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http") && !scheme.equals("vnd.youtube")) return null;
        if (pkg.equals("com.netflix.ninja")) return netflix(parsed);
        if (pkg.contains("youtube")) return youtube(pkg, parsed);
        Intent intent = new Intent(Intent.ACTION_VIEW).setPackage(pkg).addCategory(Intent.CATEGORY_BROWSABLE);
        if (pkg.contains("amazonvideo") || pkg.contains("avod") || pkg.equals("com.amazon.firebat")) {
            intent.setData(prime(parsed));
        } else {
            intent.setData(https(parsed));
        }
        // Disney+ needs a fresh task when it is already open. Clearing the task sticks Prime, Max and Hulu.
        if (pkg.equals("com.disney.disneyplus")) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        return intent;
    }

    /**
     * Netflix's TV app ({@code com.netflix.ninja.MainActivity}) reads the title only when the task starts clean.
     * {@code source} is a String, as the TV's search sends it. {@code /title/} opens the page; {@code /watch/} would play.
     */
    private static Intent netflix(Uri parsed) {
        Matcher m = NETFLIX_ID.matcher(parsed.toString());
        if (!m.find()) return null;
        return new Intent(Intent.ACTION_VIEW)
                .setClassName("com.netflix.ninja", "com.netflix.ninja.MainActivity")
                .setData(Uri.parse("https://www.netflix.com/title/" + m.group(1)))
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .putExtra("source", "30")
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    /**
     * YouTube TV starts the video from {@code https://www.youtube.com/watch?v=<id>}. {@code vnd.youtube:<id>} opens
     * the app's home (TCL G10). A {@code vnd.youtube:} address is rewritten to that https URL.
     */
    private static Intent youtube(String pkg, Uri parsed) {
        String id = null;
        if ("vnd.youtube".equals(parsed.getScheme())) {
            String part = parsed.getSchemeSpecificPart();
            if (part != null && part.matches("[A-Za-z0-9_-]{6,20}")) id = part;
        }
        if (id == null) {
            Matcher m = YOUTUBE_ID.matcher(parsed.toString());
            if (m.find()) id = m.group(1);
        }
        Intent intent = new Intent(Intent.ACTION_VIEW).setPackage(pkg).addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setData(id == null ? https(parsed) : Uri.parse("https://www.youtube.com/watch?v=" + id));
        return intent;
    }

    /** The living-room app opens {@code app.primevideo.com}, including an Amazon web address rewritten to an ASIN or gti. */
    private static Uri prime(Uri parsed) {
        String raw = parsed.toString();
        Matcher gti = PRIME_GTI.matcher(raw);
        if (gti.find()) {
            String v = gti.group(1);
            String key = v.startsWith("amzn1.") ? "gti" : "asin";
            return Uri.parse("https://app.primevideo.com/detail?" + key + "=" + v);
        }
        Matcher asin = PRIME_ASIN.matcher(raw);
        if (asin.find()) return Uri.parse("https://app.primevideo.com/detail?asin=" + asin.group(1));
        return https(parsed);
    }

    private static Uri https(Uri parsed) {
        return "http".equalsIgnoreCase(parsed.getScheme()) ? parsed.buildUpon().scheme("https").build() : parsed;
    }
}
