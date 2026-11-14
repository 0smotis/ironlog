package com.ironlog.app;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.core.app.ActivityCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Bridge between the IronLog web app and the phone. Called from the page as
 * Capacitor.nativePromise('WorkoutNotification', method, options).
 */
@CapacitorPlugin(name = "WorkoutNotification")
public class WorkoutNotificationPlugin extends Plugin {
    private static volatile WorkoutNotificationPlugin instance;
    private boolean freshLoad = true;
    private boolean askedPermission = false;

    @Override
    public void load() {
        instance = this;
        WorkoutCore.ensureChannels(getContext());
        // Buttons tapped while the app wasn't running are delivered now.
        String pending = WorkoutCore.prefs(getContext()).getString("pendingActions", null);
        if (pending != null) {
            WorkoutCore.prefs(getContext()).edit().remove("pendingActions").apply();
            try {
                JSONArray arr = new JSONArray(pending);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) notifyListeners("action", JSObject.fromJSONObject(o), true);
                }
            } catch (JSONException ignored) {
            }
        }
    }

    @Override
    protected void handleOnResume() {
        WorkoutCore.appVisible = true;
        WorkoutCore.cancel(getContext(), WorkoutCore.ID_IDLE);
        WorkoutCore.cancel(getContext(), WorkoutCore.ID_TEST);
        // A new version was switched in while the app was in the background: make sure it starts.
        final long trial = WebUpdater.prefs(getContext()).getLong("trial", 0);
        if (trial > 0) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (WebUpdater.prefs(getContext()).getLong("trial", 0) == trial) {
                    WebUpdater.rollback(getContext(), trial);
                    if (getBridge() != null) getBridge().setServerAssetPath("public");
                }
            }, 20000);
        }
        maybeCheckForUpdate();
    }

    @Override
    protected void handleOnPause() {
        WorkoutCore.appVisible = false;
        if (WebUpdater.stagedPath(getContext()) != null && restIdle()) applyStaged();
    }

    // ---------------------------------------------------------------- live updates from GitHub

    private boolean restIdle() {
        JSONObject st = WorkoutCore.state(getContext());
        return st == null || st.optLong("restEndMs", 0) <= System.currentTimeMillis();
    }

    /** Checks GitHub at most every 30 minutes; a finished download is switched in once the app is in the background. */
    private void maybeCheckForUpdate() {
        long last = WebUpdater.prefs(getContext()).getLong("lastCheck", 0);
        if (System.currentTimeMillis() - last < 30 * 60 * 1000L) return;
        WebUpdater.checkAsync(getContext(), (status, version, message) -> {
            if ("ready".equals(status) && !WorkoutCore.appVisible) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!WorkoutCore.appVisible && restIdle()) applyStaged();
                });
            }
        });
    }

    private boolean applyStaged() {
        String path = WebUpdater.stagedPath(getContext());
        if (path == null || getBridge() == null) return false;
        WebUpdater.activate(getContext(), path);
        getBridge().setServerBasePath(path);
        return true;
    }

    /** The page calls this once it has started; tells it whether it was just updated. */
    @PluginMethod
    public void webReady(PluginCall call) {
        try {
            call.resolve(JSObject.fromJSONObject(WebUpdater.confirmReady(getContext())));
        } catch (JSONException e) {
            call.resolve();
        }
    }

    @PluginMethod
    public void checkUpdate(final PluginCall call) {
        WebUpdater.checkAsync(getContext(), (status, version, message) -> {
            JSObject r = new JSObject();
            r.put("status", status);
            r.put("version", version);
            r.put("message", message);
            r.put("running", WebUpdater.runningVersion(getContext()));
            call.resolve(r);
        });
    }

    @PluginMethod
    public void applyUpdate(final PluginCall call) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (applyStaged()) call.resolve();
            else call.reject("No update is waiting");
        });
    }

    @PluginMethod
    public void webInfo(PluginCall call) {
        JSObject r = new JSObject();
        r.put("running", WebUpdater.runningVersion(getContext()));
        r.put("bundled", WebUpdater.bundledVersion(getContext()));
        r.put("staged", WebUpdater.versionIn(WebUpdater.stagedPath(getContext())));
        r.put("lastCheck", WebUpdater.prefs(getContext()).getLong("lastCheck", 0));
        try {
            r.put("app", getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName);
        } catch (Exception e) {
            r.put("app", "");
        }
        call.resolve(r);
    }

    @Override
    protected void handleOnDestroy() {
        WorkoutCore.appVisible = false;
        if (instance == this) instance = null;
    }

    /** Sends a notification-button tap to the page (or queues it until the page is running). */
    static void emitAction(Context c, String action, long restEndMs, long restTotalMs) {
        JSObject o = new JSObject();
        o.put("action", action);
        o.put("restEndMs", restEndMs);
        o.put("restTotalMs", restTotalMs);
        o.put("at", System.currentTimeMillis());
        WorkoutNotificationPlugin p = instance;
        if (p != null && p.getBridge() != null) {
            p.notifyListeners("action", o, true);
            return;
        }
        try {
            JSONArray arr = new JSONArray(WorkoutCore.prefs(c).getString("pendingActions", "[]"));
            arr.put(new JSONObject(o.toString()));
            WorkoutCore.prefs(c).edit().putString("pendingActions", arr.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    private void askPermission() {
        if (askedPermission || Build.VERSION.SDK_INT < 33 || getActivity() == null) return;
        askedPermission = true;
        if (getContext().checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(getActivity(), new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7301);
        }
    }

    @PluginMethod
    public void update(PluginCall call) {
        Context c = getContext();
        askPermission();
        try {
            JSONObject st = new JSONObject(call.getData().toString());
            JSONObject old = WorkoutCore.state(c);
            long now = System.currentTimeMillis();
            if (freshLoad) {
                freshLoad = false;
                // The app was restarted mid-rest: keep the phone's timer and tell the page about it.
                if (st.optLong("restEndMs", 0) <= now && old != null && old.optLong("restEndMs", 0) > now
                        && old.optLong("startMs", -1) == st.optLong("startMs", -2)) {
                    st.put("restEndMs", old.optLong("restEndMs"));
                    st.put("restTotalMs", old.optLong("restTotalMs", 0));
                    emitAction(c, "restSync", old.optLong("restEndMs"), old.optLong("restTotalMs", 0));
                }
            }
            WorkoutCore.saveState(c, st);
            WorkoutCore.scheduleRest(c, st);
            WorkoutCore.scheduleIdle(c, st);
            WorkoutCore.cancel(c, WorkoutCore.ID_IDLE);
            WorkoutCore.postWorkout(c, st, false);
            call.resolve();
        } catch (JSONException e) {
            call.reject("Bad workout state: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Context c = getContext();
        freshLoad = false;
        WorkoutCore.saveState(c, null);
        WorkoutCore.scheduleRest(c, null);
        WorkoutCore.scheduleIdle(c, null);
        WorkoutCore.cancel(c, WorkoutCore.ID_WORKOUT);
        WorkoutCore.cancel(c, WorkoutCore.ID_IDLE);
        call.resolve();
    }

    @PluginMethod
    public void setDailyReminders(PluginCall call) {
        askPermission();
        JSArray arr = call.getArray("reminders", new JSArray());
        WorkoutCore.prefs(getContext()).edit().putString("dailies", arr.toString()).apply();
        WorkoutCore.scheduleDailies(getContext());
        call.resolve();
    }

    @PluginMethod
    public void takeLaunchRoutine(PluginCall call) {
        JSObject r = new JSObject();
        String id = WorkoutCore.pendingRoutine;
        WorkoutCore.pendingRoutine = null;
        if (id != null) r.put("id", id);
        call.resolve(r);
    }

    /** Settings → "Test lock-screen alert": rings after N seconds so you can lock the phone first. */
    @PluginMethod
    public void test(PluginCall call) {
        askPermission();
        Context c = getContext();
        int seconds = call.getInt("seconds", 5);
        WorkoutCore.prefs(c).edit().putString("test", call.getData().toString()).apply();
        boolean ticks = call.getBoolean("countdown", true) && call.getBoolean("sound", true);
        long at = System.currentTimeMillis() + Math.max(1, seconds) * 1000L - (ticks ? 3000 : 0);
        WorkoutCore.setExact(c, Math.max(System.currentTimeMillis() + 500, at), WorkoutCore.broadcast(c, WorkoutCore.A_TEST, 23, null));
        JSObject r = new JSObject();
        r.put("notificationsAllowed", WorkoutCore.canNotify(c));
        call.resolve(r);
    }
}
