package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The assistant Worker (assistant/README.md): one POST /v1/turn per spoken request, with the TV's token from
 * config.properties. Blocking: call it off the main thread. It never logs the request, the reply or the token.
 */
final class Assistant {
    private Assistant() {}

    /** Past this, the screen falls back to the plain TMDB search. */
    static final long TIMEOUT_MS = 6000;
    private static final int MAX_BODY = 64 * 1024;
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "assistant-timeout");
        t.setDaemon(true);
        return t;
    });

    /** {@link #turn} ended: {@code turn} on a 200, else {@code status} (the HTTP status, or 0 for no network or a timeout). */
    static final class Result {
        AssistantTurn turn;
        int status;
        boolean timeout;
    }

    static boolean configured() {
        String url = Config.ASSISTANT_URL.trim();
        return url.startsWith("https://") && !Config.ASSISTANT_TOKEN.trim().isEmpty();
    }

    static String endpoint() {
        String url = Config.ASSISTANT_URL.trim().replaceAll("/+$", "");
        return url.endsWith("/v1/turn") ? url : url + "/v1/turn";
    }

    static Result turn(String query, String lang, String region, List<String> services) {
        Result r = new Result();
        HttpURLConnection c = null;
        ScheduledFuture<?> cut = null;
        try {
            JSONObject body = new JSONObject()
                    .put("v", 1)
                    .put("query", query)
                    .put("lang", lang)
                    .put("region", region)
                    .put("services", new JSONArray(services))
                    .put("history", new JSONArray());
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            c = (HttpURLConnection) new URL(endpoint()).openConnection();
            c.setConnectTimeout((int) TIMEOUT_MS);
            c.setReadTimeout((int) TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setInstanceFollowRedirects(false);   // the token must not follow a redirect to another host
            c.setRequestProperty("Authorization", "Bearer " + Config.ASSISTANT_TOKEN.trim());
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("Accept", "application/json");
            final HttpURLConnection conn = c;
            cut = WATCHDOG.schedule(conn::disconnect, TIMEOUT_MS, TimeUnit.MILLISECONDS);   // the whole call, not each read
            try (OutputStream out = c.getOutputStream()) {
                out.write(bytes);
            }
            int code = c.getResponseCode();
            if (code != 200) {
                r.status = code;
                return r;
            }
            r.turn = AssistantTurn.fromJson(new JSONObject(read(c.getInputStream())));
            r.status = 200;
        } catch (IOException | org.json.JSONException | RuntimeException e) {
            // A read timeout, or the watchdog's disconnect (also an IOException), is the timeout; the rest is no answer.
            r.turn = null;
            r.status = 0;
            r.timeout = e instanceof java.net.SocketTimeoutException || (cut != null && cut.isDone());
            AppLog.w("assistant: " + e.getClass().getSimpleName());
        } finally {
            if (cut != null) cut.cancel(false);
            if (c != null) c.disconnect();
        }
        return r;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BODY) throw new IOException("too large");
        }
        return out.toString("UTF-8");
    }
}
