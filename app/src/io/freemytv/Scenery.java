package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Nature photos for the screensaver, from Bing's photo of the day (the last 8 days that Bing offers as a wallpaper).
 * One request, then the pictures, kept on disk so they show again offline. Only www.bing.com is asked: the image
 * path must be Bing's own ("/th?id=..."), no redirects are followed, and a download stops past MAX_BYTES or if it
 * isn't a JPEG. This is not a home-screen theme.
 */
final class Scenery {
    private static final String API = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=";
    private static final String HOST = "www.bing.com";
    private static final long EVERY = 6 * 3600_000L;
    private static final int MAX_BYTES = 5 * 1024 * 1024, MAX_JSON = 256 * 1024;
    private static Scenery instance;

    private final File dir;
    private final File stamp;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private long failedAt;
    private boolean busy;

    static synchronized Scenery get(Context c) {
        if (instance == null) instance = new Scenery(c.getApplicationContext());
        return instance;
    }

    private Scenery(Context c) {
        dir = new File(c.getFilesDir(), "scenery");
        stamp = new File(dir, ".checked");
    }

    /** Photos already on disk, oldest name first. Empty until the first download. */
    static List<Recs.Pick> picks(Context c) {
        return get(c).saved();
    }

    /** Loads anything saved, then asks Bing when the last check is old. {@code onReady} runs on the main thread. */
    static void refresh(Context c, Runnable onReady) {
        get(c).refresh(onReady);
    }

    /** Screenshots: a few landscape photos so the screensaver has scenery without the network. */
    static void demo(Context c) {
        get(c).writeDemo();
    }

    private List<Recs.Pick> saved() {
        List<Recs.Pick> out = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".jpg"));
        if (files == null) return out;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        for (File f : files) {
            Recs.Pick p = new Recs.Pick();
            p.kind = "scenery";
            p.id = f.getName().hashCode();
            p.photo = f;
            p.title = "";
            out.add(p);
        }
        return out;
    }

    private void refresh(Runnable onReady) {
        if (busy) return;
        long now = System.currentTimeMillis();
        boolean stale = !stamp.exists() || now - stamp.lastModified() >= EVERY;
        if (!stale || now - failedAt < 30 * 60_000L) {
            if (onReady != null) main.post(onReady);
            return;
        }
        busy = true;
        worker.execute(() -> {
            try {
                fetch();
            } finally {
                main.post(() -> {
                    busy = false;
                    if (onReady != null) onReady.run();
                });
            }
        });
    }

    private void fetch() {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no scenery folder");
            byte[] body = read(API + Ui.locale().toLanguageTag(), true, MAX_JSON);
            JSONArray days = new JSONObject(new String(body, StandardCharsets.UTF_8)).getJSONArray("images");
            boolean any = false;
            for (int i = 0; i < days.length(); i++) {
                JSONObject day = days.getJSONObject(i);
                if (!day.optBoolean("wp", true)) continue;
                URL url = imageUrl(day.optString("url"));
                String date = day.optString("startdate");
                if (url == null || date.isEmpty()) continue;
                File to = new File(dir, date + ".jpg");
                if (to.exists()) {
                    any = true;
                    continue;
                }
                if (i > 0) pause();
                save(read(url.toString(), false, MAX_BYTES), to);
                any = true;
            }
            if (!any) throw new IOException("no photo available as a wallpaper");
            if (!stamp.exists() && !stamp.createNewFile()) throw new IOException("couldn't stamp the check");
            //noinspection ResultOfMethodCallIgnored
            stamp.setLastModified(System.currentTimeMillis());
        } catch (Exception e) {
            failedAt = System.currentTimeMillis();
            AppLog.w("scenery: " + e.getMessage());
        }
    }

    private static void pause() {
        try {
            Thread.sleep(150);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Bing's image path ("/th?id=..."), on Bing's own host; anything else is refused. */
    static URL imageUrl(String path) {
        if (path == null || path.length() < 2 || path.charAt(0) != '/' || path.charAt(1) == '/' || path.indexOf('\\') >= 0) return null;
        try {
            URL u = new URL("https", HOST, path);
            return HOST.equals(u.getHost()) && u.getPort() == -1 ? u : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** GET without following redirects. {@code json} accepts a JSON body; otherwise only a JPEG, at most {@code max} bytes. */
    private static byte[] read(String url, boolean json, int max) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            String got = c.getContentType();
            String type = got == null ? "" : got.toLowerCase(java.util.Locale.ROOT);
            if (json) {
                if (!type.contains("json") && !type.contains("javascript") && !type.startsWith("text/")) throw new IOException("not json: " + got);
            } else if (!type.startsWith("image/jpeg") && !type.startsWith("image/jpg")) {
                throw new IOException("not a jpeg: " + got);
            }
            if (c.getContentLengthLong() > max) throw new IOException("too large");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    if (out.size() + n > max) throw new IOException("too large");
                    out.write(buffer, 0, n);
                }
                if (json && (out.size() == 0 || out.toByteArray()[0] != '{')) throw new IOException("not json");
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static void save(byte[] bytes, File to) throws IOException {
        File part = new File(to.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            out.write(bytes);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        }
        if (!part.renameTo(to)) throw new IOException("couldn't save the image");
    }

    private void writeDemo() {
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File[] have = dir.listFiles((d, name) -> name.endsWith(".jpg"));
        if (have != null && have.length > 0) return;
        int[][] skies = {{0xFF8EC6E8, 0xFF1E4D3A}, {0xFFE8B15A, 0xFF243E66}, {0xFF9BB0C4, 0xFF163528}};
        for (int i = 0; i < skies.length; i++) writeLandscape(new File(dir, "demo-" + i + ".jpg"), skies[i][0], skies[i][1]);
    }

    private static void writeLandscape(File to, int sky, int land) {
        Bitmap b = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, 0, 420, sky, 0xFFE7F2FA, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1280, 720, p);
        p.setShader(null);
        p.setColor(0xCCFFF4D6);
        c.drawCircle(980, 180, 70, p);
        p.setColor(land);
        c.drawOval(-80, 430, 900, 980, p);
        c.drawOval(500, 480, 1400, 1100, p);
        try (FileOutputStream out = new FileOutputStream(to)) {
            b.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException e) {
            AppLog.w("scenery demo: " + e.getMessage());
        }
        b.recycle();
    }
}
