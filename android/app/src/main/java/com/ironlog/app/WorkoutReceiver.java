package com.ironlog.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

/** Notification buttons, the rest-over alarm, reminders, and rescheduling after a reboot. */
public class WorkoutReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        final Context c = context.getApplicationContext();
        String a = intent.getAction();
        if (a == null) return;
        try {
            switch (a) {
                case WorkoutCore.A_MINUS:
                    adjustRest(c, -15000, "restMinus");
                    break;
                case WorkoutCore.A_PLUS:
                    adjustRest(c, 15000, "restPlus");
                    break;
                case WorkoutCore.A_SKIP:
                    skipRest(c);
                    break;
                case WorkoutCore.A_COMPLETE:
                    completeSet(c);
                    break;
                case WorkoutCore.A_REST_ALARM:
                    restAlarm(c);
                    break;
                case WorkoutCore.A_TEST:
                    testAlarm(c);
                    break;
                case WorkoutCore.A_IDLE: {
                    JSONObject st = WorkoutCore.state(c);
                    if (st != null && !WorkoutCore.appVisible) WorkoutCore.postIdle(c, st);
                    break;
                }
                case WorkoutCore.A_DAILY: {
                    String id = intent.getStringExtra("id");
                    if (id != null) WorkoutCore.postDaily(c, id);
                    WorkoutCore.scheduleDailies(c); // next day
                    break;
                }
                case Intent.ACTION_BOOT_COMPLETED:
                case Intent.ACTION_MY_PACKAGE_REPLACED: {
                    WorkoutCore.scheduleDailies(c);
                    JSONObject st = WorkoutCore.state(c);
                    if (st != null) {
                        WorkoutCore.scheduleRest(c, st);
                        WorkoutCore.postWorkout(c, st, false);
                    }
                    break;
                }
                default:
                    break;
            }
        } catch (JSONException ignored) {
        }
    }

    private static void adjustRest(Context c, long delta, String name) throws JSONException {
        JSONObject st = WorkoutCore.state(c);
        if (st == null) return;
        long now = System.currentTimeMillis();
        long end = st.optLong("restEndMs", 0);
        if (end <= now) {
            WorkoutCore.postWorkout(c, st, false);
            return;
        }
        end = Math.max(now + 1000, end + delta);
        long total = Math.max(1000, st.optLong("restTotalMs", end - now) + delta);
        st.put("restEndMs", end);
        st.put("restTotalMs", total);
        WorkoutCore.saveState(c, st);
        WorkoutCore.scheduleRest(c, st);
        WorkoutCore.postWorkout(c, st, false);
        WorkoutNotificationPlugin.emitAction(c, name, end, total);
    }

    /** Logs the current set. The page may be asleep, so the phone starts the rest timer itself and the page catches up. */
    private static void completeSet(Context c) throws JSONException {
        JSONObject st = WorkoutCore.state(c);
        if (st == null) {
            WorkoutNotificationPlugin.emitAction(c, "complete", 0, 0);
            return;
        }
        long now = System.currentTimeMillis();
        long restSec = st.optLong("completeRestSec", 0);
        long end = restSec > 0 ? now + restSec * 1000 : 0;
        st.put("restEndMs", end);
        st.put("restTotalMs", restSec * 1000);
        st.put("restTitle", st.optString("restTitleAfter", "Resting"));
        st.put("restOverText", st.optString("restOverTextAfter", ""));
        st.put("title", st.optString("restOverTextAfter", "Set logged"));
        st.put("canComplete", st.optString("restOverTextAfter", "").contains(" \u00b7 Set "));
        st.put("completeRestSec", 0); // a second tap before the page wakes can't know the next rest
        WorkoutCore.saveState(c, st);
        WorkoutCore.scheduleRest(c, st);
        WorkoutCore.postWorkout(c, st, false);
        WorkoutNotificationPlugin.emitAction(c, "complete", end, restSec * 1000);
    }

    private static void skipRest(Context c) throws JSONException {
        JSONObject st = WorkoutCore.state(c);
        if (st == null) return;
        st.put("restEndMs", 0);
        st.put("restTotalMs", 0);
        WorkoutCore.saveState(c, st);
        WorkoutCore.scheduleRest(c, st);
        WorkoutCore.postWorkout(c, st, false);
        WorkoutNotificationPlugin.emitAction(c, "restSkip", 0, 0);
    }

    /** Fires at rest end (or 3 s before it when countdown ticks are on). */
    private void restAlarm(final Context c) {
        JSONObject st = WorkoutCore.state(c);
        if (st == null) return;
        final long end = st.optLong("restEndMs", 0);
        long now = System.currentTimeMillis();
        if (end <= 0) return;                 // rest was skipped
        if (end - now > 4500) {               // rest was extended after this alarm was set
            WorkoutCore.scheduleRest(c, st);
            return;
        }
        final boolean quiet = WorkoutCore.appVisible; // IronLog is on screen: it rings by itself
        if (!quiet) WorkoutCore.playSound(c, st, end - now > 1500);
        final PendingResult pending = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                JSONObject s2 = WorkoutCore.state(c);
                if (s2 != null && s2.optLong("restEndMs", 0) == end) {
                    s2.put("restEndMs", 0);
                    s2.put("restTotalMs", 0);
                    WorkoutCore.saveState(c, s2);
                    if (!WorkoutCore.appVisible) {
                        WorkoutCore.vibrate(c, s2);
                        WorkoutCore.postWorkout(c, s2, true);
                    } else {
                        WorkoutCore.postWorkout(c, s2, false);
                    }
                }
            } catch (JSONException ignored) {
            } finally {
                pending.finish();
            }
        }, Math.max(0, end - System.currentTimeMillis()));
    }

    /** "Test lock-screen alert" from IronLog's settings. */
    private void testAlarm(final Context c) {
        JSONObject st;
        try {
            st = new JSONObject(WorkoutCore.prefs(c).getString("test", "{}"));
        } catch (JSONException e) {
            st = new JSONObject();
        }
        final JSONObject opts = st;
        WorkoutCore.playSound(c, opts, opts.optBoolean("countdown", true) && opts.optBoolean("sound", true));
        final PendingResult pending = goAsync();
        long delay = opts.optBoolean("countdown", true) && opts.optBoolean("sound", true) ? 3000 : 0;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                WorkoutCore.vibrate(c, opts);
                WorkoutCore.ensureChannels(c);
                WorkoutCore.notify(c, WorkoutCore.ID_TEST, WorkoutCore.builder(c, WorkoutCore.CH_REST)
                        .setContentTitle("Rest over — test alert")
                        .setContentText("This is how the end of a rest looks and sounds on your lock screen.")
                        .setContentIntent(WorkoutCore.openApp(c, null, 3))
                        .setAutoCancel(true)
                        .setCategory(android.app.Notification.CATEGORY_ALARM)
                        .setPriority(android.app.Notification.PRIORITY_HIGH)
                        .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
                        .build());
            } finally {
                pending.finish();
            }
        }, delay);
    }
}
