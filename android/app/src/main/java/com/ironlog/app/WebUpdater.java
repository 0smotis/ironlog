package com.ironlog.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Live updates for the IronLog page, straight from GitHub.
 *
 * The app checks www/version.json in the GitHub repo; when it's newer than what's running, it downloads
 * www/index.html in the background, verifies its size and SHA-256, and switches to it (while the app is in
 * the background, or on the next launch). The page is served from the same https://localhost address as the
 * built-in copy, so workouts and settings are untouched. A version that fails to start is rolled back and
 * skipped. Installing a new APK resets to the copy bundled inside it (Capacitor does this automatically).
 */
final class WebUpdater {
    static final String REPO_RAW = "https://raw.githubusercontent.com/0smotis/ironlog/main/www/";

    private static final String PREFS = "ironlog_updater";
    private static final String CAP_PREFS = "CapWebViewSettings"; // Capacitor's own live-update setting
    private static final String CAP_PATH = "serverBasePath";

    private static volatile boolean checking = false;

    private WebUpdater() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ versions

    static long bundledVersion(Context c) {
        try (InputStream in = c.getAssets().open("public/version.json")) {
            return new JSONObject(readAll(in)).optLong("version", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /** The folder Capacitor is serving instead of the bundled copy, or null. */
    static String activePath(Context c) {
        String p = c.getSharedPreferences(CAP_PREFS, Context.MODE_PRIVATE).getString(CAP_PATH, "");
        return p != null && !p.isEmpty() && new File(p, "index.html").exists() ? p : null;
    }

    static long versionIn(String dir) {
        if (dir == null) return 0;
        try (InputStream in = new FileInputStream(new File(dir, "version.json"))) {
            return new JSONObject(readAll(in)).optLong("version", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    static long runningVersion(Context c) {
        String p = activePath(c);
        return p != null ? Math.max(versionIn(p), 0) : bundledVersion(c);
    }

    static String stagedPath(Context c) {
        String p = prefs(c).getString("staged", null);
        return p != null && new File(p, "index.html").exists() ? p : null;
    }

    private static boolean isBad(Context c, long v) {
        return prefs(c).getString("bad", "").contains("|" + v + "|");
    }

    // ------------------------------------------------------------ launch-time bookkeeping

    /** Runs in MainActivity before Capacitor starts: rolls back a version that never started, and switches to a finished download. */
    static void onLaunch(Context c) {
        SharedPreferences p = prefs(c);
        long trial = p.getLong("trial", 0);
        if (trial > 0) {
            int boots = p.getInt("trialBoots", 0) + 1;
            if (boots >= 2) {
                // Launched again on this version and the page never said it started: roll back.
                rollback(c, trial);
            } else {
                p.edit().putInt("trialBoots", boots).apply();
            }
        }
        // A download finished while the app was closed: start on it now.
        String staged = stagedPath(c);
        if (staged != null && prefs(c).getLong("trial", 0) == 0) activate(c, staged);
        // A newer APK can carry a newer copy than the downloaded one; Capacitor resets on new APKs,
        // but guard anyway.
        String active = activePath(c);
        if (active != null && versionIn(active) <= bundledVersion(c)) {
            c.getSharedPreferences(CAP_PREFS, Context.MODE_PRIVATE).edit().putString(CAP_PATH, "").apply();
        }
    }

    /** Points Capacitor at a downloaded copy (takes effect on next load). */
    static void activate(Context c, String dir) {
        long v = versionIn(dir);
        c.getSharedPreferences(CAP_PREFS, Context.MODE_PRIVATE).edit().putString(CAP_PATH, dir).commit();
        prefs(c).edit().remove("staged").putLong("trial", v).putInt("trialBoots", 0)
                .putLong("justUpdatedTo", v).apply();
    }

    static void rollback(Context c, long badVersion) {
        SharedPreferences p = prefs(c);
        p.edit().putString("bad", p.getString("bad", "|") + badVersion + "|")
                .remove("trial").remove("trialBoots").remove("justUpdatedTo").apply();
        c.getSharedPreferences(CAP_PREFS, Context.MODE_PRIVATE).edit().putString(CAP_PATH, "").commit();
    }

    /** The page started fine; returns {updated, version, notes} once after an update. */
    static JSONObject confirmReady(Context c) {
        SharedPreferences p = prefs(c);
        JSONObject r = new JSONObject();
        try {
            long just = p.getLong("justUpdatedTo", 0);
            long running = runningVersion(c);
            r.put("version", running);
            r.put("bundled", bundledVersion(c));
            if (just > 0 && just == running) {
                r.put("updated", true);
                r.put("notes", p.getString("notes_" + just, ""));
            }
        } catch (JSONException ignored) {
        }
        p.edit().remove("trial").remove("trialBoots").remove("justUpdatedTo").apply();
        cleanup(c);
        return r;
    }

    /** Deletes downloaded copies that are neither running nor waiting. */
    private static void cleanup(Context c) {
        File root = new File(c.getFilesDir(), "web");
        File[] dirs = root.listFiles();
        if (dirs == null) return;
        String keepA = activePath(c), keepB = stagedPath(c);
        for (File d : dirs) {
            String path = d.getAbsolutePath();
            if (path.equals(keepA) || path.equals(keepB)) continue;
            File[] fs = d.listFiles();
            if (fs != null) for (File f : fs) f.delete();
            d.delete();
        }
    }

    // ------------------------------------------------------------ checking & downloading

    interface Done {
        /** status: "none" (up to date), "ready" (downloaded, waiting to switch), "error". */
        void done(String status, long version, String message);
    }

    static void checkAsync(final Context c, final Done done) {
        if (checking) {
            if (done != null) done.done("busy", 0, "Already checking");
            return;
        }
        checking = true;
        new Thread(() -> {
            String status = "error";
            long ver = 0;
            String msg = "";
            try {
                prefs(c).edit().putLong("lastCheck", System.currentTimeMillis()).apply();
                JSONObject remote = new JSONObject(new String(get(REPO_RAW + "version.json?t=" + System.currentTimeMillis(), 64 * 1024), StandardCharsets.UTF_8));
                ver = remote.optLong("version", 0);
                long current = Math.max(runningVersion(c), versionIn(stagedPath(c)));
                if (ver <= current || isBad(c, ver)) {
                    status = stagedPath(c) != null ? "ready" : "none";
                    if (status.equals("ready")) ver = versionIn(stagedPath(c));
                } else {
                    long size = remote.optLong("size", 0);
                    String sha = remote.optString("sha256", "");
                    byte[] html = get(REPO_RAW + "index.html?v=" + ver, 64L * 1024 * 1024);
                    if (size > 0 && html.length != size) throw new Exception("Download incomplete (" + html.length + " of " + size + " bytes) — GitHub may still be updating, try again in a few minutes");
                    if (!sha.isEmpty() && !sha.equalsIgnoreCase(sha256(html))) throw new Exception("Download didn't match the published version — GitHub may still be updating, try again in a few minutes");
                    File dir = new File(new File(c.getFilesDir(), "web"), "v" + ver);
                    if (!dir.exists() && !dir.mkdirs()) throw new Exception("Couldn't create the update folder");
                    write(new File(dir, "index.html"), html);
                    write(new File(dir, "version.json"), remote.toString().getBytes(StandardCharsets.UTF_8));
                    prefs(c).edit().putString("staged", dir.getAbsolutePath()).putString("notes_" + ver, remote.optString("notes", "")).apply();
                    status = "ready";
                }
            } catch (Exception e) {
                msg = e.getMessage() != null ? e.getMessage() : e.toString();
            } finally {
                checking = false;
            }
            if (done != null) done.done(status, ver, msg);
        }, "ironlog-updater").start();
    }

    private static byte[] get(String url, long max) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setUseCaches(false);
        conn.setRequestProperty("Cache-Control", "no-cache");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("GitHub answered " + code + " for " + url.replace(REPO_RAW, ""));
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > max) throw new Exception("File is larger than expected");
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void write(File f, byte[] data) throws Exception {
        File tmp = new File(f.getAbsolutePath() + ".part");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(data);
        }
        if (f.exists() && !f.delete()) throw new Exception("Couldn't replace " + f.getName());
        if (!tmp.renameTo(f)) throw new Exception("Couldn't save " + f.getName());
    }

    static String sha256(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

}
